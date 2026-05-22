package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.UserResult

/** Result of probing an HTTP(S) media URL for lightweight metadata. */
public data class MediaProbeResult(
    val durationMs: Long,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
)

/**
 * Shared server-side HTTP(S) metadata probe service.
 *
 * Implementations must apply MoeMusic's shared media firewall before performing network I/O.
 */
public interface IMediaProbeService {

    /**
     * Probe [url] for metadata such as duration, title, artist, and cover-art URL.
     *
     * [headers] are optional per-request HTTP headers needed by the upstream media URL.
     *
     * Returns [org.lolicode.moemusic.api.UserResult.Success] with `null` when probing failed or the source is unsupported.
     * Returns [org.lolicode.moemusic.api.UserResult.Error] when shared policy blocks the request before network I/O.
     */
    public suspend fun probeHttp(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): UserResult<MediaProbeResult?>
}
