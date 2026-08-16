package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.isNullOrBlank
import org.lolicode.moemusic.api.service.FilterVerdict

/**
 * Immutable description of a single audio track.
 *
 * Construct one with the [TrackInfo] factory / builder DSL — do **not** implement this interface
 * (it is sealed; the only implementation is internal):
 * ```
 * val track = TrackInfo("dQw4w9WgXcQ", "Never Gonna Give You Up", artists, 213_000) {
 *     coverUrl = "https://…"
 *     sourceId = "youtube"
 * }
 * val refreshed = track.copy { lyricsFetched = true }
 * ```
 *
 * To evolve this model without breaking binary compatibility, add a new optional field as a default
 * getter here **and** a matching `var` in [TrackInfoBuilder]; both are additive. Never add a field
 * to a fixed constructor.
 *
 * @property id         Source-local unique identifier (e.g. `"dQw4w9WgXcQ"` or
 *                      `"episode:12345"`). This is an opaque key owned by the source, not
 *                      necessarily a bare upstream platform ID. Used by [MusicSource.resolve]
 *                      to obtain the playable stream, and as the stable key for deduplication
 *                      and moderation identity.
 * @property title      Human-readable track title.
 * @property artists    Artist / uploader identities. Populate [ArtistInfo.id] when the source
 *                      exposes a stable artist id. If it does not, use [ArtistInfo.name] as the
 *                      fallback id so exact artist rules still have a best-effort identity target.
 * @property durationMs Total track duration in milliseconds. -1 if unknown (e.g. live streams).
 * @property coverUrl   Optional URL of the cover art image.
 * @property sourceId   ID of the [MusicSource] that produced this track. Null only for tracks that
 *                      are not yet associated with any source (e.g. transient results before the
 *                      source id is stamped). All tracks entering the queue must have a non-null sourceId.
 * @property album      Album / playlist / collection name when the source exposes one. Null if absent.
 * @property submittedByUserName Display name of the user who submitted this track, or null for autoplay/server-owned tracks.
 * @property unavailableReason Localized reason why this track cannot currently be queued.
 *                              Use this for **inherent, un-bypassable** source-level unavailability
 *                              (e.g. regional blocks, VIP-only content, missing stream URL).
 *                              Do **not** use it for content-filter hits — use [sourceFilterVerdict]
 *                              instead so the gate can still apply bypass for privileged users.
 * @property sourceFilterVerdict Optional filter verdict from the source itself. Set this when the
 *                              source has done its own content check (e.g. on description, tags,
 *                              or lyrics) and determined the track should be filtered. Unlike
 *                              [unavailableReason], this verdict is enforced through the same
 *                              bypassable gate as server-side rules — privileged users can still
 *                              enqueue the track. This field is never forwarded to the client.
 * @property lyricLrc Primary line-timed lyrics in LRC form when available for the active track.
 * @property secondaryLyricLrc Secondary line-timed lyrics aligned with [lyricLrc] when available.
 * @property lyricsFetched True when the source has already been asked for lyrics for this track,
 * regardless of whether any lyric data was actually available.
 * @property loudness Optional source-supplied loudness metadata. Set this when the source can
 *                    provide a stable LUFS measurement and optionally a peak reading for the track
 *                    so clients may apply loudness normalization.
 */
public sealed interface TrackInfo {
    public val id: String
    public val title: String
    public val artists: List<ArtistInfo>
    public val durationMs: Long
    public val coverUrl: String? get() = null
    public val sourceId: String? get() = null
    public val album: String? get() = null
    public val submittedByUserName: String? get() = null
    public val unavailableReason: LocalizedText? get() = null
    public val sourceFilterVerdict: FilterVerdict? get() = null
    public val lyricLrc: String? get() = null
    public val secondaryLyricLrc: String? get() = null
    public val lyricsFetched: Boolean get() = false
    public val loudness: LoudnessInfo? get() = null
    public val queueEntryId: String? get() = null
    // Add new optional fields here as `public val foo: T get() = default` and mirror them in
    // [TrackInfoBuilder]. Additive — keeps binary compatibility.

    /** Returns a builder seeded with this track's values. Replaces the former data-class `copy()`. */
    public fun toBuilder(): TrackInfoBuilder
}

/**
 * Mutable builder for [TrackInfo]. Required identity fields are constructor arguments (a frozen
 * signature); every optional field is a `var`, so adding one later is a pure addition.
 */
