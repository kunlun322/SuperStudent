package com.superstudent.app.auth

import com.superstudent.core.security.CredentialFailureReason
import com.superstudent.core.security.CredentialInspection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The application-level authentication state, per ZLQ-119 design §4.2.
 *
 * It exists so "can this installation talk to the cloud" has one typed answer that every owner reads,
 * instead of each call site probing `file.exists()` and discovering the truth as an exception from
 * inside OkHttp.
 */
sealed interface AuthSessionState {
    data object Checking : AuthSessionState
    data object SignedOut : AuthSessionState
    data class Authenticated(val identityId: String) : AuthSessionState
    data class ReauthenticationRequired(
        val identityId: String?,
        val reason: CredentialFailureReason,
    ) : AuthSessionState
}

/** The one copy of the stable sentence §4.2 requires every re-authentication surface to show. */
const val REAUTHENTICATION_MESSAGE = "登录凭据已失效，请重新登录"

/**
 * A state's name in the `auth_transition` log (ZLQ-133 §3).
 *
 * An exhaustive sealed `when` over fixed strings, not a reflected class name. The release build
 * minifies, and the shipped v7 mapping renamed these four to `tf`/`sf`/`uf`/`vf` — so the reflected
 * name was never a contract value, it was whatever R8 chose that build, and the field log read
 * `from=tf to=sf`. No keep rule is added to prop it up: an observable string belongs in source, and
 * a shrinker exception that has to be re-earned at every R8 upgrade is not a contract.
 *
 * No `else`, so a fifth state without a label fails to compile rather than logging a blank.
 *
 * The three reflection APIs this replaces are deliberately not named in this file: a test scans the
 * whole log path for them as bare substrings, and naming them here — even inside a comment saying not
 * to use them — would trip it.
 */
fun AuthSessionState.label(): String = when (this) {
    AuthSessionState.Checking -> "Checking"
    AuthSessionState.SignedOut -> "SignedOut"
    is AuthSessionState.Authenticated -> "Authenticated"
    is AuthSessionState.ReauthenticationRequired -> "ReauthenticationRequired"
}

/**
 * Why an `auth_transition` happened (ZLQ-133 §1.1). Six values, closed: no `UNKNOWN`, no `OTHER`,
 * and no `-` fallback.
 *
 * Each entry carries its own [wire] string rather than relying on the enum's reflected name, for the
 * same reason [label] does not: that name is derived by reflection, R8 is free to change what it
 * returns, and this value reaches a log a human reads back after a field failure.
 *
 * `LOGIN_SUCCESS` is deliberately absent. `resume_request` already froze the same user action as
 * `LOGIN`, and whether it worked is what `to=Authenticated|SignedOut` says; a second spelling would
 * make whoever is debugging translate two vocabularies for one event.
 */
enum class AuthTransitionReason(val wire: String) {
    /** The process asked the credential store who is signed in, and got an answer. */
    PROCESS_START("PROCESS_START"),

    /** A login attempt, in either direction — `to=` says which. */
    LOGIN("LOGIN"),

    /** The user signed out. */
    LOGOUT("LOGOUT"),

    /** An identity is signed in but there is no credential file at all. */
    CREDENTIAL_MISSING("CREDENTIAL_MISSING"),

    /** The file is there and cannot be decrypted. */
    CREDENTIAL_DECRYPT_FAILED("CREDENTIAL_DECRYPT_FAILED"),

    /** The credential decrypted and the server refused it. */
    SERVER_REJECTED("SERVER_REJECTED"),
}

/**
 * The only place a local credential failure becomes a log reason (ZLQ-133 §1.2, §2 item 2).
 *
 * Exhaustive with no `else`: adding a [CredentialFailureReason] without deciding what it says in the
 * log must break the build, not fall into a catch-all that quietly reports the wrong thing.
 *
 * The three middle entries collapse on purpose. Which of a missing Keystore alias, an unparseable
 * file and a failed GCM tag it was is a debugging detail of the store, and it stays available there;
 * the session's answer to all three is the same sentence — this device cannot decrypt what it holds.
 */
fun CredentialFailureReason.toTransitionReason(): AuthTransitionReason = when (this) {
    CredentialFailureReason.MISSING_FILE -> AuthTransitionReason.CREDENTIAL_MISSING
    CredentialFailureReason.MISSING_KEY,
    CredentialFailureReason.CORRUPT,
    CredentialFailureReason.DECRYPT_FAILED -> AuthTransitionReason.CREDENTIAL_DECRYPT_FAILED
    CredentialFailureReason.REMOTE_401 -> AuthTransitionReason.SERVER_REJECTED
}

