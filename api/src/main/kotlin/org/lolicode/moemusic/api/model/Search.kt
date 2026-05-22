package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.isNullOrBlank
import org.lolicode.moemusic.api.service.FilterVerdict

/**
 * A search query submitted by a player.
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
public data class SelectionEntry(
    val selectionId: String,
    val title: String,
    val artists: List<ArtistInfo>,
    val durationMs: Long,
    val sourceId: String? = null,
    val album: String? = null,
    val unavailableReason: LocalizedText? = null,
    val sourceFilterVerdict: FilterVerdict? = null,
    val kind: SelectionEntryKind = SelectionEntryKind.TRACK,
)

/** High-level behavior hint for a [SelectionEntry]. */
public enum class SelectionEntryKind {
    /** Already the minimum playable unit. */
    TRACK,

    /** Represents a container (album / playlist / episode list / etc.). */
    CONTAINER,

    /** Source could not confidently classify the row up front. */
    UNKNOWN,
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
    public data class Track(
        val track: TrackInfo,
    ) : SelectionResolveResult

    public data class Choices(
        val entries: List<SelectionEntry>,
    ) : SelectionResolveResult
}

/**
 * Search results from a single [MusicSource].
 *
 * @property entries  Ordered page of matching rows (best match first).
 * @property sourceId The [MusicSource.id] that produced these results.
 * @property total    Total number of matches available for this query before pagination.
 * @property hasMore  True when more results are available after this page.
 * @property failure  Optional localized failure for routed/user-visible search flows.
 */
public data class SearchResult(
    val entries: List<SelectionEntry>,
    val sourceId: String,
    val total: Int,
    val hasMore: Boolean = false,
    val failure: LocalizedText? = null,
)
