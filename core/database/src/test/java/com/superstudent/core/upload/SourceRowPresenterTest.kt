package com.superstudent.core.upload

import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.model.UploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state × entry × copy matrix of design §4, asserted in full rather than sampled (§7.2 item 9).
 *
 * Three gates from the issue's reverse-gate list live here and are the reason this file enumerates
 * every code instead of picking interesting ones:
 *
 * - R3 / gate 8: no state may produce an empty entry set. ZLQ-105's shape was a permanently failed
 *   row with nothing to press, so "every combination has an exit" is asserted as a product over the
 *   whole enum, not per case.
 * - R4: `等待上传` must not appear in any source-row copy.
 * - R5②: an exhausted row must not promise an automatic path (`自动` / `正在`).
 *
 * This file also absorbs `SourceRowActionsTest`, which guarded the app-local `resolveSourceActions` the
 * presenter replaced (one contract, per R6 — two resolvers is how a row ends up with entries the copy
 * does not describe). Six of its nine assertions are covered above; three are *superseded* by the §4
 * matrix rather than carried over, and are named here so the change is not a silent loss of coverage:
 *
 * - FAILED/UNKNOWN with no local handle left → 重新选择. The matrix gives 重试、删除; see
 *   [entries do not depend on the local access mode, and a hash mismatch keeps only delete in every mode].
 * - FILE_TOO_LARGE / UNSUPPORTED_FORMAT → 删除 only. The matrix gives 重新选择、删除, because the honest
 *   exit for an oversized file is to pick a smaller one and ZLQ-134 owns that copy.
 * - A row in flight offers nothing. The matrix gives 删除 for PENDING and for a healthy UPLOADING, and
 *   重试、删除 for an interrupted one: §3.9 exists precisely so a row being uploaded can be deleted, and
 *   gate 8 forbids a zero-entry row outright.
 */
class SourceRowPresenterTest {

    private val now = 1_700_000_000_000L

    /**
     * Every code `SourceErrorCode` ships, read off the object rather than typed out here.
     *
     * `SourceErrorCode` is an object of `const val` strings, not an enum, so there is no `entries` to
     * iterate; without reflection a code added later would silently skip every matrix gate below,
     * which is exactly the drift R3/R4/R5 exist to catch.
     */
    private val allCodes: Set<String> = SourceErrorCode::class.java.fields
        .filter {
            it.type == String::class.java &&
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                java.lang.reflect.Modifier.isFinal(it.modifiers)
        }
        .map { it.get(null) as String }
        .toSet()

    private fun row(
        state: UploadState = UploadState.FAILED,
        code: String? = null,
        message: String? = null,
        retryable: Boolean = false,
        deletePending: Boolean = false,
        leaseUntil: Long? = null,
        interruptRequestedAt: Long? = null,
        uploadStartedAt: Long? = null,
        lastProgressAt: Long? = null,
    ) = SourceAssetEntity(
        sourceId = "src_1",
        packageId = "pkg_1",
        drivePath = null,
        displayName = "第三章.pdf",
        mimeType = "application/pdf",
        kind = "FILE",
        sizeBytes = 2048,
        sha256 = null,
        uploadState = state.name,
        localUri = "content://downloads/1",
        addedAt = "2026-01-01T00:00:00Z",
        errorCode = code,
        errorMessage = message,
        retryable = retryable,
        leaseUntil = leaseUntil,
        leaseOwnerId = if (state == UploadState.UPLOADING) "owner_1" else null,
        uploadStartedAt = uploadStartedAt,
        lastProgressAt = lastProgressAt,
        interruptRequestedAt = interruptRequestedAt,
        deletePending = deletePending,
    )

    // ── gate 8 / R3: no dead ends ────────────────────────────────────────────────────────────────

