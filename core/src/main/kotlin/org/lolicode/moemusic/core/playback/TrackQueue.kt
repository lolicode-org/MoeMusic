package org.lolicode.moemusic.core.playback

import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.copy
import org.lolicode.moemusic.api.model.matchesQueueIdentity
import java.util.UUID

/**
 * Dual-queue track source.
 *
 * The **user queue** holds tracks explicitly submitted by users and always takes priority.
 * When empty, [autoplaySupplier] is invoked to obtain the next autoplay track.
 *
 * Thread-safe for single-consumer use (server thread calls [nextTrack]; any thread may enqueue).
 *
 * @property autoplaySupplier Optional callback installed by [org.lolicode.moemusic.core.playback.autoplay.AutoplayManager].
 *   Called when the user queue is empty; must return the next autoplay track or null if none
 *   is currently available (e.g. while a refetch is in progress).
 */
class TrackQueue {

    data class NextTrack(
        val track: TrackInfo,
        val source: Source,
        val enqueuedBy: UUID? = null,
    ) {
        enum class Source {
            USER_QUEUE,
            AUTOPLAY,
        }
    }

    enum class UserQueueRemovalResult {
        REMOVED,
        NOT_FOUND,
        FORBIDDEN,
    }

    data class UserQueueRemovalDetails(
        val result: UserQueueRemovalResult,
        val removedTrack: TrackInfo? = null,
    )

    private data class QueuedTrack(
        val track: TrackInfo,
        val enqueuedBy: UUID?,
        val queueEntryId: String,
    )

    private val userQueue: ArrayDeque<QueuedTrack> = ArrayDeque()
    private val queueLock = Any()

    /**
     * Supplier for autoplay tracks.  Set by [org.lolicode.moemusic.core.playback.autoplay.AutoplayManager]
     * during server initialization.  Null means no autoplay is configured.
     */
    @Volatile
    var autoplaySupplier: (() -> TrackInfo?)? = null

    /** Add a track to the user-submitted queue (highest priority). */
    fun enqueueUser(track: TrackInfo, enqueuedBy: UUID? = null) {
        synchronized(queueLock) {
            val entryId = UUID.randomUUID().toString()
            val stamped = track.copy { queueEntryId = entryId }
            userQueue.addLast(QueuedTrack(stamped, enqueuedBy, entryId))
        }
    }

    /** Reinsert a previously dequeued user track at the front of the queue. */
    fun requeueUserFront(track: TrackInfo, enqueuedBy: UUID? = null) {
        synchronized(queueLock) {
            val entryId = track.queueEntryId ?: UUID.randomUUID().toString()
            val stamped = if (track.queueEntryId == null) track.copy { queueEntryId = entryId } else track
            userQueue.addFirst(QueuedTrack(stamped, enqueuedBy, entryId))
        }
    }

    /**
     * Add a track to the user-submitted queue only if the same logical track is not already
     * pending in the queue.
     *
     * @return `true` when the track was enqueued, `false` when a duplicate was already present.
     */
    fun enqueueUserIfAbsent(track: TrackInfo, enqueuedBy: UUID? = null): Boolean = synchronized(queueLock) {
        if (userQueue.any { it.track.matchesQueueIdentity(track) }) return@synchronized false
        val entryId = UUID.randomUUID().toString()
        val stamped = track.copy { queueEntryId = entryId }
        userQueue.addLast(QueuedTrack(stamped, enqueuedBy, entryId))
        true
    }

    /**
     * Returns the next track to play, or `null` if all queues are empty.
     *
     * Priority: user queue → [autoplaySupplier].
     */
    fun nextTrack(): NextTrack? {
        synchronized(queueLock) {
            val queued = userQueue.removeFirstOrNull()
            if (queued != null) {
                return NextTrack(
                    track = queued.track,
                    source = NextTrack.Source.USER_QUEUE,
                    enqueuedBy = queued.enqueuedBy,
                )
            }
        }
        val autoplayTrack = autoplaySupplier?.invoke() ?: return null
        return NextTrack(
            track = autoplayTrack,
            source = NextTrack.Source.AUTOPLAY,
        )
    }

    /** True if the user queue has at least one pending track. */
    fun hasUserTrack(): Boolean = synchronized(queueLock) { userQueue.isNotEmpty() }

    /** Total number of tracks in the user queue. */
    fun userQueueSize(): Int = synchronized(queueLock) { userQueue.size }

    /** Snapshot of the user queue for display purposes (ordered, not live). */
    fun userQueueSnapshot(): List<TrackInfo> = synchronized(queueLock) { userQueue.map { it.track } }

    data class QueueSliceResult(
        val tracks: List<TrackInfo>,
        val totalSize: Int
    )

