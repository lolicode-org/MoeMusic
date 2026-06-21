package org.lolicode.moemusic.api.model

/**
 * Resolve-time metadata patch discovered alongside a fresh [PlaybackResource].
 *
 * This intentionally exposes only a limited subset of [TrackInfo] fields. Null means "leave the
 * existing track value unchanged"; this patch cannot clear an existing field to null.
 *
 * Identity and queue-policy owned fields such as `id`, `sourceId`, `title`, `artists`,
 * `durationMs`, `submittedByUserName`, `unavailableReason`, and `sourceFilterVerdict` are not
 * writable here.
 */
public sealed interface ResolvedTrackPatch {
    public val coverUrl: String? get() = null
    public val album: String? get() = null
    public val lyricLrc: String? get() = null
    public val secondaryLyricLrc: String? get() = null
    public val lyricsFetched: Boolean? get() = null
    public val integratedLufs: Double? get() = null

    /** Returns a builder seeded with this update's values. */
    public fun toBuilder(): ResolvedTrackPatchBuilder
}

/**
 * Mutable builder for [ResolvedTrackPatch].
 *
 * All fields are optional. Leaving a field as null means the source does not want to change that
 * part of the current track metadata.
 */
public class ResolvedTrackPatchBuilder internal constructor() {
    public var coverUrl: String? = null
    public var album: String? = null
    public var lyricLrc: String? = null
    public var secondaryLyricLrc: String? = null
    public var lyricsFetched: Boolean? = null
    public var integratedLufs: Double? = null

    public fun build(): ResolvedTrackPatch = ResolvedTrackPatchImpl(
        coverUrl = coverUrl,
        album = album,
        lyricLrc = lyricLrc,
        secondaryLyricLrc = secondaryLyricLrc,
        lyricsFetched = lyricsFetched,
        integratedLufs = integratedLufs,
    )
}

/**
 * Build a [ResolvedTrackPatch] from an optional [configure] block.
 *
 * This entry point has a frozen signature; future optional fields are set inside [configure] via
 * [ResolvedTrackPatchBuilder], so adding them never breaks callers.
 */
public fun ResolvedTrackPatch(
    configure: ResolvedTrackPatchBuilder.() -> Unit = {},
): ResolvedTrackPatch = ResolvedTrackPatchBuilder().apply(configure).build()

/** Returns a copy of this update with [configure] applied to a seeded builder. */
public fun ResolvedTrackPatch.copy(configure: ResolvedTrackPatchBuilder.() -> Unit): ResolvedTrackPatch =
    toBuilder().apply(configure).build()

internal data class ResolvedTrackPatchImpl(
    override val coverUrl: String?,
    override val album: String?,
    override val lyricLrc: String?,
    override val secondaryLyricLrc: String?,
    override val lyricsFetched: Boolean?,
    override val integratedLufs: Double?,
) : ResolvedTrackPatch {
    override fun toBuilder(): ResolvedTrackPatchBuilder = ResolvedTrackPatchBuilder().also {
        it.coverUrl = coverUrl
        it.album = album
        it.lyricLrc = lyricLrc
        it.secondaryLyricLrc = secondaryLyricLrc
        it.lyricsFetched = lyricsFetched
        it.integratedLufs = integratedLufs
    }
}

/**
 * Result of resolving a queued [TrackInfo] into a playable client resource.
 *
 * Sources may optionally attach a limited [trackPatch] patch when the same upstream request that
 * produced [playback] also reveals additional stable metadata (for example integrated LUFS or
 * synchronized lyrics).
 */
public sealed interface PlaybackResolution {
    public val playback: PlaybackResource
    public val trackPatch: ResolvedTrackPatch? get() = null

    /** Returns a builder seeded with this resolution's values. */
    public fun toBuilder(): PlaybackResolutionBuilder
}

/** Mutable builder for [PlaybackResolution]. */
public class PlaybackResolutionBuilder internal constructor(
    public var playback: PlaybackResource,
) {
    public var trackPatch: ResolvedTrackPatch? = null

    public fun build(): PlaybackResolution = PlaybackResolutionImpl(
        playback = playback,
        trackPatch = trackPatch,
    )
}

/**
 * Build a [PlaybackResolution] from its required [playback] plus an optional [configure] block.
 *
 * This entry point has a frozen signature; future optional fields are set inside [configure] via
 * [PlaybackResolutionBuilder], so adding them never breaks callers.
 */
public fun PlaybackResolution(
    playback: PlaybackResource,
    configure: PlaybackResolutionBuilder.() -> Unit = {},
): PlaybackResolution = PlaybackResolutionBuilder(playback).apply(configure).build()

/** Returns a copy of this resolution with [configure] applied to a seeded builder. */
public fun PlaybackResolution.copy(configure: PlaybackResolutionBuilder.() -> Unit): PlaybackResolution =
    toBuilder().apply(configure).build()

internal data class PlaybackResolutionImpl(
    override val playback: PlaybackResource,
    override val trackPatch: ResolvedTrackPatch?,
) : PlaybackResolution {
    override fun toBuilder(): PlaybackResolutionBuilder = PlaybackResolutionBuilder(playback).also {
        it.trackPatch = trackPatch
    }
}
