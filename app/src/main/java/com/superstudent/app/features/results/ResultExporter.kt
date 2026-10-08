package com.superstudent.app.features.results

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework export and hand-off (FR-08 / FR-12).
 *
 * Everything goes through a SAF document Uri the student picked, so the app never requests broad
 * storage permission and never guesses a public directory. The returned Uri is owned by this app,
 * which is what lets `openWith` / `share` grant read access to the receiving office app.
 */
class ResultExporter(private val context: Context) {

    companion object {
        const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val HTML_MIME = "text/html"
        const val PNG_MIME = "image/png"
        const val JSON_MIME = "application/json"
    }

    fun createDocumentIntent(fileName: String, mime: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, fileName)

    /** Writes the already-verified bytes to the picked destination and returns the byte count. */
    suspend fun writeTo(uri: Uri, bytes: ByteArray): Long = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: throw IllegalStateException("无法写入所选位置")
        bytes.size.toLong()
    }

    /** AC-08: hand the saved deck to whatever office software can open it. */
    fun openWith(uri: Uri, mime: String): Boolean =
        launch(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    fun share(uri: Uri, mime: String, subject: String): Boolean =
        launch(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    /** No office app installed is a normal outcome on a fresh device, not a crash. */
    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