/**
 * Resolves and publishes [AuthSessionState].
 *
 * Everything it touches is injected, including the log sink, so the resolution rules are drivable on
 * the JVM: this repo has no Robolectric or instrumented harness, and the Android Keystore behind
 * [CredentialInspection] is exactly the part that cannot run there.
 *
 * An unusable credential is discarded here rather than inside the store, because whether "cannot
 * decrypt" means *signed out* or *signed in but lost the credential* depends on the identity, and only
 * this class reads both. The identity itself and all business data are kept (§4.1), so the UI can say
 * who has to log back in.
 *
 * Every method that can move the state fixes its own [AuthTransitionReason], and [publish] takes a
 * non-null one with no default. A caller therefore cannot omit the reason: the gap ZLQ-128 describes
 * was a nullable parameter plus a `-` fallback, which let four call sites ship `reason=-` and made
 * the log line unattributable.
 */
class AuthSession(
    private val currentIdentityId: suspend () -> String?,
    private val inspect: () -> CredentialInspection,
    private val discardCredential: () -> Unit,
    private val log: (String) -> Unit = { android.util.Log.i(TAG, it) },
) {

    private val _state = MutableStateFlow<AuthSessionState>(AuthSessionState.Checking)
    val state: StateFlow<AuthSessionState> = _state.asStateFlow()

    /**
     * Reads the identity and the credential once and publishes the state they imply.
     *
     * Both success arms are `PROCESS_START`, which is what §1.2 names this call point. The credential
     * failure arms are not: they carry the reason the credential failed, because "the process asked"
     * is not the interesting half of that transition. A gate re-resolve that reaches the same answer
     * publishes nothing at all — [publish] compares states first.
     */
    suspend fun resolve(): AuthSessionState {
        val identityId = currentIdentityId()?.takeIf { it.isNotBlank() }
        val inspection = inspect()
        if (identityId == null) {
            // Nothing is signed in, so leftover material cannot be attributed to an account. Keeping
            // it would let the next login decrypt a previous student's PAT.
            if (inspection !is CredentialInspection.Absent) discardCredential()
            return publish(AuthSessionState.SignedOut, AuthTransitionReason.PROCESS_START)
        }
        return when (inspection) {
            is CredentialInspection.Usable ->
                publish(AuthSessionState.Authenticated(identityId), AuthTransitionReason.PROCESS_START)
            is CredentialInspection.Absent ->
                reauthenticate(identityId, CredentialFailureReason.MISSING_FILE)
            is CredentialInspection.Unusable -> reauthenticate(identityId, inspection.reason)
        }
    }

    /** The server refused a credential this device believed was valid (§4.1's 401 row). */
    suspend fun reportRemoteRejected() {
        val identityId = (_state.value as? AuthSessionState.Authenticated)?.identityId
            ?: currentIdentityId()?.takeIf { it.isNotBlank() }
        discardCredential()
        publish(
            AuthSessionState.ReauthenticationRequired(identityId, CredentialFailureReason.REMOTE_401),
            AuthTransitionReason.SERVER_REJECTED,
        )
    }

    /** The student logged in and the credential is stored. */
    fun markAuthenticated(identityId: String) {
        publish(AuthSessionState.Authenticated(identityId), AuthTransitionReason.LOGIN)
    }

    /** The user asked to sign out; the caller has already cleared the credential. */
    fun markLoggedOut() {
        publish(AuthSessionState.SignedOut, AuthTransitionReason.LOGOUT)
    }

    /**
     * A login attempt failed after it had already published, so the session goes back to `SignedOut`.
     *
     * The reason is still `LOGIN`, not `LOGOUT`: nobody signed out, and reading this line as a logout
     * would put a failed login into the wrong half of the timeline. `to=SignedOut` is what says it
     * did not work.
     */
    fun markLoginRolledBack() {
        publish(AuthSessionState.SignedOut, AuthTransitionReason.LOGIN)
    }

    private fun reauthenticate(
        identityId: String,
        reason: CredentialFailureReason,
    ): AuthSessionState {
        discardCredential()
        return publish(
            AuthSessionState.ReauthenticationRequired(identityId, reason),
            reason.toTransitionReason(),
        )
    }

    private fun publish(next: AuthSessionState, reason: AuthTransitionReason): AuthSessionState {
        val previous = _state.value
        if (previous != next) {
            _state.value = next
            // §7.3: states and the reason only. No PAT, no ciphertext, no identity id.
            log(
                "auth_transition from=${previous.label()} to=${next.label()} " +
                    "reason=${reason.wire}",
            )
        }
        return next
    }

    private companion object {
        const val TAG = "SsAuth"
    }
}
