package org.lolicode.moemusic.core.media.probe

import org.lolicode.lavaplayer.player.AudioLoadResultHandler
import org.lolicode.lavaplayer.player.DefaultAudioPlayerManager
import org.lolicode.lavaplayer.source.http.HttpAudioReference
import org.lolicode.lavaplayer.source.http.HttpAudioSourceManager
import org.lolicode.lavaplayer.tools.FriendlyException
import org.lolicode.lavaplayer.track.AudioPlaylist
import org.lolicode.lavaplayer.track.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lolicode.moemusic.api.model.PlaybackResource
import org.slf4j.LoggerFactory
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Uses LavaPlayer's [DefaultAudioPlayerManager] to probe an audio URL for track metadata
 * (title, artist, duration) **without** downloading or playing the audio.
 *
 * Only HTTP/HTTPS remote sources are registered. Intended for server-side use only.
 *
 * LavaPlayer typically reads only the first few KB of the file to determine the format
 * and duration (e.g. Xing header for MP3 VBR, STREAMINFO for FLAC, OGG last page via
 * HTTP Range). Memory and bandwidth overhead is minimal.
 *
 * Returns [ProbeResult.Unknown] if the format is unsupported, the URL is unreachable,
 * or probing times out.
 */
object ServerTrackProber {

    private val logger = LoggerFactory.getLogger(ServerTrackProber::class.java)

    /** Timeout for a single probe operation. */
    private const val PROBE_TIMEOUT_MS = 10_000L

    /**
     * LavaPlayer manager configured for direct HTTP/HTTPS audio URLs only.
     * No audio frame output; we only use it for [DefaultAudioPlayerManager.loadItem].
     */
    private val manager = DefaultAudioPlayerManager().also {
        it.registerSourceManager(HttpAudioSourceManager())
    }

    /** Result of a metadata probe. */
    data class ProbeResult(
        /** Duration in milliseconds. 0 = unknown (e.g. live stream or probe failed). */
        val durationMs: Long,
        /** Track title from metadata, or empty string if unavailable. */
        val title: String,
        /** Track artist/author from metadata, or empty string if unavailable. */
        val artist: String,
        /**
         * Artwork URL from audio metadata when the source exposes an external cover URL.
         * Plain HTTP files usually return null because embedded cover art is binary data,
         * not a standalone URL.
         */
        val artworkUrl: String? = null,
    ) {
        companion object {
            val Unknown = ProbeResult(0L, "", "")
        }
    }

    /**
     * Probe [url] for metadata. Suspends on [Dispatchers.IO].
     *
     * @return [ProbeResult] with whatever metadata LavaPlayer could extract,
     *         or [ProbeResult.Unknown] on failure/timeout.
     */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): ProbeResult =
        probe(PlaybackResource(url = url, headers = headers))

    /**
     * Probe [playback] for metadata. Suspends on [Dispatchers.IO].
     *
     * [PlaybackResource.headers] are forwarded to LavaPlayer's HTTP source for sources that
     * require per-request authentication, referrer, or user-agent headers.
     *
     * @return [ProbeResult] with whatever metadata LavaPlayer could extract,
     *         or [ProbeResult.Unknown] on failure/timeout.
     */
    suspend fun probe(playback: PlaybackResource): ProbeResult = withContext(Dispatchers.IO) {
        val url = playback.url
        withTimeoutOrNull(PROBE_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine { cont ->
                val future = manager.loadItem(
                    HttpAudioReference(url, null, playback.headers),
                    object : AudioLoadResultHandler {
                        override fun trackLoaded(track: AudioTrack) {
                            val dur = if (track.isSeekable) track.duration else 0L
                            cont.resume(ProbeResult(
                                durationMs = dur,
                                title      = track.info.title.orEmpty(),
                                artist     = track.info.author.orEmpty(),
                                artworkUrl = track.info.artworkUrl?.takeIf { it.isNotBlank() },
                            ))
                        }

                        override fun playlistLoaded(playlist: AudioPlaylist) {
                            val first = playlist.tracks.firstOrNull()
                            val dur = if (first?.isSeekable == true) first.duration else 0L
                            cont.resume(ProbeResult(
                                durationMs = dur,
                                title      = first?.info?.title.orEmpty(),
                                artist     = first?.info?.author.orEmpty(),
                                artworkUrl = first?.info?.artworkUrl?.takeIf { it.isNotBlank() },
                            ))
                        }

                        override fun noMatches() {
                            logger.debug("ServerTrackProber: no matches for {}", url)
                            cont.resume(ProbeResult.Unknown)
                        }

                        override fun loadFailed(exception: FriendlyException) {
                            logger.warn("ServerTrackProber: probe failed for {}: {}", url, exception.message)
                            cont.resume(ProbeResult.Unknown)
                        }
                    },
                )
                cont.invokeOnCancellation { future.cancel(true) }
            }
        } ?: run {
            logger.warn("ServerTrackProber: probe timed out for {}", url)
            ProbeResult.Unknown
        }
    }
}
