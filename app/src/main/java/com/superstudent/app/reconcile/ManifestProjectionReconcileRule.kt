package com.superstudent.app.reconcile

import android.content.Context
import com.superstudent.core.repository.AccountRepository
import com.superstudent.core.repository.ManifestWriter
import com.superstudent.core.repository.PackageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repairs the manifest projection of installations that still have their Room database
 * (ZLQ-110 §2.5): demo4/demo5/demo6 rows that reached `UPLOADED` without ever appearing in
 * `package.json`, because publishing used to run before the row was marked and could not see itself.
 *
 * `index.json` is the comparison point. It is written after `package.json` in the same submission, so
 * it can only ever lag behind, never lead: one read therefore detects every divergence, including a
 * `package.json` that got ahead of a failed index write. The repair goes through the same
 * [ManifestWriter] a live upload uses, and identical content is a no-op that neither rewrites the
 * object nor bumps `revision` — which is what makes the second cold start silent.
 *
 * What this deliberately cannot repair belongs in the release notes instead of a workaround: an
 * installation that was uninstalled and reinstalled has no Room rows left, and a Drive object name
 * carries only a truncated hash, not the original `displayName` or full `sha256`. Fabricating a
 * SourceRef from it would put invented metadata into the restore contract.
 */
class ManifestProjectionReconcileRule(
    private val context: Context,
    private val accountRepository: AccountRepository,
    private val packageRepository: PackageRepository,
    private val manifestWriter: ManifestWriter,
) : StartupReconcileRule {

    override val name = "manifest-projection"

    override suspend fun reconcile(): Unit = withContext(Dispatchers.IO) {
        if (!hasValidatedNetwork(context)) return@withContext
        val identityId = accountRepository.currentIdentityId() ?: return@withContext
        val index = runCatching { manifestWriter.readIndex(identityId) }.getOrNull()
            ?: return@withContext
        packageRepository.listPackages(identityId).forEach { pkg ->
            // Asked of the writer, not recomputed here: the reconciliation must compare against
            // exactly what a publication would write, or the two filters drift and it either
            // republishes on every cold start or never repairs anything.
            val inRoom = manifestWriter.publishedSourceIds(pkg.packageId)
            val published = index.packages
                .firstOrNull { it.packageId == pkg.packageId }
                ?.sourceIds
                ?.toSet()
            if (published == inRoom) return@forEach
            // One package failing must not strand the rest, and none of it reaches the student: the
            // next cold start tries again, which is the deferral §2.5 asks for.
            runCatching { manifestWriter.publishState(identityId, pkg.packageId) }
        }
    }
}
