package com.superstudent.app.auth

import com.superstudent.core.security.CredentialFailureReason
import com.superstudent.core.security.CredentialInspection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZLQ-119 §4.1/§4.2's credential lifecycle, and with it §8.1 items 1 and 2.
 *
 * [AuthSession] takes the Keystore-backed inspection as an injected supplier precisely so the
 * resolution rules can be driven here: this repo has no Robolectric or instrumented harness, and the
 * Android Keystore cannot run on a JVM. What is therefore *driven* is every state transition, which
 * reason produces which state, what gets discarded, what the log line says and what the published
 * state can carry. What is *pinned by reading the production source* is the store behind the supplier
 * — the atomic write, and the order of the four checks that decide between `Absent`, `Unusable` and
 * `Usable` — because those need a real `Context`, a real `AndroidKeyStore` and `android.util.Base64`.
 * That split is declared in the delivery comment; nothing here claims to have run a Keystore.
 */
class AuthSessionTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    /** Every production Kotlin file in the repo, so a scan cannot miss a module. */
    private fun productionSources(): List<File> =
        (listOf(File(repoRoot, "app/src/main")) +
            (File(repoRoot, "core").listFiles()?.filter { File(it, "src/main").isDirectory }
                ?.map { File(it, "src/main") } ?: emptyList()))
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    /** Whitespace-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "")

    private val pat = "pt-THIS-MUST-NEVER-APPEAR-IN-A-LOG-0123456789"
    private val identity = "idt_student_1"

    private class Fixture(
        var inspection: CredentialInspection,
        var identity: String?,
    ) {
        val logs = mutableListOf<String>()
        var discards = 0
        var inspections = 0

        fun session() = AuthSession(
            currentIdentityId = { identity },
            inspect = {
                inspections++
                inspection
            },
            discardCredential = { discards++ },
            log = { logs += it },
        )
    }

    // ---- item 1: the three states -------------------------------------------------------------

    @Test
    fun `a fresh install resolves to SignedOut and discards nothing`() = runTest {
        val f = Fixture(CredentialInspection.Absent, identity = null)
        val session = f.session()
        assertTrue("the state before the first resolve is Checking", session.state.value is AuthSessionState.Checking)

        val state = session.resolve()

        assertEquals(AuthSessionState.SignedOut, state)
        assertEquals(AuthSessionState.SignedOut, session.state.value)
        assertEquals("there is no material to drop", 0, f.discards)
    }

    @Test
    fun `a usable credential with a signed-in identity resolves to Authenticated`() = runTest {
        val f = Fixture(CredentialInspection.Usable(pat), identity)
        val session = f.session()

        val state = session.resolve()

        assertEquals(AuthSessionState.Authenticated(identity), state)
        assertEquals(state, session.state.value)
        assertEquals("a working credential must not be thrown away", 0, f.discards)
    }

    @Test
    fun `a signed-out installation that still holds material drops it, so the next login cannot read it`() =
        runTest {
            // The identity is gone but the ciphertext is not: without the discard, a different student
            // logging in on this device would decrypt the previous one's PAT.
            listOf(
                CredentialInspection.Usable(pat),
                CredentialInspection.Unusable(CredentialFailureReason.CORRUPT),
            ).forEach { inspection ->
                val f = Fixture(inspection, identity = null)
                val state = f.session().resolve()
                assertEquals(AuthSessionState.SignedOut, state)
                assertEquals(1, f.discards)
            }
        }

    // ---- item 2: every failure reason becomes one typed state ---------------------------------

    @Test
    fun `every unusable credential surfaces as ReauthenticationRequired and keeps the identity`() =
        runTest {
            val cases = listOf(
                CredentialInspection.Absent to CredentialFailureReason.MISSING_FILE,
                CredentialInspection.Unusable(CredentialFailureReason.MISSING_FILE) to
                    CredentialFailureReason.MISSING_FILE,
                CredentialInspection.Unusable(CredentialFailureReason.MISSING_KEY) to
                    CredentialFailureReason.MISSING_KEY,
                CredentialInspection.Unusable(CredentialFailureReason.CORRUPT) to
                    CredentialFailureReason.CORRUPT,
                CredentialInspection.Unusable(CredentialFailureReason.DECRYPT_FAILED) to
                    CredentialFailureReason.DECRYPT_FAILED,
            )
            cases.forEach { (inspection, expected) ->
                val f = Fixture(inspection, identity)
                val session = f.session()

                val state = session.resolve()

                assertTrue("$inspection must not resolve to $state", state is AuthSessionState.ReauthenticationRequired)
                state as AuthSessionState.ReauthenticationRequired
                assertEquals(expected, state.reason)
                // §4.1: the invalid material is cleared, the business data and the identity are not —
                // the UI has to be able to say who must log back in.
                assertEquals(identity, state.identityId)
                assertEquals("$inspection must discard exactly once", 1, f.discards)
                assertEquals(state, session.state.value)
            }
        }

    @Test
    fun `the reason enum covers the four local failures and the remote one, and nothing else`() {
        assertEquals(
            listOf("MISSING_FILE", "MISSING_KEY", "CORRUPT", "DECRYPT_FAILED", "REMOTE_401"),
            CredentialFailureReason.entries.map { it.name },
        )
    }

    @Test
    fun `a server 401 discards the credential and publishes REMOTE_401`() = runTest {
        val f = Fixture(CredentialInspection.Usable(pat), identity)
        val session = f.session()
        session.resolve()
        assertEquals(0, f.discards)

        session.reportRemoteRejected()

        val state = session.state.value
        assertTrue(state is AuthSessionState.ReauthenticationRequired)
        assertEquals(CredentialFailureReason.REMOTE_401, (state as AuthSessionState.ReauthenticationRequired).reason)
        assertEquals(identity, state.identityId)
        assertEquals(1, f.discards)
    }

    @Test
    fun `the login and sign-out callbacks publish without re-reading the store`() = runTest {
        val f = Fixture(CredentialInspection.Absent, identity = null)
        val session = f.session()

        session.markAuthenticated(identity)
        assertEquals(AuthSessionState.Authenticated(identity), session.state.value)
        session.markLoggedOut()
        assertEquals(AuthSessionState.SignedOut, session.state.value)
        session.markAuthenticated(identity)
        session.markLoginRolledBack()
        assertEquals(AuthSessionState.SignedOut, session.state.value)

        assertEquals("neither callback may go back to the Keystore", 0, f.inspections)
        assertEquals(0, f.discards)
    }

    // ---- §4.2: the state may not carry the credential -----------------------------------------

    @Test
    fun `no published state or log line can carry the PAT`() = runTest {
        val f = Fixture(CredentialInspection.Usable(pat), identity)
        val session = f.session()

        session.resolve()
        session.reportRemoteRejected()

        f.logs.forEach { line ->
            assertFalse("a log line carries the credential: $line", line.contains(pat))
            assertFalse("a log line carries the identity: $line", line.contains(identity))
        }
        assertFalse(session.state.value.toString().contains(pat))
        assertFalse(CredentialInspection.Usable(pat).toString().contains(pat))
        // The reason travels as an enum name, so it cannot smuggle material with it.
        CredentialFailureReason.entries.forEach { reason ->
            assertFalse(
                AuthSessionState.ReauthenticationRequired(identity, reason).toString().contains(pat),
            )
        }
    }

    @Test
    fun `the transition log follows the one contract and only fires on a real change`() = runTest {
        val f = Fixture(CredentialInspection.Unusable(CredentialFailureReason.DECRYPT_FAILED), identity)
        val session = f.session()

        session.resolve()
        session.resolve()

        assertEquals("a repeat of the same state is not a transition", 1, f.logs.size)
        assertEquals(
            "auth_transition from=Checking to=ReauthenticationRequired reason=CREDENTIAL_DECRYPT_FAILED",
            f.logs.single(),
        )
        // ZLQ-133 §1.1: `reason` is a closed set with no `-`. The old pattern here allowed `-`, which
        // is exactly the branch that let four call sites ship an unattributable line.
        assertTrue(
            "§7.3's format: states and the reason, nothing else",
            Regex("""auth_transition from=\S+ to=\S+ reason=[A-Z_]+""").matches(f.logs.single()),
        )
    }

    // ---- ZLQ-133 §4.1: the closed sets, the labels and every real line -------------------------

    @Test
    fun `the reason wire values are exactly the six the contract names`() {
        val wires = AuthTransitionReason.entries.map { it.wire }
        assertEquals(
            listOf(
                "PROCESS_START",
                "LOGIN",
                "LOGOUT",
                "CREDENTIAL_MISSING",
                "CREDENTIAL_DECRYPT_FAILED",
                "SERVER_REJECTED",
            ),
            wires,
        )
        assertTrue(wires.all { it.isNotBlank() })
        assertEquals("two reasons share a wire value", wires.size, wires.toSet().size)
        wires.forEach { assertTrue("$it is not [A-Z_]+", Regex("[A-Z_]+").matches(it)) }
        // §1.2: `LOGIN_SUCCESS` is not adopted — `resume_request` already froze this action as LOGIN.
        assertFalse(wires.contains("LOGIN_SUCCESS"))
        // §2: no unknown value is kept, so nothing may be able to emit one.
        listOf("UNKNOWN", "OTHER", "-").forEach { assertFalse(wires.contains(it)) }
    }

    @Test
    fun `the four state labels are the contract strings and no reflected name reaches the log`() {
        assertEquals("Checking", AuthSessionState.Checking.label())
        assertEquals("SignedOut", AuthSessionState.SignedOut.label())
        assertEquals("Authenticated", AuthSessionState.Authenticated(identity).label())
        assertEquals(
            "ReauthenticationRequired",
            AuthSessionState.ReauthenticationRequired(identity, CredentialFailureReason.CORRUPT).label(),
        )

        // Split literals: this file is one of the scanned sources, and a guard list that spelled the
        // banned names outright would fail its own check.
        val banned = listOf("simple" + "Name", "qualified" + "Name", "java" + "Class")
        listOf(
            // Every production file that can emit an `auth_transition` line, found rather than
            // assumed: if a second emitter ever appears this fails and that file gets scanned too,
            // instead of quietly shipping a reflected name. The walk is scoped to files emitting this
            // one line, because other diagnostics in the app do name an exception's class — those are
            // free text rather than a closed contract field, so they are out of ZLQ-133's scope (§0)
            // and must not be swept up here.
            "app/src/main/java/com/superstudent/app/auth/AuthSession.kt",
            // And this test: the expectations must be literal strings, not derived by reflection from
            // the very names the release build renames.
            "app/src/test/java/com/superstudent/app/auth/AuthSessionTest.kt",
        ).forEach { path ->
            val source = read(path)
            banned.forEach { pattern ->
                assertFalse("$path still derives a log value from $pattern", source.contains(pattern))
            }
        }
        // The emitter list itself is pinned, so "AuthSession is the only one" is an assertion and not
        // a comment that can rot.
        assertEquals(
            "a second production file emits auth_transition — add it to the scan above",
            listOf("app/src/main/java/com/superstudent/app/auth/AuthSession.kt"),
            productionSources()
                .filter { it.readText().contains("auth_transition") }
                .map { it.relativeTo(repoRoot).invariantSeparatorsPath },
        )
        // The label must be a fixed string per state, so a fifth state without one breaks the build.
        val session = read("app/src/main/java/com/superstudent/app/auth/AuthSession.kt")
        val labelFn = session.substringAfter("fun AuthSessionState.label()").substringBefore("\n}")
        assertFalse("the label `when` grew an else", labelFn.contains("else ->"))
        listOf("Checking", "SignedOut", "Authenticated", "ReauthenticationRequired").forEach {
            assertTrue("the label `when` lost the $it arm", labelFn.contains("\"$it\""))
        }
        // Same rule for the reason: the wire string is what ships, never the enum's reflected name.
        assertFalse(session.contains("reason?.name"))
        assertTrue(session.contains("reason=\${reason.wire}"))
    }

    @Test
    fun `every real transition logs its exact contract line`() = runTest {
        // 正常启动，无 identity
        assertEquals(
            listOf("auth_transition from=Checking to=SignedOut reason=PROCESS_START"),
            lines { f, s -> f.identity = null; s.resolve() },
        )
        // 正常启动，有 identity 且凭据可用
        assertEquals(
            listOf("auth_transition from=Checking to=Authenticated reason=PROCESS_START"),
            lines(usable = true) { _, s -> s.resolve() },
        )
        // 登录成功
        assertEquals(
            listOf("auth_transition from=Checking to=Authenticated reason=LOGIN"),
            lines { _, s -> s.markAuthenticated(identity) },
        )
        // 登录失败回滚：同一条 from/to，但 reason 是 LOGIN 而不是 LOGOUT —— 没人退出登录，
        // 把它读成 logout 会把一次失败的登录放到时间线的另一半。
        assertEquals(
            listOf(
                "auth_transition from=Checking to=Authenticated reason=LOGIN",
                "auth_transition from=Authenticated to=SignedOut reason=LOGIN",
            ),
            lines { _, s -> s.markAuthenticated(identity); s.markLoginRolledBack() },
        )
        // 显式 logout
        assertEquals(
            listOf(
                "auth_transition from=Checking to=Authenticated reason=PROCESS_START",
                "auth_transition from=Authenticated to=SignedOut reason=LOGOUT",
            ),
            lines(usable = true) { _, s -> s.resolve(); s.markLoggedOut() },
        )
        // 缺文件 / 缺 alias / 坏 JSON / 坏 GCM tag
        assertEquals(
            listOf("auth_transition from=Checking to=ReauthenticationRequired reason=CREDENTIAL_MISSING"),
            lines { f, s -> f.inspection = CredentialInspection.Absent; f.identity = identity; s.resolve() },
        )
        // The three middle reasons collapse by contract (§1.2): which of a missing alias, an
        // unparseable file and a failed GCM tag it was stays in the store's own diagnostics.
        listOf(
            CredentialFailureReason.MISSING_KEY,
            CredentialFailureReason.CORRUPT,
            CredentialFailureReason.DECRYPT_FAILED,
        ).forEach { reason ->
            assertEquals(
                "$reason must read as CREDENTIAL_DECRYPT_FAILED",
                listOf(
                    "auth_transition from=Checking to=ReauthenticationRequired " +
                        "reason=CREDENTIAL_DECRYPT_FAILED",
                ),
                lines { f, s ->
                    f.inspection = CredentialInspection.Unusable(reason)
                    f.identity = identity
                    s.resolve()
                },
            )
        }
        // 远端 401
        assertEquals(
            listOf(
                "auth_transition from=Checking to=Authenticated reason=PROCESS_START",
                "auth_transition from=Authenticated to=ReauthenticationRequired reason=SERVER_REJECTED",
            ),
            lines(usable = true) { _, s -> s.resolve(); s.reportRemoteRejected() },
        )
    }

    @Test
    fun `nothing can log a reason of - and a repeated state logs nothing at all`() = runTest {
        // Every state-changing entry point, driven from both a usable and a broken credential, so the
        // sweep covers each arm of `resolve` as well as the four explicit calls.
        listOf(
            CredentialInspection.Absent,
            CredentialInspection.Usable(pat),
            CredentialInspection.Unusable(CredentialFailureReason.MISSING_FILE),
            CredentialInspection.Unusable(CredentialFailureReason.MISSING_KEY),
            CredentialInspection.Unusable(CredentialFailureReason.CORRUPT),
            CredentialInspection.Unusable(CredentialFailureReason.DECRYPT_FAILED),
        ).forEach { inspection ->
            listOf(null, identity).forEach { who ->
                val f = Fixture(inspection, who)
                val s = f.session()
                s.resolve()
                s.resolve()
                s.markAuthenticated(identity)
                s.markAuthenticated(identity)
                s.markLoginRolledBack()
                s.markLoginRolledBack()
                s.markLoggedOut()
                s.markLoggedOut()
                s.reportRemoteRejected()
                s.reportRemoteRejected()

                assertTrue("$inspection/$who produced no transition at all", f.logs.isNotEmpty())
                f.logs.forEach { line ->
                    assertFalse("an unattributable line survived: $line", line.contains("reason=-"))
                    assertFalse("a bare reason= survived: $line", line.contains("reason= "))
                    assertTrue(
                        "not a contract line: $line",
                        Regex("""auth_transition from=(Checking|SignedOut|Authenticated|""" +
                            """ReauthenticationRequired) to=(Checking|SignedOut|Authenticated|""" +
                            """ReauthenticationRequired) reason=[A-Z_]+""").matches(line),
                    )
                }
                // The first of these is a real transition; the two after it land on the state already
                // published, and §4.1 item 4 requires them to be silent.
                s.markLoggedOut()
                val before = f.logs.size
                s.markLoggedOut()
                s.markLoggedOut()
                assertEquals("a same-state re-publish logged", before, f.logs.size)
            }
        }
    }

    @Test
    fun `the five credential failure reasons merge exactly as the contract says`() {
        val expected = mapOf(
            CredentialFailureReason.MISSING_FILE to AuthTransitionReason.CREDENTIAL_MISSING,
            CredentialFailureReason.MISSING_KEY to AuthTransitionReason.CREDENTIAL_DECRYPT_FAILED,
            CredentialFailureReason.CORRUPT to AuthTransitionReason.CREDENTIAL_DECRYPT_FAILED,
            CredentialFailureReason.DECRYPT_FAILED to AuthTransitionReason.CREDENTIAL_DECRYPT_FAILED,
            CredentialFailureReason.REMOTE_401 to AuthTransitionReason.SERVER_REJECTED,
        )
        assertEquals(
            "CredentialFailureReason grew or shrank: decide its log semantics before shipping it",
            CredentialFailureReason.entries.toSet(), expected.keys,
        )
        expected.forEach { (reason, wire) ->
            assertEquals(reason.name, wire, reason.toTransitionReason())
        }
    }

    /** Runs [body] against a fresh session and returns every `auth_transition` line it produced. */
    private suspend fun lines(
        usable: Boolean = false,
        body: suspend (Fixture, AuthSession) -> Unit,
    ): List<String> {
        val f = Fixture(
            if (usable) CredentialInspection.Usable(pat) else CredentialInspection.Absent,
            identity = if (usable) identity else null,
        )
        body(f, f.session())
        return f.logs.toList()
    }

    @Test
    fun `the unified copy is one constant and every re-authentication surface reads it`() {
        assertEquals("登录凭据已失效，请重新登录", REAUTHENTICATION_MESSAGE)
        listOf(
            "app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt",
            "app/src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt",
            "app/src/main/java/com/superstudent/app/MainActivity.kt",
        ).forEach { path ->
            assertTrue("$path must not grow its own copy", read(path).contains("REAUTHENTICATION_MESSAGE"))
        }
    }

    // ---- the store behind the supplier: pinned, not driven ------------------------------------

    @Test
    fun `the credential is committed atomically, so a half-written file cannot be read back`() {
        val store = read("core/security/src/main/java/com/superstudent/core/security/CredentialStore.kt")
        val save = store.substringAfter("fun savePat(").substringBefore("fun inspect(")
        assertTrue(save.contains("FileOutputStream(tempFile)"))
        assertTrue("the bytes must be on disk before the rename", save.contains("out.fd.sync()"))
        assertTrue(save.contains("tempFile.renameTo(file)"))
        val sync = save.indexOf("out.fd.sync()")
        val rename = save.indexOf("tempFile.renameTo(file)")
        assertTrue("fsync before rename", sync in 1 until rename)
        assertTrue("a failed rename must not leave the temp file behind", save.contains("tempFile.delete()"))
        assertFalse(
            "the credential must never be written to the real path directly",
            save.contains("FileOutputStream(file)"),
        )
    }

    @Test
    fun `availability is the decrypt result, checked in the order that keeps the reasons distinct`() {
        val store = read("core/security/src/main/java/com/superstudent/core/security/CredentialStore.kt")
        val inspect = store.substringAfter("fun inspect(").substringBefore("fun loadPat(")
        val file = inspect.indexOf("if (!file.exists()) return CredentialInspection.Absent")
        val version = inspect.indexOf("stored.version != SUPPORTED_VERSION")
        val alias = inspect.indexOf("keyStore().getKey(ALIAS, null)")
        val decrypt = inspect.indexOf("CredentialInspection.Unusable(CredentialFailureReason.DECRYPT_FAILED)")
        listOf(file, version, alias, decrypt).forEach { assertTrue("a required check disappeared", it >= 0) }
        assertEquals(
            "file, then version, then alias, then GCM: each earlier check is what makes the later " +
                "reason meaningful",
            listOf(file, version, alias, decrypt),
            listOf(file, version, alias, decrypt).sorted(),
        )
        // An unsupported version fails closed rather than being read as a legacy plaintext format:
        // the only way `inspect` can hand back a PAT is by decrypting, so there is no migration path.
        assertTrue(
            inspect.flat().contains("CredentialInspection.Unusable(CredentialFailureReason.CORRUPT)"),
        )
        assertEquals(1, inspect.split("CredentialInspection.Usable(").size - 1)
        assertTrue(inspect.contains("CredentialInspection.Usable(String(cipher.doFinal(ct), Charsets.UTF_8))"))
    }

    @Test
    fun `the two availability questions both read the inspection, and clearing removes every trace`() {
        val store = read("core/security/src/main/java/com/superstudent/core/security/CredentialStore.kt")
        assertTrue(store.contains("CredentialInspection.Usable)?.pat"))
        assertTrue(store.contains("inspect() is CredentialInspection.Usable"))
        assertFalse(
            "the old `file.exists()` availability answer is what called a corrupt file usable",
            store.contains("fun hasPat(): Boolean = file.exists()"),
        )
        val clear = store.substringAfter("fun clear()")
        assertTrue(clear.contains("file.delete()"))
        assertTrue(clear.contains("tempFile.delete()"))
        assertTrue(clear.contains("keyStore().deleteEntry(ALIAS)"))
        // The Keystore alias and the file are the only things cleared: no DAO, no Prefs, no Drive.
        assertFalse(clear.contains("Dao"))
        assertFalse(clear.contains("prefs"))
    }

    @Test
    fun `the inspection and the reason enum declare no field that could hold the credential`() {
        val inspection = read(
            "core/security/src/main/java/com/superstudent/core/security/CredentialInspection.kt",
        )
        // Usable is the only carrier of material, and it is the one type that never leaves the app
        // layer: it is consumed by AuthSession and by PatProvider, never published or logged.
        assertEquals(1, inspection.split("val pat: String").size - 1)
        assertTrue(inspection.contains("override fun toString(): String = \"Usable(pat=<redacted>)\""))
        val session = read("app/src/main/java/com/superstudent/app/auth/AuthSession.kt")
        val states = session.substringAfter("sealed interface AuthSessionState")
            .substringBefore("const val REAUTHENTICATION_MESSAGE")
        assertFalse(
            "the published state carries an identity and a reason, never a credential",
            states.contains("pat") || states.contains("ciphertext") || states.contains("iv:"),
        )
        // The reason is a plain enum: no property could hold material even if a caller wanted it to.
        val reasons = read(
            "core/security/src/main/java/com/superstudent/core/security/CredentialInspection.kt",
        ).substringAfter("enum class CredentialFailureReason")
            .substringAfter("{")
            .substringBefore("}")
        assertFalse(reasons.contains("val "))
    }
}
