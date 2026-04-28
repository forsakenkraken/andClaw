package com.coderred.andclaw.ui.screen.settings

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

class OpenClawConfigEditorManager(
    private val rootfsDir: File,
) {

    data class BackupConfigFile(
        val fileName: String,
        val file: File,
    )

    data class ExtensionPruneResult(
        val removedExtensions: List<String>,
        val missingExtensions: List<String>,
        val failedExtensions: List<String>,
        val removedFileCount: Int,
        val missingFileCount: Int,
        val failedFileCount: Int,
    ) {
        val removedCount: Int
            get() = removedFileCount

        val missingCount: Int
            get() = missingFileCount

        val failedCount: Int
            get() = failedFileCount
    }

    private val openClawDir: File
        get() = File(rootfsDir, OPENCLAW_DIR_PATH)

    private val configFile: File
        get() = File(openClawDir, CONFIG_FILE_NAME)

    private val extensionsDir: File
        get() = File(rootfsDir, OPENCLAW_EXTENSIONS_DIR_PATH)

    fun loadCurrentConfig(): String? = configFile.takeIf(File::exists)?.readText()

    fun listBackupConfigs(): List<BackupConfigFile> = BACKUP_FILE_NAMES
        .map { fileName -> BackupConfigFile(fileName = fileName, file = File(openClawDir, fileName)) }
        .filter { it.file.exists() }

    fun loadBackup(fileName: String): String {
        require(fileName in BACKUP_FILE_NAMES) { "Unsupported backup file: $fileName" }
        return File(openClawDir, fileName).readText()
    }

    fun validateJson(raw: String): String {
        val tokener = JSONTokener(raw)
        val parsed = runCatching { tokener.nextValue() }
            .getOrElse { throw IllegalArgumentException("Invalid JSON", it) }

        if (tokener.nextClean() != 0.toChar()) {
            throw IllegalArgumentException("Invalid JSON")
        }

        return when (parsed) {
            is JSONObject -> parsed.toString(2)
            is JSONArray -> parsed.toString(2)
            else -> throw IllegalArgumentException("Invalid JSON")
        }
    }

    fun saveConfig(raw: String) {
        val normalized = validateJson(raw)
        openClawDir.mkdirs()

        if (configFile.exists()) {
            rotateBackupFiles()
            configFile.copyTo(File(openClawDir, BACKUP_FILE_NAMES.first()), overwrite = true)
        }
        configFile.writeText(normalized)
    }

    fun pruneBlacklistedExtensions(): ExtensionPruneResult {
        val removed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val counts = IntArray(3) // [removedFiles, missingFiles, failedFiles]

        pruneTargets(
            rootDir = extensionsDir,
            rootRelativePath = OPENCLAW_EXTENSIONS_DIR_PATH,
            targets = BLACKLISTED_EXTENSION_DIRS,
            removed = removed,
            missing = missing,
            failed = failed,
            counts = counts,
        )

        return ExtensionPruneResult(
            removedExtensions = removed,
            missingExtensions = missing,
            failedExtensions = failed,
            removedFileCount = counts[0],
            missingFileCount = counts[1],
            failedFileCount = counts[2],
        )
    }

    private fun pruneTargets(
        rootDir: File,
        rootRelativePath: String,
        targets: List<String>,
        removed: MutableList<String>,
        missing: MutableList<String>,
        failed: MutableList<String>,
        counts: IntArray,
    ) {
        if (hasSymbolicLinkOnRootPath(rootRelativePath)) {
            failed += targets
            // rootPath를 신뢰할 수 없으므로 파일 수 0으로 간주 (삭제 시도 자체 skip)
            return
        }

        val rootPath = rootDir.toPath()

        targets.forEach { name ->
            val targetPath = name.split('/').fold(rootPath) { acc, segment -> acc.resolve(segment) }
            if (!Files.exists(targetPath)) {
                missing += name
                return@forEach
            }

            if (Files.isSymbolicLink(targetPath)) {
                failed += name
                return@forEach
            }

            val fileCount = countFilesWithoutFollowingSymlinks(targetPath)
            val deleted = runCatching { deleteTreeWithoutFollowingSymlinks(targetPath) }
                .getOrDefault(false)

            if (deleted && !Files.exists(targetPath)) {
                removed += name
                counts[0] += fileCount
            } else {
                failed += name
                counts[2] += fileCount
            }
        }
    }

    private fun countFilesWithoutFollowingSymlinks(targetPath: Path): Int {
        var count = 0
        Files.walkFileTree(targetPath, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                count++
                return FileVisitResult.CONTINUE
            }
        })
        return count
    }

    private fun hasSymbolicLinkOnRootPath(rootRelativePath: String): Boolean {
        var current = rootfsDir.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(current)) return true

        rootRelativePath.split('/').forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                return true
            }
        }
        return false
    }

    private fun deleteTreeWithoutFollowingSymlinks(targetPath: Path): Boolean {
        Files.walkFileTree(targetPath, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                exc: IOException?,
            ): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return !Files.exists(targetPath)
    }

    private fun rotateBackupFiles() {
        File(openClawDir, BACKUP_FILE_NAMES.last()).delete()

        for (index in BACKUP_FILE_NAMES.lastIndex - 1 downTo 0) {
            val source = File(openClawDir, BACKUP_FILE_NAMES[index])
            if (!source.exists()) continue

            val target = File(openClawDir, BACKUP_FILE_NAMES[index + 1])
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    companion object {
        private const val OPENCLAW_DIR_PATH = "root/.openclaw"
        private const val CONFIG_FILE_NAME = "openclaw.json"
        private const val OPENCLAW_EXTENSIONS_DIR_PATH = "usr/local/lib/node_modules/openclaw/dist/extensions"
        private val BACKUP_FILE_NAMES = listOf(
            "openclaw.json.bak",
            "openclaw.json.bak.1",
            "openclaw.json.bak.2",
            "openclaw.json.bak.3",
            "openclaw.json.bak.4",
        )
        val BLACKLISTED_EXTENSION_DIRS = listOf(
            "diffs",
            "amazon-bedrock",
            "amazon-bedrock-mantle",
            "slack",
            "matrix",
            "feishu",
            "webhooks",
            "nostr",
            "qqbot",
        )
    }
}
