package com.coderred.andclaw.data.transfer

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TransferManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val createdAtEpochMs: Long,
    val includedSections: List<String>,
)

data class TransferVersionExpectation(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
)

data class TransferVersionGateResult(
    val accepted: Boolean,
    val reason: String? = null,
)

object TransferManifestContract {
    const val SCHEMA_VERSION_V1 = 1

    const val SECTION_OPENCLAW_CONFIG = "openclaw-config"
    const val SECTION_OPENCLAW_AUTH_PROFILES = "openclaw-auth-profiles"
    const val SECTION_OPENCLAW_CREDENTIALS = "openclaw-credentials"
    const val SECTION_OPENCLAW_SESSIONS = "openclaw-sessions"
    const val SECTION_OPENCLAW_AGENT_DATA = "openclaw-agent-data"
    const val SECTION_CODEX_AUTH = "codex-auth"

    val DEFAULT_INCLUDED_SECTIONS: List<String> = listOf(
        SECTION_OPENCLAW_CONFIG,
        SECTION_OPENCLAW_AUTH_PROFILES,
        SECTION_OPENCLAW_CREDENTIALS,
        SECTION_OPENCLAW_SESSIONS,
        SECTION_OPENCLAW_AGENT_DATA,
        SECTION_CODEX_AUTH,
    )

    private val INCLUDED_EXACT_PATHS = setOf(
        "root/.openclaw/openclaw.json",
        "root/.codex/auth.json",
    )

    private val INCLUDED_PATH_PREFIXES = setOf(
        "root/.openclaw/",
        "root/.openclaw/agents/main/agent/",
        "root/.openclaw/agents/main/sessions/",
        "root/.openclaw/credentials/",
    )

    private val EXCLUDED_EXACT_PATHS = setOf(
        "root/.openclaw/agents/main/agent/codex-home/codex-http-metrics.jsonl",
        "root/.openclaw/agents/main/agent/codex-home/codex-rust.log",
        "root/.openclaw/agents/main/agent/codex-home/logs.sqlite",
    )

    private val EXCLUDED_PATH_PREFIXES = setOf(
        "root/.openclaw/logs/",
        "root/.openclaw/.cache/",
        "root/.openclaw/tmp/",
        "root/.openclaw/npm/",
        "root/.openclaw/andclaw-bundled-plugins/",
        "root/.openclaw/agents/main/agent/codex-home/codex-rust.log.",
        "root/.openclaw/agents/main/agent/codex-home/log/",
        "root/.openclaw/agents/main/agent/codex-home/logs/",
        "root/.openclaw/agents/main/agent/codex-home/logs.sqlite-",
        "root/.openclaw/agents/main/agent/codex-home/logs_",
        "root/.openclaw/agents/main/agent/codex-home/.cache/",
        "root/.openclaw/agents/main/agent/codex-home/cache/",
        "root/.openclaw/agents/main/agent/codex-home/tmp/",
        "root/.openclaw/agents/main/sessions/.stale-release-backup-",
        "root/.cache/",
        "tmp/",
        "var/tmp/",
    )

    val REQUIRED_FIXED_PATHS_BY_SECTION = mapOf(
        SECTION_OPENCLAW_CONFIG to "root/.openclaw/openclaw.json",
    )

    fun newManifest(
        applicationId: String,
        versionCode: Long,
        versionName: String,
        createdAtEpochMs: Long,
        includedSections: List<String> = DEFAULT_INCLUDED_SECTIONS,
    ): TransferManifest {
        return TransferManifest(
            schemaVersion = SCHEMA_VERSION_V1,
            applicationId = applicationId.trim(),
            versionCode = versionCode,
            versionName = versionName.trim(),
            createdAtEpochMs = createdAtEpochMs,
            includedSections = includedSections
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
        )
    }

    fun detectIncludedSections(rootfsDir: java.io.File): List<String> {
        val rootCanonical = rootfsDir.canonicalFile
        val sections = linkedSetOf<String>()

        if (java.io.File(rootCanonical, "root/.openclaw/openclaw.json").isFile) {
            sections += SECTION_OPENCLAW_CONFIG
        }
        if (java.io.File(rootCanonical, "root/.openclaw/agents/main/agent/auth-profiles.json").isFile) {
            sections += SECTION_OPENCLAW_AUTH_PROFILES
        }
        val credentialsDir = java.io.File(rootCanonical, "root/.openclaw/credentials")
        if (credentialsDir.isDirectory && credentialsDir.walkTopDown().any { it.isFile }) {
            sections += SECTION_OPENCLAW_CREDENTIALS
        }
        val sessionsDir = java.io.File(rootCanonical, "root/.openclaw/agents/main/sessions")
        if (sessionsDir.isDirectory && sessionsDir.walkTopDown().any { it.isFile }) {
            sections += SECTION_OPENCLAW_SESSIONS
        }
        val agentDir = java.io.File(rootCanonical, "root/.openclaw/agents/main/agent")
        if (agentDir.isDirectory && agentDir.walkTopDown().any { it.isFile }) {
            sections += SECTION_OPENCLAW_AGENT_DATA
        }
        if (java.io.File(rootCanonical, "root/.codex/auth.json").isFile) {
            sections += SECTION_CODEX_AUTH
        }

        require(sections.contains(SECTION_OPENCLAW_CONFIG)) {
            "Transfer export requires root/.openclaw/openclaw.json"
        }

        return sections.toList()
    }

