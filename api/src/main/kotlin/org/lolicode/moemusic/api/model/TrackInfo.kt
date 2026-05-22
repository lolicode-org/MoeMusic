package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.isNullOrBlank
import org.lolicode.moemusic.api.service.FilterVerdict

/**
 * Immutable description of a single audio track.
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
 */
public data class TrackInfo(
    val id: String,
    val title: String,
    val artists: List<ArtistInfo>,
    val durationMs: Long,
    val coverUrl: String? = null,
    val sourceId: String? = null,
    val album: String? = null,
    val submittedByUserName: String? = null,
    val unavailableReason: LocalizedText? = null,
    val sourceFilterVerdict: FilterVerdict? = null,
    val lyricLrc: String? = null,
    val secondaryLyricLrc: String? = null,
    val lyricsFetched: Boolean = false,
)

/** Human-readable artist string for client/chat display. */
public val TrackInfo.artistDisplay: String
    get() = artists
        .map(ArtistInfo::displayName)
        .filter(String::isNotEmpty)
        .joinToString(", ")

/**
 * Preserve queue/runtime annotations when newer source metadata is fetched for the same track.
 */
public fun TrackInfo.mergePreservingRuntimeMetadata(refreshed: TrackInfo): TrackInfo = refreshed.copy(
    submittedByUserName = refreshed.submittedByUserName ?: submittedByUserName,
    artists = refreshed.artists.ifEmpty { artists },
)

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
