package com.superstudent.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Which hop of a chain failed (ZLQ-138 §5.2).
 *
 * The login chain has two hosts in it and only one of them is compiled into the app: `api.qoder.com`
 * for every [QcaApi] call, and a storage host the cloud names inside a presigned URL. A student whose
 * route to the second one is broken sees exactly the same "network unavailable" as one whose route to
 * the first is broken, and the two have different fixes — one is the student's network, the other is
 * where the cloud signs its URLs. This is the distinction that makes the two tellable apart in a
 * logcat and in the copy.
 *
 * Carries its own [wire] string rather than relying on the reflected name, for the reason
 * `AuthTransitionReason` gives: the release build minifies, and a value a human reads back after a
 * field failure is not something R8 gets to choose.
 */
enum class NetworkHop(val wire: String) {
    /** A call to the API host the app was built with. */
    API("api"),

    /** A call to a cloud-signed storage URL. */
    PRESIGNED("presigned"),
}

/**
 * Why a connection-class failure happened.
 *
 * [retryable] is a property of the cause, not of the call site: the call sites this ships with are all
 * idempotent, and the retry loop asks this instead of re-deciding. `TLS` is the one cause that fails
 * deterministically in practice — a certificate the device does not trust, a wrong clock, a middlebox
 * that will reject the handshake identically on every attempt — so backing off and trying again buys
 * three times the wait and the same answer.
 *
 * [timeout] marks the causes that already spent a timeout waiting, and the retry loop gives those
 * fewer attempts: a route that is black-holed costs the full connect timeout per attempt, so three of
 * them would leave the student watching "登录中…" for half a minute to reach the same failure.
 */
enum class NetworkCause(
    val wire: String,
    val retryable: Boolean,
    val timeout: Boolean = false,
) {
    /** The host did not resolve. Captive portals and split-horizon DNS both present this way. */
    DNS("dns", true),

    /** No TCP connection inside the connect timeout. */
    CONNECT_TIMEOUT("connect_timeout", true, timeout = true),

    /** Connected, but no response bytes inside the read timeout. */
    READ_TIMEOUT("read_timeout", true, timeout = true),

    /** The whole call exceeded its budget, which spans DNS, connect, TLS and the response. */
    CALL_TIMEOUT("call_timeout", true, timeout = true),

    /** Something answered the connection attempt with a refusal. */
    CONNECTION_REFUSED("connection_refused", true),

    /** No route to the host or network. */
    UNREACHABLE("unreachable", true),

    /** The peer or a middlebox dropped an established connection. */
    RESET("reset", true),

    /** The handshake failed. Not retried: see the enum's doc. */
    TLS("tls", false),

    /** The call was cancelled, so nothing failed on its own. Never retried. */
    CANCELED("canceled", false),

    /** The storage host answered and rejected the presigned URL itself. Nothing to reconnect to. */
    PRESIGNED_REJECTED("presigned_rejected", false),

    /** An I/O failure this classifier does not recognise. Retried, because the attempt count bounds it. */
    OTHER("other", true),
}

/**
 * The classified shape of one connection failure, small enough to put on an exception and safe enough
 * to put in a log.
 *
 * It deliberately carries no message text. An I/O message can contain the URL it failed on, and a
 * presigned URL carries its signature in the query string — so the message stays where the redaction
 * happens ([com.superstudent.core.security.Redaction] at the log site) and never rides along on a
 * field the UI reads. [exception] is the class name only.
 */
data class NetworkFailure(
    val hop: NetworkHop,
    val cause: NetworkCause,
    /** The host the failing call was addressed to, when the failure carries one. */
    val host: String?,
    val exception: String,
) {
    /** One line for a log: `hop=presigned cause=dns host=x exception=Y`. */
    fun wire(): String =
        "hop=${hop.wire} cause=${cause.wire} host=${host ?: "-"} exception=$exception"
}

/**
 * Turns any throwable from the login chain into a [NetworkFailure], or says it was not a connection
 * failure at all.
 *
 * It walks the cause chain rather than looking only at the top throwable, because two wrappers sit
 * between the socket and whoever is asking: [qcaCall] normalizes into [QcaException], and
 * [PresignedTransfer] tags its failures with the host. Both are transparent here — the classification
 * and the reported exception class come from the innermost throwable that has a specific answer, so a
 * `PresignedTransferException` around an `UnknownHostException` reports DNS and names the
 * `UnknownHostException`, not the wrapper.
 */
object NetworkCauses {

    private const val MAX_CAUSE_DEPTH = 8

    /** The classification, or null when [t] is not a connection failure (a 4xx, a parse error, ...). */
    fun of(t: Throwable?): NetworkFailure? {
        if (t == null) return null
        (t as? QcaException)?.network?.let { return it }
        // A mapped failure that is not NETWORK was decided by a status code, not by a socket.
        if (t is QcaException && t.kind != QcaErrorKind.NETWORK) return null
        val (source, cause) = locate(t) ?: return null
        return NetworkFailure(
            hop = if (isPresignedHop(t)) NetworkHop.PRESIGNED else NetworkHop.API,
            cause = cause,
            host = presignedHost(t),
            exception = source.javaClass.name,
        )
    }

