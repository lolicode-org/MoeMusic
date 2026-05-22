package org.lolicode.moemusic.clientcore.audio

/**
 * Shared client-side audio sink contract.
 *
 * Platform implementations own the actual playback backend (OpenAL today) while the
 * orchestration remains in `:client-core`.
 */
interface StreamingAudioOutput {

    fun start(startPositionMs: Long = 0L)

    fun stop()

    fun seek()

    fun setGain(gain: Float)
}
