package com.superstudent.app.features.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two ends of ZLQ-138's "retry entry" that only exist as wiring: the line a failed login writes to
 * logcat, and the button the student presses afterwards.
 *
 * Neither is observable on the JVM — the first goes through `android.util.Log`, the second through
 * Compose — so both are read off the production source the way `CredentialGateWiringTest` does. What the
 * copy and the credential rule themselves do is proven by `LoginFailurePolicyTest`.
 */
class LoginRetryEntryWiringTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    private val viewModel = read("app/src/main/java/com/superstudent/app/features/auth/LoginViewModel.kt")
    private val screen = read("app/src/main/java/com/superstudent/app/features/auth/LoginScreen.kt")
    private val container = read("app/src/main/java/com/superstudent/app/AppContainer.kt")

    private fun block(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        var depth = 0
        for (i in source.indexOf('{', start) until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("unbalanced braces after `$marker`")
    }

    @Test
    fun `a failed login writes its cause to the log before the screen shows the copy`() {
        assertTrue("the login catch block disappeared", viewModel.contains("catch (t: Throwable)"))
        val rollback = viewModel.substring(viewModel.indexOf("catch (t: Throwable)"))
        assertTrue(rollback.contains("container.loginDiagnostics.loginFailed(t)"))
        // After the state update, so a log line is never the reason the student is still looking at 登录中….
        assertTrue(
            rollback.indexOf("LoginUiState(error = describe(t),") < rollback.indexOf("loginDiagnostics.loginFailed(t)"),
        )
        // The ViewModel still may not touch the logger itself: it is the file holding the PAT.
        assertFalse(viewModel.contains("Log."))
        assertTrue(viewModel.contains("container.loginDiagnostics"))
    }

    @Test
    fun `the retry the student can press is driven by the classification, not by a guess`() {
        assertTrue(viewModel.contains("canRetry = NetworkCauses.isRetryable(t)"))
        assertTrue(block(viewModel, "data class LoginUiState(").contains("val canRetry: Boolean = false"))
        val button = block(screen, "SsPillButton(")
        assertTrue(button.contains("state.canRetry -> \"重试登录\""))
        // The label sits behind busy/restoring, so an attempt in flight never reads as a retry.
        assertTrue(button.indexOf("state.busy ->") < button.indexOf("state.canRetry ->"))
        assertEquals(1, Regex("""testTag\("login_button"\)""").findAll(screen).count())
    }

    @Test
    fun `the diagnostics are application scoped, like the session state they describe`() {
        assertTrue(container.contains("val loginDiagnostics = LoginDiagnostics()"))
        // The presigned client logs through the same redacted logger as the API one, in a debug build.
        assertTrue(container.contains("NetworkFactory.transferClient(BuildConfig.DEBUG)"))
    }
}