    @Test
    fun `every state and every error code leaves at least one entry`() {
        val states = UploadState.entries + null
        val codes = allCodes + null
        states.forEach { state ->
            codes.forEach { code ->
                val stateName = state?.name ?: "NOT_A_REAL_STATE"
                listOf(true, false).forEach { retryable ->
                    val presentation = SourceRowPresenter.of(
                        row(state = UploadState.PENDING, code = code, retryable = retryable)
                            .copy(uploadState = stateName),
                        nowMillis = now,
                    )
                    assertTrue(
                        "state=$stateName code=$code retryable=$retryable produced no entry, " +
                            "which is ZLQ-105's dead-end shape",
                        presentation.actions.isNotEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `delete is offered by every state except the tombstone, which offers retry-delete instead`() {
        UploadState.entries.forEach { state ->
            allCodes.forEach { code ->
                val actions = SourceRowPresenter.of(
                    row(state = state, code = code),
                    nowMillis = now,
                ).actions
                // HASH_MISMATCH and ACCESS_DENIED keep DELETE; AUTH_EXPIRED pairs it with 重新登录.
                // Nothing may drop it, because 删除 is the last exit when retry cannot help.
                assertTrue("state=$state code=$code lost DELETE", SourceAction.DELETE in actions)
            }
        }
        val tombstone = SourceRowPresenter.of(row(deletePending = true), nowMillis = now)
        assertEquals(setOf(SourceAction.RETRY_DELETE), tombstone.actions)
        assertEquals("待清理", tombstone.stateText)
        assertFalse(tombstone.busy)
        assertEquals(
            "删除中…",
            SourceRowPresenter.of(row(deletePending = true), nowMillis = now, deleting = true).stateText,
        )
        assertTrue(
            SourceRowPresenter.of(row(deletePending = true), nowMillis = now, deleting = true).busy,
        )
    }

    @Test
    fun `the tombstone overrides a state that would otherwise offer upload entries`() {
        // A row can be UPLOADED and tombstoned at once: the manifest delete is what is outstanding.
        val presentation = SourceRowPresenter.of(
            row(state = UploadState.UPLOADED, deletePending = true),
            nowMillis = now,
        )
        assertEquals(setOf(SourceAction.RETRY_DELETE), presentation.actions)
        assertEquals("待清理", presentation.stateText)
    }

    // ── R4 / R5②: forbidden wording ──────────────────────────────────────────────────────────────

    @Test
    fun `no copy anywhere says 等待上传`() {
        allPresentations().forEach { (label, presentation) ->
            assertFalse("R4 violated by $label", presentation.stateText.contains("等待上传"))
            presentation.errorText?.let {
                assertFalse("R4 violated by $label", it.contains("等待上传"))
            }
        }
    }

    @Test
    fun `an exhausted row never promises an automatic retry`() {
        // R5②: `稍后自动重试（已耗尽）` and `正在重新获取（已耗尽）` are the named defects. The budget
        // being spent is exactly when wording that promises a background path is a lie.
        SourceErrorCode.AUTO_RETRYABLE.forEach { code ->
            val presentation = SourceRowPresenter.of(
                row(code = code, retryable = false),
                nowMillis = now,
            )
            assertFalse(
                "exhausted ${code} says 自动: ${presentation.stateText}",
                presentation.stateText.contains("自动"),
            )
            assertFalse(
                "exhausted ${code} says 正在: ${presentation.stateText}",
                presentation.stateText.contains("正在"),
            )
            assertTrue(
                "exhausted ${code} must still offer 重试",
                SourceAction.RETRY_UPLOAD in presentation.actions,
            )
        }
    }

    @Test
    fun `a within-budget row keeps the automatic wording the matrix gives it`() {
        // The mirror of the gate above: only the exhausted variant may drop 自动, so the two must
        // actually differ or the exhausted branch is dead copy.
        val within = SourceRowPresenter.of(
            row(code = SourceErrorCode.NETWORK_UNAVAILABLE, retryable = true),
            nowMillis = now,
        )
        val spent = SourceRowPresenter.of(
            row(code = SourceErrorCode.NETWORK_UNAVAILABLE, retryable = false),
            nowMillis = now,
        )
        assertEquals("网络不可用，请检查网络后重试", within.stateText)
        assertEquals("网络仍不可用，请检查网络后重试", spent.stateText)
    }

    // ── §4 matrix, verbatim ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the matrix copy is reproduced verbatim for every failed code`() {
        val expected = mapOf(
            SourceErrorCode.NETWORK_UNAVAILABLE to "网络不可用，请检查网络后重试",
            SourceErrorCode.TIMEOUT to "上传超时，请检查网络后重试",
            SourceErrorCode.SERVER_BUSY to "服务暂时不可用，稍后自动重试",
            SourceErrorCode.PRESIGNED_URL_EXPIRED to "上传链接已过期，正在重新获取",
            SourceErrorCode.MANIFEST_PUBLISH_FAILED to "文件已上传，但资料清单更新失败，请重试",
            SourceErrorCode.PROCESS_INTERRUPTED to "上传中断，请重试",
            SourceErrorCode.UNKNOWN to "上传失败，请重试",
            SourceErrorCode.FILE_TOO_LARGE to "文件超过 50 MB 上限，请压缩或拆分后重新选择",
            SourceErrorCode.UNSUPPORTED_FORMAT to "暂不支持此文件格式",
            SourceErrorCode.URI_PERMISSION_REQUIRED to
                "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件",
            SourceErrorCode.READ_FAILED to "无法读取该文件内容，可能文件已被移动或损坏，请重新选择文件",
            SourceErrorCode.REQUEST_REJECTED to "云端拒绝了该文件，请更换文件后重试",
            SourceErrorCode.AUTH_EXPIRED to "登录已过期，请重新登录后再上传",
            SourceErrorCode.ACCESS_DENIED to "当前账号没有上传权限，请联系管理员",
            // C2 (ZLQ-136): the design's copy wins over R5's `文件已不存在，请重新选择或删除`.
            SourceErrorCode.NOT_FOUND to "云端目录不存在，请重试",
            SourceErrorCode.USER_CANCELLED to "已取消上传",
            // R5④: HASH_MISMATCH wording is ZLQ-131's PM ruling and this issue may not rewrite it.
            SourceErrorCode.HASH_MISMATCH to SourceRowPresenter.HASH_MISMATCH,
        )
        assertEquals(
            "a SourceErrorCode was added without a matrix row",
            allCodes,
            expected.keys,
        )
        expected.forEach { (code, text) ->
            val presentation = SourceRowPresenter.of(
                row(code = code, retryable = code in SourceErrorCode.AUTO_RETRYABLE),
                nowMillis = now,
            )
            assertEquals("FAILED/${code}", text, presentation.stateText)
            assertEquals(SourceRowTone.DANGER, presentation.tone)
        }
    }

    @Test
    fun `the matrix entry sets are reproduced for every failed code`() {
        val expected = mapOf(
            SourceErrorCode.HASH_MISMATCH to setOf(SourceAction.DELETE),
            SourceErrorCode.ACCESS_DENIED to setOf(SourceAction.DELETE),
            SourceErrorCode.AUTH_EXPIRED to setOf(SourceAction.REAUTHENTICATE, SourceAction.DELETE),
            SourceErrorCode.FILE_TOO_LARGE to setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
            SourceErrorCode.UNSUPPORTED_FORMAT to
                setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
            SourceErrorCode.URI_PERMISSION_REQUIRED to
                setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
            SourceErrorCode.READ_FAILED to setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
            SourceErrorCode.REQUEST_REJECTED to setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
        )
        allCodes.forEach { code ->
            val actions = SourceRowPresenter.of(
                row(code = code, retryable = code in SourceErrorCode.AUTO_RETRYABLE),
                nowMillis = now,
            ).actions
            assertEquals(
                "FAILED/${code}",
                expected[code] ?: setOf(SourceAction.RETRY_UPLOAD, SourceAction.DELETE),
                actions,
            )
        }
    }

    @Test
    fun `the server-busy discriminator survives in the stored message, and the exhausted one wins`() {
        // RATE_LIMITED and RETRYABLE both classify to SERVER_BUSY, so `error_message` is the only
        // thing left that can tell them apart. It is template-built, never an HTTP body.
        assertEquals(
            "上传过于频繁，请稍后自动重试",
            SourceRowPresenter.of(
                row(
                    code = SourceErrorCode.SERVER_BUSY,
                    message = "上传过于频繁，请稍后自动重试",
                    retryable = true,
                ),
                nowMillis = now,
            ).stateText,
        )
        assertEquals(
            "服务暂时不可用，请稍后重试",
            SourceRowPresenter.of(
                row(
                    code = SourceErrorCode.SERVER_BUSY,
                    message = "上传过于频繁，请稍后自动重试",
                    retryable = false,
                ),
                nowMillis = now,
            ).stateText,
        )
    }

    @Test
    fun `an unsupported format keeps the conflicting-extension wording the classifier wrote`() {
        assertEquals(
            "文件内容与扩展名不一致，暂不支持此文件格式",
            SourceRowPresenter.of(
                row(
                    code = SourceErrorCode.UNSUPPORTED_FORMAT,
                    message = "文件内容与扩展名不一致，暂不支持此文件格式",
                ),
                nowMillis = now,
            ).stateText,
        )
    }

    @Test
    fun `local-only rows use the re-select matrix, and hash mismatch keeps only delete`() {
        assertEquals(
            "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件",
            SourceRowPresenter.of(
                row(state = UploadState.LOCAL_ONLY, code = SourceErrorCode.URI_PERMISSION_REQUIRED),
                nowMillis = now,
            ).stateText,
        )
        assertEquals(
            "无法读取该文件内容，可能文件已被移动或损坏，请重新选择文件",
            SourceRowPresenter.of(
                row(state = UploadState.LOCAL_ONLY, code = SourceErrorCode.READ_FAILED),
                nowMillis = now,
            ).stateText,
        )
        val mismatch = SourceRowPresenter.of(
            row(state = UploadState.LOCAL_ONLY, code = SourceErrorCode.HASH_MISMATCH),
            nowMillis = now,
        )
        assertEquals(SourceRowPresenter.HASH_MISMATCH, mismatch.stateText)
        assertEquals(setOf(SourceAction.DELETE), mismatch.actions)
        assertEquals(
            "需重新选择文件",
            SourceRowPresenter.of(row(state = UploadState.LOCAL_ONLY), nowMillis = now).stateText,
        )
    }

    @Test
    fun `an unparseable state is still deletable rather than a dead end`() {
        val presentation = SourceRowPresenter.of(
            row().copy(uploadState = "SOMETHING_A_NEWER_BUILD_WROTE"),
            nowMillis = now,
        )
        assertEquals("资料状态异常", presentation.stateText)
        assertEquals(setOf(SourceAction.DELETE), presentation.actions)
        assertEquals(SourceRowTone.DANGER, presentation.tone)
    }

    @Test
    fun `the steady states carry their chip copy and tone`() {
        assertEquals("上传中", SourceRowPresenter.of(row(state = UploadState.PENDING), now).stateText)
        assertEquals(
            SourceRowTone.ACCENT,
            SourceRowPresenter.of(row(state = UploadState.PENDING), now).tone,
        )
        val uploaded = SourceRowPresenter.of(row(state = UploadState.UPLOADED), now)
        assertEquals("已上传", uploaded.stateText)
        assertEquals(SourceRowTone.SUCCESS, uploaded.tone)
        assertEquals(setOf(SourceAction.DELETE), uploaded.actions)
    }

    // ── §3.2 condition 4 / interrupted ───────────────────────────────────────────────────────────

    @Test
    fun `a healthy upload is 上传中 with only delete, and never probes a lock from here`() {
        val healthy = SourceRowPresenter.of(
            row(
                state = UploadState.UPLOADING,
                leaseUntil = now + 60_000,
                uploadStartedAt = now - 5_000,
                lastProgressAt = now - 1_000,
            ),
            nowMillis = now,
        )
        assertEquals("上传中", healthy.stateText)
        assertEquals(setOf(SourceAction.DELETE), healthy.actions)
        assertFalse(healthy.busy)
    }

    @Test
    fun `an expired lease, a stand-down request or a stalled attempt all present as interrupted`() {
        val expired = SourceRowPresenter.of(
            row(state = UploadState.UPLOADING, leaseUntil = now - 1),
            nowMillis = now,
        )
        assertEquals("上传中断，请重试", expired.stateText)
        assertEquals(setOf(SourceAction.RETRY_UPLOAD, SourceAction.DELETE), expired.actions)
        assertEquals(SourceRowTone.DANGER, expired.tone)

        // The 「不得回收」 case: the owner may still be alive and holding its OS lock, and this build
        // has no way to know from here. The copy is the same either way and only the coordinator
        // decides whether the row may actually be reclaimed, so the UI must not read as a promise.
        val standDown = SourceRowPresenter.of(
            row(
                state = UploadState.UPLOADING,
                leaseUntil = now + 60_000,
                interruptRequestedAt = now - 1,
            ),
            nowMillis = now,
        )
        assertEquals("上传中断，请重试", standDown.stateText)
        assertEquals(setOf(SourceAction.RETRY_UPLOAD, SourceAction.DELETE), standDown.actions)

        val stalled = SourceRowPresenter.of(
            row(
                state = UploadState.UPLOADING,
                leaseUntil = now + 60_000,
                uploadStartedAt = now - SOURCE_NO_PROGRESS_MILLIS - 1,
                lastProgressAt = now - SOURCE_NO_PROGRESS_MILLIS - 1,
            ),
            nowMillis = now,
        )
        assertEquals("上传中断，请重试", stalled.stateText)
    }

    @Test
    fun `a row with no lease column at all is interrupted, which is how a v3 orphan presents`() {
        // Post-migration historical UPLOADING rows have `lease_owner_id IS NULL` and `lease_until`
        // NULL: design §3.2 recovery condition 1. They must not render as a healthy 上传中 forever.
        val legacy = SourceRowPresenter.of(row(state = UploadState.UPLOADING), nowMillis = now)
        assertEquals("上传中断，请重试", legacy.stateText)
        assertTrue(SourceRowPresenter.isInterrupted(row(state = UploadState.UPLOADING), now))
    }

    @Test
    fun `no-progress needs a timestamp to judge, so a lease-valid row without one is not interrupted`() {
        assertFalse(
            SourceRowPresenter.isInterrupted(
                row(state = UploadState.UPLOADING, leaseUntil = now + 60_000),
                nowMillis = now,
            ),
        )
        assertFalse(
            SourceRowPresenter.isInterrupted(
                row(state = UploadState.UPLOADED, leaseUntil = now - 1),
                nowMillis = now,
            ),
        )
    }

    // ── §5.2 errorText rule ──────────────────────────────────────────────────────────────────────

    @Test
    fun `errorText only carries a stored message that differs from the rendered one`() {
        val same = SourceRowPresenter.of(
            row(code = SourceErrorCode.NOT_FOUND, message = "云端目录不存在，请重试"),
            nowMillis = now,
        )
        assertNull(same.errorText)

        // A row written by an older build whose copy this version no longer produces: dropping the
        // stored text would lose the only record of why it failed.
        val legacy = SourceRowPresenter.of(
            row(code = SourceErrorCode.NOT_FOUND, message = "文件已不存在，请重新选择或删除"),
            nowMillis = now,
        )
        assertEquals("文件已不存在，请重新选择或删除", legacy.errorText)

        assertNull(
            SourceRowPresenter.of(
                row(code = SourceErrorCode.NOT_FOUND, message = "   "),
                nowMillis = now,
            ).errorText,
        )
    }

    @Test
    fun `the persisted failure message and the rendered one cannot drift apart`() {
        // `PackageRepository.fail()` takes the copy from this presenter, so the assertion below is
        // the contract that makes that safe: for any code, feeding the matrix copy back in as
        // `error_message` yields the same `stateText` and no separate `errorText`.
        allCodes.forEach { code ->
            val first = SourceRowPresenter.of(
                row(code = code, retryable = code in SourceErrorCode.AUTO_RETRYABLE),
                nowMillis = now,
            )
            val roundTripped = SourceRowPresenter.of(
                row(
                    code = code,
                    message = first.stateText,
                    retryable = code in SourceErrorCode.AUTO_RETRYABLE,
                ),
                nowMillis = now,
            )
            assertEquals(code, first.stateText, roundTripped.stateText)
            assertNull(code, roundTripped.errorText)
        }
    }

    // ── carried over from the app-local `resolveSourceActions` this presenter replaced ─────────────

    @Test
    fun `entries do not depend on the local access mode, and a hash mismatch keeps only delete in every mode`() {
        // The resolver this replaced fell back to 重新选择 whenever `local_access_mode` was NONE, on the
        // theory that no stored handle means a retry cannot work. That fallback is gone on purpose: the
        // column records what *this build* managed to persist, not whether the bytes are readable, and
        // guessing from it is what made FAILED/UNKNOWN offer a re-pick the §4 matrix does not give it. An
        // actually unreadable handle converges through the stage classifier to URI_PERMISSION_REQUIRED or
        // READ_FAILED, which do get 重新选择; a retry that finds the file missing costs one attempt and
        // lands there.
        val codes = listOf(
            SourceErrorCode.HASH_MISMATCH,
            SourceErrorCode.UNKNOWN,
            SourceErrorCode.NETWORK_UNAVAILABLE,
            SourceErrorCode.URI_PERMISSION_REQUIRED,
            SourceErrorCode.READ_FAILED,
            null,
        )
        listOf(UploadState.FAILED, UploadState.LOCAL_ONLY).forEach { state ->
            codes.forEach { code ->
                val baseline =
                    SourceRowPresenter.of(row(state = state, code = code), nowMillis = now).actions
                LocalAccessMode.entries.forEach { mode ->
                    assertEquals(
                        "$state/$code/${mode.name}",
                        baseline,
                        SourceRowPresenter.of(
                            row(state = state, code = code).copy(localAccessMode = mode.name),
                            nowMillis = now,
                        ).actions,
                    )
                }
                if (code == SourceErrorCode.HASH_MISMATCH) {
                    // ZLQ-117 裁定一, kept by ZLQ-131: the bytes are readable but they are not the source
                    // this row describes, so a re-pick whose content differs is a door that always slams
                    // shut. Asserted in every mode so no fallback can smuggle it back.
                    assertEquals("$state/HASH_MISMATCH", setOf(SourceAction.DELETE), baseline)
                }
            }
        }
    }

    /** Every presentation the matrix can produce, for the wording gates that must hold globally. */
    private fun allPresentations(): List<Pair<String, SourceRowPresentation>> = buildList {
        UploadState.entries.forEach { state ->
            allCodes.forEach { code ->
                listOf(true, false).forEach { retryable ->
                    val label = "${state.name}/${code}/retryable=$retryable"
                    add(
                        label to SourceRowPresenter.of(
                            row(state = state, code = code, retryable = retryable),
                            nowMillis = now,
                        ),
                    )
                }
            }
        }
        add("tombstone" to SourceRowPresenter.of(row(deletePending = true), nowMillis = now))
        add("illegal" to SourceRowPresenter.of(row().copy(uploadState = "BOGUS"), nowMillis = now))
    }
}
