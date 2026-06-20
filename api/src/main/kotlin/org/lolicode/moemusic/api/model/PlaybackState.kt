package org.lolicode.moemusic.api.model

/**
 * Server-authoritative playback state.
 *
 * All position values are in milliseconds.
 * Timing logic must use monotonic clocks; these values are deltas, not wall-clock times.
 */
public abstract class PlaybackState internal constructor() {

    /**
     * Audio is actively playing. [positionMs] is the current playback position.
     * Read-only host-produced subtype. Do not construct, destructure, or copy.
     * Appending fields is binary-safe for read-only consumers.
     */
    public data class Playing(val positionMs: Long) : PlaybackState()

    /**
     * Playback is paused at [positionMs].
     * Read-only host-produced subtype. Do not construct, destructure, or copy.
     * Appending fields is binary-safe for read-only consumers.
     */
    public data class Paused(val positionMs: Long) : PlaybackState()

    /** No track is loaded or playback has fully stopped. */
    public data object Stopped : PlaybackState()
}