    fun enforceRequiredFields(manifest: TransferManifest) {
        require(manifest.schemaVersion > 0) { "schemaVersion must be positive" }
        require(manifest.applicationId.isNotBlank()) { "applicationId is required" }
        require(manifest.versionCode > 0L) { "versionCode must be positive" }
        require(manifest.versionName.isNotBlank()) { "versionName is required" }
        require(manifest.createdAtEpochMs > 0L) { "createdAtEpochMs must be positive" }
        require(manifest.includedSections.isNotEmpty()) { "includedSections is required" }
    }

    fun evaluateExactVersionGate(
        manifest: TransferManifest,
        expected: TransferVersionExpectation,
    ): TransferVersionGateResult {
        enforceRequiredFields(manifest)

        if (manifest.applicationId != expected.applicationId.trim()) {
            return TransferVersionGateResult(
                accepted = false,
                reason = "applicationId mismatch",
            )
        }
        if (manifest.versionCode != expected.versionCode) {
            return TransferVersionGateResult(
                accepted = false,
                reason = "versionCode mismatch",
            )
        }
        if (manifest.versionName != expected.versionName.trim()) {
            return TransferVersionGateResult(
                accepted = false,
                reason = "versionName mismatch",
            )
        }
        return TransferVersionGateResult(accepted = true)
    }

    // Security: normalizeRelativePath() lowercases the path internally for blocklist matching,
    // but actual archive entry names preserve original case from the filesystem.
    fun shouldIncludeExportPath(relativePath: String): Boolean {
        val normalizedPath = normalizeRelativePath(relativePath) ?: return false
        if (normalizedPath in EXCLUDED_EXACT_PATHS) return false
        if (EXCLUDED_PATH_PREFIXES.any { normalizedPath.startsWith(it) }) return false
        if (normalizedPath in INCLUDED_EXACT_PATHS) return true
        if (INCLUDED_PATH_PREFIXES.any { normalizedPath.startsWith(it) }) return true
        return false
    }

    private fun normalizeRelativePath(rawPath: String): String? {
        val normalizedSeparators = rawPath.trim().replace('\\', '/')
        if (normalizedSeparators.isEmpty()) return null

        val parts = normalizedSeparators
            .trimStart('/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }

        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null

        return parts.joinToString("/").lowercase(Locale.US)
    }
}

object TransferManifestJson {
    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_APPLICATION_ID = "applicationId"
    private const val KEY_VERSION_CODE = "versionCode"
    private const val KEY_VERSION_NAME = "versionName"
    private const val KEY_CREATED_AT_EPOCH_MS = "createdAtEpochMs"
    private const val KEY_INCLUDED_SECTIONS = "includedSections"

    fun toJson(manifest: TransferManifest): String {
        TransferManifestContract.enforceRequiredFields(manifest)

        val sections = JSONArray()
        manifest.includedSections.forEach(sections::put)
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, manifest.schemaVersion)
            .put(KEY_APPLICATION_ID, manifest.applicationId)
            .put(KEY_VERSION_CODE, manifest.versionCode)
            .put(KEY_VERSION_NAME, manifest.versionName)
            .put(KEY_CREATED_AT_EPOCH_MS, manifest.createdAtEpochMs)
            .put(KEY_INCLUDED_SECTIONS, sections)
            .toString()
    }

    fun fromJson(raw: String): TransferManifest {
        val root = JSONObject(raw)
        val schemaVersion = root.requireInt(KEY_SCHEMA_VERSION)
        val applicationId = root.requireString(KEY_APPLICATION_ID)
        val versionCode = root.requireLong(KEY_VERSION_CODE)
        val versionName = root.requireString(KEY_VERSION_NAME)
        val createdAtEpochMs = root.requireLong(KEY_CREATED_AT_EPOCH_MS)
        val sectionsArray = root.optJSONArray(KEY_INCLUDED_SECTIONS)
            ?: throw IllegalArgumentException("includedSections is required")
        val includedSections = buildList {
            for (index in 0 until sectionsArray.length()) {
                val value = sectionsArray.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
        val manifest = TransferManifest(
            schemaVersion = schemaVersion,
            applicationId = applicationId,
            versionCode = versionCode,
            versionName = versionName,
            createdAtEpochMs = createdAtEpochMs,
            includedSections = includedSections,
        )
        TransferManifestContract.enforceRequiredFields(manifest)
        return manifest
    }

    private fun JSONObject.requireString(key: String): String {
        val value = optString(key).trim()
        if (value.isEmpty()) throw IllegalArgumentException("$key is required")
        return value
    }

    private fun JSONObject.requireInt(key: String): Int {
        if (!has(key)) throw IllegalArgumentException("$key is required")
        return optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?: throw IllegalArgumentException("$key must be an integer")
    }

    private fun JSONObject.requireLong(key: String): Long {
        if (!has(key)) throw IllegalArgumentException("$key is required")
        return optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            ?: throw IllegalArgumentException("$key must be a long")
    }
}
