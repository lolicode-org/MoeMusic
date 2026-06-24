package org.lolicode.moemusic.clientcore.audio

import org.lolicode.lavaplayer.format.StandardAudioDataFormats
import org.lolicode.lavaplayer.player.AudioLoadResultHandler
import org.lolicode.lavaplayer.player.DefaultAudioPlayerManager
import org.lolicode.lavaplayer.player.AudioPlayer
import org.lolicode.lavaplayer.player.event.AudioEventAdapter
import org.lolicode.lavaplayer.source.http.HttpAudioReference
import org.lolicode.lavaplayer.source.http.HttpAudioSourceManager
import org.lolicode.lavaplayer.source.local.LocalAudioSourceManager
import org.lolicode.lavaplayer.tools.FriendlyException
import org.lolicode.lavaplayer.track.AudioPlaylist
import org.lolicode.lavaplayer.track.AudioTrack
import org.lolicode.lavaplayer.track.playback.MutableAudioFrame
import org.lolicode.moemusic.api.model.PlaybackResource
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * Loads and decodes audio tracks using LavaPlayer, writing PCM data to a [PcmRingBuffer].
 *
 * - Format: **stereo, 16-bit signed LE, 48 000 Hz** (LavaPlayer's `STEREO_48000`).
 * - The decode loop runs on a daemon thread; it stops when the track ends or [stop] is called.
 */
class LavaPlayerTrackLoader : ClientTrackLoader {

    private val logger = LoggerFactory.getLogger(LavaPlayerTrackLoader::class.java)

    private val playerManager = DefaultAudioPlayerManager().also { mgr ->
        mgr.registerSourceManager(HttpAudioSourceManager())
        mgr.registerSourceManager(LocalAudioSourceManager())
        // Use LE PCM so our OpenAL output (which treats bytes as LE 16-bit) works correctly
        mgr.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
    }

    private val player = playerManager.createPlayer()

    @Volatile private var decodeThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentErrorHandler: ((ClientAudioFailure) -> Unit)? = null
    /** Incremented on every stop()/load() to invalidate stale async loadItem callbacks. */
    @Volatile private var loadGeneration = 0

    init {
        player.addListener(object : AudioEventAdapter() {
            override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
                if (track !== currentTrack) return
                currentErrorHandler?.invoke(
                    ClientAudioFailure.fromFriendlyException("Playback failed: ", exception)
                )
            }

            override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
                if (track !== currentTrack) return
                currentErrorHandler?.invoke(ClientAudioFailure.trackStuck(thresholdMs))
            }
        })
    }

    /**
     * Load [playback] and begin streaming PCM into [ringBuffer] from [seekMs].
     * Invokes [onError] on the calling thread if loading fails.
     */
    override fun load(playback: PlaybackResource, ringBuffer: PcmRingBuffer, seekMs: Long, onError: (ClientAudioFailure) -> Unit) {
        stop(ringBuffer) // cancel any previous load
        val generation = ++loadGeneration
        currentErrorHandler = onError

        playerManager.loadItem(
            HttpAudioReference(playback.url, null, playback.headers),
            object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack) {
                    if (loadGeneration != generation) return  // superseded by a later load() or stop()
                    if (seekMs > 0) track.position = seekMs
                    currentTrack = track
                    player.playTrack(track)
                    startDecodeLoop(ringBuffer)
                }

                override fun playlistLoaded(playlist: AudioPlaylist) {
                    val track = playlist.selectedTrack ?: playlist.tracks.firstOrNull()
                    if (track != null) trackLoaded(track) else noMatches()
                }

                override fun noMatches() {
                    if (loadGeneration != generation) return
                    currentErrorHandler = null
                    onError(ClientAudioFailure.noMatches(playback.url))
                }

                override fun loadFailed(exception: FriendlyException) {
                    if (loadGeneration != generation) return
                    currentErrorHandler = null
                    onError(ClientAudioFailure.fromFriendlyException("Failed to load track: ", exception))
                }
            },
        )
    }

    /** Stop the current decode and clear the player. Blocks until the decode thread exits. */
    override fun stop(ringBuffer: PcmRingBuffer?) {
        loadGeneration++    // invalidate any pending loadItem callbacks
        stopRequested = true
        // Close the buffer FIRST so any blocked write() call wakes up and sees closed==true.
        // reset() is called after join() to re-open it for the next load.
        ringBuffer?.close()
        decodeThread?.interrupt()
        player.stopTrack()
        currentTrack = null
        currentErrorHandler = null
        decodeThread?.join(2_000)  // wait for the thread to actually finish
        decodeThread = null
        stopRequested = false
        // Re-open the buffer for the next track
        ringBuffer?.reset()
    }

    private fun startDecodeLoop(ringBuffer: PcmRingBuffer) {
        stopRequested = false
        val frame = MutableAudioFrame()
        val frameBuffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())
        frame.setBuffer(frameBuffer)

        decodeThread = Thread({
            ringBuffer.reset()
            while (!stopRequested && !Thread.currentThread().isInterrupted) {
                val provided = player.provide(frame)
                if (provided) {
                    frameBuffer.flip()
                    val data = ByteArray(frameBuffer.remaining())
                    frameBuffer.get(data)
                    frameBuffer.clear()
                    var offset = 0
                    while (offset < data.size && !stopRequested) {
                        val written = ringBuffer.write(data.copyOfRange(offset, data.size))
                        if (written == 0 && ringBuffer.closed) break
                        offset += written
                    }
                } else {
                    // No frame yet (buffering); yield briefly to avoid busy-wait.
                    // InterruptedException here is expected when stop() calls interrupt().
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                }
            }
            logger.debug("LavaPlayer decode loop ended")
        }, "MoeMusic-Decoder").also { it.isDaemon = true; it.start() }
    }
}
