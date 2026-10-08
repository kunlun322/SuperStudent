package com.superstudent.core.network

import java.io.IOException

/**
 * Thrown by the auth interceptor when there is no usable local credential, so the request is never
 * sent (ZLQ-119 design §4.3).
 *
 * It is an [IOException] deliberately. OkHttp rethrows anything an interceptor throws that is *not*
 * an IOException as an uncaught exception on its dispatcher, which surfaces as a FATAL on the main
 * thread and puts the foreground service into its restart backoff — the crash loop this class exists
 * to remove. Being an IOException makes it an ordinary, catchable call failure that [ErrorMapper]
 * turns into a structured `AUTH_REQUIRED`.
 *
 * The message is fixed and carries nothing but the category: no PAT, no ciphertext, no path.
 */
class MissingCredentialException(message: String = MESSAGE) : IOException(message) {

    companion object {
        const val CODE = "no_credential"
        const val MESSAGE = "本地无可用登录凭据，请重新登录"
    }
}
