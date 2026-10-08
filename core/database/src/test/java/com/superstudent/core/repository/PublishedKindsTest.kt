package com.superstudent.core.repository

import com.superstudent.core.database.ArtifactEntity
import com.superstudent.core.model.ResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedKindsTest {

    private fun artifact(kind: String) = ArtifactEntity(
        artifactId = "pkg_01-$kind",
        packageId = "pkg_01",
        kind = kind,
        drivePath = "superstudent/v1/packages/pkg_01/results/$kind.json",
        sha256 = null,
        sizeBytes = 0,
        schemaVersion = 1,
        localUri = null,
        cacheState = "NONE",
        updatedAt = "2026-09-30T00:00:00Z",
    )

    @Test
    fun `citations alone is not a published result`() {
        assertTrue(listOf(artifact("CITATIONS")).toPublishedKinds().isEmpty())
    }

    @Test
    fun `the five student-visible kinds all count`() {
        val kinds = listOf("PLAN", "CARDS", "CITATIONS", "MINDMAP", "DECK", "EXERCISES")
            .map(::artifact)
            .toPublishedKinds()
        assertEquals(ResultKind.entries.toSet(), kinds)
    }

    @Test
    fun `an unknown kind is ignored rather than treated as a result`() {
        assertTrue(listOf(artifact("SOMETHING_NEW")).toPublishedKinds().isEmpty())
    }

    @Test
    fun `no artifact rows means no results`() {
        assertTrue(emptyList<ArtifactEntity>().toPublishedKinds().isEmpty())
    }
}
