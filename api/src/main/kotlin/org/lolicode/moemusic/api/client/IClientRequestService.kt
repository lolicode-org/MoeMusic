package org.lolicode.moemusic.api.client

import org.lolicode.moemusic.api.service.PlaybackAction
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackInfo

/** Local request-layer failure such as disconnect, timeout, or missing handshake/session state. */
public class ClientRequestException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Typed built-in client -> server request API exposed to plugins. */
public interface IClientRequestService {

    /** Issue a routed search request to the connected server. */
    public suspend fun search(query: SearchQuery): ClientSearchPage

    /**
     * Request the complete authoritative user queue from the connected server.
     *
     * In paginated environments, this transparently fetches and merges all available pages.
     *
     * It has a default implementation to avoid ABI breakage, but new implementations should *always* override this method.
     */
    public suspend fun requestFullQueue(): ClientQueueSnapshot = requestQueue()

    /**
     * Request the full authoritative user queue from the connected server.
     *
     * @deprecated Prefer using [requestFullQueue] for an explicit complete snapshot, or [requestQueue(offset, limit)] for paginated access.
     */
    @Deprecated(
        message = "Use requestFullQueue() for full snapshot or requestQueue(offset, limit) for pagination.",
        replaceWith = ReplaceWith("requestFullQueue()"),
    )
    public suspend fun requestQueue(): ClientQueueSnapshot

    /**
     * Request a paginated slice of the authoritative user queue from the connected server.
     *
     * It has a default implementation to avoid ABI breakage, but new implementations should *always* override this method.
     */
    public suspend fun requestQueue(offset: Int = 0, limit: Int = 0): ClientQueueSnapshot = requestQueue()

    /** Request a page of choices from an active selection session on the connected server. */
    public suspend fun requestSelectionPage(
        sessionId: String,
        offset: Int = 0,
        limit: Int = 0,
    ): ClientSelectionPage = throw UnsupportedOperationException("requestSelectionPage is not supported")

    /** Request removal of a queued track by stable `(sourceId, trackId)` identity. */
    public suspend fun removeQueuedTrack(sourceId: String, trackId: String): ClientActionFeedback

    /** Request removal of a queued track by exact [queueEntryId] with fallback to `(sourceId, trackId)`. */
    public suspend fun removeQueuedTrack(
        sourceId: String,
        trackId: String,
        queueEntryId: String?,
    ): ClientActionFeedback = removeQueuedTrack(sourceId, trackId)

    /** Submit a track to the server by stable `(sourceId, trackId)` identity. */
    public suspend fun submitTrack(track: TrackInfo, mode: TrackAddMode = TrackAddMode.NORMAL): ClientTrackSubmitResult

    /** Submit a raw identifier or share link to the connected server. */
    public suspend fun submitIdentifier(
        identifier: String,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): ClientIdentifierSubmitResult

    /**
     * Submit a previously surfaced selection row to the connected server.
     *
     * Direct-track rows may be normalized onto the stable `(sourceId, trackId)` submit path
     * instead of going through the source's `resolveSelection(...)` handler.
     */
    public suspend fun submitSelection(
        entry: SelectionEntry,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): ClientSelectionSubmitResult

    /** Request a playback control action on the connected server. */
    public suspend fun controlPlayback(
        action: PlaybackAction,
        positionMs: Long = 0L,
    ): ClientActionFeedback

    /** Request an exact content-filter mutation on the connected server. */
    public suspend fun updateContentFilter(
        target: ContentFilterMutationTarget,
        sourceId: String,
        valueId: String,
        note: String? = null,
        ban: Boolean,
    ): ClientContentFilterActionResult

    /** Request clearing tracks from the user queue on the connected server. */
    public suspend fun clearQueue(
        scope: QueueClearScope = QueueClearScope.SELF,
        targetUserId: String? = null,
    ): ClientQueueClearResult = ClientQueueClearResult(0)
}

/** Scope for client queue clear requests. */
public enum class QueueClearScope {
    ALL,
    SELF,
    USER,
}

/** Result of a client queue clear request. */
public data class ClientQueueClearResult(
    val removedCount: Int,
    val successMessage: String? = null,
    val failureMessage: String? = null,
)
