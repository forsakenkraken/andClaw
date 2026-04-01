package com.coderred.andclaw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.coderred.andclaw.auth.OpenRouterAuth
import com.coderred.andclaw.proot.BundleUpdateOutcome
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.SetupStep
import com.coderred.andclaw.ui.navigation.AndClawNavGraph
import com.coderred.andclaw.service.GatewayService
import com.coderred.andclaw.ui.theme.AndClawTheme
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private enum class StartupBundleUpdateStatus {
    CHECKING,
    UPDATING,
    DONE,
}

private data class StartupOpenClawUpdatePrompt(
    val installedVersion: String?,
    val bundledVersion: String?,
)

private data class StartupOpenClawUpdateResult(
    val success: Boolean,
    val message: String,
)

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_PAIRING_REQUESTS = "com.coderred.andclaw.action.OPEN_PAIRING_REQUESTS"
        const val EXTRA_OPEN_PAIRING_REQUESTS = "open_pairing_requests"
    }

    private var authCallbackUri by mutableStateOf<Uri?>(null)
    private var openPairingRequestsOnLaunch by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        handleIncomingIntent(intent)

        val app = application as AndClawApp

        setContent {
            AndClawTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val isSetupCompleteRaw by app.preferencesManager.isSetupComplete
                        .collectAsState(initial = null)
                    val isOnboardingCompleteRaw by app.preferencesManager.isOnboardingComplete
                        .collectAsState(initial = null)

                    if (isSetupCompleteRaw == null || isOnboardingCompleteRaw == null) {
                        startupBundleUpdateScreen(step = SetupStep.CHECKING_PROOT)
                        return@Surface
                    }

                    val isSetupComplete = isSetupCompleteRaw == true
                    val isOnboardingComplete = isOnboardingCompleteRaw == true

                    var startupUpdateStatus by remember(isSetupComplete) {
                        mutableStateOf(
                            if (isSetupComplete) {
                                StartupBundleUpdateStatus.CHECKING
                            } else {
                                StartupBundleUpdateStatus.DONE
                            },
                        )
                    }
                    var startupUpdateStep by remember(isSetupComplete) {
                        mutableStateOf(SetupStep.CHECKING_PROOT)
                    }
                    var hasCheckedOpenClawUpdatePrompt by remember(isSetupComplete, isOnboardingComplete) {
                        mutableStateOf(false)
                    }
                    var startupOpenClawUpdatePrompt by remember {
                        mutableStateOf<StartupOpenClawUpdatePrompt?>(null)
                    }
                    var startupOpenClawSkipUntilNextVersion by remember {
                        mutableStateOf(false)
                    }
                    var startupOpenClawUpdateRunning by remember {
                        mutableStateOf(false)
                    }
                    var startupOpenClawUpdateResult by remember {
                        mutableStateOf<StartupOpenClawUpdateResult?>(null)
                    }
                    var hasCompletedOpenClawUpdatePromptCheck by remember(isSetupComplete, isOnboardingComplete) {
                        mutableStateOf(false)
                    }
                    var hasCheckedInAppReviewPrompt by remember(isSetupComplete, isOnboardingComplete) {
                        mutableStateOf(false)
                    }
                    val setupState by app.setupManager.state.collectAsState()
                    val scope = rememberCoroutineScope()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    var resumeSignal by remember { mutableStateOf(0) }

                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                resumeSignal += 1
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    LaunchedEffect(isSetupComplete) {
                        if (!isSetupComplete) {
                            startupUpdateStatus = StartupBundleUpdateStatus.DONE
                            return@LaunchedEffect
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.CHECKING
                        val needsBundleUpdate = withContext(Dispatchers.IO) {
                            withTimeoutOrNull(30_000L) {
                                app.setupManager.isBundleUpdateRequired()
                            } ?: false
                        }

                        if (!needsBundleUpdate) {
                            startupUpdateStatus = StartupBundleUpdateStatus.DONE
                            return@LaunchedEffect
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.UPDATING
                        startupUpdateStep = SetupStep.INSTALLING_TOOLS

                        try {
                            val result = withContext(Dispatchers.IO) {
                                app.setupManager.updateBundleIfNeededWithPolicy(onStepChanged = { step ->
                                    runOnUiThread { startupUpdateStep = step }
                                }, includeOpenClawAssetUpdate = false)
                            }
                            if (result.outcome == BundleUpdateOutcome.FAILED) {
                                Log.e("MainActivity", "Bundle update policy run failed: ${result.errorMessage}")
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.e("MainActivity", "Failed to update bundled assets on startup", error)
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.DONE
                    }

                    LaunchedEffect(
                        isSetupComplete,
                        isOnboardingComplete,
                        startupUpdateStatus,
                        startupOpenClawUpdateRunning,
                    ) {
                        if (!isSetupComplete || !isOnboardingComplete) return@LaunchedEffect
                        if (startupUpdateStatus != StartupBundleUpdateStatus.DONE) return@LaunchedEffect
                        if (startupOpenClawUpdateRunning) return@LaunchedEffect
                        if (hasCheckedOpenClawUpdatePrompt) return@LaunchedEffect
                        hasCheckedOpenClawUpdatePrompt = true
                        hasCompletedOpenClawUpdatePromptCheck = false

                        try {
                            val info = withContext(Dispatchers.IO) {
                                runCatching { app.setupManager.getOpenClawUpdateInfo() }.getOrNull()
                            } ?: return@LaunchedEffect

                            if (!info.updateAvailable) return@LaunchedEffect

                            val bundledVersion = info.bundledVersion?.trim().orEmpty()
                            if (bundledVersion.isNotEmpty()) {
                                val suppressedVersion = withContext(Dispatchers.IO) {
                                    app.preferencesManager.getOpenClawUpdatePromptSuppressedBundledVersion()
                                }?.trim().orEmpty()
                                if (suppressedVersion == bundledVersion) {
                                    return@LaunchedEffect
                                }
                            }

                            startupOpenClawUpdatePrompt = StartupOpenClawUpdatePrompt(
                                installedVersion = info.installedVersion,
                                bundledVersion = info.bundledVersion,
                            )
                        } finally {
                            hasCompletedOpenClawUpdatePromptCheck = true
                        }
                    }

                    LaunchedEffect(
                        isSetupComplete,
                        isOnboardingComplete,
                        startupUpdateStatus,
                        hasCompletedOpenClawUpdatePromptCheck,
                        startupOpenClawUpdatePrompt,
                        startupOpenClawUpdateRunning,
                        startupOpenClawUpdateResult,
                        resumeSignal,
                    ) {
                        if (!isSetupComplete || !isOnboardingComplete) return@LaunchedEffect
                        if (startupUpdateStatus != StartupBundleUpdateStatus.DONE) return@LaunchedEffect
                        if (!hasCompletedOpenClawUpdatePromptCheck) return@LaunchedEffect
                        if (startupOpenClawUpdatePrompt != null || startupOpenClawUpdateRunning || startupOpenClawUpdateResult != null) {
                            return@LaunchedEffect
                        }
                        if (hasCheckedInAppReviewPrompt) return@LaunchedEffect

                        // startup 직후 다이얼로그가 겹치지 않도록 짧게 대기한다.
                        delay(1_500L)
                        if (startupOpenClawUpdatePrompt != null || startupOpenClawUpdateRunning || startupOpenClawUpdateResult != null) {
                            return@LaunchedEffect
                        }
                        if (!this@MainActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            return@LaunchedEffect
                        }
                        hasCheckedInAppReviewPrompt = true
                        runCatching {
                            maybeLaunchInAppReview(app)
                        }.onFailure { error ->
                            Log.w("MainActivity", "In-app review flow failed safely", error)
                        }
                    }

                    if (isSetupComplete && startupUpdateStatus != StartupBundleUpdateStatus.DONE) {
                        val screenStep = when (startupUpdateStatus) {
                            StartupBundleUpdateStatus.CHECKING -> SetupStep.CHECKING_PROOT
                            StartupBundleUpdateStatus.UPDATING -> startupUpdateStep
                            StartupBundleUpdateStatus.DONE -> SetupStep.CHECKING_PROOT
                        }
                        val screenProgress = if (startupUpdateStatus == StartupBundleUpdateStatus.UPDATING) {
                            setupState.progress
                        } else {
                            0f
                        }
                        startupBundleUpdateScreen(
                            step = screenStep,
                            progress = screenProgress,
                        )
                    } else {
                        AndClawNavGraph(
                            navController = navController,
                            isSetupComplete = isSetupComplete,
                            isOnboardingComplete = isOnboardingComplete,
                            authCallbackUri = authCallbackUri,
                            openPairingRequestsOnLaunch = openPairingRequestsOnLaunch,
                            onOpenPairingRequestsHandled = {
                                openPairingRequestsOnLaunch = false
                            },
                        )
                    }

                    val openClawPrompt = startupOpenClawUpdatePrompt
                    if (openClawPrompt != null) {
                        AlertDialog(
                            onDismissRequest = {
                                startupOpenClawUpdatePrompt = null
                                startupOpenClawSkipUntilNextVersion = false
                            },
                            title = { Text(stringResource(R.string.settings_openclaw_update_action)) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val versionLabel = if (
                                        !openClawPrompt.installedVersion.isNullOrBlank() &&
                                        !openClawPrompt.bundledVersion.isNullOrBlank()
                                    ) {
                                        stringResource(
                                            R.string.settings_openclaw_update_available_version,
                                            openClawPrompt.installedVersion!!,
                                            openClawPrompt.bundledVersion!!,
                                        )
                                    } else {
                                        stringResource(R.string.settings_openclaw_update_action)
                                    }
                                    Text(text = versionLabel)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = startupOpenClawSkipUntilNextVersion,
                                            onCheckedChange = { startupOpenClawSkipUntilNextVersion = it },
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.settings_openclaw_update_skip_until_next_version))
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val prompt = startupOpenClawUpdatePrompt
                                        startupOpenClawUpdatePrompt = null
                                        val skipChecked = startupOpenClawSkipUntilNextVersion
                                        startupOpenClawSkipUntilNextVersion = false
                                        scope.launch {
                                            val bundledVersion = prompt?.bundledVersion
                                            if (skipChecked) {
                                                withContext(Dispatchers.IO) {
                                                    app.preferencesManager.setOpenClawUpdatePromptSuppressedBundledVersion(
                                                        bundledVersion,
                                                    )
                                                }
                                            }
                                            startupOpenClawUpdateRunning = true
                                            startupOpenClawUpdateResult = withContext(Dispatchers.IO) {
                                                runStartupOpenClawUpdate(app)
                                            }
                                            startupOpenClawUpdateRunning = false
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.settings_restart_confirm_yes))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        val prompt = startupOpenClawUpdatePrompt
                                        startupOpenClawUpdatePrompt = null
                                        val skipChecked = startupOpenClawSkipUntilNextVersion
                                        startupOpenClawSkipUntilNextVersion = false
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                if (skipChecked) {
                                                    app.preferencesManager.setOpenClawUpdatePromptSuppressedBundledVersion(
                                                        prompt?.bundledVersion,
                                                    )
                                                } else {
                                                    app.preferencesManager.setOpenClawUpdatePromptSuppressedBundledVersion(null)
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.settings_restart_confirm_no))
                                }
                            },
                        )
                    }

                    if (startupOpenClawUpdateRunning) {
                        val safeProgress = setupState.progress.coerceIn(0f, 1f)
                        AlertDialog(
                            onDismissRequest = {},
                            confirmButton = {},
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(setupState.currentStep.displayNameRes))
                                }
                            },
                            text = {
                                Column {
                                    LinearProgressIndicator(
                                        progress = { safeProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "${(safeProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }

                    val openClawUpdateResult = startupOpenClawUpdateResult
                    if (openClawUpdateResult != null) {
                        AlertDialog(
                            onDismissRequest = { startupOpenClawUpdateResult = null },
                            title = {
                                Text(
                                    if (openClawUpdateResult.success) {
                                        stringResource(R.string.dashboard_update_action_done)
                                    } else {
                                        stringResource(R.string.dashboard_update_action_failed)
                                    },
                                )
                            },
                            text = {
                                Text(openClawUpdateResult.message)
                            },
                            confirmButton = {
                                TextButton(onClick = { startupOpenClawUpdateResult = null }) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (shouldOpenPairingRequests(intent)) {
            openPairingRequestsOnLaunch = true
            consumePairingLaunchSignal(intent)
        }

        val uri = intent?.data ?: return
        if (uri.scheme == OpenRouterAuth.CALLBACK_SCHEME &&
            uri.host == OpenRouterAuth.CALLBACK_HOST &&
            uri.path == OpenRouterAuth.CALLBACK_PATH
        ) {
            authCallbackUri = uri
        }
    }

    private fun shouldOpenPairingRequests(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.action == ACTION_OPEN_PAIRING_REQUESTS ||
            intent.getBooleanExtra(EXTRA_OPEN_PAIRING_REQUESTS, false)
    }

    private fun consumePairingLaunchSignal(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_OPEN_PAIRING_REQUESTS) {
            intent.action = null
        }
        intent.removeExtra(EXTRA_OPEN_PAIRING_REQUESTS)
    }

    private suspend fun maybeLaunchInAppReview(app: AndClawApp) {
        val nowEpochMs = System.currentTimeMillis()
        val begun = withContext(Dispatchers.IO) {
            app.preferencesManager.tryBeginInAppReviewRequest(
                currentVersion = BuildConfig.VERSION_CODE,
                nowEpochMs = nowEpochMs,
            )
        }
        if (!begun) return

        var finalized = false
        try {
            val reviewManager = ReviewManagerFactory.create(this)
            val reviewInfo = requestReviewInfoOrNull(reviewManager) ?: return
            val launchSucceeded = launchReviewFlowSafely(reviewManager, reviewInfo)
            if (!launchSucceeded) return

            val consumedAtEpochMs = System.currentTimeMillis()
            finalized = withContext(Dispatchers.IO) {
                app.preferencesManager.finalizeInAppReviewRequest(
                    currentVersion = BuildConfig.VERSION_CODE,
                    requestedAtEpochMs = consumedAtEpochMs,
                )
            }
        } finally {
            if (!finalized) {
                withContext(NonCancellable + Dispatchers.IO) {
                    app.preferencesManager.cancelInAppReviewRequest(BuildConfig.VERSION_CODE)
                }
            }
        }
    }

    private suspend fun requestReviewInfoOrNull(reviewManager: ReviewManager): ReviewInfo? {
        return suspendCancellableCoroutine { cont ->
            reviewManager.requestReviewFlow()
                .addOnCompleteListener { task ->
                    if (!cont.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        cont.resume(task.result)
                    } else {
                        Log.w("MainActivity", "In-app review requestReviewFlow failed", task.exception)
                        cont.resume(null)
                    }
                }
        }
    }

    private suspend fun launchReviewFlowSafely(
        reviewManager: ReviewManager,
        reviewInfo: ReviewInfo,
    ): Boolean {
        return suspendCancellableCoroutine { cont ->
            reviewManager.launchReviewFlow(this, reviewInfo)
                .addOnCompleteListener { task ->
                    if (!cont.isActive) return@addOnCompleteListener
                    if (!task.isSuccessful) {
                        Log.w("MainActivity", "In-app review launchReviewFlow failed", task.exception)
                    }
                    cont.resume(task.isSuccessful)
                }
        }
    }

    private suspend fun runStartupOpenClawUpdate(app: AndClawApp): StartupOpenClawUpdateResult {
        val wasGatewayActive = app.processManager.gatewayState.value.status.let { status ->
            status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
        }

        return try {
            if (wasGatewayActive) {
                GatewayService.stop(this, source = "main_activity:startup_update_stop")
                val stopped = waitForGatewayStopped(app)
                if (!stopped) {
                    return StartupOpenClawUpdateResult(
                        success = false,
                        message = getString(R.string.dashboard_update_action_failed),
                    )
                }
            }

            app.setupManager.runOpenClawManualSync()
            StartupOpenClawUpdateResult(
                success = true,
                message = getString(R.string.dashboard_update_action_done),
            )
        } catch (error: Exception) {
            StartupOpenClawUpdateResult(
                success = false,
                message = error.message ?: getString(R.string.dashboard_update_action_failed),
            )
        } finally {
            restoreGatewayIfNeeded(
                app = app,
                shouldRestore = wasGatewayActive,
            )
        }
    }

    private suspend fun waitForGatewayStopped(app: AndClawApp, timeoutMs: Long = 10_000L): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val status = app.processManager.gatewayState.value.status
            if (status == GatewayStatus.STOPPED || status == GatewayStatus.ERROR) {
                return true
            }
            delay(250)
        }
        return false
    }

    private suspend fun restoreGatewayIfNeeded(
        app: AndClawApp,
        shouldRestore: Boolean,
        timeoutMs: Long = 10_000L,
    ) {
        if (!shouldRestore) return

        var startRequested = false
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            when (app.processManager.gatewayState.value.status) {
                GatewayStatus.RUNNING,
                GatewayStatus.STARTING,
                -> return
                GatewayStatus.STOPPING -> delay(250)
                GatewayStatus.STOPPED,
                GatewayStatus.ERROR,
                -> {
                    if (!startRequested) {
                        GatewayService.start(this, source = "main_activity:startup_update_restore")
                        startRequested = true
                    }
                    delay(250)
                }
            }
        }

        val status = app.processManager.gatewayState.value.status
        if (status != GatewayStatus.RUNNING && status != GatewayStatus.STARTING) {
            GatewayService.start(this, source = "main_activity:startup_update_restore")
        }
    }
}


@androidx.compose.runtime.Composable
private fun startupBundleUpdateScreen(
    step: SetupStep,
    progress: Float = 0f,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(step.displayNameRes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            LinearProgressIndicator(
                progress = { safeProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
            Text(
                text = "${(safeProgress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
