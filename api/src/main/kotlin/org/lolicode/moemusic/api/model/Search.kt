package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.isNullOrBlank
import org.lolicode.moemusic.api.service.FilterVerdict

/**
 * A search query submitted by a player.
 *
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 *
 * @property query       Raw query string as typed by the player.
 * @property sourceId    If non-null, restrict the search to the given [MusicSource] ID.
 *                       If null, the server routes the search to its default searchable source.
 * @property limit       Maximum number of results to return per page. Default 20. 0 = use source default.
 * @property offset      Zero-based result offset for pagination. Default 0.
 */
public data class SearchQuery(
    val query: String,
    val sourceId: String? = null,
    val limit: Int = 20,
    val offset: Int = 0,
)

/**
 * User-visible search or selection row exposed by a [MusicSource].
 *
 * This is intentionally distinct from [TrackInfo]. A row may already be the minimum playable
 * track, or it may be a container / intermediate item that requires one more source-side
 * resolution step before a playable track can be enqueued.
 *
 * Construct one with the [SelectionEntry] factory / builder DSL — do **not** implement this
 * interface; it is sealed with a single internal implementation. Add new optional fields as a
 * default getter here plus a `var` in [SelectionEntryBuilder] to evolve without breaking ABI.
 *
 * @property selectionId Opaque source-local handle fed back to [MusicSource.resolveSelection].
 *                       For [SelectionEntryKind.TRACK], this must be the final stable source-local
 *                       [TrackInfo.id] key, because direct-track moderation, duplicate detection,
 *                       and direct `(sourceId, trackId)` submission paths rely on that identity
 *                       before a follow-up resolve call happens.
 * @property title       Human-readable item title.
 * @property artists     Artist / uploader identities when available. Populate [ArtistInfo.id]
 *                       when the source exposes a stable artist id; otherwise use
 *                       [ArtistInfo.name] so quick artist moderation still has a usable
 *                       best-effort identity.
 * @property durationMs  Duration in milliseconds when known, or `-1` if unknown.
 * @property sourceId    Owning [org.lolicode.moemusic.api.MusicSource.id]. Null only before the
 *                       server stamps it.
 * @property album       Album / playlist / collection name when available.
 * @property unavailableReason Localized reason why this row should remain visible but not be
 *                             selectable right now. Use for **inherent, un-bypassable** source-level
 *                             unavailability only. For filter-gated content, use [sourceFilterVerdict].
 * @property sourceFilterVerdict Optional filter verdict from the source. Set this when the source
 *                             has determined this entry should be filtered (e.g. based on description
 *                             or tags). Privileged bypass users can still select it; others see it
 *                             as unavailable. This field is never forwarded to the client.
 * @property kind        Whether this row is already a direct track or still needs expansion.
 */
public sealed interface SelectionEntry {
    public val selectionId: String
    public val title: String
    public val artists: List<ArtistInfo>
    public val durationMs: Long
    public val sourceId: String? get() = null
    public val album: String? get() = null
    public val unavailableReason: LocalizedText? get() = null
    public val sourceFilterVerdict: FilterVerdict? get() = null
    public val kind: SelectionEntryKind get() = SelectionEntryKind.TRACK

    /** Returns a builder seeded with this entry's values. */
    public fun toBuilder(): SelectionEntryBuilder
}

/** Mutable builder for [SelectionEntry]. */
public class SelectionEntryBuilder internal constructor(
    public var selectionId: String,
    public var title: String,
    public var artists: List<ArtistInfo>,
    public var durationMs: Long,
) {
    public var sourceId: String? = null
    public var album: String? = null
    public var unavailableReason: LocalizedText? = null
    public var sourceFilterVerdict: FilterVerdict? = null
    public var kind: SelectionEntryKind = SelectionEntryKind.TRACK

    public fun build(): SelectionEntry = SelectionEntryImpl(
        selectionId = selectionId,
        title = title,
        artists = artists,
        durationMs = durationMs,
        sourceId = sourceId,
        album = album,
        unavailableReason = unavailableReason,
        sourceFilterVerdict = sourceFilterVerdict,
        kind = kind,
    )
}

/**
 * Build a [SelectionEntry] from its required fields plus an optional [configure] block. The
 * signature is frozen; future optional fields are set inside [configure] via [SelectionEntryBuilder].
 */
public fun SelectionEntry(
    selectionId: String,
    title: String,
    artists: List<ArtistInfo>,
    durationMs: Long,
    configure: SelectionEntryBuilder.() -> Unit = {},
): SelectionEntry = SelectionEntryBuilder(selectionId, title, artists, durationMs).apply(configure).build()

/** Returns a copy of this entry with [configure] applied to a seeded builder. */
public fun SelectionEntry.copy(configure: SelectionEntryBuilder.() -> Unit): SelectionEntry =
    toBuilder().apply(configure).build()

internal data class SelectionEntryImpl(
    override val selectionId: String,
    override val title: String,
    override val artists: List<ArtistInfo>,
    override val durationMs: Long,
    override val sourceId: String?,
    override val album: String?,
    override val unavailableReason: LocalizedText?,
    override val sourceFilterVerdict: FilterVerdict?,
    override val kind: SelectionEntryKind,
) : SelectionEntry {
    override fun toBuilder(): SelectionEntryBuilder = SelectionEntryBuilder(selectionId, title, artists, durationMs).also {
        it.sourceId = sourceId
        it.album = album
        it.unavailableReason = unavailableReason
        it.sourceFilterVerdict = sourceFilterVerdict
        it.kind = kind
    }
}

