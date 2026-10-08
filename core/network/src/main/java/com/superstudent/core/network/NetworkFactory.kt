package com.superstudent.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.superstudent.core.model.ssJson
import com.superstudent.core.security.Redaction
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Supplies the decrypted PAT at request time only; PAT is never cached by the network layer. */
fun interface PatProvider {
    fun currentPat(): String?
}

private class AuthInterceptor(private val patProvider: PatProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // An IOException, never a runtime exception: OkHttp rethrows a non-IOException from an
        // interceptor as an uncaught dispatcher exception, which is a FATAL on the main thread
        // (ZLQ-119 design §4.3). ErrorMapper turns this into a structured AUTH_REQUIRED.
        val pat = patProvider.currentPat() ?: throw MissingCredentialException()
        val req = chain.request().newBuilder()
            .header("Authorization", "Bearer $pat")
            .build()
        return chain.proceed(req)
    }
}

/**
 * Debug-only request/response logger; always redacts Authorization and never logs bodies in release.
 *
 * Failures are the exception to "debug only", and they are logged at `w` in every build. The reason is
 * the one ZLQ-138 exists to fix: a student overseas sees 「网络不可用」 and the app throws the only
 * evidence away, so the report that reaches a developer says nothing about which of the two hosts in the
 * login chain was unreachable or why. A failed call is rare, one line, and carries no message text —
 * only the host, the classified cause and the exception class — so it costs nothing to always keep.
 */
private class RedactedLogger(
    private val verboseBodies: Boolean,
    private val hop: NetworkHop,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (verboseBodies) {
            android.util.Log.d(
                TAG,
                "--> ${req.method} ${target(req)} " + Redaction.redact(req.headers.toString()),
            )
        }
        return try {
            val resp = chain.proceed(req)
            if (verboseBodies) {
                android.util.Log.d(TAG, "<-- ${resp.code} ${target(req)}")
            }
            resp
        } catch (e: java.io.IOException) {
            android.util.Log.w(TAG, failureLine(req, e))
            throw e
        }
    }

    /**
     * The part of the URL that is safe to write down.
     *
     * The query is dropped for the presigned hop because that query *is* the signature: a debug logcat
     * is the single most likely place a URL gets copied out of the app, and a copied presigned URL is a
     * bearer credential for one object in the student's Drive. The host stays — knowing which storage
     * region the cloud signed for is the whole point of logging this hop.
     */
    private fun target(req: Request): String {
        val url = req.url
        val query = if (hop == NetworkHop.API) "?${url.encodedQuery.orEmpty()}" else ""
        return "${url.host}${url.encodedPath}$query"
    }

    /**
     * `FAILED PUT /x hop=presigned cause=dns host=x exception=Y` — the shape [NetworkFailure.wire]
     * already defines, plus the method and path so the line can be matched against the request above it.
     *
     * [NetworkCauses.of] rather than a direct classification of the throwable, so that a
     * [MissingCredentialException] — an `IOException` by construction, for which no request was ever
     * made (ZLQ-119 §4.3) — can never be reported as a connection failure. It gets `cause=none`.
     */
    private fun failureLine(req: Request, e: java.io.IOException): String {
        val cause = NetworkCauses.of(e)?.cause?.wire ?: "none"
        return "FAILED ${req.method} ${req.url.encodedPath} " +
            "hop=${hop.wire} cause=$cause host=${req.url.host} exception=${e.javaClass.name}"
    }

    private companion object {
        const val TAG = "SsNet"
    }
}

object NetworkFactory {

    private val JSON_MEDIA = "application/json".toMediaType()

    fun apiClient(patProvider: PatProvider, debug: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(patProvider))
            .addInterceptor(RedactedLogger(debug, NetworkHop.API))
            .build()

    /** Client for presigned upload/download: no auth header (signature is in the URL), long IO timeouts. */
    fun transferClient(debug: Boolean = false): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(RedactedLogger(debug, NetworkHop.PRESIGNED))
            .build()

    /** SSE client: shares auth, long read timeout, heartbeats keep the connection warm. */
    fun sseClient(patProvider: PatProvider): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(patProvider))
            .build()

    fun retrofit(apiClient: OkHttpClient, baseUrl: String = "https://api.qoder.com/"): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(apiClient)
            .addConverterFactory(ssJson.asConverterFactory(JSON_MEDIA))
            .build()

    fun sseRequest(baseUrl: String, sessionId: String, pat: String, lastEventId: String?): Request {
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/v1/forward/sessions/$sessionId/events/stream")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "text/event-stream")
        if (!lastEventId.isNullOrBlank()) builder.header("Last-Event-ID", lastEventId)
        return builder.build()
    }
}
