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

    /** Request the current authoritative user queue from the connected server. */
    public suspend fun requestQueue(): ClientQueueSnapshot

    /** Request removal of a queued track by stable `(sourceId, trackId)` identity. */
    public suspend fun removeQueuedTrack(sourceId: String, trackId: String): ClientActionFeedback

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
}
