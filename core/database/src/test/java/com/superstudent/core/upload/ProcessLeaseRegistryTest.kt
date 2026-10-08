package com.superstudent.core.upload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Design §7.2 item 1: a live owner's lock cannot be taken, a dead owner's can, and two recovery
 * probes only ever produce one winner.
 *
 * The kernel — not a fake — decides these. A `FileLock` is dropped when the holding process dies for
 * any reason, which is why `tryLock` succeeding on another owner's file is admissible as proof that
 * owner is gone, and why a PID is not: PIDs are recycled and a recycled PID reads as alive forever.
 *
 * Reverse gate 2 is respected by ordering, and the ordering *is* the assertion: every "dead owner"
 * case below first proves the same owner was alive and un-takeable, and only then closes it. Seeding
 * an owner that was never alive would prove nothing about reclamation.
 */
class ProcessLeaseRegistryTest {

    private lateinit var dir: File
    private val open = mutableListOf<ProcessLeaseRegistry>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ss-lease").toFile()
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
        dir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    private fun registry(): ProcessLeaseRegistry = ProcessLeaseRegistry(dir).also { open += it }

    @Test
    fun `a live process holds its owner lock and a recovery probe cannot take it`() {
        val alive = registry()
        assertTrue(alive.holdsProcessLock())

        val probe = registry()
        assertNull(
            "a live owner's kernel lock was taken, which would let two processes reclaim one row",
            probe.tryAcquireOwner(alive.ownerId),
        )
    }

    @Test
    fun `the same owner becomes takeable only once it is gone`() {
        val doomed = registry()
        val ownerId = doomed.ownerId
        val probe = registry()
        assertNull(probe.tryAcquireOwner(ownerId))

        // Standing in for process death: closing releases the kernel lock exactly as an exit, a crash
        // or a force-stop does, and there is no other way to model it inside one JVM.
        doomed.close()
        open -= doomed

        val taken = probe.tryAcquireOwner(ownerId)
        assertNotNull("a dead owner's lock must be takeable or its rows are orphaned forever", taken)

        // And it stays exclusive while held: a second recovery pass in the same instant must not win.
        assertNull(
            "two recovery passes both acquired one owner lock",
            registry().tryAcquireOwner(ownerId),
        )
        taken?.close()
    }

    @Test
    fun `two recovery probes of one dead owner produce exactly one winner`() {
        val doomed = registry()
        val ownerId = doomed.ownerId
        doomed.close()
        open -= doomed

        val first = registry().tryAcquireOwner(ownerId)
        val second = registry().tryAcquireOwner(ownerId)
        assertNotNull(first)
        assertNull(second)
        first?.close()
    }

    @Test
    fun `a process never probes its own owner id, which within one JVM would be an overlap not a death`() {
        val self = registry()
        assertNull(self.tryAcquireOwner(self.ownerId))
        assertNull(self.tryAcquireOwner(null))
        assertNull(self.tryAcquireOwner(""))
        assertNull(self.tryAcquireOwner("   "))
    }

    @Test
    fun `the per-source execution lock excludes a second worker on the same row`() {
        val worker = registry()
        val held = worker.tryAcquireSource("src_1")
        assertNotNull(held)

        // A different registry stands for a different process; the same registry would throw
        // OverlappingFileLockException and report null, which is the same answer for the caller.
        val other = registry()
        assertNull(
            "two workers hold the execution lock for one source, so two PUTs of one object are live",
            other.tryAcquireSource("src_1"),
        )
        assertNotNull(other.tryAcquireSource("src_2"))

        held?.close()
        val reacquired = other.tryAcquireSource("src_1")
        assertNotNull("the lock was not released when the attempt finished", reacquired)
        reacquired?.close()
    }

    @Test
    fun `an id that is not in the emitted alphabet is refused rather than used as a path segment`() {
        val probe = registry()
        listOf(
            "../../etc/passwd",
            "a/b",
            "src\\1",
            "..",
            ".",
            "x".repeat(129),
            "src 1",
        ).forEach { id ->
            assertNull("unsafe sourceId accepted: `$id`", probe.tryAcquireSource(id))
            assertNull("unsafe ownerId accepted: `$id`", probe.tryAcquireOwner(id))
        }
        // Nothing escaped the registry's directories.
        assertEquals(
            setOf("upload-owner", "source-lock"),
            dir.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    @Test
    fun `the attempt registry distinguishes a live attempt from an orphan of a cancelled coroutine`() {
        val worker = registry()
        assertNull(worker.activeAttempt("src_1"))

        worker.registerAttempt("src_1", "tok_a")
        assertEquals("tok_a", worker.activeAttempt("src_1"))

        // A stale unregister must not clear a newer attempt's registration.
        worker.unregisterAttempt("src_1", "tok_old")
        assertEquals("tok_a", worker.activeAttempt("src_1"))

        worker.unregisterAttempt("src_1", "tok_a")
        assertNull(worker.activeAttempt("src_1"))
    }

    @Test
    fun `lock files are left behind on purpose and a fresh registry still starts clean`() {
        val first = registry()
        val firstOwner = first.ownerId
        first.close()
        open -= first

        val staleLockFiles = File(dir, "upload-owner").listFiles().orEmpty()
        assertTrue(staleLockFiles.any { it.name == "$firstOwner.lock" })

        val second = registry()
        assertTrue(second.holdsProcessLock())
        assertNull(second.activeAttempt("src_1"))
        val reacquired = second.tryAcquireSource("src_1")
        assertNotNull(reacquired)
        reacquired?.close()
    }
}
