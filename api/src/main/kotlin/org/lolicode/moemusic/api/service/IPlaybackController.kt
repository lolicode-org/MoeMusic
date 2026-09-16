package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.AlreadyQueuedException
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.UserTrackMetrics
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.ServerSessionContext
import java.util.UUID

/**
 * High-level playback controller interface exposed to plugins.
 *
 * Implementations live in `:core` and are wired into each plugin's [ServerRuntimeContext] during
 * [Plugin.onServerRuntimeLoad] and [ServerSessionContext] during [Plugin.onServerSessionLoad].
 * All methods are designed to be called from the server thread (or synchronized externally).
 *
 * This is a raw plugin -> core boundary. Shared permission and rate-limit checks are intentionally
 * not applied here. Plugins that want MoeMusic's checked user-behalf path should prefer
 * [org.lolicode.moemusic.api.service.IUserActionService].
 */
public interface IPlaybackController {

    /** Current server-side playback snapshot. Null when nothing is loaded. */
    public val currentContext: TrackContext?

    /**
     * Ordered snapshot of the current user-submitted queue.
     *
     * This excludes the currently loaded track and excludes autoplay tracks. The returned
     * list is detached from the live queue and safe to iterate without external synchronization.
     */
    public fun userQueueSnapshot(): List<TrackInfo>

    /**
     * Start playing [track] immediately.
     * [playback] is the concrete client-playable resource that will be sent to clients in
     * playback packets and persisted in [currentContext]. It must already be resolved; opaque
     * source IDs like `ncm:1330849751` are not valid here.
     */
    public fun play(track: TrackInfo, playback: PlaybackResource)

    /** Pause current playback. No-op if already paused or nothing is playing. */
    public fun pause()

    /** Resume paused playback. No-op if already playing or nothing is loaded. */
    public fun resume()

    /**
     * Seek to [positionMs] milliseconds. Works whether currently playing or paused.
     * Broadcasts the state-update packet to all clients with the new reference point.
     */
    public fun seek(positionMs: Long)

    /**
     * Skip the current track and start the next one from the queue.
     * Calls [stop] if the queue is empty.
     */
    public fun skip()

    /**
     * Whether a skip action or track advance is currently in flight resolving the next track.
     *
     * Callers can check this to discard or coalesce redundant skip requests.
     */
    public val isSkipInFlight: Boolean get() = false

    /** Stop playback and clear the current context. Broadcasts a STOPPED state update. */
    public fun stop()

    /**
     * Enqueue [track] in the user queue and immediately start playback if nothing is
     * currently playing.
     *
     * This is the primary entry-point for both command-driven and packet-driven track requests:
     * it combines enqueue + conditional play in one thread-safe call.
     *
     * Throws [AlreadyQueuedException] when the same logical track is already pending in the
     * user queue or is the current playing track.
     */
    public fun enqueueAndPlay(track: TrackInfo)

    /**
     * Remove a queued user-submitted track by its stable `(sourceId, trackId)` identity.
     *
     * This is the raw queue-removal path. [bypassOwnership] is not permission-checked here.
     */
    public fun removeQueuedTrack(
        sourceId: String,
        trackId: String,
        requester: MoeMusicUser? = null,
        bypassOwnership: Boolean = false,
    ): QueueRemoveResult

    /**
     * Remove a queued user-submitted track by [queueEntryId], falling back to `(sourceId, trackId)`
     * when the ID is null or blank.
     *
     * This is the raw queue-removal path. [bypassOwnership] is not permission-checked here.
     */
    public fun removeQueuedTrackByEntryId(
        sourceId: String,
        trackId: String,
        queueEntryId: String?,
        requester: MoeMusicUser? = null,
        bypassOwnership: Boolean = false,
    ): QueueRemoveResult = removeQueuedTrack(sourceId, trackId, requester, bypassOwnership)

    /**
     * Clear all tracks or tracks from a specific user from the user queue.
     *
     * This is the raw queue clearing path. [bypassOwnership] is not permission-checked here.
     *
     * @param targetUserId Optional user UUID filter. If null and [targetUserName] is null, clears all tracks.
     * @param targetUserName Optional user display name filter for offline players.
     * @param requester Optional user performing the clear.
     * @param bypassOwnership If true, ignores ownership and clears all matched tracks regardless of [requester].
     * @return Outcome detailing the number of tracks removed.
     */
    public fun clearQueue(
        targetUserId: UUID? = null,
        targetUserName: String? = null,
        requester: MoeMusicUser? = null,
        bypassOwnership: Boolean = false,
    ): QueueClearOutcome = QueueClearOutcome(0)

    /**
     * Compute the count and total duration in milliseconds of active tracks submitted by the user
     * in the queue (pending tracks), plus the currently playing track if it was submitted by this user.
     */
    public fun currentUserTrackMetrics(userId: UUID?, userName: String?): UserTrackMetrics =
        UserTrackMetrics(0, 0L)

    /**
     * Compute active track metrics for [user].
     */
    public fun currentUserTrackMetrics(user: MoeMusicUser?): UserTrackMetrics =
        if (user == null) UserTrackMetrics(0, 0L) else currentUserTrackMetrics(user.id, user.displayName)

    /**
     * Returns true if the currently playing track is active and was submitted by the specified user.
     * Autoplay tracks always return false.
     */
    public fun isCurrentTrackFromUser(userId: UUID?, userName: String?): Boolean = false

    /**
     * Returns true if the currently playing track is active and was submitted by [user].
     */
    public fun isCurrentTrackFromUser(user: MoeMusicUser?): Boolean =
        if (user == null) false else isCurrentTrackFromUser(user.id, user.displayName)
}
