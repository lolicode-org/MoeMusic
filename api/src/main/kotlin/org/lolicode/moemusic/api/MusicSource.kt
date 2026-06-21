package org.lolicode.moemusic.api

import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.PlaybackResolution
import org.lolicode.moemusic.api.model.SelectionResolveResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.plugin.Plugin

/**
 * Result of asking a source whether it recognizes a raw identifier.
 *
 * Use [Blocked] when the source can explain to the user why the identifier should not proceed
 * as a single-track submission. Use [Pass] when the identifier does not belong to this source or
 * should be handled by another source. Use [Resolved] only when you can confidently construct the
 * final [TrackInfo] without another choice step. Use [Choices] when the identifier belongs to
 * this source but points at a higher-level object (album / playlist / episode list / etc.) that
 * must be narrowed further before a minimum playable track can be submitted.
 */
public sealed interface IdentifierResolutionResult {
    public data object Pass : IdentifierResolutionResult

    /**
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     */
    public data class Resolved(
        val track: TrackInfo,
    ) : IdentifierResolutionResult

    /**
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     */
    public data class Choices(
        val entries: List<SelectionEntry>,
    ) : IdentifierResolutionResult

    /**
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     */
    public data class Blocked(
        val message: LocalizedText,
    ) : IdentifierResolutionResult
}

/**
 * Extends [MusicSource] with text search capability.
 *
 * Implement this interface in addition to [MusicSource] when your source can accept a free-text
     * query and return a ranked list of matching user-visible rows.
 *
 * Sources that do **not** implement this interface are excluded from search routing and will not
 * appear as selectable search sources in client UIs.
 */
public interface SearchableMusicSource : MusicSource {

    /**
     * Searches for tracks matching [query].
     *
     * This is a user-facing boundary. Expected failures should be returned as [UserResult.Error]
     * with a localized message. Do not throw for empty results; return an empty page with
     * `total = 0` instead.
     *
     * Implementations should honour [SearchQuery.limit] and [SearchQuery.offset], returning the
     * requested page plus the full result count in [SearchResult.total].
     *
     * Each returned row is a [org.lolicode.moemusic.api.model.SelectionEntry], not necessarily a
     * minimum playable [TrackInfo]. Direct tracks should use
     * [org.lolicode.moemusic.api.model.SelectionEntryKind.TRACK] and must reuse the source's
     * canonical source-local track key as [org.lolicode.moemusic.api.model.SelectionEntry.selectionId].
     * Common client/command flows may submit those direct-track rows through the stable
     * `(sourceId, trackId)` path instead of `resolveSelection(...)`. Container or otherwise indirect rows
     * should expose an opaque selection id and later answer
     * [MusicSource.resolveSelection].
     *
     * Throw only when the search attempt itself has failed exceptionally and normal result flow is
     * no longer appropriate.
     *
     * @param query     The search query from the user.
     * @param submitter The user who initiated the search, or `null` for server-internal calls.
     *                  Sources may use this for per-user rate limiting or access control.
     */
    public suspend fun search(query: SearchQuery, submitter: MoeMusicUser? = null): UserResult<SearchResult>
}

public interface IdentifierResolvableMusicSource : MusicSource {
    /**
     * If `true`, this source is tried **only after** all non-fallback sources have returned
     * [IdentifierResolutionResult.Pass]. Use this for generic resolvers (e.g. plain HTTP/HTTPS)
     * that accept a broad set of identifiers but should yield to platform-specific sources.
     *
     * Default: `false` (specific source — tried in the first pass).
     */
    public val isFallbackResolver: Boolean get() = false

    /**
     * Attempt to interpret a raw user-supplied identifier or share link.
     *
     * This is a user-facing boundary, so expected rejections should be returned as
     * [IdentifierResolutionResult.Blocked] with a localized message rather than thrown.
     *
     * @param identifier The raw string submitted by the user or command.
     * @param submitter  The user who submitted the identifier, or `null` for server-internal
     *                   calls (e.g. autoplay, admin commands). Sources may perform permission
     *                   checks against this value **before** doing any network I/O to prevent
     *                   unauthorized resource access (e.g. IP leaks from HTTP probing).
     *                   Return [IdentifierResolutionResult.Pass] (if you want to allow other sources to try)
     *                   or [IdentifierResolutionResult.Blocked]
     *                   (if you are confident that the identifier belongs to and *only* to your source)
     *                   without probing if the submitter lacks the required permission.
     */
    public suspend fun resolveIdentifier(identifier: String, submitter: MoeMusicUser?): IdentifierResolutionResult
}

/**
 * Contract for a pluggable audio content source.
 *
 * Implementations are registered via [org.lolicode.moemusic.api.plugin.ServerRuntimeContext.registerMusicSource] during
 * [Plugin.onServerRuntimeLoad] and are queried by the server whenever a user searches for or
 * queues a track.
 *
 * All methods are suspending; use coroutines or `runBlocking` if bridging from Java.
 */
