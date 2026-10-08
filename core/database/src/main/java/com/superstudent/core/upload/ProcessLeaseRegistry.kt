package com.superstudent.core.upload

import com.superstudent.core.model.Ids
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** One held OS lock. Closing releases it; the lock file itself is left behind on purpose. */
class ProcessLeaseHandle internal constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}

/**
 * OS-level liveness behind the upload lease (design §3.1–§3.2).
 *
 * A lease deadline in Room says *when* an attempt promised to renew; it cannot say whether the
 * process that made the promise still exists. A PID cannot answer that either — PIDs are recycled,
 * and a recycled PID reads as alive forever. An exclusive `FileLock` can: the kernel drops it when
 * the holding process dies for any reason, including a crash and a force-stop, so `tryLock` on
 * another owner's file succeeding *is* the proof that owner is gone.
 *
 * Two independent lock families live here:
 *
 * - `upload-owner/<ownerId>.lock`, held for the whole process lifetime. This is what a recovery pass
 *   probes before it may release somebody else's attempt.
 * - `source-lock/<sourceId>.lock`, held only around one source's PUT-and-commit, or around the
 *   `interruptAttempt` that releases it. It is what makes "never two concurrent PUTs of one object"
 *   a property of the runtime rather than of call ordering.
 *
 * The UI never calls into this class. Probing liveness is the recovery coordinator's job alone
 * (R6); a composable that tried a lock would turn a scroll into a filesystem syscall and would race
 * the coordinator for a decision only one of them is allowed to make.
 */
class ProcessLeaseRegistry(dir: File) : Closeable {

    /** This process's lease owner id. Written to `lease_owner_id`, never derived from a PID. */
    val ownerId: String = Ids.uuidV7()

    private val ownerDir = File(dir, "upload-owner")
    private val sourceDir = File(dir, "source-lock")

    /**
     * Held for the process lifetime and deliberately never closed on a normal exit: releasing it
     * would let a recovery pass in another process treat this live process as dead.
     */
    private val ownerHandle: ProcessLeaseHandle? = runCatching {
        ownerDir.mkdirs()
        sourceDir.mkdirs()
        tryLock(File(ownerDir, "$ownerId.lock"))
    }.getOrNull()

    private val attempts = ConcurrentHashMap<String, String>()

    /** False only if the lock directory is unwritable; the caller must then refuse to upload. */
    fun holdsProcessLock(): Boolean = ownerHandle != null

    /**
     * Design §3.2 condition 2: may this process release [otherOwnerId]'s attempt?
     *
     * Returns non-null only when that owner's kernel lock is free, i.e. the process is gone. The
     * caller must hold the returned handle until its `interruptAttempt` UPDATE has committed — the
     * design requires the lock to be held *across* the release, so that a process resurrecting
     * mid-statement cannot re-acquire and then find its row already handed over.
     *
     * Asking about our own id returns null: we hold that lock, so within one JVM `tryLock` would
     * throw [OverlappingFileLockException], and "the owner is us" is answerable from [activeAttempt]
     * without a syscall (condition 3).
     */
    fun tryAcquireOwner(otherOwnerId: String?): ProcessLeaseHandle? {
        if (otherOwnerId.isNullOrBlank() || otherOwnerId == ownerId) return null
        if (!isSafeId(otherOwnerId)) return null
        return tryLock(File(ownerDir, "$otherOwnerId.lock"))
    }

    /** The per-source execution lock. Null means somebody is working this source right now. */
    fun tryAcquireSource(sourceId: String): ProcessLeaseHandle? {
        if (!isSafeId(sourceId)) return null
        return tryLock(File(sourceDir, "$sourceId.lock"))
    }

    /**
     * Records that *this* process is executing [token] on [sourceId], which is what design §3.2
     * condition 3 reads: an `UPLOADING` row owned by us with nothing registered against it is an
     * orphan of a cancelled coroutine, not a live attempt.
     */
    fun registerAttempt(sourceId: String, token: String) {
        attempts[sourceId] = token
    }

    fun unregisterAttempt(sourceId: String, token: String) {
        attempts.remove(sourceId, token)
    }

    fun activeAttempt(sourceId: String): String? = attempts[sourceId]

    override fun close() {
        ownerHandle?.close()
    }

    /**
     * A lock file name is built from an id that may have arrived from a cloud manifest, so it is
     * checked before it is used as a path segment. Only the id alphabet `Ids` emits is accepted;
     * anything else could otherwise write a lock file outside the registry's directory.
     */
    private fun isSafeId(id: String): Boolean =
        id.length in 1..128 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun tryLock(file: File): ProcessLeaseHandle? = runCatching {
        val channel = FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            // Same JVM already holds it, which for an owner probe means "alive".
            null
        }
        if (lock == null) {
            runCatching { channel.close() }
            null
        } else {
            ProcessLeaseHandle(channel, lock)
        }
    }.getOrNull()
}
