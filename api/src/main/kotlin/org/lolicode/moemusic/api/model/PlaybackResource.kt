package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.MusicSource

/**
 * Concrete client-playable resource produced by [MusicSource.resolve] immediately before playback.
 *
 * This is transport-focused state: [url] must be directly fetchable by the client, and [headers]
 * carries any per-request HTTP headers the upstream service requires. Contrast with
 * [TrackInfo.id], which is the stable source-local identifier passed to [MusicSource.resolve]
 * on the server and never sent to clients as a playable URL.
 */
public data class PlaybackResource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
