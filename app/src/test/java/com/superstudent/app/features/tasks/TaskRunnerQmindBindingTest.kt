package com.superstudent.app.features.tasks

import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.model.ProfileJson
import com.superstudent.core.model.QmindSourceRefJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZLQ-90 scope B/D-2: the binding handed to the Agent and the first-build admission decision. */
class TaskRunnerQmindBindingTest {

    private fun source(id: String, sha256: String?) = SourceAssetEntity(
        sourceId = id,
        packageId = "pkg_01",
        drivePath = "superstudent/v1/packages/pkg_01/sources/$id/file.pdf",
        displayName = "file.pdf",
        mimeType = "application/pdf",
        kind = "FILE",
        sizeBytes = 1024,
        sha256 = sha256,
        uploadState = "UPLOADED",
        localUri = null,
        addedAt = "2026-09-30T00:00:00Z",
    )

    private fun profileWith(vararg refs: QmindSourceRefJson) = ProfileJson(
        identityId = "idn_test000001",
        externalIdHash = "ext_hash",
        displayName = "Tester",
        qmindNotebookId = "nb_bound_01",
        qmindSources = refs.toList(),
        createdAt = "2026-09-01T00:00:00Z",
        updatedAt = "2026-09-01T00:00:00Z",
    )

    @Test
    fun `first ingest carries null ingestedSha256 and null qmindSourceId`() {
        val binding = TaskRunner.qmindBinding(null, listOf(source("src_01", "a".repeat(64))))
        val ref = binding.sources.single()
        assertEquals("src_01", ref.sourceId)
        assertEquals("a".repeat(64), ref.sha256)
        assertNull(ref.ingestedSha256)
        assertNull(ref.qmindSourceId)
    }

    @Test
    fun `changed content carries the previous ingestedSha256 so the skip rule cannot fire`() {
        val profile = profileWith(
            QmindSourceRefJson(
                sourceId = "src_01",
                sourceSha256 = "a".repeat(64),
                qmindSourceId = "qs_prev",
            ),
        )
        val binding = TaskRunner.qmindBinding(profile, listOf(source("src_01", "c".repeat(64))))
        val ref = binding.sources.single()
        assertEquals("a".repeat(64), ref.ingestedSha256)
        assertEquals("c".repeat(64), ref.sha256)
        assertNotEquals(ref.ingestedSha256, ref.sha256)
        assertEquals("qs_prev", ref.qmindSourceId)
        assertEquals("nb_bound_01", binding.notebookId)
    }

    @Test
    fun `unchanged content keeps sha256 equal to ingestedSha256`() {
        val sha = "b".repeat(64)
        val profile = profileWith(
            QmindSourceRefJson(sourceId = "src_02", sourceSha256 = sha, qmindSourceId = "qs_2"),
        )
        val binding = TaskRunner.qmindBinding(profile, listOf(source("src_02", sha)))
        val ref = binding.sources.single()
        assertEquals(sha, ref.ingestedSha256)
        assertEquals(ref.ingestedSha256, ref.sha256)
    }

    @Test
    fun `admission guard lets exactly one first-build task through`() {
        // Notebook not bound yet: any other active run of the same identity blocks the start.
        assertTrue(TaskRunner.admitFirstBuild(notebookBound = false, sameIdentityActiveRuns = 0))
        assertFalse(TaskRunner.admitFirstBuild(notebookBound = false, sameIdentityActiveRuns = 1))
        assertFalse(TaskRunner.admitFirstBuild(notebookBound = false, sameIdentityActiveRuns = 2))
        // Once bound, `notebook create` can no longer race, so no limit applies.
        assertTrue(TaskRunner.admitFirstBuild(notebookBound = true, sameIdentityActiveRuns = 0))
        assertTrue(TaskRunner.admitFirstBuild(notebookBound = true, sameIdentityActiveRuns = 3))
    }

    @Test
    fun `failed envelope keeps the qmind snapshot parseable with compiled false`() {
        val manifest = PromptTemplate.parseFinalManifest(
            """{"status":"FAILED","stage":"QMIND_INDEX","reason":"retrieval_not_ready",
               "qmind":{"notebookId":"nb_1","ownerUserHash":"beef","compiled":false,
                        "sources":[{"sourceId":"src_01","sha256":"aa","qmindSourceId":null}]}}"""
        )!!
        assertFalse(manifest.succeeded)
        assertEquals("QMIND_INDEX", manifest.stage)
        assertEquals("retrieval_not_ready", manifest.reason)
        assertEquals("nb_1", manifest.qmind?.notebookId)
        assertFalse(manifest.qmind!!.compiled)
        assertNull(manifest.qmind!!.sources.single().qmindSourceId)
    }
}