/**
 * High-level behavior hint for a [SelectionEntry].
 *
 * This is an **open** value set, not an enum: a source may expose row kinds beyond the ones below,
 * and future API versions may add more. `when` over a [SelectionEntryKind] therefore cannot be
 * exhaustive and must always include an `else` branch — treat unrecognized kinds like [UNKNOWN].
 */
@JvmInline
public value class SelectionEntryKind private constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** Already the minimum playable unit. */
        public val TRACK: SelectionEntryKind = SelectionEntryKind("TRACK")

        /** Represents a container (album / playlist / episode list / etc.). */
        public val CONTAINER: SelectionEntryKind = SelectionEntryKind("CONTAINER")

        /** Source could not confidently classify the row up front. */
        public val UNKNOWN: SelectionEntryKind = SelectionEntryKind("UNKNOWN")

        /** Values known to this build. New values may appear at runtime; always handle `else`. */
        public val entries: List<SelectionEntryKind> = listOf(TRACK, CONTAINER, UNKNOWN)

        /** Returns the value for [id], creating an unknown-but-valid value when not recognized. */
        public fun of(id: String): SelectionEntryKind = SelectionEntryKind(id)
    }
}

/** Human-readable artist string for client/chat display. */
public val SelectionEntry.artistDisplay: String
    get() = artists
        .map(ArtistInfo::displayName)
        .filter(String::isNotEmpty)
        .joinToString(", ")

/** True when the entry can currently be selected by the player. */
public val SelectionEntry.isSelectable: Boolean
    get() = unavailableReason.isNullOrBlank()

/** True when selecting this row should normally enqueue immediately instead of expanding choices. */
public val SelectionEntry.isDirectTrack: Boolean
    get() = kind == SelectionEntryKind.TRACK

/**
 * Exact track identity for direct-track rows.
 *
 * Direct-track selections must use the final source-local [TrackInfo.id] as
 * [SelectionEntry.selectionId].
 * Callers that already know a row is direct-track should prefer the direct
 * `(sourceId, trackId)` submission path; container or indirection rows must leave exact track
 * moderation until they resolve to a concrete track.
 */
public val SelectionEntry.directTrackId: String?
    get() = selectionId.takeIf { isDirectTrack && it.isNotBlank() }

/** Short player-facing error payload used when a visible row cannot currently be selected. */
public fun SelectionEntry.unavailabilityMessage(): LocalizedText =
    unavailableReason?.takeUnless { it.isNullOrBlank() }
        ?.let { LocalizedText.key("error.moemusic.track_unavailable.reason", it) }
        ?: LocalizedText.key("error.moemusic.track_unavailable")

/**
 * Source-side resolution result for a [SelectionEntry].
 *
 * A source may resolve the selection directly to a playable [TrackInfo], or it may expand the
 * entry into a further list of child [SelectionEntry] choices.
 */
public sealed interface SelectionResolveResult {
    /**
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     */
    public data class Track(
        val track: TrackInfo,
    ) : SelectionResolveResult

    /**
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     */
    public data class Choices(
        val entries: List<SelectionEntry>,
    ) : SelectionResolveResult
}

/**
 * Search results from a single [MusicSource].
 *
 * Construct one with the [SearchResult] factory / builder DSL — do **not** implement this
 * interface; it is sealed with a single internal implementation.
 *
 * @property entries  Ordered page of matching rows (best match first).
 * @property sourceId The [MusicSource.id] that produced these results.
 * @property total    Total number of matches available for this query before pagination.
 * @property hasMore  True when more results are available after this page.
 * @property failure  Optional localized failure for routed/user-visible search flows.
 */
public sealed interface SearchResult {
    public val entries: List<SelectionEntry>
    public val sourceId: String
    public val total: Int
    public val hasMore: Boolean get() = false
    public val failure: LocalizedText? get() = null

    /** Returns a builder seeded with this result's values. */
    public fun toBuilder(): SearchResultBuilder
}

/** Mutable builder for [SearchResult]. */
public class SearchResultBuilder internal constructor(
    public var entries: List<SelectionEntry>,
    public var sourceId: String,
    public var total: Int,
) {
    public var hasMore: Boolean = false
    public var failure: LocalizedText? = null

    public fun build(): SearchResult = SearchResultImpl(entries, sourceId, total, hasMore, failure)
}

/**
 * Build a [SearchResult] from its required fields plus an optional [configure] block. The signature
 * is frozen; future optional fields are set inside [configure] via [SearchResultBuilder].
 */
public fun SearchResult(
    entries: List<SelectionEntry>,
    sourceId: String,
    total: Int,
    configure: SearchResultBuilder.() -> Unit = {},
): SearchResult = SearchResultBuilder(entries, sourceId, total).apply(configure).build()

/** Returns a copy of this result with [configure] applied to a seeded builder. */
public fun SearchResult.copy(configure: SearchResultBuilder.() -> Unit): SearchResult =
    toBuilder().apply(configure).build()

internal data class SearchResultImpl(
    override val entries: List<SelectionEntry>,
    override val sourceId: String,
    override val total: Int,
    override val hasMore: Boolean,
    override val failure: LocalizedText?,
) : SearchResult {
    override fun toBuilder(): SearchResultBuilder = SearchResultBuilder(entries, sourceId, total).also {
        it.hasMore = hasMore
        it.failure = failure
    }
}
