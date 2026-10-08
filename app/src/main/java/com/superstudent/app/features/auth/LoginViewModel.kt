package com.superstudent.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.superstudent.app.AppContainer
import com.superstudent.core.network.NetworkCauses
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import com.superstudent.core.repository.AmbiguousIdentityException
import com.superstudent.core.repository.IdentityDisabledException
import com.superstudent.core.repository.ProfileConflictException
import com.superstudent.core.repository.RestoreResult
import com.superstudent.core.security.PatValidator
import com.superstudent.core.security.UsernameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val restoring: Boolean = false,
    val loggedIn: Boolean = false,
    val recovered: Boolean = false,
    /**
     * The failure was a connection failure worth another attempt, so the button says 重试登录 instead of
     * 登录. Cosmetic, but it is the half of ZLQ-138's "retry entry" the student can see: the automatic
     * retries have already been spent by the time this is true.
     */
    val canRetry: Boolean = false,
)

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Saves the PAT into the Keystore-backed store, resolves the Identity strictly per design §2.2,
     * then restores the student's Drive index. Never logs or persists the PAT anywhere else.
     */
    fun login(rawPat: String, rawUsername: String) {
        if (_state.value.busy) return
        val pat = rawPat.trim()
        val normalized = runCatching { UsernameNormalizer.normalize(rawUsername) }
            .getOrElse { e ->
                _state.update { LoginUiState(error = e.message ?: "用户名不合法") }
                return
            }
        val patError = PatValidator.validate(rawPat)
        if (patError != null) {
            _state.update { LoginUiState(error = patError) }
            return
        }

        _state.update { LoginUiState(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.credentialStore.savePat(pat) }
                val session = container.accountRepository.login(normalized)
                // Published as soon as the server has accepted the identity, before the restore: the
                // restore is best effort, and a session state left at `Checking` while the student is
                // already looking at their packages is what makes a later gate read as a surprise.
                container.authSession.markAuthenticated(session.identityId)
                _state.update { it.copy(busy = false, restoring = true) }
                val result = runCatching { container.restoreRepository.restore(session.identityId) }.getOrNull()
                _state.update {
                    it.copy(
                        restoring = false,
                        recovered = result == RestoreResult.RECOVERY_REQUIRED,
                        loggedIn = true,
                    )
                }
            } catch (t: Throwable) {
                // A connection failure never reached a server, so nothing has judged this credential and
                // it stays: the student retries without pasting a 40-character token again. Every other
                // failure drops it, and so does a connection failure on a device that still has an
                // identity bound — `logout` keeps the Room rows, and a leftover credential next to an
                // identity is what the next cold start signs in with (§4.1).
                val identityBound = withContext(Dispatchers.IO) {
                    container.accountRepository.currentIdentityId() != null
                }
                if (!LoginFailurePolicy.keepsCredential(t, identityBound)) {
                    withContext(Dispatchers.IO) { container.credentialStore.clear() }
                }
                // A rollback, not a logout: the log has to keep this on the login timeline, where
                // `to=SignedOut` is what says the attempt failed.
                container.authSession.markLoginRolledBack()
                _state.update {
                    LoginUiState(error = describe(t), canRetry = NetworkCauses.isRetryable(t))
                }
                container.loginDiagnostics.loginFailed(t)
            }
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is AmbiguousIdentityException -> t.message ?: "用户名冲突"
        is IdentityDisabledException -> t.message ?: "账号已停用"
        is ProfileConflictException -> "云端 profile 与当前账号不一致，已停止登录"
        is UsernameNormalizer.InvalidUsernameException -> t.message ?: "用户名不合法"
        is QcaException -> when (t.kind) {
            QcaErrorKind.AUTH_EXPIRED -> "访问令牌无效或已过期，请重新输入"
            // Only reachable if the PAT this screen just saved cannot be read back, so the copy points
            // at the token field rather than at a session the student has not got yet.
            QcaErrorKind.AUTH_REQUIRED -> "令牌未能保存到本机安全存储，请重试"
            QcaErrorKind.ACCESS_DENIED -> "访问被拒绝，请确认令牌权限"
            QcaErrorKind.NETWORK -> LoginFailurePolicy.describe(t) ?: "网络不可用，请检查网络后重试"
            QcaErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
            else -> t.message ?: "登录失败"
        }
        // A presigned transport failure arrives here raw: the Drive calls in the login chain are not
        // normalized into QcaException, so without this arm the student reads an English exception
        // message — and the one thing it names is the host, which is the useful half.
        else -> LoginFailurePolicy.describe(t) ?: t.message ?: "登录失败"
    }
}
