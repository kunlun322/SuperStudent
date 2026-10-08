package com.superstudent.core.drive

import com.superstudent.core.model.DeleteEntryRequest
import com.superstudent.core.model.DownloadUrlRequest
import com.superstudent.core.model.DriveEntry
import com.superstudent.core.model.PresignedUrlResponse
import com.superstudent.core.model.UploadUrlRequest
import com.superstudent.core.model.ssJson
import com.superstudent.core.network.PresignedTransfer
import com.superstudent.core.network.PresignedUrlExpiredException
import com.superstudent.core.network.QcaApi
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import com.superstudent.core.network.qcaCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

class DriveNotFoundException(val path: String) : Exception("Drive 对象不存在: $path")

/**
 * All Drive I/O for one identity. Paths are relative, validated, and never root-recursive.
 */
class DriveRepository(
    private val api: QcaApi,
    private val transfer: PresignedTransfer,
) : ManifestStore {

    suspend fun listEntries(identityId: String, path: String?): List<DriveEntry> =
        withContext(Dispatchers.IO) {
            val normalized = path?.let { DrivePath.validateRelative(it) }
            val out = mutableListOf<DriveEntry>()
            var token: String? = null
            do {
                val page = qcaCall { api.listDriveEntries(identityId, normalized, 100, token) }
                out += page.entries
                token = page.pageToken?.takeIf { it.isNotBlank() }
            } while (token != null)
            out
        }

    suspend fun listRecursive(identityId: String, prefix: String): List<DriveEntry> =
        withContext(Dispatchers.IO) {
            val validated = DrivePath.validateRelative(prefix)
            val out = mutableListOf<DriveEntry>()
            val queue = ArrayDeque<String>()
            queue.add(validated)
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                val entries = try {
                    listEntries(identityId, dir)
                } catch (_: DriveNotFoundException) {
                    emptyList()
                } catch (e: QcaException) {
                    if (e.kind == QcaErrorKind.NOT_FOUND) emptyList() else throw e
                }
                entries.forEach { e ->
                    out += e
                    if (e.isDir()) queue.add(e.path)
                }
            }
            out
        }

    private fun DriveEntry.isDir(): Boolean =
        type.equals("directory", true) || type == "dir"

    suspend fun downloadUrl(identityId: String, path: String): PresignedUrlResponse =
        withContext(Dispatchers.IO) {
            DrivePath.validateRelative(path)
            qcaCall { api.downloadUrl(identityId, DownloadUrlRequest(path = path)) }
        }

    suspend fun uploadUrl(identityId: String, path: String, contentType: String): PresignedUrlResponse =
        withContext(Dispatchers.IO) {
            DrivePath.validateRelative(path)
            qcaCall { api.uploadUrl(identityId, UploadUrlRequest(path = path, contentType = contentType)) }
        }

    suspend fun downloadBytes(identityId: String, path: String): ByteArray =
        withContext(Dispatchers.IO) {
            DrivePath.validateRelative(path)
            val signed = try {
                qcaCall { api.downloadUrl(identityId, DownloadUrlRequest(path = path)) }
            } catch (e: QcaException) {
                if (e.kind == QcaErrorKind.NOT_FOUND) throw DriveNotFoundException(path)
                throw e
            }
            transfer.download(signed)
        }

    /** Presigned PUT; a rejected/expired URL is re-requested up to 3 times. */
    suspend fun uploadBytes(
        identityId: String,
        path: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        DrivePath.validateRelative(path)
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                val signed = qcaCall {
                    api.uploadUrl(identityId, UploadUrlRequest(path = path, contentType = contentType))
                }
                try {
                    transfer.upload(signed, contentType, bytes, onProgress ?: { _, _ -> })
                    return@withContext
                } catch (e: PresignedUrlExpiredException) {
                    attempt++
                    if (attempt >= 3) throw e
                }
            }
        }
    }

    suspend fun exists(identityId: String, path: String): Boolean = try {
        downloadUrl(identityId, path)
        true
    } catch (_: DriveNotFoundException) {
        false
    }

    suspend fun <T> readJson(identityId: String, path: String, serializer: KSerializer<T>): T {
        val text = downloadBytes(identityId, path).decodeToString()
        return ssJson.decodeFromString(serializer, text)
    }

    override suspend fun <T> readJsonOrNull(identityId: String, path: String, serializer: KSerializer<T>): T? = try {
        readJson(identityId, path, serializer)
    } catch (_: DriveNotFoundException) {
        null
    } catch (_: SerializationException) {
        null
    }

    override suspend fun <T> writeJson(identityId: String, path: String, value: T, serializer: KSerializer<T>) {
        val bytes = ssJson.encodeToString(serializer, value).encodeToByteArray()
        uploadBytes(identityId, path, "application/json", bytes)
    }

    suspend fun delete(identityId: String, path: String, recursive: Boolean = false) =
        withContext(Dispatchers.IO) {
            val validated = DrivePath.validateRelative(path)
            require(validated != DrivePath.ROOT) { "禁止删除 identity Drive 根目录" }
            qcaCall {
                api.deleteDriveEntry(identityId, DeleteEntryRequest(path = validated, recursive = recursive))
            }
        }

    /** Deletes a run/source sub-tree. Refuses anything at or above the Drive root. */
    suspend fun deleteSubtree(identityId: String, prefix: String) {
        val validated = DrivePath.validateRelative(prefix)
        require(validated != DrivePath.ROOT) { "禁止删除 identity Drive 根目录" }
        require(validated.startsWith(DrivePath.ROOT + "/")) { "只能删除 superstudent/v1/ 下的路径" }
        require(validated.split("/").size >= 4) { "删除范围过宽: $prefix" }
        withContext(Dispatchers.IO) {
            qcaCall {
                api.deleteDriveEntry(identityId, DeleteEntryRequest(path = validated, recursive = true))
            }
        }
    }
}
