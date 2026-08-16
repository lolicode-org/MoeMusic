package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.AlreadyQueuedException
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.ServerSessionContext

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
}
