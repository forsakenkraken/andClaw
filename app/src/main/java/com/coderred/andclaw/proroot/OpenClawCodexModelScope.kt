package com.coderred.andclaw.proroot

import java.io.File
import org.json.JSONObject

object OpenClawCodexModelScope {
    const val LEGACY_PROVIDER = "openai-codex"
    const val CODEX_PROVIDER = "codex"

    private const val PROVIDER_SWITCH_VERSION = "2026.6.1"
    private const val LEGACY_DEFAULT_MODEL = "gpt-5.3-codex"
    private const val CODEX_DEFAULT_MODEL = "gpt-5.5"
    private val CODEX_PROVIDER_MODEL_IDS = setOf("gpt-5.5", "gpt-5.4-mini")

    fun readInstalledOpenClawVersion(rootfsDir: File?): String? {
        val packageJson = rootfsDir
            ?.resolve("usr/local/lib/node_modules/openclaw/package.json")
            ?: return null
        if (!packageJson.isFile) return null
        return runCatching {
            JSONObject(packageJson.readText()).optString("version").trim().ifBlank { null }
        }.getOrNull()
    }

    fun providerForInstalledVersion(version: String?): String {
        return if (isAtLeast(version, PROVIDER_SWITCH_VERSION)) CODEX_PROVIDER else LEGACY_PROVIDER
    }

    fun providerForRootfs(rootfsDir: File?): String {
        return providerForInstalledVersion(readInstalledOpenClawVersion(rootfsDir))
    }

    fun defaultBareModelId(version: String?): String {
        return when (providerForInstalledVersion(version)) {
            CODEX_PROVIDER -> CODEX_DEFAULT_MODEL
            else -> LEGACY_DEFAULT_MODEL
        }
    }

    fun bareModelId(modelId: String): String {
        return modelId.trim()
            .removePrefix("$LEGACY_PROVIDER/")
            .removePrefix("$CODEX_PROVIDER/")
            .removePrefix("openai/")
            .trim()
    }

    fun normalizedBareModelId(modelId: String): String {
        return bareModelId(modelId).lowercase()
    }

    fun scopedModelId(
        version: String?,
        modelId: String,
        availableBareModelIds: Set<String> = emptySet(),
    ): String {
        val provider = providerForInstalledVersion(version)
        val bareModelId = resolveBareModelId(version, modelId, availableBareModelIds)
        return "$provider/$bareModelId"
    }

    fun preferredBareModelId(version: String?, availableBareModelIds: Set<String>): String {
        val provider = providerForInstalledVersion(version)
        val availableByNormalizedId = availableBareModelIds
            .mapNotNull { bareModelId ->
                val normalized = normalizedBareModelId(bareModelId)
                bareModelId.trim().takeIf { it.isNotBlank() }?.let { normalized to it }
            }
            .toMap()
        val preferredOrder = when (provider) {
            CODEX_PROVIDER -> listOf(CODEX_DEFAULT_MODEL, "gpt-5.4-mini")
            else -> listOf("gpt-5.4", LEGACY_DEFAULT_MODEL)
        }
        return preferredOrder
            .firstNotNullOfOrNull { availableByNormalizedId[it] }
            ?: availableBareModelIds.firstOrNull { it.isNotBlank() }?.trim()
            ?: defaultBareModelId(version)
    }

    fun resolveBareModelId(
        version: String?,
        modelId: String,
        availableBareModelIds: Set<String>,
    ): String {
        val provider = providerForInstalledVersion(version)
        val requestedBareModelId = bareModelId(modelId)
            .takeUnless { it.isBlank() || it.contains("/") }
            ?: return preferredBareModelId(version, availableBareModelIds)

        val availableByNormalizedId = availableBareModelIds
            .mapNotNull { bareModelId ->
                val normalized = normalizedBareModelId(bareModelId)
                bareModelId.trim().takeIf { it.isNotBlank() }?.let { normalized to it }
            }
            .toMap()
        if (availableByNormalizedId.isNotEmpty()) {
            return availableByNormalizedId[normalizedBareModelId(requestedBareModelId)]
                ?: preferredBareModelId(version, availableBareModelIds)
        }

        val normalizedRequested = normalizedBareModelId(requestedBareModelId)
        return when {
            provider == LEGACY_PROVIDER && normalizedRequested in CODEX_PROVIDER_MODEL_IDS ->
                defaultBareModelId(version)
            provider == CODEX_PROVIDER && normalizedRequested !in CODEX_PROVIDER_MODEL_IDS ->
                defaultBareModelId(version)
            else -> requestedBareModelId
        }
    }

    fun scopedModelIdForProvider(provider: String, modelId: String): String {
        val normalizedProvider = provider.trim().lowercase().ifBlank { LEGACY_PROVIDER }
        val bareModelId = bareModelId(modelId)
            .takeUnless { it.isBlank() || it.contains("/") }
            ?: when (normalizedProvider) {
                CODEX_PROVIDER -> CODEX_DEFAULT_MODEL
                else -> LEGACY_DEFAULT_MODEL
            }
        return "$normalizedProvider/$bareModelId"
    }

    private fun isAtLeast(version: String?, minimum: String): Boolean {
        val currentParts = parseComparableVersion(version) ?: return false
        val minimumParts = parseComparableVersion(minimum) ?: return false
        val maxSize = maxOf(currentParts.size, minimumParts.size)
        for (index in 0 until maxSize) {
            val currentPart = currentParts.getOrElse(index) { 0 }
            val minimumPart = minimumParts.getOrElse(index) { 0 }
            if (currentPart > minimumPart) return true
            if (currentPart < minimumPart) return false
        }
        return true
    }

    private fun parseComparableVersion(version: String?): List<Int>? {
        if (version.isNullOrBlank()) return null
        val parts = version.trim().split(".").map { segment ->
            val digits = segment.takeWhile(Char::isDigit)
            if (digits.isEmpty()) return null
            digits.toIntOrNull() ?: return null
        }
        return parts.takeIf { it.isNotEmpty() }
    }
}
