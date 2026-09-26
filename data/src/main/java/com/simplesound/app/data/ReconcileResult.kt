package com.simplesound.app.data

import com.simplesound.app.data.db.TrackEntity

/**
 * The outcome of merging a fresh MediaStore scan into the persisted `tracks` table.
 *
 * @property tracks the full new table contents: every scanned track (present) plus
 *   every previously known track that is missing but still inside its grace period.
 * @property idRemap old id -> new id for tracks MediaStore re-indexed under a new id
 *   (matched by [TrackEntity.path]). Favorites, playlist membership, play stats and
 *   custom orders referencing the old id must be moved to the new one.
 * @property droppedIds ids whose references must be removed: missing past the grace
 *   period, or whose numeric id MediaStore has since handed to a *different* file
 *   (keeping them would silently attach the old song's favorite/playlist slot to an
 *   unrelated one).
 */
internal data class ReconcileResult(
    val tracks: List<TrackEntity>,
    val idRemap: Map<Long, Long>,
    val droppedIds: Set<Long>,
) {
    val missingIds: Set<Long> get() = tracks.filter { it.missingSinceSec != 0L }.mapTo(HashSet()) { it.id }

    val changesReferences: Boolean get() = idRemap.isNotEmpty() || droppedIds.isNotEmpty()

    /** Where a reference to [id] should now point, or null if it must be dropped. */
    fun resolve(id: Long): Long? = idRemap[id] ?: id.takeUnless { it in droppedIds }
}

/** How long a vanished track keeps its favorites/playlists/stats before they are purged. */
internal const val MISSING_TRACK_RETENTION_SEC: Long = 30L * 24 * 60 * 60

/**
 * Pure merge of [scanned] (a complete MediaStore snapshot) into [existing] (every row
 * currently in the `tracks` table). Kept free of Room/Android so the identity rules
 * can be unit-tested directly:
 *
 * - Same id, and same path (or either path unknown — rows written before v2 have
 *   none): the same file. Nothing to do.
 * - The old path now shows up under a different id: MediaStore re-indexed the file
 *   (OS update, media DB rebuild). Remap references old -> new.
 * - Otherwise, if the old id now belongs to a different file: drop the old
 *   references (see [ReconcileResult.droppedIds]).
 * - Otherwise the file is simply gone *for now* (SD card out, scanner mid-rebuild):
 *   keep the row, stamped with when it went missing, and only drop it once it has
 *   been missing for longer than [retentionSec].
 */
internal fun reconcileLibrary(
    existing: List<TrackEntity>,
    scanned: List<TrackEntity>,
    nowSec: Long,
    retentionSec: Long = MISSING_TRACK_RETENTION_SEC,
): ReconcileResult {
    val scannedById = scanned.associateBy { it.id }
    val scannedByPath = HashMap<String, TrackEntity>()
    for (s in scanned) if (s.path.isNotBlank()) scannedByPath.putIfAbsent(s.path, s)

    val remap = HashMap<Long, Long>()
    val dropped = HashSet<Long>()
    val keptMissing = ArrayList<TrackEntity>()

    for (e in existing) {
        val sameId = scannedById[e.id]
        if (sameId != null && isSameFile(e, sameId)) continue

        val moved = e.path.takeIf { it.isNotBlank() }?.let { scannedByPath[it] }
        when {
            moved != null -> remap[e.id] = moved.id
            sameId != null -> dropped += e.id
            else -> {
                val since = if (e.missingSinceSec != 0L) e.missingSinceSec else nowSec
                if (nowSec - since > retentionSec) {
                    dropped += e.id
                } else {
                    keptMissing += e.copy(missingSinceSec = since)
                }
            }
        }
    }

    return ReconcileResult(tracks = scanned + keptMissing, idRemap = remap, droppedIds = dropped)
}

/** Same path, or either path unknown (rows written before schema v2 have none). */
private fun isSameFile(
    a: TrackEntity,
    b: TrackEntity,
): Boolean = a.path.isBlank() || b.path.isBlank() || a.path == b.path

/**
 * Rewrites an ordered id list through [ReconcileResult.resolve], dropping removed ids
 * and any duplicates a remap can create (keeps the first occurrence).
 */
internal fun ReconcileResult.remapIds(ids: List<Long>): List<Long> {
    val seen = HashSet<Long>()
    return ids.mapNotNull { resolve(it) }.filter { seen.add(it) }
}
