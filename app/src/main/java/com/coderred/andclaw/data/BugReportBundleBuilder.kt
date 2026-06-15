@file:Suppress("PackageDirectoryMismatch")

package com.coderred.andclaw.data

import android.content.Context
import android.os.Build
import com.coderred.andclaw.BuildConfig
import com.coderred.andclaw.proroot.OpenClawPluginInstallStateStore
import java.io.File
import java.util.Locale

data class BugReportBundle(
    val generatedAtEpochMs: Long,
    val metadata: BugReportMetadata,
    val gatewayErrorMessage: String? = null,
    val processErrorMessage: String? = null,
    val sessionErrors: List<BugReportSessionErrorEntry> = emptyList(),
    val gatewayLogs: List<String> = emptyList(),
    val attachments: List<BugReportTextAttachment> = emptyList(),
)

data class BugReportTextAttachment(
    val entryName: String,
    val content: String,
)

data class BugReportMetadata(
    val packageName: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidSdkInt: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val locale: String,
)

data class BugReportSessionErrorEntry(
    val timestamp: String,
    val role: String,
    val model: String?,
    val stopReason: String?,
    val errorMessage: String?,
    val tokenUsage: Int,
)

object BugReportBundleBuilder {
    fun collectSupplementalRuntimeAttachments(rootfsDir: File): List<BugReportTextAttachment> {
        val fileAttachments = collectSupplementalRuntimeLogSpecs(rootfsDir).mapNotNull { spec ->
            val file = resolveSupplementalRuntimeFile(rootfsDir, spec) ?: return@mapNotNull null
            if (!file.isFile) return@mapNotNull null

            BugReportTextAttachment(
                entryName = spec.zipEntryName,
                content = sanitizeSupplementalRuntimeFileContent(file, spec),
            )
        }
        val pluginStateAttachment = OpenClawPluginInstallStateStore.readDiagnostic(rootfsDir)?.let { diagnostic ->
            BugReportTextAttachment(
                entryName = "runtime/openclaw/plugin-install-state.json",
                content = diagnostic.json.toString(2) + "\n",
            )
        }
        return fileAttachments + listOfNotNull(pluginStateAttachment)
    }

    fun collectSupplementalRuntimeLogLines(rootfsDir: File): List<String> {
        val lines = mutableListOf<String>()

        collectSupplementalRuntimeLogSpecs(rootfsDir).forEach { spec ->
            val file = resolveSupplementalRuntimeFile(rootfsDir, spec) ?: return@forEach
            if (!file.isFile) return@forEach

            lines += "[andClaw][RuntimeFile] ${spec.displayPath}"
            file.useLines { sequence ->
                sanitizeSupplementalRuntimeLines(
                    sequence.map { it.trimEnd() }.toList(),
                    spec,
                ).asSequence()
                    .filter { it.isNotBlank() }
                    .take(MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE)
                    .forEach(lines::add)
            }
        }

        OpenClawPluginInstallStateStore.readDiagnostic(rootfsDir)?.let { diagnostic ->
            lines += diagnostic.summaryLine
        }

        return lines
    }

    fun sanitizeGatewayLogLines(gatewayLogLines: List<String>): List<String> {
        val sanitizedLines = gatewayLogLines
            .asSequence()
            .map { it.sanitizeGatewayLogLine() }
            .filter { it.isNotBlank() }
            .toList()

        val cappedLines = sanitizedLines.take(MAX_GATEWAY_LOG_LINES)
        val preservedProrootLines = sanitizedLines
            .drop(MAX_GATEWAY_LOG_LINES)
            .filter { it.contains(PROROOT_PRESERVE_KEYWORD, ignoreCase = true) }

        return if (preservedProrootLines.isEmpty()) {
            cappedLines
        } else {
            cappedLines + preservedProrootLines
        }
    }

    fun build(
        sessionEntries: List<SessionLogEntry>,
        gatewayLogLines: List<String>,
        metadata: BugReportMetadata,
        gatewayErrorMessage: String? = null,
        processErrorMessage: String? = null,
        attachments: List<BugReportTextAttachment> = emptyList(),
        generatedAtEpochMs: Long = System.currentTimeMillis(),
    ): BugReportBundle {
        return BugReportBundle(
            generatedAtEpochMs = generatedAtEpochMs,
            metadata = metadata,
            gatewayErrorMessage = gatewayErrorMessage.normalizeError(),
            processErrorMessage = processErrorMessage.normalizeError(),
            sessionErrors = sessionEntries
                .asSequence()
                .filter { it.isSessionError() }
                .map { entry ->
                    BugReportSessionErrorEntry(
                        timestamp = entry.timestamp,
                        role = entry.role,
                        model = entry.model,
                        stopReason = entry.stopReason,
                        errorMessage = entry.errorMessage,
                        tokenUsage = entry.tokenUsage,
                    )
                }
                .toList(),
            gatewayLogs = sanitizeGatewayLogLines(gatewayLogLines),
            attachments = attachments,
        )
    }

