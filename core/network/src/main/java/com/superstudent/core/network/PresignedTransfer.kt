package com.superstudent.core.network

import com.superstudent.core.model.PresignedUrlResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.IOException

/** Raised when a presigned URL is rejected/expired; caller must re-request a fresh URL. */
class PresignedUrlExpiredException(val httpCode: Int) : IOException("presigned url rejected: $httpCode")

/**
 * The storage host answered, with a status that is neither success nor a rejected URL.
 *
 * Its message is byte-for-byte what the bare `IOException` this replaces used to build, and it stays an
 * `IOException`, so `SourceFailureClassifier` puts it in the same `NETWORK_UNAVAILABLE` row it always
 * did. The type exists only so [PresignedTransfer.transport] can tell "the host answered" from "the
 * host could not be reached" without reading a message.
 */
class PresignedStatusException(val operation: String, val httpCode: Int) :
    IOException("$operation failed: HTTP $httpCode")

/**
 * A transport failure on the way to a cloud-signed storage URL (ZLQ-138 §5.2).
 *
 * It exists to answer one question the bare `IOException` cannot: *which* host was unreachable. The
 * login chain talks to two — the API host the app was built with, and a storage host the cloud names
 * inside the presigned URL — and only the second one is invisible at compile time. Without the tag, a
 * student whose route to the storage region is broken is told "network unavailable" while the app's own
 * API host is perfectly reachable, which sends the diagnosis in exactly the wrong direction.
 *
 * Only the host goes on the exception. The presigned URL itself never does: its query string *is* the
 * signature, and an exception message is the one thing about a failure that gets pasted into a bug
 * report.
 */
class PresignedTransferException(
    val host: String,
    cause: Throwable,
) : IOException("presigned transfer failed on $host: ${cause.javaClass.simpleName}", cause)

class PresignedTransfer(private val client: OkHttpClient) {

    fun upload(presigned: PresignedUrlResponse, contentType: String, bytes: ByteArray, onProgress: (Long, Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                val counting = CountingSink(sink, bytes.size.toLong(), onProgress)
                val buffered = counting.buffer()
                buffered.write(bytes)
                buffered.flush()
            }
        }
        val builder = Request.Builder().url(presigned.url).put(body)
        presigned.headers.forEach { (k, v) -> builder.header(k, v) }
        if (presigned.headers.keys.none { it.equals("Content-Type", true) }) {
            builder.header("Content-Type", contentType)
        }
        transport(presigned.url) {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 403 || resp.code == 400) throw PresignedUrlExpiredException(resp.code)
                    throw PresignedStatusException("upload", resp.code)
                }
                onProgress(bytes.size.toLong(), bytes.size.toLong())
            }
        }
    }

    fun download(presigned: PresignedUrlResponse): ByteArray {
        val builder = Request.Builder().url(presigned.url).get()
        presigned.headers.forEach { (k, v) -> builder.header(k, v) }
        return transport(presigned.url) {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 403 || resp.code == 400) throw PresignedUrlExpiredException(resp.code)
                    throw PresignedStatusException("download", resp.code)
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    /**
     * Runs one presigned exchange and tags whatever the transport threw with the host it was going to.
     *
     * The two status-driven failures are rethrown untouched. They are not transport failures — the host
     * answered — and `SourceFailureClassifier` reads their types to decide between "the link expired,
     * re-request it" and "the server is unhappy", so wrapping them would move an upload into the wrong
     * row copy.
     */
    private inline fun <T> transport(url: String, block: () -> T): T = try {
        block()
    } catch (e: PresignedUrlExpiredException) {
        throw e
    } catch (e: PresignedStatusException) {
        throw e
    } catch (e: IOException) {
        throw PresignedTransferException(url.toHttpUrlOrNull()?.host ?: UNKNOWN_HOST, e)
    }

    private class CountingSink(delegate: Sink, private val total: Long, private val onProgress: (Long, Long) -> Unit) :
        ForwardingSink(delegate) {
        private var written = 0L
        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            written += byteCount
            onProgress(written, total)
        }
    }

    private companion object {
        const val UNKNOWN_HOST = "unknown"
    }
}
