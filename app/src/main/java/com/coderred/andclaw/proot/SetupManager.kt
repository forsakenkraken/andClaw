package com.coderred.andclaw.proot

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.coderred.andclaw.data.BundleUpdateFailureRecord
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.data.SetupState
import com.coderred.andclaw.data.SetupStep
import com.coderred.andclaw.proot.installer.ExecutableManifest
import com.coderred.andclaw.proot.installer.TarInstallSpec
import com.coderred.andclaw.proot.installer.TarInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStreamReader

/**
 * 원클릭 환경 세팅을 관리하는 매니저.
 *
 * rootfs, Node.js, 시스템 도구, OpenClaw, Playwright Chromium 은
 * APK assets 에 번들되어 네트워크 없이 로컬에서 추출/설치가 가능하다.
 *
 * assets 구조:
 *   assets/
 *     rootfs.tar.gz.bin                     (~30MB)  Ubuntu 24.04 arm64 base
 *     node-arm64.tar.gz.bin                 (~25MB)  Node.js 24 arm64 linux
 *     system-tools-arm64.tar.gz.bin         (~80-100MB) git, curl, python3, 시스템 libs
 *     openclaw-arm64.tar.gz.bin             (~20-50MB) OpenClaw npm 패키지
 *     playwright-chromium-arm64.tar.gz.bin  (~150-180MB) Chromium headless_shell
 *
 * 전체 흐름:
 * 1. proot 바이너리 확인 (jniLibs 에서 추출됨)
 * 2. rootfs 추출 (assets -> filesDir/rootfs)
 * 3. rootfs 설정 (DNS, hosts, profile)
 * 4. Node.js 추출 (assets -> rootfs/usr/local)
 * 5. 시스템 도구 설치 (assets -> rootfs)
 * 6. OpenClaw 설치 (assets -> rootfs)
 * 7. Playwright Chromium 설치 (assets -> rootfs)
 * 8. Bionic libc 호환성 패치
 * 9. 설치 검증
 * 10. OpenClaw 온보딩
 */