    /** True when a connection-class failure is worth another attempt. */
    fun isRetryable(t: Throwable?): Boolean = of(t)?.cause?.retryable == true

    /**
     * The throwable in [t]'s chain that carries the classification, and the classification it carries.
     *
     * The first *specific* answer wins, and an `OTHER` is only ever a fallback: a wrapper's own message
     * ("presigned transfer failed on host") classifies as nothing more precise than OTHER, and stopping
     * there would throw away the `UnknownHostException` underneath it that says what actually happened.
     */
    private fun locate(t: Throwable): Pair<Throwable, NetworkCause>? {
        var current: Throwable? = t
        var depth = 0
        var fallback: Pair<Throwable, NetworkCause>? = null
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            // An IOException by construction (ZLQ-119 §4.3) that means no request was ever made. It is
            // not a connection failure, and nothing wrapping it can make it one.
            if (current is MissingCredentialException) return null
            when (val cause = classifyDirect(current)) {
                null -> Unit
                NetworkCause.OTHER -> if (fallback == null) fallback = current to cause
                else -> return current to cause
            }
            val next = current.cause
            if (next === current) break
            current = next
            depth++
        }
        return fallback
    }

    private fun isPresignedHop(t: Throwable): Boolean = walk(t) {
        if (it is PresignedTransferException ||
            it is PresignedStatusException ||
            it is PresignedUrlExpiredException
        ) {
            true
        } else {
            null
        }
    } == true

    private fun presignedHost(t: Throwable): String? =
        walk(t) { (it as? PresignedTransferException)?.host }

    /** First non-null [pick] over the cause chain, bounded so a self-referencing cause cannot spin. */
    private fun <R> walk(t: Throwable?, pick: (Throwable) -> R?): R? {
        var current = t
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            pick(current)?.let { return it }
            val next = current.cause
            if (next === current) return null
            current = next
            depth++
        }
        return null
    }

    /**
     * Classification of one throwable, no chain walking.
     *
     * Order matters twice over. `NoRouteToHostException` extends `ConnectException`, so asking about
     * the general one first would report every unroutable host as refused. And `SocketTimeoutException`
     * extends `InterruptedIOException`, so the connect-timeout message has to be read before the
     * general call-budget branch claims it.
     */
    private fun classifyDirect(t: Throwable): NetworkCause? = when {
        t is PresignedUrlExpiredException -> NetworkCause.PRESIGNED_REJECTED
        t is MissingCredentialException -> null
        t is SSLException -> NetworkCause.TLS
        t is UnknownHostException -> NetworkCause.DNS
        t is SocketTimeoutException ->
            if (t.message.orEmpty().contains("connect timed out", ignoreCase = true)) {
                NetworkCause.CONNECT_TIMEOUT
            } else {
                NetworkCause.READ_TIMEOUT
            }
        // OkHttp's own cancellation surfaces as an IOException, not a CancellationException, because it
        // is the call that was cancelled from under a blocking socket read.
        t is InterruptedIOException ->
            if (t.message.orEmpty().contains("canceled", ignoreCase = true)) {
                NetworkCause.CANCELED
            } else {
                NetworkCause.CALL_TIMEOUT
            }
        t is NoRouteToHostException -> NetworkCause.UNREACHABLE
        t is ConnectException -> messageCause(t) ?: NetworkCause.CONNECTION_REFUSED
        t is SocketException -> messageCause(t) ?: NetworkCause.RESET
        t is IOException -> messageCause(t) ?: NetworkCause.OTHER
        else -> null
    }

    /** The causes that only show up in the message, because several classes report them identically. */
    private fun messageCause(t: Throwable): NetworkCause? {
        val message = t.message.orEmpty()
        if (isCancellationText(t)) return NetworkCause.CANCELED
        return when {
            message.contains("network is unreachable", true) ||
                message.contains("host is unresolved", true) ||
                message.contains("no address associated", true) ||
                message.contains("enetunreach", true) -> NetworkCause.UNREACHABLE
            message.contains("ehostunreach", true) -> NetworkCause.UNREACHABLE
            message.contains("connection reset", true) ||
                message.contains("broken pipe", true) ||
                message.contains("stream was reset", true) ||
                message.contains("unexpected end of stream", true) -> NetworkCause.RESET
            message.contains("connection refused", true) ||
                message.contains("econnrefused", true) -> NetworkCause.CONNECTION_REFUSED
            message.contains("timed out", true) ||
                message.contains("timeout", true) -> NetworkCause.CONNECT_TIMEOUT
            else -> null
        }
    }

    private fun isCancellationText(t: Throwable): Boolean =
        t.message.orEmpty().contains("canceled", ignoreCase = true) ||
            t.message.orEmpty().contains("cancelled", ignoreCase = true) ||
            t.message.orEmpty().contains("socket closed", ignoreCase = true)
}
