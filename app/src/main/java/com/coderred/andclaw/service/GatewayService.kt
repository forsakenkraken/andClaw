package com.coderred.andclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.MainActivity
import com.coderred.andclaw.R
import com.coderred.andclaw.data.GatewaySurvivorMetadata
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PairingRequest
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.proroot.BundleUpdateOutcome
import com.coderred.andclaw.proroot.ExecutionRuntime
import com.coderred.andclaw.proroot.ProcessManager
import com.coderred.andclaw.receiver.GatewayWatchdogReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GatewayService : Service() {

    private enum class GatewayActionType {
        START,
        RESTART,
        ATTACH,
        STOP,
    }

    companion object {
        internal enum class AttachRecoveryAction {
            ATTACH,
            RESTART,
            START,
        }

        const val CHANNEL_ID = "andclaw_gateway"
        const val PAIRING_CHANNEL_ID = "andclaw_pairing"
        const val NOTIFICATION_ID = 1
        const val PAIRING_NOTIFICATION_ID = 2
        private const val START_WAKE_LOCK_TIMEOUT_MS = 300_000L
        // Bundle update policy 기본 최대치(20분) + startup final state 대기(5분) + 여유(1분)
        private const val START_WAKE_LOCK_TIMEOUT_BACKGROUND_MS = 26L * 60L * 1000L
        private const val START_TERMINAL_WAIT_TIMEOUT_MS = 300_000L
        private const val RESTART_WAKE_LOCK_TIMEOUT_MS = 300_000L
        private const val PROOT_START_TERMINAL_WAIT_TIMEOUT_MS = 900_000L
        private const val PROOT_RESTART_WAKE_LOCK_TIMEOUT_MS = 900_000L
        private const val ATTACH_HEALTH_PROBE_TIMEOUT_MS = 8_000L
        private const val STICKY_PREFLIGHT_HEALTH_PROBE_TIMEOUT_MS = 2_500L
        private const val WHATSAPP_LOGIN_WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 1000L
        private const val DEFAULT_GATEWAY_WS_ENDPOINT = "127.0.0.1:18789"
        const val ACTION_START = "com.coderred.andclaw.action.START"
        const val ACTION_STOP = "com.coderred.andclaw.action.STOP"
        const val ACTION_STOP_FOR_MISSING_MODEL = "com.coderred.andclaw.action.STOP_FOR_MISSING_MODEL"
        const val ACTION_RESTART = "com.coderred.andclaw.action.RESTART"
        const val ACTION_ATTACH = "com.coderred.andclaw.action.ATTACH"
        const val ACTION_VALIDATE_RUNTIME = "com.coderred.andclaw.action.VALIDATE_RUNTIME"
        const val ACTION_WHATSAPP_LOGIN_GUARD_START = "com.coderred.andclaw.action.WHATSAPP_LOGIN_GUARD_START"
        const val ACTION_WHATSAPP_LOGIN_GUARD_STOP = "com.coderred.andclaw.action.WHATSAPP_LOGIN_GUARD_STOP"
        private const val EXTRA_FROM_WATCHDOG = "from_watchdog"
        private const val EXTRA_USER_INITIATED = "user_initiated"
        private const val EXTRA_TRIGGER_SOURCE = "trigger_source"
        private const val EXTRA_WAKE_LOCK_TIMEOUT_MS = "wake_lock_timeout_ms"
        const val EXTRA_VALIDATE_REQUIRE_PATCHED_NODE_OPTIONS = "require_patched_node_options"
        private const val UNKNOWN_TRIGGER_SOURCE = "unknown"
        private const val STICKY_RECOVERY_TRIGGER_SOURCE = "system:sticky_recovery"
        private const val DIAGNOSTIC_SOURCE_MAX_LENGTH = 80
        const val VALIDATION_RESULT_FILE_NAME = "gateway-runtime-validation.json"

        internal fun resolveGatewayStartupTerminalTimeoutMs(runtime: ExecutionRuntime): Long {
            return when (runtime) {
                ExecutionRuntime.PROOT -> PROOT_START_TERMINAL_WAIT_TIMEOUT_MS
                ExecutionRuntime.PROROOT -> START_TERMINAL_WAIT_TIMEOUT_MS
            }
        }

        internal fun resolveGatewayRestartTimeoutMs(runtime: ExecutionRuntime): Long {
            return when (runtime) {
                ExecutionRuntime.PROOT -> PROOT_RESTART_WAKE_LOCK_TIMEOUT_MS
                ExecutionRuntime.PROROOT -> RESTART_WAKE_LOCK_TIMEOUT_MS
            }
        }

        private var _instance: GatewayService? = null
        private var retainedProcessManager: ProcessManager? = null

        internal fun shouldHoldChargingWakeLock(batteryStatus: Int): Boolean {
            return batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        }

        internal data class InAppReviewRunTracker(
            val runActive: Boolean = false,
            val runCounted: Boolean = false,
        )

        internal enum class InAppReviewCounterAction {
            NONE,
            INCREMENT,
            RESET,
        }

        internal fun advanceInAppReviewRunTracker(
            tracker: InAppReviewRunTracker,
            status: GatewayStatus,
            previousStatus: GatewayStatus?,
        ): Pair<InAppReviewRunTracker, InAppReviewCounterAction> {
            return when (status) {
                GatewayStatus.STARTING -> {
                    InAppReviewRunTracker(runActive = true) to InAppReviewCounterAction.NONE
                }

                GatewayStatus.RUNNING -> {
                    val fromTerminalState = previousStatus == GatewayStatus.STOPPED ||
                        previousStatus == GatewayStatus.ERROR
                    if (!tracker.runCounted && (tracker.runActive || fromTerminalState)) {
                        InAppReviewRunTracker(runActive = true, runCounted = true) to InAppReviewCounterAction.INCREMENT
                    } else {
                        tracker to InAppReviewCounterAction.NONE
                    }
                }

                GatewayStatus.ERROR -> {
                    InAppReviewRunTracker() to InAppReviewCounterAction.RESET
                }

                GatewayStatus.STOPPED -> {
                    InAppReviewRunTracker() to InAppReviewCounterAction.NONE
                }

                GatewayStatus.STOPPING -> {
                    tracker to InAppReviewCounterAction.NONE
                }
            }
        }

        val processManager: ProcessManager?
            get() = _instance?.pm ?: retainedProcessManager

        val isInstanceActive: Boolean
            get() = _instance != null

        internal fun bindRetainedProcessManager(processManager: ProcessManager) {
            retainedProcessManager = processManager
        }

        internal fun clearRetainedProcessManagerForTest() {
            retainedProcessManager = null
        }

        internal fun normalizeTriggerSource(source: String?): String {
            if (source.isNullOrBlank()) return UNKNOWN_TRIGGER_SOURCE
            val builder = StringBuilder()
            var previousDash = false
            source.trim().lowercase().forEach { ch ->
                when {
                    ch in 'a'..'z' || ch in '0'..'9' || ch == ':' || ch == '_' -> {
                        builder.append(ch)
                        previousDash = false
                    }

                    ch == '-' || ch.isWhitespace() || ch == '/' || ch == '.' -> {
                        if (!previousDash && builder.isNotEmpty()) {
                            builder.append('-')
                            previousDash = true
                        }
                    }
                }
                if (builder.length >= DIAGNOSTIC_SOURCE_MAX_LENGTH) return@forEach
            }
            return builder
                .toString()
                .trim('-', ':', '_')
                .ifBlank { UNKNOWN_TRIGGER_SOURCE }
                .take(DIAGNOSTIC_SOURCE_MAX_LENGTH)
        }

        internal fun buildDiagnosticLogLine(
            action: String,
            source: String,
            userInitiated: Boolean,
            fromWatchdog: Boolean,
            startId: Int,
            flags: Int,
        ): String {
            val normalizedAction = action.ifBlank { "UNKNOWN" }
            val normalizedSource = normalizeTriggerSource(source)
            val base = StringBuilder()
                .append("[andClaw][Diag] received action=")
                .append(normalizedAction)
                .append(" source=")
                .append(normalizedSource)
                .append(" user=")
                .append(userInitiated)
                .append(" watchdog=")
                .append(fromWatchdog)
                .append(" startId=")
                .append(startId)
            if (flags != 0) {
                base.append(" flags=").append(flags)
            }
            return base.toString()
        }

        internal fun buildStartupFailureDiagnosticLine(
            stage: String,
            cause: String,
            detail: String? = null,
            finalStatus: GatewayStatus? = null,
            errorMessage: String? = null,
        ): String {
            val base = StringBuilder()
                .append("[andClaw][Diag] startup_failure stage=")
                .append(normalizeTriggerSource(stage))
                .append(" cause=")
                .append(normalizeTriggerSource(cause))
            detail?.trim()?.takeIf { it.isNotEmpty() }?.let {
                base.append(" detail=").append(normalizeTriggerSource(it))
            }
            finalStatus?.let {
                base.append(" finalStatus=").append(it.name)
            }
            errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.let {
                base.append(" error=").append(normalizeTriggerSource(it))
            }
            return base.toString()
        }

        internal fun shouldAttachOnStickyRecovery(
            status: GatewayStatus?,
            runningHealthy: Boolean,
        ): Boolean {
            return status == GatewayStatus.RUNNING && runningHealthy
        }

        internal fun shouldRejoinStickyStartup(
            status: GatewayStatus?,
            hasActiveStartupAttempt: Boolean,
        ): Boolean {
            return hasActiveStartupAttempt && status == GatewayStatus.STARTING
        }

        internal fun determineAttachRecoveryAction(
            status: GatewayStatus?,
            runningHealthy: Boolean,
        ): AttachRecoveryAction {
            return when {
                status == GatewayStatus.RUNNING && runningHealthy -> AttachRecoveryAction.ATTACH
                status == GatewayStatus.RUNNING -> AttachRecoveryAction.RESTART
                else -> AttachRecoveryAction.START
            }
        }

        internal fun isSurvivorPidAlive(pid: Int?): Boolean {
            if (pid == null || pid <= 0) return false
            return File("/proc/$pid").exists()
        }

        internal fun shouldAttachOnStickyPreflight(
            shouldRecover: Boolean,
            metadata: GatewaySurvivorMetadata?,
            pidAlive: Boolean,
            healthCheckPassed: Boolean,
        ): Boolean {
            if (!shouldRecover) return false
            if (metadata == null) return false
            if (metadata.startupAttemptActive) return false
            if (!pidAlive || !healthCheckPassed) return false
            val endpoint = metadata.wsEndpoint.trim().lowercase()
            return endpoint == DEFAULT_GATEWAY_WS_ENDPOINT || endpoint == "localhost:18789"
        }

        internal fun shouldPersistGatewaySurvivorMetadata(
            status: GatewayStatus,
            pid: Int?,
            hasActiveStartupAttempt: Boolean,
        ): Boolean {
            if (pid == null || pid <= 0) return false
            return status == GatewayStatus.RUNNING ||
                (status == GatewayStatus.STARTING && hasActiveStartupAttempt)
        }

        internal fun survivorMetadataStartupAttemptActive(
            status: GatewayStatus,
            hasActiveStartupAttempt: Boolean,
        ): Boolean {
            return status == GatewayStatus.STARTING && hasActiveStartupAttempt
        }

        fun start(
            context: Context,
            fromWatchdog: Boolean = false,
            userInitiated: Boolean = true,
            source: String = UNKNOWN_TRIGGER_SOURCE,
        ) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FROM_WATCHDOG, fromWatchdog)
                putExtra(EXTRA_USER_INITIATED, userInitiated)
                putExtra(EXTRA_TRIGGER_SOURCE, normalizeTriggerSource(source))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun restart(
            context: Context,
            userInitiated: Boolean = true,
            fromWatchdog: Boolean = false,
            source: String = UNKNOWN_TRIGGER_SOURCE,
        ) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_RESTART
                putExtra(EXTRA_USER_INITIATED, userInitiated)
                putExtra(EXTRA_FROM_WATCHDOG, fromWatchdog)
                putExtra(EXTRA_TRIGGER_SOURCE, normalizeTriggerSource(source))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, source: String = UNKNOWN_TRIGGER_SOURCE) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TRIGGER_SOURCE, normalizeTriggerSource(source))
            }
            context.startService(intent)
        }

        fun attachToRunningGateway(
            context: Context,
            fromWatchdog: Boolean = false,
            source: String = UNKNOWN_TRIGGER_SOURCE,
        ) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_ATTACH
                putExtra(EXTRA_FROM_WATCHDOG, fromWatchdog)
                putExtra(EXTRA_USER_INITIATED, false)
                putExtra(EXTRA_TRIGGER_SOURCE, normalizeTriggerSource(source))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopForMissingModelSelection(
            context: Context,
            source: String = "gateway:missing_model_selection",
        ) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP_FOR_MISSING_MODEL
                putExtra(EXTRA_TRIGGER_SOURCE, normalizeTriggerSource(source))
            }
            context.startService(intent)
        }

        fun startWhatsAppLoginGuard(
            context: Context,
            timeoutMs: Long = WHATSAPP_LOGIN_WAKE_LOCK_TIMEOUT_MS,
        ) {
            // 로그인 가드는 "이미 떠있는 GatewayService의 보조 wakelock" 용도다.
            // 서비스가 꺼져있을 때 guard 시작으로 서비스를 새로 띄우면
            // guard 종료 후에도 FGS가 남는 lifecycle 문제가 생길 수 있어 no-op 처리한다.
            val service = _instance ?: return
            val normalizedTimeoutMs = timeoutMs.coerceIn(15_000L, 10L * 60L * 1000L)
            service.acquireWhatsAppLoginWakeLock(normalizedTimeoutMs)
        }

        fun stopWhatsAppLoginGuard() {
            // Guard STOP은 "서비스를 깨워서 액션 전달"이 아니라,
            // 이미 살아있는 서비스 인스턴스의 로그인 전용 wakelock만 해제해야 한다.
            // (서비스가 꺼져있는 경우에는 no-op)
            _instance?.releaseWhatsAppLoginWakeLock()
        }
    }

    private lateinit var pm: ProcessManager
    private lateinit var prefs: PreferencesManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val inAppReviewCounterMutex = Mutex()
    private var actionJob: Job? = null
    private var actionJobType: GatewayActionType? = null
    private val actionSequence = AtomicLong(0L)
    private val desiredRunningMutex = Mutex()
    private var lastPersistedSurvivorPid: Int? = null
    private var lastPersistedSurvivorStartupAttemptActive: Boolean? = null
    private val wakeLockGuard = Any()
    private var activeWakeLock: PowerManager.WakeLock? = null
    private val whatsappLoginWakeLockGuard = Any()
    private var whatsappLoginWakeLock: PowerManager.WakeLock? = null
    private val chargingWakeLockGuard = Any()
    private var chargingWakeLock: PowerManager.WakeLock? = null
    private var isBatteryReceiverRegistered = false
    private val batteryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            updateChargingWakeLock(shouldHoldChargingWakeLock(status))
        }
    }

    override fun onCreate() {
        super.onCreate()

        val app = application as AndClawApp
        pm = app.processManager
        prefs = app.preferencesManager
        bindRetainedProcessManager(pm)
        _instance = this

        createNotificationChannel()
        createPairingNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_waiting)))
        startChargingWakeLockController()

        // 상태 변화 → 알림 업데이트
        serviceScope.launch {
            var lastNotificationText: String? = null
            var lastGatewayStatus: GatewayStatus? = null
            var reviewRunTracker = InAppReviewRunTracker()
            pm.gatewayState.collect { state ->
                val status = state.status
                val hasActiveStartupAttempt = pm.hasActiveStartupAttempt()
                if (shouldPersistGatewaySurvivorMetadata(status, state.pid, hasActiveStartupAttempt)) {
                    val startupAttemptActive = survivorMetadataStartupAttemptActive(
                        status,
                        hasActiveStartupAttempt,
                    )
                    if (
                        lastPersistedSurvivorPid != state.pid ||
                        lastPersistedSurvivorStartupAttemptActive != startupAttemptActive
                    ) {
                        updateGatewaySurvivorMetadata(
                            startupAttemptActive = startupAttemptActive,
                            pidOverride = state.pid,
                        )
                        lastPersistedSurvivorPid = state.pid
                        lastPersistedSurvivorStartupAttemptActive = startupAttemptActive
                    }
                } else {
                    lastPersistedSurvivorPid = null
                    lastPersistedSurvivorStartupAttemptActive = null
                }
                if (status != lastGatewayStatus) {
                    val (updatedTracker, counterAction) = advanceInAppReviewRunTracker(
                        tracker = reviewRunTracker,
                        status = status,
                        previousStatus = lastGatewayStatus,
                    )
                    reviewRunTracker = updatedTracker
                    if (counterAction != InAppReviewCounterAction.NONE) {
                        applyInAppReviewCounterUpdateSerialized(counterAction, status)
                    }
                    lastGatewayStatus = status
                }

                val text = when (status) {
                    GatewayStatus.RUNNING -> getString(R.string.notification_running)
                    GatewayStatus.STARTING -> getString(R.string.notification_starting)
                    GatewayStatus.STOPPING -> getString(R.string.notification_stopping)
                    GatewayStatus.ERROR -> getString(R.string.notification_error, state.errorMessage?.take(50) ?: getString(R.string.notification_unknown))
                    GatewayStatus.STOPPED -> getString(R.string.notification_stopped)
                }
                if (text == lastNotificationText) return@collect
                lastNotificationText = text
                updateNotification(text)
            }
        }

        // 페어링 요청 → 푸시 알림
        serviceScope.launch {
            pm.pairingRequests.collect { requests ->
                if (requests.isNotEmpty()) {
                    postPairingNotification(requests)
                } else {
                    cancelPairingNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_WHATSAPP_LOGIN_GUARD_START) {
            val timeoutMs = intent.getLongExtra(EXTRA_WAKE_LOCK_TIMEOUT_MS, WHATSAPP_LOGIN_WAKE_LOCK_TIMEOUT_MS)
                .coerceIn(15_000L, 10L * 60L * 1000L)
            acquireWhatsAppLoginWakeLock(timeoutMs)
            return START_STICKY
        }
        if (action == ACTION_WHATSAPP_LOGIN_GUARD_STOP) {
            releaseWhatsAppLoginWakeLock()
            return START_STICKY
        }
        if (action == ACTION_VALIDATE_RUNTIME) {
            val requirePatchedNodeOptions = intent.getBooleanExtra(EXTRA_VALIDATE_REQUIRE_PATCHED_NODE_OPTIONS, false)
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    handleRuntimeValidation(startId, requirePatchedNodeOptions)
                }
            }
            return START_NOT_STICKY
        }
        if (action == null) {
            pm.appendGatewayDiagnosticLog(
                buildDiagnosticLogLine(
                    action = "START",
                    source = STICKY_RECOVERY_TRIGGER_SOURCE,
                    userInitiated = false,
                    fromWatchdog = true,
                    startId = startId,
                    flags = flags,
                ),
            )
            if (isWhatsAppLoginGuardActive()) {
                android.util.Log.i(
                    "GatewayService",
                    "Skip sticky recovery start while WhatsApp login guard is active",
                )
                return START_STICKY
            }
            // START_STICKY 재생성(null intent) 시 이전 사용자 의도가 running이면 복구를 재시도한다.
            runAction(GatewayActionType.START, startId) { actionToken, actionStartId ->
                val shouldRecover = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
                val survivorMetadata = prefs.getGatewaySurvivorMetadata()
                val survivorPidAlive = isSurvivorPidAlive(survivorMetadata?.pid)
                val survivorHealthy = if (survivorPidAlive) {
                    pm.probeGatewayHealthDirect(timeoutMs = STICKY_PREFLIGHT_HEALTH_PROBE_TIMEOUT_MS)
                } else {
                    false
                }
                val shouldPreflightAttach = shouldAttachOnStickyPreflight(
                    shouldRecover = shouldRecover,
                    metadata = survivorMetadata,
                    pidAlive = survivorPidAlive,
                    healthCheckPassed = survivorHealthy,
                )
                var stickyStatus = pm.gatewayState.value.status
                val stickyHealthy = if (stickyStatus == GatewayStatus.RUNNING) {
                    val healthy = pm.probeGatewayHealth(timeoutMs = ATTACH_HEALTH_PROBE_TIMEOUT_MS)
                    stickyStatus = pm.gatewayState.value.status
                    stickyStatus != GatewayStatus.RUNNING || healthy
                } else {
                    true
                }
                val shouldAttach = shouldAttachOnStickyRecovery(stickyStatus, stickyHealthy)
                val shouldRejoinStartup = shouldRejoinStickyStartup(
                    status = stickyStatus,
                    hasActiveStartupAttempt = pm.hasActiveStartupAttempt(),
                )
                pm.appendGatewayDiagnosticLog(
                    "[andClaw][Diag] auto_start source=$STICKY_RECOVERY_TRIGGER_SOURCE shouldRecover=$shouldRecover preflightAttach=$shouldPreflightAttach attach=$shouldAttach rejoin=$shouldRejoinStartup status=${stickyStatus?.name ?: "null"} healthy=$stickyHealthy startId=$actionStartId",
                )
                if (!shouldRecover) {
                    GatewayWatchdogReceiver.cancel(applicationContext)
                    prefs.clearGatewaySurvivorMetadata()
                    stopServiceForeground(actionStartId)
                    return@runAction
                }
                if (shouldPreflightAttach) {
                    handleAttach(
                        actionToken = actionToken,
                        startId = actionStartId,
                    )
                    return@runAction
                }
                if (shouldAttach) {
                    handleAttach(actionToken = actionToken, startId = actionStartId)
                    return@runAction
                }
                if (shouldRejoinStartup) {
                    val runtime = resolveExecutionRuntimeForStartup()
                    val startupTimeoutMs = resolveGatewayStartupTerminalTimeoutMs(runtime)
                    withTimedWakeLock(startupTimeoutMs) {
                        val finalStatus = awaitGatewayStartupTerminalState(startupTimeoutMs)
                        if (!isActionCurrent(actionToken)) return@withTimedWakeLock
                        handleStartupOutcome(finalStatus, actionToken)
                    }
                    return@runAction
                }
                if (stickyStatus == GatewayStatus.RUNNING) {
                    handleRestart(
                        userInitiated = false,
                        fromWatchdog = true,
                        actionToken = actionToken,
                        startId = actionStartId,
                    )
                    return@runAction
                }
                handleStart(
                    fromWatchdog = true,
                    userInitiated = false,
                    actionToken = actionToken,
                    startId = actionStartId,
                )
            }
            return START_STICKY
        }

        val fromWatchdog = intent.getBooleanExtra(EXTRA_FROM_WATCHDOG, false)
        val userInitiated = intent.getBooleanExtra(EXTRA_USER_INITIATED, true)
        val source = intent.getStringExtra(EXTRA_TRIGGER_SOURCE) ?: UNKNOWN_TRIGGER_SOURCE
        pm.appendGatewayDiagnosticLog(
            buildDiagnosticLogLine(
                action = action.substringAfterLast('.'),
                source = source,
                userInitiated = userInitiated,
                fromWatchdog = fromWatchdog,
                startId = startId,
                flags = flags,
            ),
        )
        when (action) {
            ACTION_START -> {
                if (!userInitiated && isWhatsAppLoginGuardActive()) {
                    android.util.Log.i(
                        "GatewayService",
                        "Skip auto start while WhatsApp login guard is active",
                    )
                    return START_STICKY
                }
                runAction(GatewayActionType.START, startId) { actionToken, actionStartId ->
                    handleStart(
                        fromWatchdog = fromWatchdog,
                        userInitiated = userInitiated,
                        actionToken = actionToken,
                        startId = actionStartId,
                    )
                }
            }
            ACTION_RESTART -> {
                if (!userInitiated && isWhatsAppLoginGuardActive()) {
                    android.util.Log.i(
                        "GatewayService",
                        "Skip auto restart while WhatsApp login guard is active",
                    )
                    return START_STICKY
                }
                runAction(GatewayActionType.RESTART, startId) { actionToken, actionStartId ->
                    handleRestart(
                        userInitiated = userInitiated,
                        fromWatchdog = fromWatchdog,
                        actionToken = actionToken,
                        startId = actionStartId,
                    )
                }
            }
            ACTION_ATTACH -> {
                runAction(GatewayActionType.ATTACH, startId) { actionToken, actionStartId ->
                    handleAttach(
                        actionToken = actionToken,
                        startId = actionStartId,
                    )
                }
            }
            ACTION_STOP -> {
                runAction(GatewayActionType.STOP, startId) { actionToken, actionStartId ->
                    handleStop(actionToken = actionToken, startId = actionStartId)
                }
            }
            ACTION_STOP_FOR_MISSING_MODEL -> {
                runAction(GatewayActionType.STOP, startId) { _, actionStartId ->
                    stopForMissingModelSelection(actionStartId)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun handleRuntimeValidation(startId: Int, requirePatchedNodeOptions: Boolean) {
        val app = application as AndClawApp
        val resultFile = File(filesDir, VALIDATION_RESULT_FILE_NAME)
        resultFile.delete()

        val result = runCatching {
            app.setupManager.runOpenClawValidationInCurrentProcess(requirePatchedNodeOptions)
        }.fold(
            onSuccess = { commandResult ->
                JSONObject().apply {
                    put("exitCode", commandResult?.exitCode ?: JSONObject.NULL)
                    put("output", commandResult?.output ?: JSONObject.NULL)
                    put("timedOut", commandResult?.timedOut ?: false)
                    put("error", JSONObject.NULL)
                }
            },
            onFailure = { error ->
                JSONObject().apply {
                    put("exitCode", JSONObject.NULL)
                    put("output", JSONObject.NULL)
                    put("timedOut", false)
                    put("error", error.message ?: error.javaClass.simpleName)
                }
            },
        )

        resultFile.writeText(result.toString())
        stopServiceForeground(startId)
    }

    override fun onDestroy() {
        stopChargingWakeLockController()
        releaseActiveWakeLock()
        releaseWhatsAppLoginWakeLock()
        serviceScope.cancel()
        _instance = null
        super.onDestroy()
    }

    private suspend fun applyInAppReviewCounterUpdate(
        action: InAppReviewCounterAction,
        status: GatewayStatus,
    ) {
        val counterResult = runCatching {
            when (action) {
                InAppReviewCounterAction.NONE -> Unit
                InAppReviewCounterAction.INCREMENT -> prefs.incrementInAppReviewGatewayHealthyRunCount()
                InAppReviewCounterAction.RESET -> prefs.resetInAppReviewGatewayHealthyRunCount()
            }
        }
        if (counterResult.isFailure) {
            android.util.Log.e(
                "GatewayService",
                "Failed to update in-app review counter on gateway status change: $status",
                counterResult.exceptionOrNull(),
            )
        }
    }

    private suspend fun applyInAppReviewCounterUpdateSerialized(
        action: InAppReviewCounterAction,
        status: GatewayStatus,
    ) {
        inAppReviewCounterMutex.withLock {
            withContext(Dispatchers.IO) {
                applyInAppReviewCounterUpdate(action, status)
            }
        }
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TRIGGER_SOURCE, normalizeTriggerSource("notification:stop_action"))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("andClaw")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_action_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ── Pairing Notification ──

    private fun createPairingNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PAIRING_CHANNEL_ID,
                getString(R.string.pairing_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications for new pairing requests"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun postPairingNotification(requests: List<PairingRequest>) {
        val first = requests.first()
        val channelName = first.channel.replaceFirstChar { it.uppercase() }
        val displayName = if (first.username.isNotBlank()) first.username else first.code
        val text = getString(R.string.pairing_notification_text, channelName, displayName)

        val openIntent = PendingIntent.getActivity(
            this,
            PAIRING_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_PAIRING_REQUESTS
                putExtra(MainActivity.EXTRA_OPEN_PAIRING_REQUESTS, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setContentTitle(getString(R.string.pairing_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(PAIRING_NOTIFICATION_ID, notification)
    }

    private fun cancelPairingNotification() {
        getSystemService(NotificationManager::class.java)
            .cancel(PAIRING_NOTIFICATION_ID)
    }

    // ── WakeLock ──

    private suspend fun handleAttach(actionToken: Long, startId: Int) {
        if (!isActionCurrent(actionToken)) return
        var status = pm.gatewayState.value.status
        val runningHealthy = if (status == GatewayStatus.RUNNING) {
            val healthy = pm.probeGatewayHealth(timeoutMs = ATTACH_HEALTH_PROBE_TIMEOUT_MS)
            status = pm.gatewayState.value.status
            status != GatewayStatus.RUNNING || healthy
        } else {
            true
        }
        if (!isActionCurrent(actionToken)) return
        when (determineAttachRecoveryAction(status, runningHealthy)) {
            AttachRecoveryAction.ATTACH -> Unit
            AttachRecoveryAction.RESTART -> {
                pm.appendGatewayDiagnosticLog(
                    "[andClaw][Diag] attach_fallback action=restart status=${status?.name ?: "null"} healthy=$runningHealthy",
                )
                handleRestart(
                    userInitiated = false,
                    fromWatchdog = true,
                    actionToken = actionToken,
                    startId = startId,
                )
                return
            }
            AttachRecoveryAction.START -> {
                pm.appendGatewayDiagnosticLog(
                    "[andClaw][Diag] attach_fallback action=start status=${status?.name ?: "null"} healthy=$runningHealthy",
                )
                handleStart(
                    fromWatchdog = true,
                    userInitiated = false,
                    actionToken = actionToken,
                    startId = startId,
                )
                return
            }
        }
        if (!isActionCurrent(actionToken)) return
        pm.clearStartupAttempt()
        updateGatewaySurvivorMetadata(startupAttemptActive = false)
        val shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
        if (!isActionCurrent(actionToken)) return
        if (shouldKeepRunning) {
            GatewayWatchdogReceiver.schedule(applicationContext)
        } else {
            stopServiceForeground(startId)
        }
    }

    private suspend fun handleStart(
        fromWatchdog: Boolean,
        userInitiated: Boolean,
        actionToken: Long,
        startId: Int,
    ) {
        if (!isActionCurrent(actionToken)) return

        if (fromWatchdog) {
            val shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
            if (!shouldKeepRunning) {
                pm.appendGatewayDiagnosticLog(
                    buildStartupFailureDiagnosticLine(
                        stage = "handle_start",
                        cause = "watchdog_start_cancelled",
                        detail = "desired_running_false",
                    ),
                )
                GatewayWatchdogReceiver.cancel(applicationContext)
                stopServiceForeground(startId)
                return
            }
        }

        if (!isActionCurrent(actionToken)) return
        val app = application as AndClawApp
        val bundleUpdateRequired = runCatching { app.setupManager.isBundleUpdateRequired() }
            .getOrDefault(true)
        val runtime = resolveExecutionRuntimeForStartup()
        val startupTimeoutMs = resolveGatewayStartupTerminalTimeoutMs(runtime)

        val startWakeLockTimeoutMs = resolveStartWakeLockTimeoutMs(
            fromWatchdog = fromWatchdog,
            userInitiated = userInitiated,
            bundleUpdateRequired = bundleUpdateRequired,
        ).coerceAtLeast(startupTimeoutMs)

        // 사용자가 START를 요청한 순간부터 desired-running 상태를 유지한다.
        // 단, bundle update가 끝나기 전에는 watchdog를 예약하지 않아
        // 장시간 업데이트 중 watchdog가 STOPPED 상태를 오인해 START를 선점하는 루프를 막는다.
        if (
            !setDesiredRunningAndWatchdog(
                shouldRun = true,
                actionToken = actionToken,
                updateWatchdog = false,
            )
        ) {
            return
        }

        pm.markStartupAttemptStarted()
        updateGatewaySurvivorMetadata(startupAttemptActive = true)
        GatewayWatchdogReceiver.schedule(
            applicationContext,
            delayMs = startWakeLockTimeoutMs,
        )

        withTimedWakeLock(startWakeLockTimeoutMs) {
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock

            try {
                val result = app.setupManager.updateBundleIfNeededWithPolicy(
                    includeOpenClawAssetUpdate = false,
                )
                if (result.outcome == BundleUpdateOutcome.FAILED) {
                    android.util.Log.e("GatewayService", "Bundle update policy run failed: ${result.errorMessage}")
                    pm.appendGatewayDiagnosticLog(
                        buildStartupFailureDiagnosticLine(
                            stage = "handle_start",
                            cause = "bundle_update_failed",
                            detail = result.errorMessage,
                        ),
                    )
                    if (isActionCurrent(actionToken)) {
                        pm.clearStartupAttempt()
                        // 번들 업데이트 실패 시 부분 설치 상태로 런타임을 시작하면 ENOENT로 연쇄 실패할 수 있다.
                        // 사용자 의도는 유지하고 watchdog 복구를 위해 desired-running 상태만 유지한다.
                        markDesiredRunningAndScheduleWatchdog(actionToken)
                    }
                    return@withTimedWakeLock
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("GatewayService", "Bundle update failed during ACTION_START", error)
                pm.appendGatewayDiagnosticLog(
                    buildStartupFailureDiagnosticLine(
                        stage = "handle_start",
                        cause = "bundle_update_exception",
                        detail = error.message ?: error.javaClass.simpleName,
                    ),
                )
                if (isActionCurrent(actionToken)) {
                    pm.clearStartupAttempt()
                    markDesiredRunningAndScheduleWatchdog(actionToken)
                }
                return@withTimedWakeLock
            }

            if (!isActionCurrent(actionToken)) return@withTimedWakeLock

            prefs.ensureCurrentProviderModelSelection()
            val launchConfig = prefs.getGatewayLaunchConfigSnapshot()

            if (!setDesiredRunningAndWatchdog(shouldRun = true, actionToken = actionToken)) {
                return@withTimedWakeLock
            }

            if (launchConfig.selectedModelEntries.isEmpty()) {
                pm.appendGatewayDiagnosticLog(
                    buildStartupFailureDiagnosticLine(
                        stage = "handle_start",
                        cause = "missing_model_selection",
                    ),
                )
                stopForMissingModelSelection(startId)
                return@withTimedWakeLock
            }

            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            pm.start(
                launchConfig.apiProvider,
                launchConfig.apiKey,
                launchConfig.selectedModel,
                launchConfig.selectedModelEntries.map { entry ->
                    ProcessManager.ModelSelectionEntry(
                        id = entry.id,
                        supportsReasoning = entry.supportsReasoning,
                        supportsImages = entry.supportsImages,
                        contextLength = entry.contextLength,
                        maxOutputTokens = entry.maxOutputTokens,
                    )
                },
                launchConfig.primaryModelId,
                launchConfig.openAiCompatibleBaseUrl,
                launchConfig.ollamaBaseUrl,
                launchConfig.channelConfig,
                launchConfig.modelReasoning,
                launchConfig.modelImages,
                launchConfig.modelContext,
                launchConfig.modelMaxOutput,
                launchConfig.braveSearchApiKey,
                launchConfig.hasExplicitMemorySearchPrefs,
                launchConfig.memorySearchEnabled,
                launchConfig.memorySearchProvider,
                launchConfig.memorySearchApiKey,
                survivorMetadata = if (fromWatchdog || !userInitiated) {
                    prefs.getGatewaySurvivorMetadata()
                } else {
                    null
                },
                probePhase = pm.gatewayProbePhase(isRestart = false),
            )

            val finalStatus = awaitGatewayStartupTerminalState(startupTimeoutMs)
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            handleStartupOutcome(finalStatus, actionToken)
        }
    }

    private suspend fun handleRestart(
        userInitiated: Boolean,
        fromWatchdog: Boolean,
        actionToken: Long,
        startId: Int,
    ) {
        if (!isActionCurrent(actionToken)) return

        if (!userInitiated) {
            val shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
            if (fromWatchdog && !shouldKeepRunning) {
                pm.appendGatewayDiagnosticLog(
                    buildStartupFailureDiagnosticLine(
                        stage = "handle_restart",
                        cause = "watchdog_restart_cancelled",
                        detail = "desired_running_false",
                    ),
                )
                GatewayWatchdogReceiver.cancel(applicationContext)
                stopServiceForeground(startId)
                return
            }

            val status = pm.gatewayState.value.status
            val alreadyActive = status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
            if (!fromWatchdog && !shouldKeepRunning && !alreadyActive) {
                pm.appendGatewayDiagnosticLog(
                    buildStartupFailureDiagnosticLine(
                        stage = "handle_restart",
                        cause = "auto_restart_cancelled",
                        detail = "desired_running_false",
                    ),
                )
                GatewayWatchdogReceiver.cancel(applicationContext)
                stopServiceForeground(startId)
                return
            }
        }

        // RESTART는 사용자 의도상 running 유지이므로 즉시 desired-running=true를 반영한다.
        if (!setDesiredRunningAndWatchdog(shouldRun = true, actionToken = actionToken)) return
        pm.markStartupAttemptStarted()
        updateGatewaySurvivorMetadata(startupAttemptActive = true)
        val runtime = resolveExecutionRuntimeForStartup()
        val restartTimeoutMs = resolveGatewayRestartTimeoutMs(runtime)

        withTimedWakeLock(restartTimeoutMs) {
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock

            prefs.ensureCurrentProviderModelSelection()
            val launchConfig = prefs.getGatewayLaunchConfigSnapshot()

            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            if (launchConfig.selectedModelEntries.isEmpty()) {
                pm.appendGatewayDiagnosticLog(
                    buildStartupFailureDiagnosticLine(
                        stage = "handle_restart",
                        cause = "missing_model_selection",
                    ),
                )
                stopForMissingModelSelection(startId)
                return@withTimedWakeLock
            }
            pm.restart(
                launchConfig.apiProvider,
                launchConfig.apiKey,
                launchConfig.selectedModel,
                launchConfig.selectedModelEntries.map { entry ->
                    ProcessManager.ModelSelectionEntry(
                        id = entry.id,
                        supportsReasoning = entry.supportsReasoning,
                        supportsImages = entry.supportsImages,
                        contextLength = entry.contextLength,
                        maxOutputTokens = entry.maxOutputTokens,
                    )
                },
                launchConfig.primaryModelId,
                launchConfig.openAiCompatibleBaseUrl,
                launchConfig.ollamaBaseUrl,
                launchConfig.channelConfig,
                launchConfig.modelReasoning,
                launchConfig.modelImages,
                launchConfig.modelContext,
                launchConfig.modelMaxOutput,
                launchConfig.braveSearchApiKey,
                launchConfig.hasExplicitMemorySearchPrefs,
                launchConfig.memorySearchEnabled,
                launchConfig.memorySearchProvider,
                launchConfig.memorySearchApiKey,
                probePhase = pm.gatewayProbePhase(isRestart = true),
            )
            val finalStatus = awaitGatewayStartupTerminalState(restartTimeoutMs)
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            handleStartupOutcome(finalStatus, actionToken)
        }
    }

    private suspend fun handleStop(actionToken: Long, startId: Int) {
        // STOP 선점 시 in-flight START/RESTART가 늦게 unwind 되더라도 즉시 wake lock을 해제한다.
        releaseActiveWakeLock()

        // STOP 요청은 최신 토큰 여부와 무관하게 항상 desired-running=false를 강제 반영한다.
        forceDesiredStopped()
        pm.stop()
        stopServiceForeground(startId)
    }

    private suspend fun stopForMissingModelSelection(startId: Int) {
        releaseActiveWakeLock()
        pm.stop()
        pm.setStoppedNotice(getString(R.string.settings_model_none_found))
        forceDesiredStopped()
        stopServiceForeground(startId)
    }

    private suspend fun handleStartupOutcome(finalStatus: GatewayStatus?, actionToken: Long) {
        if (!isActionCurrent(actionToken)) return

        if (finalStatus == GatewayStatus.RUNNING) {
            updateGatewaySurvivorMetadata(startupAttemptActive = false)
            markDesiredRunningAndScheduleWatchdog(actionToken)
            return
        }

        pm.appendGatewayDiagnosticLog(
            buildStartupFailureDiagnosticLine(
                stage = "startup_terminal",
                cause = when (finalStatus) {
                    null -> "startup_timeout"
                    GatewayStatus.ERROR -> "startup_terminal_error"
                    GatewayStatus.STOPPED -> "startup_terminal_stopped"
                    GatewayStatus.STOPPING -> "startup_terminal_stopping"
                    GatewayStatus.STARTING -> "startup_terminal_starting"
                    GatewayStatus.RUNNING -> "startup_terminal_running"
                },
                finalStatus = finalStatus,
                errorMessage = pm.gatewayState.value.errorMessage,
            ),
        )

        if (finalStatus == null) {
            // STARTING 상태 고착 방지
            pm.stop()
        }
        prefs.clearGatewaySurvivorMetadata()
        // START/RESTART 실패는 사용자 의도를 꺾지 않는다.
        // transient failure 후에도 watchdog/boot/update 경로가 복구를 계속 시도해야 한다.
        markDesiredRunningAndScheduleWatchdog(actionToken)
    }

    private suspend fun markDesiredRunningAndScheduleWatchdog(actionToken: Long) {
        setDesiredRunningAndWatchdog(shouldRun = true, actionToken = actionToken)
    }

    private suspend fun forceDesiredStopped() {
        desiredRunningMutex.withLock {
            prefs.setGatewayWasRunning(false)
            prefs.clearGatewaySurvivorMetadata()
            GatewayWatchdogReceiver.cancel(applicationContext)
        }
    }

    private suspend fun updateGatewaySurvivorMetadata(
        startupAttemptActive: Boolean,
        pidOverride: Int? = null,
    ) {
        val pid = pidOverride ?: pm.gatewayState.value.pid ?: return
        val now = System.currentTimeMillis()
        prefs.setGatewaySurvivorMetadata(
            GatewaySurvivorMetadata(
                pid = pid,
                launchedAtEpochMs = now,
                wsEndpoint = DEFAULT_GATEWAY_WS_ENDPOINT,
                startupAttemptActive = startupAttemptActive,
                updatedAtEpochMs = now,
            ),
        )
    }

    private suspend fun setDesiredRunningAndWatchdog(
        shouldRun: Boolean,
        actionToken: Long,
        updateWatchdog: Boolean = true,
    ): Boolean {
        return desiredRunningMutex.withLock {
            if (!isActionCurrent(actionToken)) {
                return@withLock false
            }

            prefs.setGatewayWasRunning(shouldRun)

            if (!isActionCurrent(actionToken)) {
                return@withLock false
            }

            if (updateWatchdog) {
                if (shouldRun) {
                    GatewayWatchdogReceiver.schedule(applicationContext)
                } else {
                    GatewayWatchdogReceiver.cancel(applicationContext)
                }
            }

            isActionCurrent(actionToken)
        }
    }

    private suspend fun <T> withTimedWakeLock(timeoutMs: Long, block: suspend () -> T): T {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("deprecation")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "andClaw::GatewayWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        registerActiveWakeLock(wakeLock)
        return try {
            block()
        } finally {
            releaseWakeLockInstance(wakeLock)
        }
    }

    private suspend fun awaitGatewayStartupTerminalState(timeoutMs: Long): GatewayStatus? {
        val finalState = withTimeoutOrNull(timeoutMs) {
            pm.gatewayState.first { state ->
                state.status == GatewayStatus.RUNNING ||
                    state.status == GatewayStatus.ERROR ||
                    state.status == GatewayStatus.STOPPED
            }
        }
        if (finalState == null) {
            android.util.Log.w(
                "GatewayService",
                "Timed out waiting for gateway startup terminal state after ${timeoutMs}ms",
            )
            pm.appendGatewayDiagnosticLog(
                buildStartupFailureDiagnosticLine(
                    stage = "await_terminal_state",
                    cause = "startup_timeout",
                    detail = "timeout_${timeoutMs}ms",
                    errorMessage = pm.gatewayState.value.errorMessage,
                ),
            )
        }
        return finalState?.status
    }

    private suspend fun resolveExecutionRuntimeForStartup(): ExecutionRuntime {
        return ExecutionRuntime.fromStorageValue(prefs.executionRuntime.first())
    }

    private fun runAction(
        actionType: GatewayActionType,
        startId: Int,
        block: suspend (Long, Int) -> Unit,
    ) {
        val actionToken = actionSequence.incrementAndGet()
        val previousAction = actionJob
        val previousActionType = actionJobType
        actionJobType = actionType
        actionJob = serviceScope.launch {
            try {
                if (actionType == GatewayActionType.STOP) {
                    // STOP은 지연 없이 선점한다. 오래 걸리는 START/RESTART 종료를 기다리지 않는다.
                    previousAction?.cancel()
                    if (previousAction != null) {
                        pm.appendGatewayDiagnosticLog(
                            "[andClaw][Diag] action=${actionType.name} previous=${previousActionType?.name ?: "UNKNOWN"} policy=cancel_previous startId=$startId",
                        )
                        val stopToken = actionToken
                        serviceScope.launch {
                            try {
                                previousAction.join()
                            } catch (_: CancellationException) {
                                // ignore
                            }
                            withContext(Dispatchers.IO) {
                                if (!isActionCurrent(stopToken)) return@withContext
                                // STOP 선점 이후 이전 START/RESTART가 늦게 반영한 상태를 한 번 더 정리한다.
                                setDesiredRunningAndWatchdog(shouldRun = false, actionToken = stopToken)
                                pm.stop()
                            }
                        }
                    }
                } else {
                    if (previousActionType == GatewayActionType.STOP) {
                        if (previousAction != null) {
                            pm.appendGatewayDiagnosticLog(
                                "[andClaw][Diag] action=${actionType.name} previous=${previousActionType.name} policy=await_previous_stop startId=$startId",
                            )
                        }
                        // STOP 이후 START/RESTART가 들어오면 STOP 완료를 보장한 뒤 진행한다.
                        previousAction?.join()
                    } else {
                        if (previousAction != null) {
                            pm.appendGatewayDiagnosticLog(
                                "[andClaw][Diag] action=${actionType.name} previous=${previousActionType?.name ?: "UNKNOWN"} policy=cancel_and_join_previous startId=$startId",
                            )
                        }
                        previousAction?.cancelAndJoin()
                    }
                }
                withContext(Dispatchers.IO) {
                    if (!isActionCurrent(actionToken)) return@withContext
                    block(actionToken, startId)
                }
            } finally {
                if (isActionCurrent(actionToken)) {
                    actionJobType = null
                }
            }
        }
    }

    private fun isActionCurrent(actionToken: Long): Boolean = actionSequence.get() == actionToken

    private fun resolveStartWakeLockTimeoutMs(
        fromWatchdog: Boolean,
        userInitiated: Boolean,
        bundleUpdateRequired: Boolean,
    ): Long {
        if (fromWatchdog || !userInitiated || bundleUpdateRequired) return START_WAKE_LOCK_TIMEOUT_BACKGROUND_MS
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (powerManager.isInteractive) {
            START_WAKE_LOCK_TIMEOUT_MS
        } else {
            START_WAKE_LOCK_TIMEOUT_BACKGROUND_MS
        }
    }

    private suspend fun stopServiceForeground(startId: Int) {
        withContext(Dispatchers.Main) {
            if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun registerActiveWakeLock(wakeLock: PowerManager.WakeLock) {
        synchronized(wakeLockGuard) {
            activeWakeLock = wakeLock
        }
    }

    private fun releaseWakeLockInstance(wakeLock: PowerManager.WakeLock) {
        synchronized(wakeLockGuard) {
            if (activeWakeLock === wakeLock) {
                activeWakeLock = null
            }
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun releaseActiveWakeLock() {
        val wakeLock = synchronized(wakeLockGuard) {
            val current = activeWakeLock
            activeWakeLock = null
            current
        }
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    private fun acquireWhatsAppLoginWakeLock(timeoutMs: Long) {
        synchronized(whatsappLoginWakeLockGuard) {
            val lock = whatsappLoginWakeLock ?: run {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("deprecation")
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "andClaw::WhatsAppLoginWakeLock",
                ).apply { setReferenceCounted(false) }
            }
            if (lock.isHeld) {
                lock.release()
            }
            lock.acquire(timeoutMs)
            whatsappLoginWakeLock = lock
        }
    }

    private fun releaseWhatsAppLoginWakeLock() {
        val lock = synchronized(whatsappLoginWakeLockGuard) {
            val current = whatsappLoginWakeLock
            whatsappLoginWakeLock = null
            current
        }
        if (lock?.isHeld == true) {
            lock.release()
        }
    }

    private fun isWhatsAppLoginGuardActive(): Boolean {
        return synchronized(whatsappLoginWakeLockGuard) {
            whatsappLoginWakeLock?.isHeld == true
        }
    }

    private fun startChargingWakeLockController() {
        if (isBatteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryStateReceiver, filter)
        }
        isBatteryReceiverRegistered = true
        val status = stickyIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        updateChargingWakeLock(shouldHoldChargingWakeLock(status))
    }

    private fun stopChargingWakeLockController() {
        if (isBatteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryStateReceiver)
            } catch (_: IllegalArgumentException) {
                // ignore
            } finally {
                isBatteryReceiverRegistered = false
            }
        }
        releaseChargingWakeLock()
    }

    private fun updateChargingWakeLock(shouldHold: Boolean) {
        if (shouldHold) {
            acquireChargingWakeLock()
        } else {
            releaseChargingWakeLock()
        }
    }

    private fun acquireChargingWakeLock() {
        synchronized(chargingWakeLockGuard) {
            if (chargingWakeLock?.isHeld == true) return
            val lock = chargingWakeLock ?: run {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("deprecation")
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "andClaw::ChargingWakeLock",
                ).apply { setReferenceCounted(false) }
            }
            if (!lock.isHeld) {
                lock.acquire()
            }
            chargingWakeLock = lock
        }
    }

    private fun releaseChargingWakeLock() {
        val lock = synchronized(chargingWakeLockGuard) {
            val current = chargingWakeLock
            chargingWakeLock = null
            current
        }
        if (lock?.isHeld == true) {
            lock.release()
        }
    }
}