public interface MusicSource {

    /**
     * A unique, stable identifier for this source (e.g. `"moemusic:http"`, `"youtube"`).
     * Must not contain whitespace.
     *
     * Source ids are global across builtin and plugin-owned registrations. Registering two
     * sources with the same id is a fatal startup error.
     */
    public val id: String

    /**
     * Human-readable source name shown in search UIs, handshake catalogs, and command feedback.
     *
     * Defaults to [id]; prefer [LocalizedText.key] so server-side rendering can localize it per
     * user before it is sent to the client.
     */
    public val displayName: LocalizedText
        get() = LocalizedText.plain(id)


    /**
     * Resolves [track] to a direct, playable client resource plus an optional resolve-time metadata
     * patch for the active track.
     *
     * Called when core needs a direct, client-playable resource for the current track. This is
     * always done immediately before the initial playback start, and may also happen again later
     * for reopen paths such as resume, seek, or late-join sync when the server wants a fresher
     * playback URL or header set.
     *
     * The returned [PlaybackResolution.playback] is sent to the client and may include per-request
     * HTTP headers required for playback. When the same upstream request also reveals additional
     * stable track metadata (for example synchronized lyrics or integrated LUFS), return a limited
     * [PlaybackResolution.trackPatch] rather than trying to smuggle those fields through
     * [PlaybackResource].
     *
     * The source should use [TrackInfo.id] to locate and sign the audio stream. This value is an
     * opaque source-local key: for HTTP sources it is the direct URL itself; for API-backed
     * sources it may be a bare native id or a typed key such as `"song:123"` / `"episode:456"`.
     *
     * This is an exception-based boundary by design. If playback cannot proceed, throw a typed
     * [UserFacingException] such as [TrackUnavailableException], [SourceTimeoutException], or
     * [SourceAuthException] instead of returning a sentinel value.
     *
     * @param track     The track to resolve.
     * @param submitter The user about to receive the stream, or `null` for server-internal
     *                  resolution (e.g. autoplay). Sources may use this to generate
     *                  per-user signed URLs or enforce playback quotas.
     */
    public suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser? = null): PlaybackResolution

    /**
     * Fetches metadata for a single track by its source-local [trackId] (the [TrackInfo.id] value).
     *
     * This is also a user-facing boundary. Return [UserResult.Success] with `null` when the track
     * simply does not exist. Return [UserResult.Error] when the user should receive a specific,
     * localized explanation. Throw only for exceptional failures that abort the current lookup.
     *
     * Used by the `/music addById <source> <uniqueId>` command, by structured submit refreshes,
     * and by the default [resolveSelection] implementation for direct-track entries.
     * Default implementation returns null (source does not support direct ID lookup).
     *
     * @param trackId   The [TrackInfo.id] for this source (e.g. `"12345"` or `"episode:12345"`).
     * @param submitter The user requesting the track, or `null` for server-internal calls
     *                  (e.g. metadata refresh before playback). Sources may use this for
     *                  per-user access control or quota enforcement.
     * @return Full [TrackInfo] for the given ID, or null if the track cannot be found.
     */
    public suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser? = null): UserResult<TrackInfo?> = UserResult.Success(null)

    /**
     * Resolve a source-local opaque [selectionId] previously returned from [SearchableMusicSource.search] or
     * [IdentifierResolvableMusicSource.resolveIdentifier].
     *
     * This is a user-facing boundary. Return [UserResult.Success] with `null` when the selection
     * no longer exists. Return [UserResult.Error] when the user should receive a specific,
     * localized explanation. Throw only for exceptional failures that abort the current lookup.
     *
     * Direct-track rows must use the canonical [TrackInfo.id] as [selectionId]. The default
     * implementation delegates to [getTrackInfo] and wraps the result in [SelectionResolveResult.Track],
     * but common client/command flows may bypass `resolveSelection(...)` entirely for known direct-track
     * rows and submit them via the stable `(sourceId, trackId)` path instead. Sources with
     * container rows should override this and return either a direct [TrackInfo] or a further
     * narrowed list of child choices.
     */
    public suspend fun resolveSelection(
        selectionId: String,
        submitter: MoeMusicUser? = null,
    ): UserResult<SelectionResolveResult?> = when (val result = getTrackInfo(selectionId, submitter)) {
        is UserResult.Success -> UserResult.Success(result.value?.let(SelectionResolveResult::Track))
        is UserResult.Error -> result
    }

    /**
     * Returns tracks that this source contributes to **autoplay**.
     *
     * The autoplay plays ambient music when no user has queued anything.
     * Tracks from all registered sources are combined, shuffled into a non-repeating deck,
     * and cycled through; the deck is refreshed once fully consumed.
     *
     * Default implementation returns an empty list (source does not contribute to autoplay).
     * Sources that wish to provide autoplay content should override this method and return
     * a representative set of tracks (curated chart, station, etc.).
     */
    public suspend fun getAutoplayTracks(): List<TrackInfo> = emptyList()
}
