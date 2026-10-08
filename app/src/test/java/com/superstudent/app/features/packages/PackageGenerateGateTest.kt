package com.superstudent.app.features.packages

import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.UploadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-generation gate of ZLQ-91 P1-4: an object that reached Drive but whose content is not
 * recognizable (`canonicalType = UNKNOWN`, stored as `.bin`) must not be able to start a run that can
 * only fail at PARSE — and the reason has to be visible rather than a silently disabled button.
 */
class PackageGenerateGateTest {

    private fun source(
        id: String,
        state: UploadState,
        type: CanonicalType,
        retryable: Boolean = false,
    ) = SourceAssetEntity(
        sourceId = id,
        packageId = "pkg_01",
        drivePath = if (state == UploadState.UPLOADED) {
            "superstudent/v1/packages/pkg_01/sources/$id/$id-${"a".repeat(12)}.${type.ext}"
        } else {
            null
        },
        displayName = "高等数学第三章 $id.pdf",
        mimeType = type.mimeType,
        kind = "FILE",
        sizeBytes = 2048,
        sha256 = "a".repeat(64),
        uploadState = state.name,
        localUri = null,
        addedAt = "2026-09-30T00:00:00Z",
        canonicalType = type.name,
        retryable = retryable,
    )

    @Test
    fun `an uploaded parseable source enables generation`() {
        val ui = PackageDetailUi(sources = listOf(source("src_01", UploadState.UPLOADED, CanonicalType.PDF)))
        assertTrue(ui.canGenerate)
        assertFalse(ui.blockedByFormat)
    }

    @Test
    fun `an uploaded but unrecognized source blocks generation and says so`() {
        val ui = PackageDetailUi(sources = listOf(source("src_01", UploadState.UPLOADED, CanonicalType.UNKNOWN)))
        assertFalse(ui.canGenerate)
        assertTrue(ui.blockedByFormat)
    }

    @Test
    fun `one parseable source is enough in a mixed package`() {
        val ui = PackageDetailUi(
            sources = listOf(
                source("src_01", UploadState.UPLOADED, CanonicalType.UNKNOWN),
                source("src_02", UploadState.UPLOADED, CanonicalType.DOCX),
            )
        )
        assertTrue(ui.canGenerate)
        assertFalse(ui.blockedByFormat)
    }

    @Test
    fun `a failed source waits for a retry instead of blaming the format`() {
        val ui = PackageDetailUi(
            sources = listOf(source("src_01", UploadState.FAILED, CanonicalType.UNKNOWN, retryable = true))
        )
        assertFalse(ui.canGenerate)
        assertFalse(ui.blockedByFormat)
    }
}
