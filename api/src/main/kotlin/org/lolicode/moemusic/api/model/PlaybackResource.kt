package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.MusicSource

/**
 * Concrete client-playable resource produced by [MusicSource.resolve] immediately before playback.
 *
 * This is transport-focused state: [url] must be directly fetchable by the client, and [headers]
 * carries any per-request HTTP headers the upstream service requires. Contrast with
 * [TrackInfo.id], which is the stable source-local identifier passed to [MusicSource.resolve]
 * on the server and never sent to clients as a playable URL.
 *
 * Construct one with the [PlaybackResource] factory / builder DSL — do **not** implement this
 * interface; it is sealed with a single internal implementation:
 * ```
 * val res = PlaybackResource("https://cdn/audio.mp3") { headers = mapOf("Referer" to "…") }
 * ```
 */
public sealed interface PlaybackResource {
    public val url: String
    public val headers: Map<String, String> get() = emptyMap()

    /** Returns a builder seeded with this resource's values. */
    public fun toBuilder(): PlaybackResourceBuilder
}

/** Mutable builder for [PlaybackResource]. */
public class PlaybackResourceBuilder internal constructor(
    public var url: String,
) {
    public var headers: Map<String, String> = emptyMap()

    public fun build(): PlaybackResource = PlaybackResourceImpl(url, headers)
}

/**
 * Build a [PlaybackResource] from its [url] plus an optional [configure] block.
 *
 * The signature is frozen; future optional fields are set inside [configure] via
 * [PlaybackResourceBuilder].
 */
public fun PlaybackResource(
    url: String,
    configure: PlaybackResourceBuilder.() -> Unit = {},
): PlaybackResource = PlaybackResourceBuilder(url).apply(configure).build()

/** Returns a copy of this resource with [configure] applied to a seeded builder. */
public fun PlaybackResource.copy(configure: PlaybackResourceBuilder.() -> Unit): PlaybackResource =
    toBuilder().apply(configure).build()

internal data class PlaybackResourceImpl(
    override val url: String,
    override val headers: Map<String, String>,
) : PlaybackResource {
    override fun toBuilder(): PlaybackResourceBuilder = PlaybackResourceBuilder(url).also {
        it.headers = headers
    }
}