    fun collectMetadata(context: Context): BugReportMetadata {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return BugReportMetadata(
            packageName = context.packageName,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = versionCode,
            androidSdkInt = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL.orEmpty(),
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            locale = Locale.getDefault().toLanguageTag(),
        )
    }
}

fun SessionLogEntry.isSessionError(): Boolean {
    return this.stopReason == "error" || this.errorMessage != null
}

private fun String?.normalizeError(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

private fun String.sanitizeGatewayLogLine(): String {
    return sanitizeGatewayLogLineUnbounded(this).take(MAX_GATEWAY_LOG_LINE_LENGTH)
}

private const val MAX_GATEWAY_LOG_LINES = 400
private const val MAX_GATEWAY_LOG_LINE_LENGTH = 500
private const val MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE = 200
private const val MAX_OPENCLAW_RUNTIME_LOG_FILES = 2
private const val PROROOT_PRESERVE_KEYWORD = "proroot"

private enum class SupplementalRuntimeBase { ROOTFS, ROOTFS_PARENT }

private data class SupplementalRuntimeLogSpec(
    val base: SupplementalRuntimeBase,
    val sourceRelativePath: String,
    val zipEntryName: String,
    val sanitizeMode: SupplementalRuntimeSanitizeMode = SupplementalRuntimeSanitizeMode.NONE,
) {
    val displayPath: String
        get() = when (base) {
            SupplementalRuntimeBase.ROOTFS -> "/$sourceRelativePath"
            SupplementalRuntimeBase.ROOTFS_PARENT -> "../$sourceRelativePath"
        }
}

private enum class SupplementalRuntimeSanitizeMode {
    NONE,
    LAUNCHER_POSTMORTEM,
    OPENCLAW_LOG_TAIL,
}

private fun resolveSupplementalRuntimeFile(
    rootfsDir: File,
    spec: SupplementalRuntimeLogSpec,
): File? {
    val baseDir = when (spec.base) {
        SupplementalRuntimeBase.ROOTFS -> rootfsDir
        SupplementalRuntimeBase.ROOTFS_PARENT -> rootfsDir.parentFile ?: return null
    }
    return File(baseDir, spec.sourceRelativePath)
}

private val SUPPLEMENTAL_RUNTIME_LOG_FILES = listOf(
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "tmp/proroot-sigsys-last.txt",
        zipEntryName = "runtime/proroot-sigsys-last.txt",
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "tmp/proroot-launcher-last.txt",
        zipEntryName = "runtime/proroot-launcher-last.txt",
        sanitizeMode = SupplementalRuntimeSanitizeMode.LAUNCHER_POSTMORTEM,
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS_PARENT,
        sourceRelativePath = "proroot-sigbus-maps.txt",
        zipEntryName = "runtime/proroot-sigbus-maps.txt",
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "root/.openclaw/agents/main/agent/codex-home/codex-http-metrics.jsonl",
        zipEntryName = "runtime/codex/codex-http-metrics.jsonl",
        sanitizeMode = SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL,
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "root/.openclaw/agents/main/agent/codex-home/codex-rust.log",
        zipEntryName = "runtime/codex/codex-rust.log",
        sanitizeMode = SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL,
    ),
)

private val OPENCLAW_LOG_FILE_REGEX = Regex("""openclaw-\d{4}-\d{2}-\d{2}\.log""")

private fun collectSupplementalRuntimeLogSpecs(rootfsDir: File): List<SupplementalRuntimeLogSpec> {
    return SUPPLEMENTAL_RUNTIME_LOG_FILES + collectOpenClawRuntimeLogSpecs(rootfsDir)
}

private fun collectOpenClawRuntimeLogSpecs(rootfsDir: File): List<SupplementalRuntimeLogSpec> {
    val openClawLogDir = File(rootfsDir, "tmp/openclaw")
    if (!openClawLogDir.isDirectory) return emptyList()

    return openClawLogDir
        .listFiles { file ->
            file.isFile && OPENCLAW_LOG_FILE_REGEX.matches(file.name)
        }
        .orEmpty()
        .sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenByDescending { it.name }
        )
        .take(MAX_OPENCLAW_RUNTIME_LOG_FILES)
        .map { file ->
            SupplementalRuntimeLogSpec(
                base = SupplementalRuntimeBase.ROOTFS,
                sourceRelativePath = "tmp/openclaw/${file.name}",
                zipEntryName = "runtime/openclaw/${file.name}",
                sanitizeMode = SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL,
            )
        }
}

private fun sanitizeSupplementalRuntimeFileContent(
    file: File,
    spec: SupplementalRuntimeLogSpec,
): String {
    return when (spec.sanitizeMode) {
        SupplementalRuntimeSanitizeMode.NONE -> file.readText()
        SupplementalRuntimeSanitizeMode.LAUNCHER_POSTMORTEM -> file.useLines { sequence ->
            sanitizeSupplementalRuntimeLines(sequence.toList(), spec).joinToString("\n")
        }
        SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL -> file.useLines { sequence ->
            sanitizeSupplementalRuntimeLines(sequence.toList(), spec).joinToString("\n", postfix = "\n")
        }
    }
}

