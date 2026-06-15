package com.coderred.andclaw.data.transfer

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TransferArchive {
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_PREFERENCES = "preferences.json"
    private const val ENTRY_CHECKSUMS = "checksums.json"
    private const val STREAM_BUFFER_SIZE = 32 * 1024

    fun writeDeterministicArchive(
        targetFile: File,
        manifest: TransferManifest,
        approvedPreferencesSnapshot: Map<String, String>,
        rootfsDir: File,
    ) {
        val normalizedPreferences = approvedPreferencesSnapshot
            .mapNotNull { (key, value) ->
                val normalizedKey = key.trim()
                if (normalizedKey.isEmpty()) null else normalizedKey to value
            }
            .toMap()

        val manifestBytes = TransferManifestJson.toJson(manifest).toByteArray(StandardCharsets.UTF_8)
        val preferencesBytes = encodeDeterministicPreferencesJson(normalizedPreferences)
            .toByteArray(StandardCharsets.UTF_8)

        val curatedFileSet = collectCuratedOpenClawFiles(rootfsDir, manifest)
        val curatedFiles = curatedFileSet.includedFiles

        // Size check before writing
        val totalPayloadBytes = curatedFiles.sumOf { (_, file) -> file.length() }
        if (totalPayloadBytes > TransferSizeLimits.MAX_TRANSFER_PAYLOAD_BYTES) {
            throw IllegalStateException(
                buildPayloadLimitMessage(
                    includedFiles = curatedFiles,
                    excludedFiles = curatedFileSet.excludedFiles,
                    totalPayloadBytes = totalPayloadBytes,
                ),
            )
        }

        // Single-pass: write ZIP entries and compute checksums simultaneously.
        // checksums.json is appended last to avoid TOCTOU — files are read once,
        // written to ZIP and hashed in the same read pass.
        val checksumsByPath = linkedMapOf<String, String>()

        // Prepare sorted entry order (checksums.json excluded, added last)
        val sortedEntries = buildList {
            add(EntrySource(ENTRY_MANIFEST, InMemory(manifestBytes)))
            add(EntrySource(ENTRY_PREFERENCES, InMemory(preferencesBytes)))
            curatedFiles.forEach { (name, file) -> add(EntrySource(name, OnDisk(file))) }
        }.sortedBy { it.name }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetFile))).use { zipOutput ->
            // Write all entries except checksums, computing checksums as we go
            sortedEntries.forEach { src ->
                val entry = ZipEntry(src.name).apply { time = manifest.createdAtEpochMs }
                zipOutput.putNextEntry(entry)

                when (val data = src.data) {
                    is InMemory -> {
                        zipOutput.write(data.bytes)
                        checksumsByPath[src.name] = TransferCrypto.sha256Hex(data.bytes)
                    }
                    is OnDisk -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        data.file.inputStream().use { fis ->
                            val buffer = ByteArray(STREAM_BUFFER_SIZE)
                            while (true) {
                                val read = fis.read(buffer)
                                if (read < 0) break
                                zipOutput.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                            }
                        }
                        checksumsByPath[src.name] = hexString(digest.digest())
                    }
                }
                zipOutput.closeEntry()
            }

            // Append checksums.json as the last entry
            val checksumsBytes = encodeDeterministicChecksumsJson(checksumsByPath)
                .toByteArray(StandardCharsets.UTF_8)
            val checksumsEntry = ZipEntry(ENTRY_CHECKSUMS).apply { time = manifest.createdAtEpochMs }
            zipOutput.putNextEntry(checksumsEntry)
            zipOutput.write(checksumsBytes)
            zipOutput.closeEntry()
        }
    }

    private fun collectCuratedOpenClawFiles(rootfsDir: File, manifest: TransferManifest): CuratedOpenClawFiles {
        val rootCanonical = rootfsDir.canonicalFile
        val entries = linkedMapOf<String, File>()
        val excludedEntries = linkedMapOf<String, File>()

        val scanDirs = listOf(
            File(rootCanonical, "root/.openclaw"),
            File(rootCanonical, "root/.codex"),
        )

        scanDirs.forEach scanDir@{ dir ->
            if (!dir.exists() || !dir.isDirectory) return@scanDir
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach eachFile@{ file ->
                    val normalizedPath = normalizePathForArchive(file, rootCanonical) ?: return@eachFile
                    if (!TransferManifestContract.shouldIncludeExportPath(normalizedPath)) {
                        excludedEntries[normalizedPath] = file
                        return@eachFile
                    }
                    entries[normalizedPath] = file
                }
        }

        validateRequiredEntries(manifest, entries.keys)

        return CuratedOpenClawFiles(
            includedFiles = entries
                .toList()
                .sortedBy { (path, _) -> path },
            excludedFiles = excludedEntries
                .toList()
                .sortedBy { (path, _) -> path },
        )
    }

    private fun validateRequiredEntries(manifest: TransferManifest, exportedPaths: Set<String>) {
        val lowercasePaths = exportedPaths.map { it.lowercase(java.util.Locale.US) }.toSet()
        val missingRequiredPaths = manifest.includedSections.mapNotNull { section ->
            TransferManifestContract.REQUIRED_FIXED_PATHS_BY_SECTION[section]?.takeUnless { lowercasePaths.contains(it) }
        }
        if (missingRequiredPaths.isNotEmpty()) {
            throw IllegalStateException(
                "Transfer export is incomplete. Missing required files: ${missingRequiredPaths.joinToString()}"
            )
        }
    }

    private fun normalizePathForArchive(file: File, rootCanonical: File): String? {
        val canonicalFile = file.canonicalFile
        val rootPath = rootCanonical.path
        val filePath = canonicalFile.path
        if (filePath == rootPath || !filePath.startsWith(rootPath + File.separator)) {
            return null
        }

        return filePath
            .removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
    }

    private fun encodeDeterministicPreferencesJson(snapshot: Map<String, String>): String {
        val out = StringBuilder(snapshot.size * 48 + 2)
        out.append('{')
        snapshot.toSortedMap().entries.forEachIndexed { index, (key, value) ->
            if (index > 0) out.append(',')
            appendJsonString(out, key)
            out.append(':')
            appendJsonString(out, value)
        }
        out.append('}')
        return out.toString()
    }

    private fun appendJsonString(out: StringBuilder, value: String) {
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u")
                        out.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
    }

    private fun encodeDeterministicChecksumsJson(checksumsByPath: Map<String, String>): String {
        val out = StringBuilder(checksumsByPath.size * 96 + 2)
        out.append('{')
        checksumsByPath.toSortedMap().entries.forEachIndexed { index, (path, checksum) ->
            if (index > 0) out.append(',')
            appendJsonString(out, path)
            out.append(':')
            appendJsonString(out, checksum)
        }
        out.append('}')
        return out.toString()
    }

    private fun hexString(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            out.append(((byte.toInt() ushr 4) and 0xF).toString(16))
            out.append((byte.toInt() and 0xF).toString(16))
        }
        return out.toString()
    }

    private fun buildPayloadLimitMessage(
        includedFiles: List<Pair<String, File>>,
        excludedFiles: List<Pair<String, File>>,
        totalPayloadBytes: Long,
    ): String {
        val includedSizeEntries = includedFiles.map { (path, file) -> SizeEntry(path, file.length()) }
        val excludedSizeEntries = excludedFiles.map { (path, file) -> SizeEntry(path, file.length()) }
            .filter { it.bytes > 0L }
        val largestIncludedFiles = formatTopSizeEntries(includedSizeEntries)
        val largestIncludedDirectories = formatTopSizeEntries(groupByDirectory(includedSizeEntries))
        val largestExcludedFiles = formatTopSizeEntries(excludedSizeEntries)
        val excludedTotalBytes = excludedSizeEntries.sumOf { it.bytes }

        return buildString {
            append("Transfer payload exceeds the allowed total size limit ")
            append("(${TransferSizeLimits.MAX_TRANSFER_PAYLOAD_BYTES / 1024 / 1024}MB)")
            append(": included=")
            append(formatBytes(totalPayloadBytes))
            append(" across ")
            append(includedFiles.size)
            append(" files")
            append(". Largest included files: ")
            append(largestIncludedFiles)
            append(". Largest included directories: ")
            append(largestIncludedDirectories)
            if (excludedSizeEntries.isNotEmpty()) {
                append(". Excluded transient files: ")
                append(formatBytes(excludedTotalBytes))
                append(" across ")
                append(excludedSizeEntries.size)
                append(" files; largest excluded: ")
                append(largestExcludedFiles)
            }
        }
    }

    private fun groupByDirectory(entries: List<SizeEntry>): List<SizeEntry> {
        return entries
            .groupBy { summarizeDirectory(it.path) }
            .map { (path, groupedEntries) -> SizeEntry(path, groupedEntries.sumOf { it.bytes }) }
    }

    private fun summarizeDirectory(path: String): String {
        return when {
            path.startsWith("root/.openclaw/agents/main/agent/codex-home/") ->
                "root/.openclaw/agents/main/agent/codex-home"
            path.startsWith("root/.openclaw/agents/main/agent/") ->
                "root/.openclaw/agents/main/agent"
            path.startsWith("root/.openclaw/agents/main/sessions/") ->
                "root/.openclaw/agents/main/sessions"
            path.startsWith("root/.openclaw/credentials/") ->
                "root/.openclaw/credentials"
            path.startsWith("root/.openclaw/") ->
                "root/.openclaw"
            path.startsWith("root/.codex/") ->
                "root/.codex"
            else -> path.substringBeforeLast('/', missingDelimiterValue = path)
        }
    }

    private fun formatTopSizeEntries(entries: List<SizeEntry>, limit: Int = 5): String {
        val topEntries = entries
            .filter { it.bytes > 0L }
            .sortedWith(compareByDescending<SizeEntry> { it.bytes }.thenBy { it.path })
            .take(limit)
        if (topEntries.isEmpty()) return "none"
        return topEntries.joinToString("; ") { "${it.path}=${formatBytes(it.bytes)}" }
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        if (mib < 1024.0) {
            return String.format(Locale.US, "%.1fMB", mib)
        }
        return String.format(Locale.US, "%.2fGB", mib / 1024.0)
    }

    private data class CuratedOpenClawFiles(
        val includedFiles: List<Pair<String, File>>,
        val excludedFiles: List<Pair<String, File>>,
    )

    private data class SizeEntry(
        val path: String,
        val bytes: Long,
    )

    private sealed interface EntryData
    private class InMemory(val bytes: ByteArray) : EntryData
    private class OnDisk(val file: File) : EntryData

    private class EntrySource(val name: String, val data: EntryData)
}
