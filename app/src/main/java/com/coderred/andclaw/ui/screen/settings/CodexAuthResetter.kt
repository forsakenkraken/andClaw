package com.coderred.andclaw.ui.screen.settings

import com.coderred.andclaw.proroot.OpenClawAuthProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal object CodexAuthResetter {
    private const val OPENCLAW_AUTH_PROFILES_PATH = "root/.openclaw/agents/main/agent/auth-profiles.json"
    private const val OPENCLAW_AUTH_STATE_PATH = "root/.openclaw/agents/main/agent/auth-state.json"
    private const val OPENCLAW_CODEX_HOME_PATH = "root/.openclaw/agents/main/agent/codex-home"
    private const val OPENCLAW_SESSIONS_PATH = "root/.openclaw/agents/main/sessions"
    private const val CODEX_AUTH_PATH = "root/.codex/auth.json"

    data class Result(
        val authProfilesChanged: Boolean,
        val authStateChanged: Boolean,
        val codexAuthDeleted: Boolean,
        val codexHomeDeleted: Boolean,
        val codexAppServerBindingsDeleted: Boolean,
        val before: Diagnostic,
        val after: Diagnostic,
    ) {
        val changed: Boolean
            get() = authProfilesChanged ||
                authStateChanged ||
                codexAuthDeleted ||
                codexHomeDeleted ||
                codexAppServerBindingsDeleted

        fun diagnosticSummary(): String {
            return listOf(
                before.summary("before"),
                after.summary("after"),
            ).joinToString("\n")
        }
    }

    data class Diagnostic(
        val authProfilesBytes: Long,
        val authStateBytes: Long,
        val codexAuthBytes: Long,
        val codexHomeExists: Boolean,
        val codexHomeFileCount: Int,
        val codexHomeBytes: Long,
        val stateSqliteBytes: Long,
        val logsSqliteBytes: Long,
        val logsWalBytes: Long,
        val goalsSqliteBytes: Long,
        val codexAppServerBindingCount: Int,
        val cookieCandidateCount: Int,
        val codexOAuthProfileCount: Int,
        val openAiOAuthMirrorCount: Int,
        val oauthMaterialBytes: Long,
        val badRequestHitCount: Int,
        val requestHeaderHitCount: Int,
        val cookieTooLargeHitCount: Int,
        val cloudflareHitCount: Int,
        val cookieHitCount: Int,
        val setCookieHitCount: Int,
        val capturedRequestCount: Int,
        val maxCapturedHeaderBytes: Long,
        val maxCapturedCookieBytes: Long,
        val maxCapturedCookieCount: Int,
        val capturedBadRequestCount: Int,
        val capturedHosts: List<String>,
        val diagnosticSnippets: List<String>,
        val keywordHitCount: Int,
    ) {
        fun summary(label: String): String {
            return "$label: " +
                "profiles=${authProfilesBytes}B, " +
                "state=${authStateBytes}B, " +
                "codexAuth=${codexAuthBytes}B, " +
                "home=${if (codexHomeExists) "yes" else "no"} " +
                "files=$codexHomeFileCount bytes=$codexHomeBytes, " +
                "sqlite=state:${stateSqliteBytes}B logs:${logsSqliteBytes}B wal:${logsWalBytes}B goals:${goalsSqliteBytes}B, " +
                "bindings=$codexAppServerBindingCount, " +
                "cookieCandidates=$cookieCandidateCount, " +
                "codexProfiles=$codexOAuthProfileCount, " +
                "openAiMirrors=$openAiOAuthMirrorCount, " +
                "oauthBytes=$oauthMaterialBytes, " +
                "hits=bad400:$badRequestHitCount header:$requestHeaderHitCount tooLarge:$cookieTooLargeHitCount " +
                "cloudflare:$cloudflareHitCount cookie:$cookieHitCount setCookie:$setCookieHitCount, " +
                "captured=$capturedRequestCount maxHeader=${maxCapturedHeaderBytes}B " +
                "maxCookie=${maxCapturedCookieBytes}B maxCookieCount=$maxCapturedCookieCount " +
                "bad400=$capturedBadRequestCount hosts=${capturedHosts.joinToString("|")}, " +
                "snips=${diagnosticSnippets.ifEmpty { listOf("none") }.joinToString(" || ")}, " +
                "keywordHits=$keywordHitCount"
        }
    }

    fun reset(rootfsDir: File): Result {
        val before = diagnose(rootfsDir)
        val authProfileResult = OpenClawAuthProfileStore.resetCodexOAuthProfiles(rootfsDir)
        val authStateChanged = resetAuthState(
            stateFile = rootfsDir.resolve(OPENCLAW_AUTH_STATE_PATH),
            removedProfileIds = authProfileResult.removedProfileIds,
            keptProfileIds = authProfileResult.keptProfileIds,
        )
        val codexAuthDeleted = deleteCodexAuthFile(rootfsDir.resolve(CODEX_AUTH_PATH))
        val codexHomeDeleted = deleteCodexHome(rootfsDir.resolve(OPENCLAW_CODEX_HOME_PATH))
        val codexAppServerBindingsDeleted = deleteCodexAppServerBindings(rootfsDir.resolve(OPENCLAW_SESSIONS_PATH))
        val after = diagnose(rootfsDir)

        return Result(
            authProfilesChanged = authProfileResult.changed,
            authStateChanged = authStateChanged,
            codexAuthDeleted = codexAuthDeleted,
            codexHomeDeleted = codexHomeDeleted,
            codexAppServerBindingsDeleted = codexAppServerBindingsDeleted,
            before = before,
            after = after,
        )
    }

    fun diagnose(rootfsDir: File): Diagnostic {
        val codexHome = rootfsDir.resolve(OPENCLAW_CODEX_HOME_PATH)
        val codexHomeFiles = codexHome.safeFiles()
        val authCounters = countAuthProfileDiagnostics(rootfsDir.resolve(OPENCLAW_AUTH_PROFILES_PATH))
        val textHits = countDiagnosticTextHits(codexHomeFiles)
        val headerMetrics = readCapturedHeaderMetrics(codexHome.resolve(CODEX_HTTP_METRICS_FILE))
        return Diagnostic(
            authProfilesBytes = rootfsDir.resolve(OPENCLAW_AUTH_PROFILES_PATH).fileBytes(),
            authStateBytes = rootfsDir.resolve(OPENCLAW_AUTH_STATE_PATH).fileBytes(),
            codexAuthBytes = rootfsDir.resolve(CODEX_AUTH_PATH).fileBytes(),
            codexHomeExists = codexHome.isDirectory,
            codexHomeFileCount = codexHomeFiles.size,
            codexHomeBytes = codexHomeFiles.sumOf { it.fileBytes() },
            stateSqliteBytes = codexHome.resolve("state_5.sqlite").fileBytes(),
            logsSqliteBytes = codexHome.resolve("logs_2.sqlite").fileBytes(),
            logsWalBytes = codexHome.resolve("logs_2.sqlite-wal").fileBytes(),
            goalsSqliteBytes = codexHome.resolve("goals_1.sqlite").fileBytes(),
            codexAppServerBindingCount = countCodexAppServerBindings(rootfsDir.resolve(OPENCLAW_SESSIONS_PATH)),
            cookieCandidateCount = countCookieCandidates(codexHomeFiles),
            codexOAuthProfileCount = authCounters.codexOAuthProfileCount,
            openAiOAuthMirrorCount = authCounters.openAiOAuthMirrorCount,
            oauthMaterialBytes = authCounters.oauthMaterialBytes,
            badRequestHitCount = textHits.badRequestHitCount,
            requestHeaderHitCount = textHits.requestHeaderHitCount,
            cookieTooLargeHitCount = textHits.cookieTooLargeHitCount,
            cloudflareHitCount = textHits.cloudflareHitCount,
            cookieHitCount = textHits.cookieHitCount,
            setCookieHitCount = textHits.setCookieHitCount,
            capturedRequestCount = headerMetrics.capturedRequestCount,
            maxCapturedHeaderBytes = headerMetrics.maxCapturedHeaderBytes,
            maxCapturedCookieBytes = headerMetrics.maxCapturedCookieBytes,
            maxCapturedCookieCount = headerMetrics.maxCapturedCookieCount,
            capturedBadRequestCount = headerMetrics.capturedBadRequestCount,
            capturedHosts = headerMetrics.capturedHosts,
            diagnosticSnippets = textHits.diagnosticSnippets,
            keywordHitCount = textHits.keywordHitCount,
        )
    }

    private data class AuthProfileDiagnostics(
        val codexOAuthProfileCount: Int,
        val openAiOAuthMirrorCount: Int,
        val oauthMaterialBytes: Long,
    )

    private data class DiagnosticTextHits(
        val badRequestHitCount: Int,
        val requestHeaderHitCount: Int,
        val cookieTooLargeHitCount: Int,
        val cloudflareHitCount: Int,
        val cookieHitCount: Int,
        val setCookieHitCount: Int,
        val diagnosticSnippets: List<String>,
        val keywordHitCount: Int,
    )

    private data class CapturedHeaderMetrics(
        val capturedRequestCount: Int,
        val maxCapturedHeaderBytes: Long,
        val maxCapturedCookieBytes: Long,
        val maxCapturedCookieCount: Int,
        val capturedBadRequestCount: Int,
        val capturedHosts: List<String>,
    )

    private fun isCodexMirrorProfile(profileId: String, profile: JSONObject): Boolean {
        val hasOAuthMaterial = profile.optString("access").isNotBlank() ||
            profile.optString("refresh").isNotBlank()
        return profileId == "openai:default" || profile.optString("accountId").isNotBlank() || hasOAuthMaterial
    }

    private fun resetAuthState(
        stateFile: File,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ): Boolean {
        if (!stateFile.isFile) return false

        val root = JSONObject(stateFile.readText())
        val changed = pruneProviderProfileReferences(root, removedProfileIds, keptProfileIds)
        if (changed) {
            stateFile.writeText(root.toString(2))
        }
        return changed
    }

    private fun pruneProviderProfileReferences(
        root: JSONObject,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ): Boolean {
        var changed = false
        changed = pruneLastGood(root.optJSONObject("lastGood"), removedProfileIds, keptProfileIds) || changed
        changed = pruneOrder(root.optJSONObject("order"), removedProfileIds, keptProfileIds) || changed
        changed = pruneUsageStats(root.optJSONObject("usageStats"), removedProfileIds, keptProfileIds) || changed
        return changed
    }

    private fun pruneLastGood(
        lastGood: JSONObject?,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ): Boolean {
        if (lastGood == null) return false

        var changed = false
        for (provider in lastGood.keyList()) {
            val profileId = lastGood.optString(provider).trim()
            val shouldRemove = provider == "openai-codex" ||
                removedProfileIds.contains(profileId) ||
                (keptProfileIds.isNotEmpty() && profileId.isNotBlank() && !keptProfileIds.contains(profileId))
            if (shouldRemove) {
                lastGood.remove(provider)
                changed = true
            }
        }
        return changed
    }

    private fun pruneOrder(
        order: JSONObject?,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ): Boolean {
        if (order == null) return false

        var changed = false
        for (provider in order.keyList()) {
            val raw = order.optJSONArray(provider)
            if (provider == "openai-codex") {
                order.remove(provider)
                changed = true
                continue
            }
            if (raw == null) continue

            val next = JSONArray()
            for (index in 0 until raw.length()) {
                val profileId = raw.optString(index).trim()
                val shouldKeep = profileId.isNotBlank() &&
                    !removedProfileIds.contains(profileId) &&
                    (keptProfileIds.isEmpty() || keptProfileIds.contains(profileId))
                if (shouldKeep) {
                    next.put(profileId)
                }
            }

            if (next.length() != raw.length()) {
                if (next.length() == 0) {
                    order.remove(provider)
                } else {
                    order.put(provider, next)
                }
                changed = true
            }
        }
        return changed
    }

    private fun pruneUsageStats(
        usageStats: JSONObject?,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ): Boolean {
        if (usageStats == null) return false

        var changed = false
        for (profileId in usageStats.keyList()) {
            val shouldRemove = removedProfileIds.contains(profileId) ||
                (keptProfileIds.isNotEmpty() && !keptProfileIds.contains(profileId))
            if (shouldRemove) {
                usageStats.remove(profileId)
                changed = true
            }
        }
        return changed
    }

    private fun deleteCodexAuthFile(codexAuthFile: File): Boolean {
        if (!codexAuthFile.isFile) return false
        if (!codexAuthFile.delete()) {
            throw IOException("Failed to delete ${codexAuthFile.absolutePath}")
        }
        return true
    }

    private fun deleteCodexHome(codexHome: File): Boolean {
        if (!codexHome.exists()) return false
        if (!codexHome.isDirectory) {
            throw IOException("Codex home is not a directory: ${codexHome.absolutePath}")
        }
        if (!codexHome.deleteRecursively() || codexHome.exists()) {
            throw IOException("Failed to delete ${codexHome.absolutePath}")
        }
        return true
    }

    private fun deleteCodexAppServerBindings(sessionsDir: File): Boolean {
        if (!sessionsDir.isDirectory) return false

        var changed = false
        sessionsDir.listFiles()
            ?.filter { file -> file.isFile && file.name.endsWith(".codex-app-server.json") }
            ?.forEach { file ->
                if (!file.delete()) {
                    throw IOException("Failed to delete ${file.absolutePath}")
                }
                changed = true
        }
        return changed
    }

    private fun countCodexAppServerBindings(sessionsDir: File): Int {
        if (!sessionsDir.isDirectory) return 0
        return sessionsDir.listFiles()
            ?.count { file -> file.isFile && file.name.endsWith(".codex-app-server.json") }
            ?: 0
    }

    private fun countCookieCandidates(files: List<File>): Int {
        return files.count { file ->
            val name = file.name.lowercase()
            name.contains("cookie")
        }
    }

    private fun readCapturedHeaderMetrics(metricsFile: File): CapturedHeaderMetrics {
        if (!metricsFile.isFile) return CapturedHeaderMetrics(0, 0L, 0L, 0, 0, emptyList())
        return runCatching {
            var count = 0
            var maxHeaderBytes = 0L
            var maxCookieBytes = 0L
            var maxCookieCount = 0
            var badRequestCount = 0
            val hosts = linkedSetOf<String>()
            metricsFile.useLines { lines ->
                lines.take(MAX_CAPTURED_METRIC_LINES).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@forEach
                    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@forEach
                    count += 1
                    maxHeaderBytes = maxOf(maxHeaderBytes, obj.optLong("headerBytes", 0L))
                    maxCookieBytes = maxOf(maxCookieBytes, obj.optLong("cookieBytes", 0L))
                    maxCookieCount = maxOf(maxCookieCount, obj.optInt("cookieCount", 0))
                    if (obj.optInt("status", 0) == 400) {
                        badRequestCount += 1
                    }
                    val host = obj.optString("host").trim().lowercase()
                    if (host.isNotBlank()) {
                        hosts += host
                    }
                }
            }
            CapturedHeaderMetrics(
                capturedRequestCount = count,
                maxCapturedHeaderBytes = maxHeaderBytes,
                maxCapturedCookieBytes = maxCookieBytes,
                maxCapturedCookieCount = maxCookieCount,
                capturedBadRequestCount = badRequestCount,
                capturedHosts = hosts.take(MAX_CAPTURED_HOSTS),
            )
        }.getOrElse {
            CapturedHeaderMetrics(0, 0L, 0L, 0, 0, emptyList())
        }
    }

    private fun countAuthProfileDiagnostics(authFile: File): AuthProfileDiagnostics {
        if (!authFile.isFile) return AuthProfileDiagnostics(0, 0, 0L)
        return runCatching {
            val root = JSONObject(authFile.readText())
            val profiles = root.optJSONObject("profiles") ?: return@runCatching AuthProfileDiagnostics(0, 0, 0L)
            var codexProfileCount = 0
            var openAiMirrorCount = 0
            var oauthMaterialBytes = 0L
            for (profileId in profiles.keyList()) {
                val profile = profiles.optJSONObject(profileId) ?: continue
                val provider = profile.optString("provider").trim().lowercase()
                val type = profile.optString("type").trim().lowercase()
                val isCodexProfile = provider == "openai-codex"
                val isOpenAiMirror = provider == "openai" && type == "oauth" && isCodexMirrorProfile(profileId, profile)
                if (isCodexProfile) codexProfileCount += 1
                if (isOpenAiMirror) openAiMirrorCount += 1
                if (isCodexProfile || isOpenAiMirror) {
                    oauthMaterialBytes += profile.optString("access").length.toLong()
                    oauthMaterialBytes += profile.optString("refresh").length.toLong()
                    oauthMaterialBytes += profile.optString("token").length.toLong()
                }
            }
            AuthProfileDiagnostics(codexProfileCount, openAiMirrorCount, oauthMaterialBytes)
        }.getOrElse {
            AuthProfileDiagnostics(0, 0, 0L)
        }
    }

    private fun countDiagnosticTextHits(files: List<File>): DiagnosticTextHits {
        var remainingBytes = MAX_DIAGNOSTIC_SCAN_BYTES
        var badRequestHits = 0
        var requestHeaderHits = 0
        var cookieTooLargeHits = 0
        var cloudflareHits = 0
        var cookieHits = 0
        var setCookieHits = 0
        var keywordHits = 0
        val snippets = mutableListOf<String>()
        for (file in files.sortedByDescending { it.length() }) {
            if (remainingBytes <= 0L) break
            if (!file.isFile || file.length() <= 0L) continue
            val bytesToRead = minOf(file.length(), remainingBytes, MAX_DIAGNOSTIC_SCAN_BYTES_PER_FILE).toInt()
            val rawContent = file.inputStream().use { input ->
                val buffer = ByteArray(bytesToRead)
                val read = input.read(buffer)
                if (read <= 0) "" else buffer.decodeToString(0, read)
            }
            val content = rawContent.lowercase()
            badRequestHits += content.countOccurrences("400 bad request")
            requestHeaderHits += content.countOccurrences("request header")
            cookieTooLargeHits += content.countOccurrences("cookie too large")
            cloudflareHits += content.countOccurrences("cloudflare")
            cookieHits += content.countOccurrences("cookie")
            setCookieHits += content.countOccurrences("set-cookie")
            for (keyword in DIAGNOSTIC_KEYWORDS) {
                keywordHits += content.countOccurrences(keyword)
            }
            collectDiagnosticSnippets(rawContent, content, snippets)
            remainingBytes -= bytesToRead
        }
        return DiagnosticTextHits(
            badRequestHitCount = badRequestHits,
            requestHeaderHitCount = requestHeaderHits,
            cookieTooLargeHitCount = cookieTooLargeHits,
            cloudflareHitCount = cloudflareHits,
            cookieHitCount = cookieHits,
            setCookieHitCount = setCookieHits,
            diagnosticSnippets = snippets,
            keywordHitCount = keywordHits,
        )
    }

    private fun collectDiagnosticSnippets(
        rawContent: String,
        lowerContent: String,
        snippets: MutableList<String>,
    ) {
        if (snippets.size >= MAX_DIAGNOSTIC_SNIPPETS) return
        for (needle in DIAGNOSTIC_SNIPPET_NEEDLES) {
            if (snippets.size >= MAX_DIAGNOSTIC_SNIPPETS) return
            val index = lowerContent.indexOf(needle)
            if (index < 0) continue
            val start = (index - DIAGNOSTIC_SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
            val end = (index + needle.length + DIAGNOSTIC_SNIPPET_CONTEXT_CHARS).coerceAtMost(rawContent.length)
            val snippet = sanitizeDiagnosticSnippet(rawContent.substring(start, end))
            if (snippet.isNotBlank() && !snippets.contains(snippet)) {
                snippets += snippet
            }
        }
    }

    private fun sanitizeDiagnosticSnippet(raw: String): String {
        return raw
            .replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._-]+"), "Bearer <redacted>")
            .replace(Regex("[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), "<jwt>")
            .replace(
                Regex("(?i)(access_token|refresh_token|id_token|access|refresh|authorization|cookie|set-cookie)(\\s*[=:]\\s*)[^\\s,;<>\"']+"),
            ) { match -> "${match.groupValues[1]}${match.groupValues[2]}<redacted>" }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_DIAGNOSTIC_SNIPPET_LENGTH)
    }

    private fun File.fileBytes(): Long {
        return if (isFile) length() else 0L
    }

    private fun File.safeFiles(): List<File> {
        if (!isDirectory) return emptyList()
        val out = mutableListOf<File>()
        walkTopDown().forEach { file ->
            if (file.isFile) out += file
        }
        return out
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count += 1
            index = indexOf(needle, startIndex = index + needle.length)
        }
        return count
    }

    private fun JSONObject.keyList(): List<String> {
        val out = mutableListOf<String>()
        val keys = keys()
        while (keys.hasNext()) {
            out += keys.next()
        }
        return out
    }

    private val DIAGNOSTIC_KEYWORDS = listOf(
        "cookie",
        "request header",
        "cloudflare",
        "chatgpt",
        "openai",
        "auth",
    )
    private val DIAGNOSTIC_SNIPPET_NEEDLES = listOf(
        "400 bad request",
        "request header",
        "cookie too large",
        "cloudflare",
    )
    private const val CODEX_HTTP_METRICS_FILE = "codex-http-metrics.jsonl"
    private const val MAX_DIAGNOSTIC_SCAN_BYTES = 16L * 1024L * 1024L
    private const val MAX_DIAGNOSTIC_SCAN_BYTES_PER_FILE = 4L * 1024L * 1024L
    private const val MAX_CAPTURED_METRIC_LINES = 2_000
    private const val MAX_CAPTURED_HOSTS = 6
    private const val MAX_DIAGNOSTIC_SNIPPETS = 3
    private const val DIAGNOSTIC_SNIPPET_CONTEXT_CHARS = 140
    private const val MAX_DIAGNOSTIC_SNIPPET_LENGTH = 260
}