class SetupManager(
    private val context: Context,
    private val prootManager: ProotManager,
    private val preferencesManager: PreferencesManager? = null,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val nowElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    data class OpenClawUpdateInfo(
        val installedVersion: String?,
        val bundledVersion: String?,
        val updateAvailable: Boolean,
    )

    private val executableManifest = ExecutableManifest(context)
    private val tarInstaller = TarInstaller(context, executableManifest)

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()
    private val bundleFingerprintByAsset: Map<String, String> by lazy { loadBundleFingerprintByAsset() }

    // ── 로그 / 상태 헬퍼 ──

    private fun log(message: String) {
        _state.value = _state.value.copy(
            logLines = _state.value.logLines + message,
        )
    }

    private fun updateStep(step: SetupStep, progress: Float = -1f) {
        _state.value = _state.value.copy(
            currentStep = step,
            progress = if (progress >= 0) progress else _state.value.progress,
        )
    }

    private fun updateBytes(downloaded: Long, total: Long) {
        _state.value = _state.value.copy(
            downloadedBytes = downloaded,
            totalBytes = total,
        )
    }

    private fun updateProgressInRange(
        current: Float,
        rangeStart: Float,
        rangeEnd: Float,
    ) {
        val clamped = current.coerceIn(0f, 1f)
        val target = rangeStart + (rangeEnd - rangeStart) * clamped
        if (target <= _state.value.progress) return
        _state.value = _state.value.copy(progress = target)
    }

    // ── 메인 설치 흐름 ──

    suspend fun runFullSetup(): Boolean = withContext(Dispatchers.IO) {
        _state.value = SetupState(isInProgress = true)

        try {
            // ─── Step 1: proot 바이너리 확인 ───
            updateStep(SetupStep.CHECKING_PROOT, 0.02f)
            log(">> Checking proot binary...")
            if (!prootManager.isProotAvailable) {
                throw SetupException(
                    "Cannot find proot binary (libproot.so).\n" +
                        "Run scripts/setup-assets.sh and build again."
                )
            }
            log("   proot: ${prootManager.prootBinaryPath}")
            prootManager.setupNativeLibLinks()
            val tallocLink = java.io.File(prootManager.libLinksDir, "libtalloc.so.2")
            if (tallocLink.exists()) {
                log("   libtalloc.so.2: ${tallocLink.absolutePath} (${tallocLink.length()} bytes)")
            } else {
                log("   WARNING: Failed to create libtalloc.so.2")
                log("   nativeLibDir: ${context.applicationInfo.nativeLibraryDir}")
                val nativeDir = java.io.File(context.applicationInfo.nativeLibraryDir)
                nativeDir.listFiles()?.forEach { f ->
                    log("     ${f.name} (${f.length()} bytes)")
                }
            }
            log("   LD_LIBRARY_PATH: ${prootManager.ldLibraryPath()}")
            log("   Library links ready")

            // ─── Step 2: rootfs 추출 (assets -> filesDir) ───
            if (shouldExtractRootfs()) {
                if (prootManager.isRootfsInstalled && !rootfsReadyFile.exists()) {
                    log(">> Incomplete rootfs installation detected, recovering...")
                    clearDependentInstallMarkers()
                }
                extractRootfsFromAssets()
            } else {
                log(">> rootfs already installed, skipping")
                updateStep(SetupStep.CONFIGURING_ROOTFS, 0.22f)
            }

            // ─── Step 3: rootfs 기본 설정 ───
            updateStep(SetupStep.CONFIGURING_ROOTFS, 0.24f)
            configureRootfs()

            // ─── Step 4: Node.js 추출 (assets -> rootfs/usr/local) ───
            if (shouldExtractNodeJs()) {
                extractNodeJsFromAssets()
            } else {
                log(">> Node.js already installed, skipping")
                updateStep(SetupStep.INSTALLING_TOOLS, 0.40f)
            }

            // ─── Step 5: 시스템 도구 설치 (git, curl, python3, libs) ───
            if (!prootManager.isSystemToolsInstalled || isToolsOutdated()) {
                installSystemTools()
            } else {
                log(">> System tools already installed (v${getInstalledVersion(toolsVersionFile)}), skipping")
                updateStep(SetupStep.INSTALLING_OPENCLAW, 0.57f)
            }

            // ─── Step 6: OpenClaw 설치 ───
            if (!prootManager.isOpenClawInstalled || isOpenClawOutdated()) {
                installOpenClaw()
            } else {
                log(">> OpenClaw already installed (v${getInstalledVersion(openclawVersionFile)}), skipping")
                updateStep(SetupStep.INSTALLING_CHROMIUM, 0.72f)
            }

            // ─── Step 7: Playwright Chromium 설치 ───
            if (!prootManager.isChromiumInstalled || isPlaywrightOutdated()) {
                installPlaywright()
            } else {
                log(">> Chromium already installed (v${getInstalledVersion(playwrightVersionFile)}), skipping")
                updateStep(SetupStep.APPLYING_PATCHES, 0.90f)
            }

            // ─── Step 8: 호환성 패치 ───
            updateStep(SetupStep.APPLYING_PATCHES, 0.90f)
            applyPatches()

            // ─── Step 9: 검증 ───
            updateStep(SetupStep.VERIFYING, 0.92f)
            verify()

            // ─── Step 10: OpenClaw 초기 설정 (ensureOpenClawConfig에서 처리) ───
            log(">> OpenClaw config will be created on first gateway start")

            // ─── 완료 ───
            updateStep(SetupStep.COMPLETE, 1.0f)
            log(">> Setup completed successfully!")
            _state.value = _state.value.copy(isInProgress = false)
            true
        } catch (e: Exception) {
            log("!! Error: ${e.message}")
            _state.value = _state.value.copy(
                isInProgress = false,
                currentStep = SetupStep.FAILED,
                error = e.message,
            )
            false
        }
    }

    // ── Step 2: assets 에서 rootfs 추출 ──

    private suspend fun extractRootfsFromAssets() {
        updateStep(SetupStep.EXTRACTING_ROOTFS, 0.05f)
        log(">> Extracting rootfs (bundled assets)...")
        rootfsReadyFile.delete()

        // tar.gz 추출
        updateStep(SetupStep.EXTRACTING_ROOTFS, 0.10f)
        log("   Extracting... (this may take a few minutes)")
        updateBytes(0, 0)

        tarInstaller.install(
            spec = TarInstallSpec(
                assetName = ProotManager.ROOTFS_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
            ),
            onProgress = { entries ->
                log("   Extracting... ($entries entries)")
                val pct = (entries.toFloat() / 20000).coerceAtMost(1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.14f,
                    rangeEnd = 0.22f,
                )
            },
            onCopyProgress = { copiedBytes, totalBytes ->
                val safeTotalBytes = totalBytes.coerceAtLeast(0L)
                updateBytes(copiedBytes, safeTotalBytes)
                if (totalBytes <= 0L) return@install
                val pct = (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.10f,
                    rangeEnd = 0.14f,
                )
            },
        )

        saveVersion(rootfsReadyFile)
        log("   rootfs extraction complete")
        updateStep(SetupStep.EXTRACTING_ROOTFS, 0.22f)
    }

    // ── Step 3: rootfs 설정 ──

    private fun configureRootfs() {
        val rootfsDir = prootManager.rootfsDir
        log(">> Applying rootfs configuration...")

        // DNS
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }
        log("   DNS configured (8.8.8.8)")

        // /etc/hosts
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        // /etc/hostname
        File(rootfsDir, "etc/hostname").apply {
            parentFile?.mkdirs()
            writeText("andclaw\n")
        }

        // /root
        File(rootfsDir, "root").mkdirs()

        // /root/.profile
        val deviceTz = java.util.TimeZone.getDefault().id  // e.g. "Asia/Seoul"
        File(rootfsDir, "root/.profile").writeText(
            buildString {
                appendLine("export HOME=/root")
                appendLine("export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
                appendLine("export LANG=C.UTF-8")
                appendLine("export TZ=$deviceTz")
                appendLine("export UV_USE_IO_URING=0")
                appendLine("export OPENCLAW_NO_RESPAWN=1")
                appendLine("export npm_config_cache=/tmp/.npm")
                appendLine("export PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright")
            }
        )
        log("   Timezone: $deviceTz")

        // /tmp, /var/tmp
        File(rootfsDir, "tmp").apply { mkdirs(); setWritable(true, false) }
        File(rootfsDir, "var/tmp").mkdirs()

        // /etc/passwd, /etc/group (Node.js 모듈이 참조)
        File(rootfsDir, "etc/passwd").apply {
            if (!exists() || !readText().contains("root:")) {
                writeText("root:x:0:0:root:/root:/bin/sh\nnobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n")
            }
        }
        File(rootfsDir, "etc/group").apply {
            if (!exists()) {
                writeText("root:x:0:\nnogroup:x:65534:\n")
            }
        }

        // .gitconfig — SSH->HTTPS 리다이렉트 + SSL 검증 비활성화 (ca-certificates 없이 동작)
        File(rootfsDir, "root/.gitconfig").writeText(buildString {
            appendLine("[url \"https://github.com/\"]")
            appendLine("    insteadOf = ssh://git@github.com/")
            appendLine("    insteadOf = git@github.com:")
            appendLine("[http]")
            appendLine("    sslVerify = false")
        })

        log("   Configuration complete")
    }

    // ── Step 4: assets 에서 Node.js 추출 ──

    private suspend fun extractNodeJsFromAssets() {
        updateStep(SetupStep.EXTRACTING_NODEJS, 0.26f)
        log(">> Installing Node.js ${ProotManager.NODEJS_VERSION} (bundled assets)...")
        nodeVersionFile.delete()

        // 추출 (strip-components=1 로 node-v22.x/ 프리픽스 제거)
        updateStep(SetupStep.EXTRACTING_NODEJS, 0.30f)
        log("   Installing...")

        val usrLocal = File(prootManager.rootfsDir, "usr/local")
        usrLocal.mkdirs()

        tarInstaller.install(
            spec = TarInstallSpec(
                assetName = ProotManager.NODEJS_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = usrLocal,
                permissionRootDir = prootManager.rootfsDir,
                stripComponents = 1,
            ),
            onProgress = { entries ->
                if (entries % 500 == 0) log("   Installing... ($entries entries)")
                val pct = (entries.toFloat() / 5000).coerceAtMost(1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.33f,
                    rangeEnd = 0.38f,
                )
            },
            onCopyProgress = { copiedBytes, totalBytes ->
                val safeTotalBytes = totalBytes.coerceAtLeast(0L)
                updateBytes(copiedBytes, safeTotalBytes)
                if (totalBytes <= 0L) return@install
                val pct = (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.30f,
                    rangeEnd = 0.33f,
                )
            },
        )

        saveVersion(nodeVersionFile)
        saveFingerprint(nodeFingerprintFile, ProotManager.NODEJS_ASSET)
        log("   Node.js installation complete")
        updateStep(SetupStep.EXTRACTING_NODEJS, 0.38f)
    }

    // ── Step 5: 시스템 도구 설치 (git, curl, python3, 시스템 libs) ──

    private suspend fun installSystemTools() = withContext(Dispatchers.IO) {
        updateStep(SetupStep.INSTALLING_TOOLS, 0.40f)
        log(">> Installing system tools (git, curl, python3, libs)...")

        updateStep(SetupStep.INSTALLING_TOOLS, 0.44f)
        log("   Extracting bundle...")

        tarInstaller.install(
            spec = TarInstallSpec(
                assetName = ProotManager.SYSTEM_TOOLS_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
            ),
            onProgress = { entries ->
                if (entries % 1000 == 0) log("   Extracting... ($entries entries)")
                val pct = (entries.toFloat() / 15000).coerceAtMost(1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.49f,
                    rangeEnd = 0.55f,
                )
            },
            onCopyProgress = { copiedBytes, totalBytes ->
                val safeTotalBytes = totalBytes.coerceAtLeast(0L)
                updateBytes(copiedBytes, safeTotalBytes)
                if (totalBytes <= 0L) return@install
                val pct = (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.44f,
                    rangeEnd = 0.49f,
                )
            },
        )

        saveVersion(toolsVersionFile)
        saveFingerprint(toolsFingerprintFile, ProotManager.SYSTEM_TOOLS_ASSET)
        updateStep(SetupStep.INSTALLING_TOOLS, 0.55f)
        log(">> System tools installation complete")
    }

    // ── Step 6: OpenClaw 설치 ──

    private suspend fun installOpenClaw() = withContext(Dispatchers.IO) {
        updateStep(SetupStep.INSTALLING_OPENCLAW, 0.57f)
        updateBytes(0, 0)
        log(">> Installing OpenClaw...")
        openclawVersionFile.delete()

        updateStep(SetupStep.INSTALLING_OPENCLAW, 0.60f)
        log("   Extracting bundle...")

        tarInstaller.install(
            spec = TarInstallSpec(
                assetName = ProotManager.OPENCLAW_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
            ),
            onProgress = { entries ->
                if (entries % 1000 == 0) log("   Extracting... ($entries entries)")
                val pct = (entries.toFloat() / 90000).coerceAtMost(1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = OPENCLAW_INSTALL_PROGRESS_START,
                    rangeEnd = OPENCLAW_INSTALL_PROGRESS_END,
                )
            },
            onCopyProgress = { copiedBytes, totalBytes ->
                if (totalBytes <= 0L) return@install
                val pct = (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.57f,
                    rangeEnd = OPENCLAW_INSTALL_PROGRESS_START,
                )
            },
        )

        ensureOpenClawExecutable()

        log("   OpenClaw installation complete")

        if (!prootManager.isOpenClawInstalled) {
            throw SetupException("Cannot find OpenClaw module after installation")
        }

        saveVersion(openclawVersionFile)
        saveFingerprint(openclawFingerprintFile, ProotManager.OPENCLAW_ASSET)
        updateStep(SetupStep.INSTALLING_OPENCLAW, OPENCLAW_INSTALL_PROGRESS_END)
        updateBytes(0, 0)
        log(">> OpenClaw installation complete")
    }

    suspend fun getOpenClawUpdateInfo(): OpenClawUpdateInfo = withContext(Dispatchers.IO) {
        val installedVersion = readInstalledOpenClawVersion()
        val bundledVersion = readBundledOpenClawVersion()
        val fingerprintOutdated = isOpenClawOutdated()
        OpenClawUpdateInfo(
            installedVersion = installedVersion,
            bundledVersion = bundledVersion,
            updateAvailable = determineOpenClawUpdateAvailable(
                installedVersion = installedVersion,
                bundledVersion = bundledVersion,
                fingerprintOutdated = fingerprintOutdated,
            ),
        )
    }

    suspend fun runOpenClawManualSync() = withContext(Dispatchers.IO) {
        updateStep(SetupStep.INSTALLING_OPENCLAW, 0.57f)
        updateBytes(0, 0)
        _state.value = _state.value.copy(isInProgress = true, error = null)

        try {
            log(">> OpenClaw manual sync (full reinstall)...")

            tarInstaller.install(
                spec = TarInstallSpec(
                    assetName = ProotManager.OPENCLAW_ASSET,
                    cacheDir = prootManager.cacheDir,
                    destinationDir = prootManager.rootfsDir,
                    permissionRootDir = prootManager.rootfsDir,
                ),
                onProgress = { entries ->
                    if (entries % 1000 == 0) log("   Extracting... ($entries entries)")
                    val pct = (entries.toFloat() / 90000).coerceAtMost(1f)
                    updateProgressInRange(
                        current = pct,
                        rangeStart = OPENCLAW_MANUAL_SYNC_PROGRESS_START,
                        rangeEnd = OPENCLAW_MANUAL_SYNC_PROGRESS_END,
                    )
                },
                onCopyProgress = { _, _ -> },
            )

            updateStep(SetupStep.VERIFYING, 0.95f)
            ensureOpenClawExecutable()
            saveVersion(openclawVersionFile)
            saveFingerprint(openclawFingerprintFile, ProotManager.OPENCLAW_ASSET)
            updateStep(SetupStep.COMPLETE, 1.0f)
            _state.value = _state.value.copy(isInProgress = false)
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                isInProgress = false,
                currentStep = SetupStep.FAILED,
                error = error.message,
            )
            throw error
        }
    }

    private fun readInstalledOpenClawVersion(): String? {
        val packageJson = File(prootManager.rootfsDir, "usr/local/lib/node_modules/openclaw/package.json")
        if (!packageJson.exists()) return null
        return runCatching {
            JSONObject(packageJson.readText()).optString("version").trim().ifBlank { null }
        }.getOrNull()
    }

    private fun readBundledOpenClawVersion(): String? {
        return runCatching {
            context.assets.open("openclaw-version.txt").bufferedReader().use { it.readText().trim() }
        }.getOrNull()?.ifBlank { null }
    }

    internal fun determineOpenClawUpdateAvailable(
        installedVersion: String?,
        bundledVersion: String?,
        fingerprintOutdated: Boolean,
    ): Boolean {
        val versionComparison = compareBundledOpenClawVersion(installedVersion, bundledVersion)
        return when {
            versionComparison != null && versionComparison < 0 -> false
            versionComparison != null && versionComparison > 0 -> true
            else -> fingerprintOutdated
        }
    }

    private fun compareBundledOpenClawVersion(installedVersion: String?, bundledVersion: String?): Int? {
        val bundled = parseComparableVersion(bundledVersion) ?: return null
        val installed = parseComparableVersion(installedVersion) ?: return 1
        val maxSize = maxOf(installed.size, bundled.size)
        for (index in 0 until maxSize) {
            val installedPart = installed.getOrElse(index) { 0 }
            val bundledPart = bundled.getOrElse(index) { 0 }
            if (bundledPart > installedPart) return 1
            if (bundledPart < installedPart) return -1
        }
        return 0
    }

    private fun parseComparableVersion(version: String?): List<Int>? {
        if (version.isNullOrBlank()) return null
        val normalized = version.trim()
        val parts = normalized.split(".")
            .map { segment ->
                val digits = segment.takeWhile { it.isDigit() }
                if (digits.isEmpty()) return null
                digits.toIntOrNull() ?: return null
            }
        return if (parts.isEmpty()) null else parts
    }

    private fun ensureOpenClawExecutable() {
        val openClawBin = File(prootManager.rootfsDir, "usr/local/bin/openclaw")
        if (!openClawBin.exists()) {
            throw SetupException("OpenClaw executable not found: ${openClawBin.path}")
        }
        if (!openClawBin.canExecute()) {
            throw SetupException(
                "OpenClaw executable is not executable after install: ${openClawBin.path}",
            )
        }
        val validationResult = runOpenClawValidation(requirePatchedNodeOptions = false)
        if (validationResult == null || validationResult.exitCode != 0) {
            throw SetupException(
                "OpenClaw executable validation failed: ${openClawBin.path}",
            )
        }
        log("   OpenClaw executable verified")
    }

    // ── Step 7: Playwright Chromium 설치 ──

    private suspend fun installPlaywright() = withContext(Dispatchers.IO) {
        updateStep(SetupStep.INSTALLING_CHROMIUM, 0.72f)
        log(">> Installing Playwright Chromium...")

        updateStep(SetupStep.INSTALLING_CHROMIUM, 0.76f)
        log("   Extracting bundle...")

        tarInstaller.install(
            spec = TarInstallSpec(
                assetName = ProotManager.PLAYWRIGHT_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
            ),
            onProgress = { entries ->
                if (entries % 1000 == 0) log("   Extracting... ($entries entries)")
                val pct = (entries.toFloat() / 5000).coerceAtMost(1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.82f,
                    rangeEnd = 0.90f,
                )
            },
            onCopyProgress = { copiedBytes, totalBytes ->
                val safeTotalBytes = totalBytes.coerceAtLeast(0L)
                updateBytes(copiedBytes, safeTotalBytes)
                if (totalBytes <= 0L) return@install
                val pct = (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                updateProgressInRange(
                    current = pct,
                    rangeStart = 0.76f,
                    rangeEnd = 0.82f,
                )
            },
        )

        if (!prootManager.refreshChromiumExecutableMarker()) {
            throw SetupException("Cannot find Chromium executable after installation")
        }
        log("   Chromium executable marker updated")

        saveVersion(playwrightVersionFile)
        saveFingerprint(playwrightFingerprintFile, ProotManager.PLAYWRIGHT_ASSET)
        updateStep(SetupStep.INSTALLING_CHROMIUM, 0.90f)
        log(">> Playwright Chromium installation complete")
    }

    // ── 번들 업데이트 (앱 업데이트 후 게이트웨이 시작 전 호출) ──

    /**
     * Node.js + 3개 번들을 각각 독립적으로 체크하고, 아웃데이트된 것만 재추출한다.
     * SetupScreen 없이 GatewayService에서 직접 호출 가능.
     */
    fun isBundleUpdateRequired(includeOpenClawAssetUpdate: Boolean = false): Boolean {
        val openClawUpdateRequired = !prootManager.isOpenClawInstalled ||
            (includeOpenClawAssetUpdate && isOpenClawOutdated())
        return shouldExtractNodeJsByMetadata() ||
            !prootManager.isSystemToolsInstalled ||
            isToolsOutdated() ||
            openClawUpdateRequired ||
            !prootManager.isChromiumInstalled ||
            isPlaywrightOutdated()
    }

    suspend fun updateBundleIfNeeded(
        onStepChanged: ((SetupStep) -> Unit)? = null,
        includeOpenClawAssetUpdate: Boolean = false,
        forceOpenClawReinstall: Boolean = false,
        includeNodeRuntimeUpdate: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val appVersion = getAppVersionCode()

        if (includeNodeRuntimeUpdate && shouldExtractNodeJs()) {
            android.util.Log.i(
                "SetupManager",
                "Node.js update required (installed=${getInstalledVersion(nodeVersionFile)}, app=$appVersion)",
            )
            onStepChanged?.invoke(SetupStep.EXTRACTING_NODEJS)
            extractNodeJsFromAssets()
            android.util.Log.i("SetupManager", "Node.js update complete")
        }

        if (!prootManager.isSystemToolsInstalled || isToolsOutdated()) {
            android.util.Log.i("SetupManager", "System tools update required (installed=${getInstalledVersion(toolsVersionFile)}, app=$appVersion)")
            onStepChanged?.invoke(SetupStep.INSTALLING_TOOLS)
            installSystemTools()
            android.util.Log.i("SetupManager", "System tools update complete")
        }

        val openClawUpdateRequired = forceOpenClawReinstall ||
            !prootManager.isOpenClawInstalled ||
            (includeOpenClawAssetUpdate && isOpenClawOutdated())
        if (openClawUpdateRequired) {
            android.util.Log.i("SetupManager", "OpenClaw update required (installed=${getInstalledVersion(openclawVersionFile)}, app=$appVersion)")
            onStepChanged?.invoke(SetupStep.INSTALLING_OPENCLAW)
            installOpenClaw()
            android.util.Log.i("SetupManager", "OpenClaw update complete")
        }

        if (!prootManager.isChromiumInstalled || isPlaywrightOutdated()) {
            android.util.Log.i("SetupManager", "Playwright update required (installed=${getInstalledVersion(playwrightVersionFile)}, app=$appVersion)")
            onStepChanged?.invoke(SetupStep.INSTALLING_CHROMIUM)
            installPlaywright()
            android.util.Log.i("SetupManager", "Playwright update complete")
        }
    }

    suspend fun getBundleUpdateFailureState(): BundleUpdateFailureState? = withContext(Dispatchers.IO) {
        val prefs = preferencesManager ?: return@withContext null
        val record = prefs.getBundleUpdateFailure(getAppVersionCode())
        toFailureState(record)
    }

    suspend fun updateBundleIfNeededWithPolicy(
        onStepChanged: ((SetupStep) -> Unit)? = null,
        timeoutMs: Long = DEFAULT_UPDATE_TIMEOUT_MS,
        manualRetry: Boolean = false,
        includeOpenClawAssetUpdate: Boolean = true,
        forceOpenClawReinstall: Boolean = false,
    ): BundleUpdateAttemptResult = withContext(Dispatchers.IO) {
        return@withContext runBundleUpdateWithPolicy(
            onStepChanged = onStepChanged,
            timeoutMs = timeoutMs,
            requestKind = if (manualRetry) {
                BundleUpdateRequestKind.MANUAL_RETRY
            } else {
                BundleUpdateRequestKind.AUTO
            },
            includeOpenClawAssetUpdate = includeOpenClawAssetUpdate,
            forceOpenClawReinstall = forceOpenClawReinstall,
        )
    }

    suspend fun runRecoveryInstall(onStepChanged: ((SetupStep) -> Unit)? = null): BundleUpdateAttemptResult = withContext(Dispatchers.IO) {
        return@withContext runBundleUpdateWithPolicy(
            onStepChanged = onStepChanged,
            timeoutMs = DEFAULT_UPDATE_TIMEOUT_MS,
            requestKind = BundleUpdateRequestKind.RECOVERY,
            // Keep currently working runtime files until recovery actually succeeds.
            // Clearing install markers is enough to force re-install on the recovery path.
            beforeUpdate = { clearDependentInstallMarkers(clearNodeMarkers = false) },
            allowWhenUpdateNotRequired = true,
            includeOpenClawAssetUpdate = true,
            forceOpenClawReinstall = true,
        )
    }

    private suspend fun runBundleUpdateWithPolicy(
        onStepChanged: ((SetupStep) -> Unit)? = null,
        timeoutMs: Long,
        requestKind: BundleUpdateRequestKind,
        beforeUpdate: (() -> Unit)? = null,
        allowWhenUpdateNotRequired: Boolean = false,
        includeOpenClawAssetUpdate: Boolean = false,
        forceOpenClawReinstall: Boolean = false,
    ): BundleUpdateAttemptResult = withContext(Dispatchers.IO) {
        val appVersion = getAppVersionCode()
        val prefs = preferencesManager
        var consumeManualRetryOnFailure = false
        val failure = prefs?.getBundleUpdateFailure(appVersion)?.let(::toFailureState)
        val shouldApplyFailurePolicy = requestKind != BundleUpdateRequestKind.RECOVERY

        if (failure != null && !failure.inCooldown && failure.manualRetryUsed) {
            // A previous cooldown window has ended; manual retry allowance reopens.
            prefs.setBundleUpdateManualRetryUsed(appVersion, false)
        }
        if (
            shouldApplyFailurePolicy &&
            requestKind == BundleUpdateRequestKind.MANUAL_RETRY &&
            failure?.inCooldown == true &&
            !failure.manualRetryUsed
        ) {
            consumeManualRetryOnFailure = true
        }

        return@withContext try {
            val result = withTimeout(timeoutMs) {
                val nodeRepairRequired = shouldExtractNodeJs()
                val updateRequired = forceOpenClawReinstall ||
                    nodeRepairRequired ||
                    isBundleUpdateRequired(
                        includeOpenClawAssetUpdate = includeOpenClawAssetUpdate,
                    )
                if (!updateRequired && !allowWhenUpdateNotRequired) {
                    prefs?.clearBundleUpdateFailure(appVersion)
                    return@withTimeout BundleUpdateAttemptResult(
                        outcome = BundleUpdateOutcome.SKIPPED_NOT_REQUIRED,
                    )
                }

                var skipBundleUpdateDueToPolicy = false
                var skipOutcome = BundleUpdateOutcome.SKIPPED_NOT_REQUIRED
                var skipFailure: BundleUpdateFailureState? = null
                if (shouldApplyFailurePolicy && failure?.inCooldown == true) {
                    if (requestKind == BundleUpdateRequestKind.AUTO) {
                        if (!nodeRepairRequired) {
                            return@withTimeout BundleUpdateAttemptResult(
                                outcome = BundleUpdateOutcome.SKIPPED_COOLDOWN,
                                failure = failure,
                            )
                        }
                        skipBundleUpdateDueToPolicy = true
                        skipOutcome = BundleUpdateOutcome.SKIPPED_COOLDOWN
                        skipFailure = failure
                    } else if (
                        requestKind == BundleUpdateRequestKind.MANUAL_RETRY &&
                        failure.manualRetryUsed
                    ) {
                        if (!nodeRepairRequired) {
                            return@withTimeout BundleUpdateAttemptResult(
                                outcome = BundleUpdateOutcome.SKIPPED_MANUAL_RETRY_EXHAUSTED,
                                failure = failure,
                            )
                        }
                        skipBundleUpdateDueToPolicy = true
                        skipOutcome = BundleUpdateOutcome.SKIPPED_MANUAL_RETRY_EXHAUSTED
                        skipFailure = failure
                    }
                }

                if (!skipBundleUpdateDueToPolicy) {
                    beforeUpdate?.invoke()
                }

                if (nodeRepairRequired) {
                    android.util.Log.i(
                        "SetupManager",
                        "Node.js runtime refresh required before bundle update (app=$appVersion)",
                    )
                    onStepChanged?.invoke(SetupStep.EXTRACTING_NODEJS)
                    extractNodeJsFromAssets()
                    android.util.Log.i("SetupManager", "Node.js runtime refresh complete")
                }

                if (skipBundleUpdateDueToPolicy) {
                    return@withTimeout BundleUpdateAttemptResult(
                        outcome = skipOutcome,
                        failure = skipFailure,
                    )
                }

                updateBundleIfNeeded(
                    onStepChanged = onStepChanged,
                    includeOpenClawAssetUpdate = includeOpenClawAssetUpdate,
                    forceOpenClawReinstall = forceOpenClawReinstall,
                    includeNodeRuntimeUpdate = false,
                )

                BundleUpdateAttemptResult(
                    outcome = BundleUpdateOutcome.UPDATED,
                )
            }
            if (result.outcome == BundleUpdateOutcome.UPDATED) {
                prefs?.clearBundleUpdateFailure(appVersion)
            }
            result
        } catch (error: TimeoutCancellationException) {
            val summary = (error.message ?: error::class.java.simpleName).take(MAX_ERROR_LENGTH)
            if (consumeManualRetryOnFailure) {
                prefs?.setBundleUpdateManualRetryUsed(appVersion, true)
            }
            prefs?.recordBundleUpdateFailure(
                currentVersion = appVersion,
                failureType = BundleUpdateFailureType.TIMEOUT.name,
                errorMessage = summary,
                nowEpochMs = nowEpochMs(),
                nowElapsedMs = nowElapsedMs(),
            )
            val failure = prefs?.getBundleUpdateFailure(appVersion)?.let(::toFailureState)
            BundleUpdateAttemptResult(
                outcome = BundleUpdateOutcome.FAILED,
                failureType = BundleUpdateFailureType.TIMEOUT,
                errorMessage = summary,
                failure = failure,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failureType = classifyBundleUpdateFailure(error)
            val summary = (error.message ?: error::class.java.simpleName).take(MAX_ERROR_LENGTH)
            if (consumeManualRetryOnFailure) {
                prefs?.setBundleUpdateManualRetryUsed(appVersion, true)
            }
            prefs?.recordBundleUpdateFailure(
                currentVersion = appVersion,
                failureType = failureType.name,
                errorMessage = summary,
                nowEpochMs = nowEpochMs(),
                nowElapsedMs = nowElapsedMs(),
            )
            val failure = prefs?.getBundleUpdateFailure(appVersion)?.let(::toFailureState)
            BundleUpdateAttemptResult(
                outcome = BundleUpdateOutcome.FAILED,
                failureType = failureType,
                errorMessage = summary,
                failure = failure,
            )
        }
    }

    // ── 번들 버전 관리 (3분할) ──

    private val toolsVersionFile: File
        get() = File(prootManager.rootfsDir, ".tools_version")

    private val rootfsReadyFile: File
        get() = File(prootManager.rootfsDir, ".rootfs_version")

    private val nodeVersionFile: File
        get() = File(prootManager.rootfsDir, ".node_version")

    private val nodeFingerprintFile: File
        get() = File(prootManager.rootfsDir, ".node_fingerprint")

    private val openclawVersionFile: File
        get() = File(prootManager.rootfsDir, ".bundle_version")

    private val playwrightVersionFile: File
        get() = File(prootManager.rootfsDir, ".playwright_version")

    private val toolsFingerprintFile: File
        get() = File(prootManager.rootfsDir, ".tools_fingerprint")

    private val openclawFingerprintFile: File
        get() = File(prootManager.rootfsDir, ".bundle_fingerprint")

    private val playwrightFingerprintFile: File
        get() = File(prootManager.rootfsDir, ".playwright_fingerprint")

    private fun getAppVersionCode(): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            info.versionCode
        } catch (_: Exception) {
            0
        }
    }

    private fun getInstalledVersion(file: File): Int {
        return try {
            file.readText().trim().toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun saveVersion(file: File) {
        file.writeText(getAppVersionCode().toString())
    }

    private fun getInstalledNodeVersion(): String {
        return runCatching {
            prootManager.executeAndCapture("node --version")?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun getInstalledFingerprint(file: File): String {
        return try {
            file.readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun saveFingerprint(file: File, assetName: String) {
        val bundledFingerprint = getBundledAssetFingerprint(assetName) ?: return
        file.writeText(bundledFingerprint)
    }

    private fun getBundledAssetFingerprint(assetName: String): String? {
        return bundleFingerprintByAsset[assetName]
    }

    private fun isAssetOutdated(
        assetName: String,
        fingerprintFile: File,
        versionFile: File,
    ): Boolean {
        val bundledFingerprint = getBundledAssetFingerprint(assetName)
        if (bundledFingerprint != null) {
            return getInstalledFingerprint(fingerprintFile) != bundledFingerprint
        }
        // fingerprint 매니페스트가 없는 구버전 앱은 기존 versionCode 기반 비교로 폴백
        return getInstalledVersion(versionFile) < getAppVersionCode()
    }

    internal fun shouldExtractRootfs(): Boolean {
        return !prootManager.isRootfsInstalled || getInstalledVersion(rootfsReadyFile) <= 0
    }

    private fun shouldExtractNodeJsByMetadata(): Boolean {
        if (!prootManager.isNodeInstalled) return true
        return isAssetOutdated(
            assetName = ProotManager.NODEJS_ASSET,
            fingerprintFile = nodeFingerprintFile,
            versionFile = nodeVersionFile,
        )
    }

    internal fun shouldExtractNodeJs(): Boolean {
        if (shouldExtractNodeJsByMetadata()) return true
        if (getInstalledNodeVersion() != ProotManager.NODEJS_VERSION) return true
        return false
    }

    private fun clearDependentInstallMarkers(clearNodeMarkers: Boolean = true) {
        if (clearNodeMarkers) {
            nodeVersionFile.delete()
            nodeFingerprintFile.delete()
        }
        toolsVersionFile.delete()
        openclawVersionFile.delete()
        playwrightVersionFile.delete()
        toolsFingerprintFile.delete()
        openclawFingerprintFile.delete()
        playwrightFingerprintFile.delete()
    }

    private fun clearBundleInstallArtifacts() {
        clearDependentInstallMarkers()

        val allowlistPaths = listOf(
            "usr/local/lib/node_modules/openclaw",
            "usr/local/bin/openclaw",
            "root/.cache/ms-playwright",
            ".playwright_chrome_path",
        )
        allowlistPaths.forEach { relativePath ->
            val target = File(prootManager.rootfsDir, relativePath)
            if (!target.exists()) return@forEach
            if (target.isDirectory) {
                target.deleteRecursively()
            } else {
                target.delete()
            }
        }
    }

    private fun toFailureState(record: BundleUpdateFailureRecord): BundleUpdateFailureState {
        val wallElapsed = record.lastFailAtEpochMs?.let { nowEpochMs() - it }
        val monotonicElapsed = record.lastFailElapsedMs?.let { nowElapsedMs() - it }
        val elapsed = listOfNotNull(wallElapsed, monotonicElapsed)
            .filter { it >= 0L }
            .minOrNull() ?: 0L
        val inCooldown = record.failCountForCurrentVersion >= AUTO_RETRY_LIMIT &&
            elapsed in 0 until COOLDOWN_MS
        return BundleUpdateFailureState(
            failCountForCurrentVersion = record.failCountForCurrentVersion,
            lastFailAtEpochMs = record.lastFailAtEpochMs,
            lastFailVersion = record.lastFailVersion,
            lastError = record.lastError,
            lastFailureType = record.lastFailureType,
            manualRetryUsed = record.manualRetryUsed,
            inCooldown = inCooldown,
            cooldownRemainingMs = if (inCooldown) (COOLDOWN_MS - elapsed).coerceAtLeast(0L) else 0L,
        )
    }

    private fun classifyBundleUpdateFailure(error: Throwable): BundleUpdateFailureType {
        val message = (error.message ?: "").lowercase()
        return when {
            "timeout" in message -> BundleUpdateFailureType.TIMEOUT
            "no space" in message || "enospc" in message -> BundleUpdateFailureType.NO_SPACE
            "verify" in message || "validation failed" in message -> BundleUpdateFailureType.VERIFY_FAIL
            error is java.io.IOException ||
                "eio" in message ||
                "input/output error" in message ||
                "read-only file system" in message ||
                "failed to read" in message ||
                "failed to write" in message ||
                "disk i/o" in message -> BundleUpdateFailureType.IO_ERROR
            else -> BundleUpdateFailureType.UNKNOWN
        }
    }

    private enum class BundleUpdateRequestKind {
        AUTO,
        MANUAL_RETRY,
        RECOVERY,
    }

    private companion object {
        private const val OPENCLAW_VALIDATION_BASE_COMMAND =
            "openclaw --version >/dev/null 2>&1 || openclaw --help >/dev/null 2>&1"
        private const val OPENCLAW_INSTALL_PROGRESS_START = 0.60f
        private const val OPENCLAW_INSTALL_PROGRESS_END = 0.72f
        private const val OPENCLAW_MANUAL_SYNC_PROGRESS_START = 0.60f
        private const val OPENCLAW_MANUAL_SYNC_PROGRESS_END = 0.92f
        private const val BUNDLE_FINGERPRINT_ASSET = "bundle-fingerprint.json"
        private const val AUTO_RETRY_LIMIT = 3
        private const val COOLDOWN_MS = 24L * 60L * 60L * 1000L
        private const val DEFAULT_UPDATE_TIMEOUT_MS = 20L * 60L * 1000L
        private const val MAX_ERROR_LENGTH = 500
    }

    private fun isToolsOutdated(): Boolean {
        return isAssetOutdated(
            assetName = ProotManager.SYSTEM_TOOLS_ASSET,
            fingerprintFile = toolsFingerprintFile,
            versionFile = toolsVersionFile,
        )
    }

    private fun isOpenClawOutdated(): Boolean {
        return isAssetOutdated(
            assetName = ProotManager.OPENCLAW_ASSET,
            fingerprintFile = openclawFingerprintFile,
            versionFile = openclawVersionFile,
        )
    }

    private fun isPlaywrightOutdated(): Boolean {
        return isAssetOutdated(
            assetName = ProotManager.PLAYWRIGHT_ASSET,
            fingerprintFile = playwrightFingerprintFile,
            versionFile = playwrightVersionFile,
        )
    }

    private fun loadBundleFingerprintByAsset(): Map<String, String> {
        val jsonText = try {
            context.assets.open(BUNDLE_FINGERPRINT_ASSET).bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            return emptyMap()
        } catch (_: Exception) {
            return emptyMap()
        }

        return try {
            val root = JSONObject(jsonText)
            val assetsObj = root.optJSONObject("assets") ?: JSONObject()
            buildMap {
                val keys = assetsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val entryObj = assetsObj.optJSONObject(key)
                    val sha = entryObj?.optString("sha256").orEmpty().trim()
                    if (sha.isNotEmpty()) {
                        put(key, sha)
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Step 8: 패치 ──

    private fun applyPatches() {
        log(">> Applying Bionic libc compatibility patches...")

        // UV_USE_IO_URING=0 은 이미 .profile 에 포함

        // Node.js os.networkInterfaces() 패치
        // proot에서 getifaddrs() syscall이 EACCES를 반환하므로
        // 가짜 loopback 인터페이스를 반환하는 preload 스크립트
        val patchFile = File(prootManager.rootfsDir, "root/.openclaw-patch.js")
        patchFile.writeText(buildString {
            appendLine("const os = require('os');")
            appendLine("const _ni = os.networkInterfaces;")
            appendLine("os.networkInterfaces = function() {")
            appendLine("  try { return _ni.call(this); } catch(e) {")
            appendLine("    return {")
            appendLine("      lo: [{")
            appendLine("        address: '127.0.0.1',")
            appendLine("        netmask: '255.0.0.0',")
            appendLine("        family: 'IPv4',")
            appendLine("        mac: '00:00:00:00:00:00',")
            appendLine("        internal: true,")
            appendLine("        cidr: '127.0.0.1/8'")
            appendLine("      }]")
            appendLine("    };")
            appendLine("  }")
            appendLine("};")
        })
        log("   Created os.networkInterfaces() patch")

        log("   Patches applied")
    }

    // ── Step 9: 검증 ──

    private suspend fun verify() = withContext(Dispatchers.IO) {
        log(">> Verifying installation...")

        log("   Checking proot...")
        if (executeInProot("echo 'proot OK'") != 0) {
            throw SetupException("proot check failed")
        }

        log("   Checking Node.js...")
        if (executeInProot("node --version") != 0) {
            throw SetupException("Node.js check failed")
        }

        log("   Checking OpenClaw...")
        val openClawValidationResult = runOpenClawValidation(requirePatchedNodeOptions = true)
        if (openClawValidationResult == null || openClawValidationResult.exitCode != 0 || !prootManager.isOpenClawInstalled) {
            throw SetupException("OpenClaw validation failed")
        }

        if (prootManager.isChromiumInstalled) {
            log("   Chromium: installed")
        } else {
            log("   WARNING: Chromium not installed (browser tools disabled)")
        }

        log("   All checks passed!")
    }



    // ── 유틸 ──

    private fun executeInProot(command: String): Int {
        val cmd = prootManager.buildProotCommand(command)
        val env = prootManager.buildEnvironment(
            mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "LANG" to "C.UTF-8",
            ),
        )

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)

        val process = pb.start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { log("   $it") }
            }
        }
        return process.waitFor()
    }

    private fun runOpenClawValidation(requirePatchedNodeOptions: Boolean): ProotManager.CommandResult? {
        val extraEnv = if (requirePatchedNodeOptions) {
            mapOf("NODE_OPTIONS" to "--require /root/.openclaw-patch.js")
        } else {
            emptyMap()
        }
        return prootManager.executeWithResult(
            command = OPENCLAW_VALIDATION_BASE_COMMAND,
            extraEnv = extraEnv,
        )
    }

}

class SetupException(message: String) : Exception(message)

data class BundleUpdateFailureState(
    val failCountForCurrentVersion: Int,
    val lastFailAtEpochMs: Long?,
    val lastFailVersion: Int?,
    val lastError: String?,
    val lastFailureType: String?,
    val manualRetryUsed: Boolean,
    val inCooldown: Boolean,
    val cooldownRemainingMs: Long,
)

enum class BundleUpdateFailureType {
    NO_SPACE,
    TIMEOUT,
    VERIFY_FAIL,
    IO_ERROR,
    UNKNOWN,
}

enum class BundleUpdateOutcome {
    UPDATED,
    FAILED,
    SKIPPED_NOT_REQUIRED,
    SKIPPED_COOLDOWN,
    SKIPPED_MANUAL_RETRY_EXHAUSTED,
}

data class BundleUpdateAttemptResult(
    val outcome: BundleUpdateOutcome,
    val failureType: BundleUpdateFailureType? = null,
    val errorMessage: String? = null,
    val failure: BundleUpdateFailureState? = null,
)
