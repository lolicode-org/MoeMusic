package org.lolicode.moemusic.api.model

/**
 * Server-authoritative playback state.
 *
 * All position values are in milliseconds.
 * Timing logic must use monotonic clocks; these values are deltas, not wall-clock times.
 */
public sealed class PlaybackState {

    /** Audio is actively playing. [positionMs] is the current playback position. */
    public data class Playing(val positionMs: Long) : PlaybackState()

    /** Playback is paused at [positionMs]. */
    public data class Paused(val positionMs: Long) : PlaybackState()

    /** No track is loaded or playback has fully stopped. */
    public data object Stopped : PlaybackState()
}
