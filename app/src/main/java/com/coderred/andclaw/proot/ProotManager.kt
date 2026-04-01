package com.coderred.andclaw.proot

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * proot 바이너리, rootfs, Node.js 경로를 관리하고
 * proot 명령어를 구성하는 핵심 매니저.
 *
 * proot 바이너리는 APK의 jniLibs/arm64-v8a/libproot.so 로 패키징되어
 * 설치 시 nativeLibraryDir에 자동 추출된다.
 */
class ProotManager(private val context: Context) {
    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
    )

    companion object {
        // Ubuntu 24.04 LTS arm64 base rootfs (bundled in assets)
        const val ROOTFS_ASSET = "rootfs.tar.gz.bin"

        // Node.js 24 LTS arm64 linux (bundled in assets)
        const val NODEJS_VERSION = "v24.2.0"
        const val NODEJS_ASSET = "node-arm64.tar.gz.bin"
        const val NODEJS_DIR_NAME = "node-$NODEJS_VERSION-linux-arm64"

        // 3분할 번들 에셋
        const val SYSTEM_TOOLS_ASSET = "system-tools-arm64.tar.gz.bin"
        const val OPENCLAW_ASSET = "openclaw-arm64.tar.gz.bin"
        const val PLAYWRIGHT_ASSET = "playwright-chromium-arm64.tar.gz.bin"
        private const val PLAYWRIGHT_CHROME_MARKER = ".playwright_chrome_path"
    }

    // ── 경로 ──

    /** nativeLibraryDir 의 원본 proot (APK에서 추출됨) */
    private val nativeProotPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    /** nativeLibraryDir 의 원본 libtalloc */
    private val nativeTallocPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so").absolutePath

    /** 실행용 라이브러리 디렉토리 (filesDir/lib) */
    val libLinksDir: File
        get() = File(context.filesDir, "lib")

    /** 실제 실행할 proot 바이너리 경로 (nativeLibraryDir 에서 직접 실행) */
    val prootBinaryPath: String
        get() = nativeProotPath

    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    val cacheDir: File
        get() = context.cacheDir

    private val playwrightCacheDir: File
        get() = File(rootfsDir, "root/.cache/ms-playwright")

    private val playwrightChromeMarkerFile: File
        get() = File(rootfsDir, PLAYWRIGHT_CHROME_MARKER)

    val homeDir: File
        get() = File(rootfsDir, "root")

    // ── 상태 확인 ──

    val isProotAvailable: Boolean
        get() {
            val native = File(nativeProotPath)
            return native.exists() && native.canExecute()
        }

    val isRootfsInstalled: Boolean
        get() = File(rootfsDir, "bin/sh").exists()

    val isNodeInstalled: Boolean
        get() = File(rootfsDir, "usr/local/bin/node").exists()

    val isSystemToolsInstalled: Boolean
        get() = File(rootfsDir, "usr/bin/git").exists()

    val isOpenClawInstalled: Boolean
        get() = File(rootfsDir, "usr/local/lib/node_modules/openclaw").exists()

    val isChromiumInstalled: Boolean
        get() {
            val markerPath = readChromiumMarkerPath()
            if (markerPath != null) {
                val markerBinary = File(markerPath)
                if (markerBinary.exists() && markerBinary.canExecute()) {
                    return true
                }
            }

            val detectedPath = detectChromiumExecutablePath()
            if (detectedPath != null) {
                writeChromiumMarkerPath(detectedPath)
                return true
            }

            clearChromiumMarkerPath()
            return false
        }

    val isFullySetup: Boolean
        get() = isProotAvailable && isRootfsInstalled && isNodeInstalled && isOpenClawInstalled

    fun refreshChromiumExecutableMarker(): Boolean {
        val detectedPath = detectChromiumExecutablePath() ?: run {
            clearChromiumMarkerPath()
            return false
        }

        writeChromiumMarkerPath(detectedPath)
        return true
    }

    fun detectChromiumExecutableProotPath(): String? {
        val detectedPath = detectChromiumExecutablePath() ?: return null
        return "/" + File(detectedPath).toRelativeString(rootfsDir).replace('\\', '/')
    }

    private fun detectChromiumExecutablePath(): String? {
        val browserRoot = playwrightCacheDir
        val browserDirs = browserRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("chromium") }
            ?.sortedWith(
                compareByDescending<File> { it.name.startsWith("chromium_headless_shell") }
                    .thenByDescending { it.name },
            )
            ?: return null

        for (browserDir in browserDirs) {
            val headlessShell = File(browserDir, "chrome-linux/headless_shell")
            if (headlessShell.exists() && headlessShell.canExecute()) {
                return headlessShell.absolutePath
            }

            val chrome = File(browserDir, "chrome-linux/chrome")
            if (chrome.exists() && chrome.canExecute()) {
                return chrome.absolutePath
            }
        }

        return null
    }

    private fun readChromiumMarkerPath(): String? {
        return runCatching {
            if (!playwrightChromeMarkerFile.exists()) return null
            playwrightChromeMarkerFile.readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun writeChromiumMarkerPath(path: String) {
        runCatching {
            playwrightChromeMarkerFile.writeText(path)
        }
    }

    private fun clearChromiumMarkerPath() {
        if (playwrightChromeMarkerFile.exists()) {
            playwrightChromeMarkerFile.delete()
        }
    }

    // ── 바이너리 준비 ──

    fun setupNativeLibLinks() {
        libLinksDir.mkdirs()
        val nativeTalloc = File(nativeTallocPath)
        val localTalloc = File(libLinksDir, "libtalloc.so.2")

        val isSymlink = localTalloc.exists() &&
            java.nio.file.Files.isSymbolicLink(localTalloc.toPath())
        val needsCopy = !localTalloc.exists() || isSymlink ||
            localTalloc.length() != nativeTalloc.length()

        if (nativeTalloc.exists() && needsCopy) {
            localTalloc.delete()
            nativeTalloc.inputStream().use { input ->
                localTalloc.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            localTalloc.setReadable(true, false)
        }
    }

    /**
     * proot 실행 시 사용할 LD_LIBRARY_PATH.
     */
    fun ldLibraryPath(): String {
        return "${libLinksDir.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
    }

    // ── 명령어 구성 ──

    /** /dev/shm 에뮬레이션 디렉토리 (Chromium shared memory 용) */
    val shmDir: File
        get() = File(context.cacheDir, "shm").apply { mkdirs() }

    fun buildProotCommand(command: String): List<String> {
        return listOf(
            prootBinaryPath,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${shmDir.absolutePath}:/dev/shm",
            "-0",                // UID 0 (root) 시뮬레이션
            "-w", "/root",
            "--link2symlink",    // Android 파일시스템에서 hardlink→symlink 변환
            "/bin/sh", "-c", command,
        )
    }

    fun buildGatewayCommand(): List<String> {
        return buildProotCommand(
            "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "TG_IP=$(node -e \"const dns=require('dns');dns.resolve4('api.telegram.org',(e,a)=>{if(e||!a||!a.length)process.exit(1);process.stdout.write(a[0]);});\" 2>/dev/null || true); " +
                "(grep -v 'api.telegram.org' /etc/hosts 2>/dev/null; [ -n \"\$TG_IP\" ] && echo \"\$TG_IP api.telegram.org\") > /tmp/hosts.andclaw 2>/dev/null && cat /tmp/hosts.andclaw > /etc/hosts 2>/dev/null || true; " +
                "openclaw gateway run"
        )
    }

    val isOpenClawConfigured: Boolean
        get() = File(rootfsDir, "root/.openclaw/openclaw.json").exists()

    fun buildEnvironment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return buildMap {
            put("HOME", homeDir.absolutePath)
            put("LD_LIBRARY_PATH", ldLibraryPath())
            put("PROOT_TMP_DIR", File(context.cacheDir, "proot_tmp").apply { mkdirs() }.absolutePath)
            put("PROOT_LOADER", "$nativeDir/libproot-loader.so")
            put("PROOT_LOADER_32", "$nativeDir/libproot-loader32.so")
            put("PLAYWRIGHT_BROWSERS_PATH", File(rootfsDir, "root/.cache/ms-playwright").absolutePath)
            putAll(extra)
        }
    }

    /**
     * proot 안에서 명령을 실행하고 stdout 결과를 반환한다.
     */
    fun executeAndCapture(command: String): String? {
        return try {
            val cmd = buildProotCommand(command)
            val env = buildEnvironment(mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "LANG" to "C.UTF-8",
            ))
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            pb.environment().putAll(env)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proot 안에서 명령을 실행하고 종료코드/출력을 반환한다.
     */
    fun executeWithResult(
        command: String,
        timeoutMs: Long = 300_000,
        extraEnv: Map<String, String> = emptyMap(),
    ): CommandResult? {
        return try {
            val cmd = buildProotCommand(command)
            val env = buildEnvironment(
                mapOf(
                    "HOME" to "/root",
                    "PATH" to "/usr/local/bin:/usr/bin:/bin",
                    "LANG" to "C.UTF-8",
                ) + extraEnv,
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    exitCode = -1,
                    output = process.inputStream.bufferedReader().readText().trim(),
                    timedOut = true,
                )
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            CommandResult(
                exitCode = process.exitValue(),
                output = output,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proot 안에서 명령을 실행하며 출력 라인을 실시간으로 전달한다.
     */
    fun executeWithStreamingOutput(
        command: String,
        onLine: (String) -> Unit,
    ): CommandResult? {
        return try {
            val cmd = buildProotCommand(command)
            val env = buildEnvironment(mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "LANG" to "C.UTF-8",
            ))
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    output.append(current).append('\n')
                    onLine(current)
                }
            }

            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                output = output.toString().trim(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proot 안에서 명령을 실행하며 텍스트 조각을 실시간으로 전달한다.
     * (줄바꿈 없는 프롬프트/진행 출력 캡처용)
     */
    fun executeWithStreamingText(
        command: String,
        onChunk: (String) -> Unit,
    ): CommandResult? {
        return try {
            val cmd = buildProotCommand(command)
            val env = buildEnvironment(mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "LANG" to "C.UTF-8",
            ))
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(1024)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    val chunk = String(buf, 0, n)
                    output.append(chunk)
                    onChunk(chunk)
                }
            }

            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                output = output.toString().trim(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getAvailableStorageMb(): Long {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    fun hasEnoughStorage(): Boolean = getAvailableStorageMb() > 1500
}
