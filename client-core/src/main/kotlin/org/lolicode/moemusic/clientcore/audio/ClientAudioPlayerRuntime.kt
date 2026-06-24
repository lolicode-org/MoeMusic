package org.lolicode.moemusic.clientcore.audio

import org.lolicode.moemusic.api.model.PlaybackResource
import org.slf4j.LoggerFactory

/**
 * Shared client-side audio orchestration:
 * [ClientTrackLoader] -> [PcmRingBuffer] -> [StreamingAudioOutput].
 *
 * The actual output backend remains platform-owned; this runtime owns playback state,
 * reload-save/restore behavior, and loader/output coordination.
 */
class ClientAudioPlayerRuntime(
    private val ringBuffer: PcmRingBuffer,
    private val loader: ClientTrackLoader,
    private val output: StreamingAudioOutput,
) {

    private val logger = LoggerFactory.getLogger(ClientAudioPlayerRuntime::class.java)

    /** Concrete playback resource of the currently loaded track (null when stopped). */
    @Volatile private var currentPlayback: PlaybackResource? = null

    @Volatile private var reportedPositionMs: Long = 0L

    /** Incremented whenever playback is replaced or cleared, invalidating stale loader callbacks. */
    @Volatile private var playGeneration: Long = 0L

    /** Saved state across a sound-engine reload (destroy -> reload cycle). */
    @Volatile private var savedPlayback: PlaybackResource? = null
    @Volatile private var savedSeekMs: Long = 0L
    @Volatile private var savedErrorHandler: ((String) -> Unit)? = null

    @Volatile private var currentErrorHandler: ((String) -> Unit)? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var volume: Float = 1.0f
        private set

    @Volatile
    var normalizationGain: Float = 1.0f
        private set

    /**
     * Load [playback] and start playback from [seekMs].
     *
     * Stops any current playback first. If [playback] fails to load, [onError] is called.
     */
    fun play(
        playback: PlaybackResource,
        seekMs: Long = 0L,
        onError: (String) -> Unit = { logger.error("Audio error: {}", it) },
    ) {
        stop()
        val generation = ++playGeneration
        currentPlayback = playback
        currentErrorHandler = onError
        reportedPositionMs = seekMs.coerceAtLeast(0L)
        applyOutputGain("track load")
        loader.load(playback, ringBuffer, seekMs) { error ->
            if (playGeneration != generation) return@load
            logger.error("Track load failed: {}", error)
            clearFailedPlayback(generation)
            onError(error)
        }
        if (playGeneration != generation) return
        output.start(reportedPositionMs)
        isPlaying = true
        logger.debug("ClientAudioPlayer: playing {}", playback.url)
    }

    /** Pause playback (freezes audio output; decoder continues buffering). */
    fun pause() {
        if (!isPlaying) return
        output.stop()
        isPlaying = false
    }

    /** Resume from the current position. */
    fun resume() {
        if (isPlaying) return
        output.start(reportedPositionMs)
        isPlaying = true
    }

    /**
     * Seek to [positionMs] in the current track.
     * Flushes the ring buffer; the caller supplies the fresh seek point with a new [play] call.
     */
    fun seek(positionMs: Long) {
        output.seek()
        logger.debug("ClientAudioPlayer: seek to {}ms", positionMs)
    }

    /** Stop all playback and release resources. */
    fun stop() {
        playGeneration++
        output.stop()
        loader.stop(ringBuffer)
        currentPlayback = null
        currentErrorHandler = null
        reportedPositionMs = 0L
        isPlaying = false
    }

    /**
     * Save the current resource + computed seek position so playback can be restored after
     * the platform sound engine recreates its backend.
     */
    fun saveStateForReload() {
        val playback = currentPlayback ?: return
        savedPlayback = playback
        savedSeekMs = currentPositionMs()
        savedErrorHandler = currentErrorHandler
    }

    /**
     * Restore playback using the state saved by [saveStateForReload].
     */
    fun restoreAfterReload() {
        val playback = savedPlayback ?: return
        val seekMs = savedSeekMs
        val onError = savedErrorHandler ?: { error: String -> logger.error("Audio error: {}", error) }
        savedPlayback = null
        savedSeekMs = 0L
        savedErrorHandler = null
        logger.debug("ClientAudioPlayer: restoring playback after reload at {}ms", seekMs)
        play(playback, seekMs, onError)
    }

    /** Discard any saved-for-reload state. */
    fun clearSavedState() {
        savedPlayback = null
        savedSeekMs = 0L
        savedErrorHandler = null
    }

    fun currentPositionMs(): Long = reportedPositionMs.coerceAtLeast(0L)

    /** Update the last position reported by the platform output backend. */
    fun reportPlaybackPosition(positionMs: Long) {
        reportedPositionMs = positionMs.coerceAtLeast(0L)
    }

    /** Set client-local playback volume in the `0.0 .. 1.0` range. */
    fun setVolume(value: Float) {
        volume = value.coerceIn(0.0f, 1.0f)
        applyOutputGain("volume update")
    }

    /** Apply a non-negative normalization multiplier. Final output is still clamped to `0.0 .. 1.0`. */
    fun setNormalizationGain(value: Float) {
        normalizationGain = value.takeIf(Float::isFinite)?.coerceAtLeast(0.0f) ?: 1.0f
        applyOutputGain("normalization update")
    }

    private fun applyOutputGain(reason: String) {
        val unclampedGain = volume * normalizationGain
        val finalGain = unclampedGain.coerceIn(0.0f, 1.0f)
        output.setGain(finalGain)
        logger.debug(
            "ClientAudioPlayer: {} volume={} normalizationGain={} unclampedGain={} finalGain={} playback={}",
            reason,
            volume,
            normalizationGain,
            unclampedGain,
            finalGain,
            currentPlayback?.url ?: "<none>",
        )
    }

    private fun clearFailedPlayback(generation: Long) {
        if (playGeneration != generation) return
        playGeneration++
        output.stop()
        loader.stop(ringBuffer)
        currentPlayback = null
        currentErrorHandler = null
        reportedPositionMs = 0L
        isPlaying = false
    }
}