private fun sanitizeSupplementalRuntimeLines(
    lines: List<String>,
    spec: SupplementalRuntimeLogSpec,
): List<String> {
    return when (spec.sanitizeMode) {
        SupplementalRuntimeSanitizeMode.NONE -> lines
        SupplementalRuntimeSanitizeMode.LAUNCHER_POSTMORTEM -> sanitizeLauncherPostmortemLines(lines)
        SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL -> lines
            .takeLast(MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE)
            .map { it.trimEnd().sanitizeGatewayLogLine() }
    }
}

private fun sanitizeLauncherPostmortemLines(lines: List<String>): List<String> {
    var redactNextArg = false

    return lines.map { line ->
        val trimmed = line.trimEnd()
        val argvPrefix = ARG_LINE_REGEX.matchEntire(trimmed)
        if (argvPrefix != null) {
            val argValue = argvPrefix.groupValues[2]
            val sanitizedValue = when {
                redactNextArg -> "<redacted>"
                isSensitiveLauncherFlag(argValue) -> sanitizeSensitiveLauncherArg(argValue)
                else -> sanitizeGatewayLogLineUnbounded(argValue)
            }
            redactNextArg = argValue in SENSITIVE_LAUNCHER_NEXT_ARG_FLAGS
            return@map "${argvPrefix.groupValues[1]}$sanitizedValue"
        }

        redactNextArg = false
        sanitizeGatewayLogLineUnbounded(trimmed)
    }
}

private fun isSensitiveLauncherFlag(argValue: String): Boolean {
    return argValue in SENSITIVE_LAUNCHER_NEXT_ARG_FLAGS ||
        SENSITIVE_LAUNCHER_INLINE_FLAGS.any { flag -> argValue.startsWith("$flag=") }
}

private fun sanitizeSensitiveLauncherArg(argValue: String): String {
    SENSITIVE_LAUNCHER_INLINE_FLAGS.forEach { flag ->
        if (argValue.startsWith("$flag=")) {
            return "$flag=<redacted>"
        }
    }
    return sanitizeGatewayLogLineUnbounded(argValue)
}
private const val SECRET_KEY_PATTERN =
    "TELEGRAM_BOT_TOKEN|DISCORD_BOT_TOKEN|OPENROUTER_API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|GOOGLE_API_KEY|GEMINI_API_KEY|COPILOT_GITHUB_TOKEN|GH_TOKEN|GITHUB_TOKEN|ZAI_API_KEY|Z_AI_API_KEY|KIMI_API_KEY|KIMICODE_API_KEY|MINIMAX_API_KEY|BRAVE_API_KEY|BRAVE_SEARCH_API_KEY|API_KEY|API-KEY|AUTHORIZATION|COOKIE|PASSWORD|SECRET|TOKEN|ACCESS_TOKEN|REFRESH_TOKEN|ID_TOKEN|X_API_KEY|X-API-KEY"
private val JSON_SECRET_REGEX = Regex(
    "(?i)(\"(?:$SECRET_KEY_PATTERN)\"\\s*:\\s*\")([^\"]+)(\")"
)
private val AUTH_HEADER_REGEX = Regex(
    "(?i)(\\bauthorization\\b\\s*[:=]\\s*)[^,;\\r\\n]+"
)
private val COOKIE_HEADER_REGEX = Regex(
    "(?i)(\\bcookie\\b\\s*[:=]\\s*)[^,;\\r\\n]+"
)
private val KEY_VALUE_SECRET_REGEX = Regex(
    "(?i)\\b($SECRET_KEY_PATTERN)\\b\\s*([=:])\\s*[^\\s,;]+"
)
private val BEARER_REGEX = Regex("(?i)bearer\\s+[a-z0-9._\\-]+")
private val QUERY_SECRET_REGEX = Regex(
    "(?i)([?&](?:token|api_key|key|access_token|refresh_token|id_token|email_token)=)[^&\\s]+"
)
private val RAW_KEY_REGEX = Regex("\\b(?:sk|or|rk)-[A-Za-z0-9_\\-]{8,}\\b")
private val ARG_LINE_REGEX = Regex("""^(\[proroot] launcher: argv\[\d+]=)(.*)$""")
private val SENSITIVE_LAUNCHER_NEXT_ARG_FLAGS = setOf(
    "--api-key",
    "--token",
    "--password",
    "--header",
    "-H",
    "--cookie",
    "--authorization",
)
private val SENSITIVE_LAUNCHER_INLINE_FLAGS = setOf(
    "--api-key",
    "--token",
    "--password",
    "--cookie",
    "--authorization",
)

private fun sanitizeGatewayLogLineUnbounded(line: String): String {
    var sanitized = line
    sanitized = JSON_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>${match.groupValues[3]}"
    }
    sanitized = AUTH_HEADER_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = COOKIE_HEADER_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = KEY_VALUE_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
    }
    sanitized = BEARER_REGEX.replace(sanitized, "Bearer <redacted>")
    sanitized = QUERY_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = RAW_KEY_REGEX.replace(sanitized, "<redacted>")
    return sanitized
}
