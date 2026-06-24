package org.lolicode.moemusic.clientcore.audio

import org.lolicode.moemusic.api.model.PlaybackResource

/**
 * Shared client-side decoder/loader contract.
 *
 * Implementations resolve a [PlaybackResource] into PCM bytes written into the supplied
 * [PcmRingBuffer].
 */
interface ClientTrackLoader {

    /**
     * Load [playback] and begin streaming PCM into [ringBuffer] from [seekMs].
     */
    fun load(
        playback: PlaybackResource,
        ringBuffer: PcmRingBuffer,
        seekMs: Long = 0L,
        onError: (ClientAudioFailure) -> Unit = {},
    )

    /**
     * Stop the current decode and release any loader-owned state.
     */
    fun stop(ringBuffer: PcmRingBuffer? = null)
}
