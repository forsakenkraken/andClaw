package com.coderred.andclaw.proroot

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object OpenClawAuthProfileStore {
    private const val AGENT_AUTH_PROFILES_PATH = "root/.openclaw/agents/main/agent/auth-profiles.json"
    private const val AGENT_AUTH_SQLITE_PATH = "root/.openclaw/agents/main/agent/openclaw-agent.sqlite"
    private const val LEGACY_CODEX_OAUTH_PROVIDER = "openai-codex"
    private const val CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER = "openai"
    private const val LEGACY_CODEX_OAUTH_PROFILE_ID = "openai-codex:default"
    private const val CODEX_APP_SERVER_OPENAI_PROFILE_ID = "openai:default"
    private const val PRIMARY_ROW_KEY = "primary"
    private const val AGENT_ID = "main"
    private const val AGENT_SCHEMA_VERSION = 1

    data class ResetResult(
        val changed: Boolean,
        val removedProfileIds: Set<String>,
        val keptProfileIds: Set<String>,
    )

    fun authProfilesJsonFile(rootfsDir: File): File {
        return File(rootfsDir, AGENT_AUTH_PROFILES_PATH)
    }

    fun authProfilesSqliteFile(rootfsDir: File): File {
        return File(rootfsDir, AGENT_AUTH_SQLITE_PATH)
    }

    fun writeCodexOAuthCredentials(
        rootfsDir: File,
        accessToken: String,
        refreshToken: String,
        expires: Long,
        accountId: String,
    ) {
        val existingStore = mergeAuthStores(
            primary = readSqliteStore(rootfsDir),
            fallback = readJsonStore(rootfsDir),
        ) ?: JSONObject().put("version", 1).put("profiles", JSONObject())
        val profiles = existingStore.optJSONObject("profiles") ?: JSONObject().also { existingStore.put("profiles", it) }

        val codexCredential = JSONObject().apply {
            put("type", "oauth")
            put("provider", LEGACY_CODEX_OAUTH_PROVIDER)
            put("access", accessToken)
            put("refresh", refreshToken)
            put("expires", expires)
            put("accountId", accountId)
        }
        val openAiCredential = JSONObject(codexCredential.toString()).apply {
            put("provider", CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER)
        }

        profiles.put(LEGACY_CODEX_OAUTH_PROFILE_ID, codexCredential)
        profiles.put(CODEX_APP_SERVER_OPENAI_PROFILE_ID, openAiCredential)
        ensureOpenAiDefaultSelection(existingStore, profiles)

        writeJsonStore(rootfsDir, existingStore)
        writeSqliteStore(rootfsDir, existingStore)
    }

    fun mirrorCodexAppServerOpenAiProfile(rootfsDir: File): Boolean {
        val jsonStore = readJsonStore(rootfsDir)
        val sqliteStore = readSqliteStore(rootfsDir)
        val store = mergeAuthStores(
            primary = sqliteStore,
            fallback = jsonStore,
        ) ?: return false
        val profiles = store.optJSONObject("profiles") ?: return false
        val before = JSONObject(store.toString())

        val existingOpenAiCredential = profiles.optJSONObject(CODEX_APP_SERVER_OPENAI_PROFILE_ID)
        val hasOpenAiDefaultProfile =
            existingOpenAiCredential?.optString("provider")?.trim()?.lowercase() == CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER
        if (!hasOpenAiDefaultProfile) {
            val sourceCredential = findLegacyCodexAuthProfile(profiles) ?: return false
            profiles.put(
                CODEX_APP_SERVER_OPENAI_PROFILE_ID,
                JSONObject(sourceCredential.toString()).apply {
                    put("provider", CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER)
                },
            )
        }
        ensureOpenAiDefaultSelection(store, profiles)

        val changed = !jsonContentEquals(before, store)
        val sqliteNeedsWrite = sqliteStore == null || !jsonContentEquals(sqliteStore, store)
        val jsonNeedsWrite = jsonStore != null && !jsonContentEquals(jsonStore, store)
        if (jsonNeedsWrite) writeJsonStore(rootfsDir, store)
        if (sqliteNeedsWrite) writeSqliteStore(rootfsDir, store)
        return changed || sqliteNeedsWrite || jsonNeedsWrite
    }

    fun resetCodexOAuthProfiles(rootfsDir: File): ResetResult {
        val jsonStore = readJsonStore(rootfsDir)
        val sqliteStore = readSqliteStore(rootfsDir)
        val jsonReset = removeCodexOAuthProfiles(jsonStore)
        val sqliteReset = removeCodexOAuthProfiles(sqliteStore)

        if (jsonReset.changed && jsonReset.store != null) {
            writeJsonStore(rootfsDir, jsonReset.store)
        }
        if (sqliteReset.changed && sqliteReset.store != null) {
            writeSqliteStore(rootfsDir, sqliteReset.store)
        }

        return ResetResult(
            changed = jsonReset.changed || sqliteReset.changed,
            removedProfileIds = jsonReset.removedProfileIds + sqliteReset.removedProfileIds,
            keptProfileIds = jsonReset.keptProfileIds + sqliteReset.keptProfileIds,
        )
    }

    private fun readJsonStore(rootfsDir: File): JSONObject? {
        val authFile = authProfilesJsonFile(rootfsDir)
        if (!authFile.isFile) return null
        return runCatching { JSONObject(authFile.readText()) }.getOrNull()
    }

    private fun jsonContentEquals(left: JSONObject, right: JSONObject): Boolean {
        return left.toString() == right.toString()
    }

    private fun writeJsonStore(rootfsDir: File, store: JSONObject) {
        val authFile = authProfilesJsonFile(rootfsDir)
        authFile.parentFile?.mkdirs()
        authFile.writeText(store.toString(2))
    }

    private fun readSqliteStore(rootfsDir: File): JSONObject? {
        val databaseFile = authProfilesSqliteFile(rootfsDir)
        if (!databaseFile.isFile) return null
        return runCatching {
            var rawStore: String? = null
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT store_json FROM auth_profile_store WHERE store_key = ?",
                    arrayOf(PRIMARY_ROW_KEY),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        rawStore = cursor.getString(0)
                    }
                }
            }
            rawStore?.let { JSONObject(it) }
        }.getOrNull()
    }

    private fun writeSqliteStore(rootfsDir: File, store: JSONObject) {
        val databaseFile = authProfilesSqliteFile(rootfsDir)
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.beginTransaction()
            try {
                ensureAgentSchema(db)
                val values = ContentValues().apply {
                    put("store_key", PRIMARY_ROW_KEY)
                    put("store_json", store.toString())
                    put("updated_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict(
                    "auth_profile_store",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun ensureAgentSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS schema_meta (
              meta_key TEXT NOT NULL PRIMARY KEY,
              role TEXT NOT NULL,
              schema_version INTEGER NOT NULL,
              agent_id TEXT,
              app_version TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        assertCompatibleSchemaOwner(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cache_entries (
              scope TEXT NOT NULL,
              key TEXT NOT NULL,
              value_json TEXT,
              blob BLOB,
              expires_at INTEGER,
              updated_at INTEGER NOT NULL,
              PRIMARY KEY (scope, key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_cache_expiry
              ON cache_entries(scope, expires_at, key)
              WHERE expires_at IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_cache_updated
              ON cache_entries(scope, updated_at DESC, key)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auth_profile_store (
              store_key TEXT NOT NULL PRIMARY KEY,
              store_json TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auth_profile_state (
              state_key TEXT NOT NULL PRIMARY KEY,
              state_json TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("PRAGMA user_version = $AGENT_SCHEMA_VERSION")

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("meta_key", PRIMARY_ROW_KEY)
            put("role", "agent")
            put("schema_version", AGENT_SCHEMA_VERSION)
            put("agent_id", AGENT_ID)
            putNull("app_version")
            put("created_at", now)
            put("updated_at", now)
        }
        db.insertWithOnConflict("schema_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun assertCompatibleSchemaOwner(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT role, agent_id FROM schema_meta WHERE meta_key = ?",
            arrayOf(PRIMARY_ROW_KEY),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val role = cursor.getString(0)
            val agentId = if (cursor.isNull(1)) null else cursor.getString(1)
            check(role == "agent") { "OpenClaw agent database has schema role $role; expected agent." }
            check(agentId.isNullOrBlank() || agentId == AGENT_ID) {
                "OpenClaw agent database belongs to agent $agentId; expected $AGENT_ID."
            }
        }
    }

    private fun mergeAuthStores(primary: JSONObject?, fallback: JSONObject?): JSONObject? {
        val base = when {
            primary != null -> JSONObject(primary.toString())
            fallback != null -> JSONObject(fallback.toString())
            else -> return null
        }
        if (!base.has("version")) base.put("version", 1)
        val baseProfiles = base.optJSONObject("profiles") ?: JSONObject().also { base.put("profiles", it) }
        val fallbackProfiles = fallback?.optJSONObject("profiles")
        if (fallbackProfiles != null) {
            val keys = fallbackProfiles.keys()
            while (keys.hasNext()) {
                val profileId = keys.next()
                val fallbackCredential = fallbackProfiles.optJSONObject(profileId) ?: continue
                val provider = fallbackCredential.optString("provider").trim().lowercase()
                val shouldOverride = provider == LEGACY_CODEX_OAUTH_PROVIDER ||
                    provider == CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER ||
                    baseProfiles.optJSONObject(profileId) == null
                if (shouldOverride) {
                    baseProfiles.put(profileId, JSONObject(fallbackCredential.toString()))
                }
            }
        }
        mergeProviderSelection(base, fallback, "lastGood")
        mergeProviderSelection(base, fallback, "order")
        return base
    }

    private fun mergeProviderSelection(base: JSONObject, fallback: JSONObject?, key: String) {
        val fallbackSelection = fallback?.optJSONObject(key) ?: return
        val baseSelection = base.optJSONObject(key) ?: JSONObject().also { base.put(key, it) }
        for (provider in listOf(LEGACY_CODEX_OAUTH_PROVIDER, CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER)) {
            if (fallbackSelection.has(provider)) {
                baseSelection.put(provider, fallbackSelection.get(provider))
            }
        }
    }

    private fun ensureOpenAiDefaultSelection(store: JSONObject, profiles: JSONObject) {
        val lastGood = store.optJSONObject("lastGood") ?: JSONObject().also { store.put("lastGood", it) }
        val currentLastGoodOpenAi = lastGood.optString(CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER).trim()
        if (currentLastGoodOpenAi.isBlank() || profiles.optJSONObject(currentLastGoodOpenAi) == null) {
            lastGood.put(CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER, CODEX_APP_SERVER_OPENAI_PROFILE_ID)
        }

        val order = store.optJSONObject("order") ?: JSONObject().also { store.put("order", it) }
        val existingOrder = order.optJSONArray(CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER)
        val orderedIds = mutableListOf(CODEX_APP_SERVER_OPENAI_PROFILE_ID)
        if (existingOrder != null) {
            for (index in 0 until existingOrder.length()) {
                val profileId = existingOrder.optString(index).trim()
                if (
                    profileId.isNotBlank() &&
                    profileId != CODEX_APP_SERVER_OPENAI_PROFILE_ID &&
                    profiles.optJSONObject(profileId) != null &&
                    profileId !in orderedIds
                ) {
                    orderedIds += profileId
                }
            }
        }
        order.put(CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER, JSONArray(orderedIds))
    }

    private data class StoreReset(
        val store: JSONObject?,
        val changed: Boolean,
        val removedProfileIds: Set<String>,
        val keptProfileIds: Set<String>,
    )

    private fun removeCodexOAuthProfiles(store: JSONObject?): StoreReset {
        if (store == null) {
            return StoreReset(null, false, emptySet(), emptySet())
        }
        val next = JSONObject(store.toString())
        val profiles = next.optJSONObject("profiles")
            ?: return StoreReset(next, false, emptySet(), emptySet())
        val removedProfileIds = collectCodexOAuthProfileIds(profiles)
        if (removedProfileIds.isEmpty()) {
            return StoreReset(next, false, emptySet(), profiles.keys().asSequence().toSet())
        }

        for (profileId in removedProfileIds) {
            profiles.remove(profileId)
        }
        val keptProfileIds = profiles.keys().asSequence().toSet()
        pruneProviderProfileReferences(next, removedProfileIds, keptProfileIds)
        return StoreReset(next, true, removedProfileIds, keptProfileIds)
    }

    private fun collectCodexOAuthProfileIds(profiles: JSONObject): Set<String> {
        val removed = linkedSetOf<String>()
        val profileIds = profiles.keys().asSequence().toList()
        for (profileId in profileIds) {
            val profile = profiles.optJSONObject(profileId) ?: continue
            val provider = profile.optString("provider").trim().lowercase()
            val type = profile.optString("type").trim().lowercase()
            when {
                provider == LEGACY_CODEX_OAUTH_PROVIDER -> removed += profileId
                provider == CODEX_APP_SERVER_OPENAI_AUTH_PROVIDER && type == "oauth" && isCodexMirrorProfile(profileId, profile) ->
                    removed += profileId
            }
        }
        return removed
    }

    private fun isCodexMirrorProfile(profileId: String, profile: JSONObject): Boolean {
        val hasOAuthMaterial = profile.optString("access").isNotBlank() ||
            profile.optString("refresh").isNotBlank()
        return profileId == CODEX_APP_SERVER_OPENAI_PROFILE_ID ||
            profile.optString("accountId").isNotBlank() ||
            hasOAuthMaterial
    }

    private fun pruneProviderProfileReferences(
        root: JSONObject,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ) {
        pruneLastGood(root.optJSONObject("lastGood"), removedProfileIds, keptProfileIds)
        pruneOrder(root.optJSONObject("order"), removedProfileIds, keptProfileIds)
        pruneUsageStats(root.optJSONObject("usageStats"), removedProfileIds)
    }

    private fun pruneLastGood(
        lastGood: JSONObject?,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ) {
        if (lastGood == null) return
        val providers = lastGood.keys().asSequence().toList()
        for (provider in providers) {
            val profileId = lastGood.optString(provider).trim()
            if (profileId !in removedProfileIds) continue
            val replacement = keptProfileIds.firstOrNull { it.substringBefore(":") == provider }
            if (replacement == null) lastGood.remove(provider) else lastGood.put(provider, replacement)
        }
    }

    private fun pruneOrder(
        order: JSONObject?,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ) {
        if (order == null) return
        val providers = order.keys().asSequence().toList()
        for (provider in providers) {
            val array = order.optJSONArray(provider) ?: continue
            val kept = mutableListOf<String>()
            for (index in 0 until array.length()) {
                val profileId = array.optString(index).trim()
                if (profileId.isNotBlank() && profileId !in removedProfileIds && profileId in keptProfileIds) {
                    kept += profileId
                }
            }
            if (kept.isEmpty()) order.remove(provider) else order.put(provider, JSONArray(kept))
        }
    }

    private fun pruneUsageStats(
        usageStats: JSONObject?,
        removedProfileIds: Set<String>,
    ) {
        if (usageStats == null) return
        for (profileId in removedProfileIds) {
            usageStats.remove(profileId)
        }
    }

    private fun findLegacyCodexAuthProfile(profiles: JSONObject): JSONObject? {
        profiles.optJSONObject(LEGACY_CODEX_OAUTH_PROFILE_ID)
            ?.takeIf { it.optString("provider").trim().lowercase() == LEGACY_CODEX_OAUTH_PROVIDER }
            ?.let { return it }

        val profileIds = profiles.keys().asSequence().toList().sorted()
        return profileIds
            .asSequence()
            .mapNotNull { profileId -> profiles.optJSONObject(profileId) }
            .firstOrNull { it.optString("provider").trim().lowercase() == LEGACY_CODEX_OAUTH_PROVIDER }
    }
}
