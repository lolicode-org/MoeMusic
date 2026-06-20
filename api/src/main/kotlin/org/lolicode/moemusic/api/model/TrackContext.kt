package org.lolicode.moemusic.api.model

/**
 * Server-side snapshot of what is currently loaded and playing.
 *
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 *
 * @property track                Metadata of the track that is loaded (or was last loaded).
 *                                 [TrackInfo.id] is the source-local stable identifier;
 *                                 the concrete, client-fetchable resource lives in [playback].
 * @property playback             Concrete client-playable resource, including any per-request
 *                                 HTTP headers required for playback.
 * @property state                Current [PlaybackState].
 * @property serverResumeMonotonic Monotonic nanosecond timestamp (from the server's clock) at which
 *                                 the current [PlaybackState.Playing] segment began. Used by clients to calculate
 *                                 the correct seek offset via the time-sync round-trip.
 *                                 Meaningless when [state] is [PlaybackState.Paused] or [PlaybackState.Stopped].
 * @property serverStartMonotonic  Monotonic nanosecond timestamp at which the server intends (or intended)
 *                                 the track to start playing from position 0 (plus any initial seek offset).
 *                                 Clients subtract their server-offset from this to compute the local
 *                                 wall-clock start time, then seek accordingly.
 */
public data class TrackContext(
    val track: TrackInfo,
    val playback: PlaybackResource,
    val state: PlaybackState,
    val serverResumeMonotonic: Long = 0L,
    val serverStartMonotonic: Long = 0L,
)
