package org.lolicode.moemusic.api.client

import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo

/**
 * Coarse local availability issue for the builtin client request/playback UI.
 *
 * This 1.x API intentionally keeps the historical names for compatibility.
 */
public enum class ClientAvailabilityIssue {
    /**
     * The client has no usable MoeMusic handshake for the current connection.
     *
     * The name is historical: this covers any handshake failure, including no server
     * response, a missing server-side mod, protocol mismatch, or explicit server rejection.
     * It is kept for 1.x compatibility and may be renamed or removed in API 2.0.
     */
    SERVER_MISSING,
}

/** Server-provided search source descriptor from the connection handshake. */
public data class ClientSearchSource(
    val id: String,
    val displayName: String,
    val searchable: Boolean,
)

/** Server-provided search source catalog from the connection handshake. */
public data class ClientSearchCatalog(
    val sources: List<ClientSearchSource>,
    val defaultSourceId: String,
)

/** Generic client-side action feedback from a request/response exchange. */
public data class ClientActionFeedback(
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/** Client-side wrapper for a routed search page returned by the server. */
public data class ClientSearchPage(
    val result: SearchResult,
    val failureMessage: String? = null,
)

/** Client-side wrapper for a queue snapshot returned by the server. */
public data class ClientQueueSnapshot(
    val tracks: List<TrackInfo>,
    val failureMessage: String? = null,
)

/** Client-side response for a direct track submission. */
public data class ClientTrackSubmitResult(
    val trackId: String,
    val trackTitle: String,
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/** Client-side response for an identifier submission. */
public data class ClientIdentifierSubmitResult(
    val trackId: String? = null,
    val trackTitle: String? = null,
    val choices: List<SelectionEntry> = emptyList(),
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/** Client-side response for a selection submission. */
public data class ClientSelectionSubmitResult(
    val trackId: String? = null,
    val trackTitle: String? = null,
    val choices: List<SelectionEntry> = emptyList(),
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/** Target kind for a client-side content-filter mutation request. */
public enum class ContentFilterMutationTarget {
    TRACK,
    ARTIST,
}

/** Client-side response for a content-filter mutation request. */
public data class ClientContentFilterActionResult(
    val target: ContentFilterMutationTarget,
    val sourceId: String,
    val valueId: String,
    val blockedNow: Boolean,
    val successMessage: String? = null,
    val failureMessage: String? = null,
)