    fun userQueueSliceWithTotal(offset: Int, limit: Int): QueueSliceResult = synchronized(queueLock) {
        val total = userQueue.size
        val normalizedOffset = offset.coerceIn(0, total)
        val effectiveLimit = limit.coerceAtLeast(0)
        val toIndex = (normalizedOffset + effectiveLimit).coerceAtMost(total)
        if (normalizedOffset >= total || effectiveLimit <= 0) return QueueSliceResult(emptyList(), total)
        val result = ArrayList<TrackInfo>(toIndex - normalizedOffset)
        var currentIndex = 0
        for (queued in userQueue) {
            if (currentIndex in normalizedOffset until toIndex) {
                result.add(queued.track)
            }
            currentIndex++
            if (currentIndex >= toIndex) break
        }
        return QueueSliceResult(result, total)
    }

    /** Returns a paginated slice of user-queue tracks. */
    fun userQueueSlice(offset: Int, limit: Int): List<TrackInfo> = synchronized(queueLock) {
        val total = userQueue.size
        val normalizedOffset = offset.coerceIn(0, total)
        val effectiveLimit = limit.coerceAtLeast(0)
        val toIndex = (normalizedOffset + effectiveLimit).coerceAtMost(total)
        if (normalizedOffset >= total || effectiveLimit <= 0) return emptyList()
        val result = ArrayList<TrackInfo>(toIndex - normalizedOffset)
        var currentIndex = 0
        for (queued in userQueue) {
            if (currentIndex in normalizedOffset until toIndex) {
                result.add(queued.track)
            }
            currentIndex++
            if (currentIndex >= toIndex) break
        }
        result
    }

    /** True when the same logical track is already pending in the user queue. */
    fun containsUserTrack(track: TrackInfo): Boolean = synchronized(queueLock) {
        userQueue.any { it.track.matchesQueueIdentity(track) }
    }

    /** Remove the first pending user-queue entry matching [track]. */
    fun removeMatchingUserTrack(track: TrackInfo): Boolean = synchronized(queueLock) {
        val snapshot = userQueue.toList()
        val removeIndex = snapshot.indexOfFirst { it.track.matchesQueueIdentity(track) }
        if (removeIndex < 0) return@synchronized false
        userQueue.clear()
        snapshot.forEachIndexed { index, queuedTrack ->
            if (index != removeIndex) userQueue.addLast(queuedTrack)
        }
        true
    }

    /** Remove the first pending user-queue entry matching [sourceId] and [trackId]. */
    fun removeUserTrack(sourceId: String, trackId: String): Boolean =
        removeUserTrackDetailed(sourceId, trackId, queueEntryId = null, requesterId = null, bypassOwnership = true).result == UserQueueRemovalResult.REMOVED

    /**
     * Remove the first pending user-queue entry matching [sourceId] and [trackId] only if
     * [requesterId] queued it or [bypassOwnership] is true.
     *
     * Used by packet-driven and command-driven queue deletion so the original enqueuer can
     * always remove their own track even without the general queue-removal permission.
     */
    fun removeUserTrack(
        sourceId: String,
        trackId: String,
        requesterId: UUID?,
        bypassOwnership: Boolean,
    ): UserQueueRemovalResult =
        removeUserTrackDetailed(sourceId, trackId, queueEntryId = null, requesterId = requesterId, bypassOwnership = bypassOwnership).result

    fun removeUserTrack(
        sourceId: String,
        trackId: String,
        queueEntryId: String?,
        requesterId: UUID?,
        bypassOwnership: Boolean,
    ): UserQueueRemovalResult =
        removeUserTrackDetailed(sourceId, trackId, queueEntryId = queueEntryId, requesterId = requesterId, bypassOwnership = bypassOwnership).result

    fun removeUserTrackDetailed(
        sourceId: String,
        trackId: String,
        queueEntryId: String? = null,
        requesterId: UUID? = null,
        bypassOwnership: Boolean = false,
    ): UserQueueRemovalDetails {
        synchronized(queueLock) {
            val snapshot = userQueue.toList()
            val removeIndex = if (!queueEntryId.isNullOrBlank()) {
                snapshot.indexOfFirst { it.queueEntryId == queueEntryId }
            } else if (!bypassOwnership && requesterId != null) {
                val ownIndex = snapshot.indexOfFirst {
                    it.track.sourceId == sourceId && it.track.id == trackId && it.enqueuedBy == requesterId
                }
                if (ownIndex >= 0) ownIndex else snapshot.indexOfFirst {
                    it.track.sourceId == sourceId && it.track.id == trackId
                }
            } else {
                snapshot.indexOfFirst {
                    it.track.sourceId == sourceId && it.track.id == trackId
                }
            }
            if (removeIndex < 0) return UserQueueRemovalDetails(UserQueueRemovalResult.NOT_FOUND)
            val target = snapshot[removeIndex]
            if (!bypassOwnership && (requesterId == null || target.enqueuedBy != requesterId)) {
                return UserQueueRemovalDetails(UserQueueRemovalResult.FORBIDDEN)
            }
            // Rebuild queue without the matched element.
            userQueue.clear()
            snapshot.forEachIndexed { i, queuedTrack ->
                if (i != removeIndex) userQueue.addLast(queuedTrack)
            }
            return UserQueueRemovalDetails(
                result = UserQueueRemovalResult.REMOVED,
                removedTrack = target.track,
            )
        }
    }
}