public class TrackInfoBuilder internal constructor(
    public var id: String,
    public var title: String,
    public var artists: List<ArtistInfo>,
    public var durationMs: Long,
) {
    public var coverUrl: String? = null
    public var sourceId: String? = null
    public var album: String? = null
    public var submittedByUserName: String? = null
    public var unavailableReason: LocalizedText? = null
    public var sourceFilterVerdict: FilterVerdict? = null
    public var lyricLrc: String? = null
    public var secondaryLyricLrc: String? = null
    public var lyricsFetched: Boolean = false
    public var loudness: LoudnessInfo? = null
    public var queueEntryId: String? = null
    // Add new optional fields here as `public var foo: T = default`.

    public fun build(): TrackInfo = TrackInfoImpl(
        id = id,
        title = title,
        artists = artists,
        durationMs = durationMs,
        coverUrl = coverUrl,
        sourceId = sourceId,
        album = album,
        submittedByUserName = submittedByUserName,
        unavailableReason = unavailableReason,
        sourceFilterVerdict = sourceFilterVerdict,
        lyricLrc = lyricLrc,
        secondaryLyricLrc = secondaryLyricLrc,
        lyricsFetched = lyricsFetched,
        loudness = loudness,
        queueEntryId = queueEntryId,
    )
}

/**
 * Build a [TrackInfo] from its required identity fields plus an optional [configure] block.
 *
 * This entry point has a frozen signature; future optional fields are set inside [configure] via
 * [TrackInfoBuilder], so adding them never breaks callers.
 */
public fun TrackInfo(
    id: String,
    title: String,
    artists: List<ArtistInfo>,
    durationMs: Long,
    configure: TrackInfoBuilder.() -> Unit = {},
): TrackInfo = TrackInfoBuilder(id, title, artists, durationMs).apply(configure).build()

/** Returns a copy of this track with [configure] applied to a seeded builder. Replaces `copy(...)`. */
public fun TrackInfo.copy(configure: TrackInfoBuilder.() -> Unit): TrackInfo =
    toBuilder().apply(configure).build()

internal data class TrackInfoImpl(
    override val id: String,
    override val title: String,
    override val artists: List<ArtistInfo>,
    override val durationMs: Long,
    override val coverUrl: String?,
    override val sourceId: String?,
    override val album: String?,
    override val submittedByUserName: String?,
    override val unavailableReason: LocalizedText?,
    override val sourceFilterVerdict: FilterVerdict?,
    override val lyricLrc: String?,
    override val secondaryLyricLrc: String?,
    override val lyricsFetched: Boolean,
    override val loudness: LoudnessInfo?,
    override val queueEntryId: String?,
) : TrackInfo {
    override fun toBuilder(): TrackInfoBuilder = TrackInfoBuilder(id, title, artists, durationMs).also {
        it.coverUrl = coverUrl
        it.sourceId = sourceId
        it.album = album
        it.submittedByUserName = submittedByUserName
        it.unavailableReason = unavailableReason
        it.sourceFilterVerdict = sourceFilterVerdict
        it.lyricLrc = lyricLrc
        it.secondaryLyricLrc = secondaryLyricLrc
        it.lyricsFetched = lyricsFetched
        it.loudness = loudness
        it.queueEntryId = queueEntryId
    }
}

/** Human-readable artist string for client/chat display. */
public val TrackInfo.artistDisplay: String
    get() = artists
        .map(ArtistInfo::displayName)
        .filter(String::isNotEmpty)
        .joinToString(", ")

/**
 * Preserve queue/runtime annotations when newer source metadata is fetched for the same track.
 */
public fun TrackInfo.mergePreservingRuntimeMetadata(refreshed: TrackInfo): TrackInfo {
    val existing = this
    return refreshed.copy {
        queueEntryId = existing.queueEntryId ?: refreshed.queueEntryId
        submittedByUserName = refreshed.submittedByUserName ?: existing.submittedByUserName
        artists = refreshed.artists.ifEmpty { existing.artists }
        loudness = existing.loudness.mergedWith(refreshed.loudness)
    }
}

/** True when the track is currently queueable / playable for users. */
public val TrackInfo.isAvailable: Boolean
    get() = unavailableReason.isNullOrBlank()

/** Short user-facing error payload used when a source exposes an unavailable track. */
public fun TrackInfo.unavailabilityMessage(): LocalizedText =
    unavailableReason?.takeUnless { it.isNullOrBlank() }
        ?.let { LocalizedText.key("error.moemusic.track_unavailable.reason", it) }
        ?: LocalizedText.key("error.moemusic.track_unavailable")

public fun TrackInfo.unavailabilityDebugMessage(): String = unavailabilityMessage().debugString()

/**
 * Queue identity match used for duplicate-prevention in the user-submitted queue.
 *
 * Uses the stable `(sourceId, id)` pair. Both fields must be non-null for a match;
 * tracks without a source identity are never considered duplicates of each other.
 */
public fun TrackInfo.matchesQueueIdentity(other: TrackInfo): Boolean {
    if (sourceId == null || other.sourceId == null) return false
    if (id.isBlank() || other.id.isBlank()) return false
    return sourceId == other.sourceId && id == other.id
}
