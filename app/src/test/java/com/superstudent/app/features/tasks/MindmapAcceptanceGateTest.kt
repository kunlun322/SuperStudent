package com.superstudent.app.features.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZLQ-140 D3 makes the visual contract a success gate: a document-style `mindmap.html` has to fail
 * the run rather than be quietly dropped. Neither `ResultsRepository` nor `TaskRunner` can be
 * executed in a JVM unit test here — they need a Drive client and a Room database — so the wiring
 * is pinned against source.
 */
class MindmapAcceptanceGateTest {

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate.readText()
            if (File(dir, "settings.gradle.kts").exists()) break
            dir = dir.parentFile
        }
        throw AssertionError("找不到源文件 $relative（当前目录 ${File(".").absolutePath}）")
    }

    private val repository =
        source("core/database/src/main/java/com/superstudent/core/repository/ResultsRepository.kt")
    private val validator = source("core/model/src/main/java/com/superstudent/core/model/Validation.kt")
    private val runner = source("app/src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt")

    @Test
    fun `the html is fetched in the same batch as its manifest and the gate defaults to strict`() {
        assertTrue(repository.contains("strictV2: Boolean = true"))
        assertTrue(repository.contains("""text(identityId, packageId, "mindmap.json")"""))
        assertTrue(repository.contains("""text(identityId, packageId, "mindmap.html")"""))
    }

    @Test
    fun `only html that cleared the cross check becomes a recorded artifact`() {
        assertTrue(repository.contains("ResultValidator.acceptedMindmapHtml(mindmapHtmlRaw, mindmap, strictV2)"))
        assertTrue(repository.contains("if (mindmapHtml != null) {"))
        assertTrue(repository.contains("""Triple("MINDMAP_HTML", "mindmap.html""""))
    }

    @Test
    fun `both paths apply the same verdict and only the consequence differs`() {
        // A weaker check on the lenient path is how a pre-v2 outline would keep being displayed as
        // though it were an accepted map.
        assertTrue(validator.contains("runCatching { validateMindmapHtml(html, mindmap, strictV2 = true) }"))
        assertTrue(validator.contains("if (!strictV2) return null"))
        assertTrue(validator.contains("throw failure"))
    }

    @Test
    fun `the run that decides success never opts out of strict v2`() {
        assertTrue(runner.contains("resultsRepository.fetch("))
        // TaskRunner takes the strict default by not naming the flag at all; the only lenient caller
        // is the display path in ResultsViewModel, which reads an already-finished package.
        assertFalse(runner.contains("strictV2"))
    }
}
