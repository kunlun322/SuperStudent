package com.superstudent.core.repository

import android.content.Context
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.security.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local LRU store for on-demand Drive binaries: ImageGen card pictures (design §6), the mindmap PNG
 * fallback and any artifact the student opens after a reinstall (§3.5 step 4).
 *
 * Presigned Drive URLs expire, so the UI is fed a stable local File rather than the URL — the cache
 * key then survives re-signing. A missing or failed image is never an error: the card stays fully
 * usable and Compose falls back to a drawn placeholder.
 */
class DriveBinaryCache(context: Context, private val drive: DriveRepository) {

    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
        private const val PART_SUFFIX = ".part"
    }

    private val dir = File(context.cacheDir, "drive_binaries").apply { mkdirs() }
    private val mutex = Mutex()

    fun cached(drivePath: String): File? = fileFor(drivePath).takeIf { it.exists() && it.length() > 0 }

    /** Downloads once and caches; returns null when the image is absent or unreachable. */
    suspend fun load(identityId: String, drivePath: String): File? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = fileFor(drivePath)
            if (target.exists() && target.length() > 0) return@withContext target
            val part = File(target.parentFile, target.name + PART_SUFFIX)
            val bytes = runCatching { drive.downloadBytes(identityId, drivePath) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                part.delete()
                return@withContext null
            }
            val written = runCatching {
                part.writeBytes(bytes)
                part.renameTo(target)
            }.getOrDefault(false)
            if (!written) {
                part.delete()
                return@withContext null
            }
            evict()
            target
        }
    }

    /** Batch prefetch, one file at a time so a slow link is never saturated (design §6). */
    suspend fun prefetch(identityId: String, drivePaths: List<String>) {
        drivePaths.forEach { load(identityId, it) }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun evict() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun fileFor(drivePath: String): File {
        val ext = drivePath.substringAfterLast('.', "png").ifBlank { "png" }
        return File(dir, "${Hashing.sha256Hex(drivePath.encodeToByteArray())}.$ext")
    }
}
