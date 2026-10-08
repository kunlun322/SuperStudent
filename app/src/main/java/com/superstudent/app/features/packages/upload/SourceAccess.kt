package com.superstudent.app.features.packages.upload

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.model.Ids
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.upload.SourceFailureClassifier
import com.superstudent.core.upload.SourceTooLargeException
import com.superstudent.core.upload.SourceUnreadableException
import com.superstudent.core.upload.normalizeLocalReadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Reaches the picked content after the picker returned (design increment §1).
 *
 * The display name is read verbatim and only ever used for display; it never reaches a Drive path.
 * A source that cannot be re-read is surfaced through [SourceUnreadableException] so the caller can
 * move the row to `LOCAL_ONLY` with "需重新选择文件" — never a silent drop.
 */
object SourceAccess {

    const val MAX_BYTES: Long = SourceFailureClassifier.MAX_BYTES

    /** Pasted text is capped by characters, not bytes, because the dialog counts characters. */
    const val MAX_TEXT_CHARS = 200_000

    /** Formats the add-source screen lists, kept next to the cap so they cannot drift apart. */
    const val SUPPORTED_HINT = "支持 PDF / DOCX / PPTX / TXT / Markdown / JPG / PNG / WEBP / HEIC / HEIF，" +
        "单个文件不超过 50 MB。无法识别的格式会明确提示，不会静默失败。"

    data class Picked(val displayName: String, val mimeType: String, val sizeBytes: Long)

    /** SAF gives a display name and, usually, a size. Both are display metadata only. */
    fun describe(context: Context, uri: Uri): Picked {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return Picked(
            displayName = name?.takeIf { it.isNotBlank() } ?: "资料-${Ids.src().takeLast(6)}",
            mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            sizeBytes = if (size < 0) 0L else size,
        )
    }

    /**
     * Promotes the picker's one-shot grant to a persistable one and re-checks it took effect. Call
     * from the picker callback: the grant flags are only in force while the result is being handled.
     */
    fun takePersistableGrant(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }.getOrDefault(false)

    fun releaseGrant(context: Context, uri: String?) {
        if (uri.isNullOrBlank()) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun stagingDir(context: Context) = File(context.filesDir, "source-staging")

    private fun stagingFile(context: Context, sourceId: String) =
        File(stagingDir(context), DrivePathSafe.name(sourceId))

    /**
     * Providers that cannot hand out a persistable grant (the system Photo Picker above all) get a
     * private copy instead, so a retry hours later still has bytes to read.
     */
    fun stageCopy(context: Context, uri: Uri, sourceId: String): Long {
        val target = stagingFile(context, sourceId)
        target.parentFile?.mkdirs()
        var written = 0L
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw SourceUnreadableException()
            input.use { source ->
                target.outputStream().use { sink ->
                    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(chunk)
                        if (read < 0) break
                        written += read
                        if (written > MAX_BYTES) throw SourceTooLargeException(written)
                        sink.write(chunk, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            // Staging reads the same picker URI the upload path reads, so it gets the same stage
            // normalization: a source deleted between the picker and this copy must land on
            // URI_PERMISSION_REQUIRED, not be reported as an unavailable network (ZLQ-110 §3.1).
            throw normalizeLocalReadFailure(t)
        }
        return written
    }

    /** Pasted text has no URI at all, so the staged copy is its only durable handle. */
    fun stageText(context: Context, sourceId: String, text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_BYTES) throw SourceTooLargeException(bytes.size.toLong())
        val target = stagingFile(context, sourceId)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return bytes.size.toLong()
    }

    fun deleteStaging(context: Context, sourceId: String) {
        runCatching { stagingFile(context, sourceId).delete() }
    }

    fun hasStaging(context: Context, sourceId: String): Boolean =
        runCatching { stagingFile(context, sourceId).exists() }.getOrDefault(false)

    /** Reads the content for one attempt, from whichever handle the row still has. */
    suspend fun read(context: Context, row: SourceAssetEntity): ByteArray = withContext(Dispatchers.IO) {
        if (row.sizeBytes > MAX_BYTES) throw SourceTooLargeException(row.sizeBytes)
        when (LocalAccessMode.of(row.localAccessMode)) {
            LocalAccessMode.APP_COPY -> {
                val file = stagingFile(context, row.sourceId)
                if (!file.exists()) throw SourceUnreadableException()
                readLocal { file.inputStream() }
            }

            LocalAccessMode.PERSISTED_URI -> {
                val uri = row.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    ?: throw SourceUnreadableException()
                readLocal {
                    context.contentResolver.openInputStream(uri) ?: throw SourceUnreadableException()
                }
            }

            LocalAccessMode.NONE -> throw SourceUnreadableException()
        }
    }

    /**
     * Whether the row's local handle can still be opened, without reading the content. The startup
     * reconciler uses it to tell a real network failure from a file that was deleted or moved after
     * the failure was recorded, instead of trusting the stored `error_code` (ZLQ-110 §3.4).
     *
     * Blocking: call from an IO dispatcher.
     */
    fun canRead(context: Context, row: SourceAssetEntity): Boolean {
        return when (LocalAccessMode.of(row.localAccessMode)) {
            LocalAccessMode.APP_COPY -> stagingFile(context, row.sourceId).exists()
            LocalAccessMode.PERSISTED_URI -> {
                val uri = row.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                uri != null && runCatching {
                    context.contentResolver.openInputStream(uri)?.use { true } ?: false
                }.getOrDefault(false)
            }
            LocalAccessMode.NONE -> false
        }
    }

    /**
     * Reads through [readCapped] and normalizes every failure of the local read stage into the typed
     * exception that names it. Without this, a deleted original escapes as a bare
     * [java.io.FileNotFoundException] and the classifier can only see "an `IOException`", which it
     * reports as an unavailable network (ZLQ-105).
     */
    private fun readLocal(open: () -> InputStream): ByteArray = try {
        readCapped(open)
    } catch (t: Throwable) {
        throw normalizeLocalReadFailure(t)
    }

    private fun readCapped(open: () -> InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        open().use { input ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw SourceTooLargeException(total)
                buffer.write(chunk, 0, read)
            }
        }
        return buffer.toByteArray()
    }
}

/** `source_id` is generated locally, but staging still refuses to build a path from it blindly. */
private object DrivePathSafe {
    fun name(raw: String): String {
        val safe = raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        require(safe.isNotEmpty() && safe != "." && safe != "..") { "非法本地文件名" }
        return safe
    }
}
