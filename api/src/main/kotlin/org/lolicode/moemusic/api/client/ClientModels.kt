package org.lolicode.moemusic.api.client

import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo

/**
 * Local availability issue for the builtin client request/playback UI.
 *
 * This is an **open** value set, not an enum: more specific reasons may be reported as the handshake
 * layer learns to distinguish them, and future API versions may add values. `when` over a
 * [ClientAvailabilityIssue] therefore cannot be exhaustive and must always include an `else` branch.
 * Treat any unrecognized value like the coarse [HANDSHAKE_UNAVAILABLE].
 */
@JvmInline
public value class ClientAvailabilityIssue private constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** No usable MoeMusic handshake for the current connection; specific cause unspecified. */
        public val HANDSHAKE_UNAVAILABLE: ClientAvailabilityIssue = ClientAvailabilityIssue("HANDSHAKE_UNAVAILABLE")

        /** The server never answered the MoeMusic handshake. */
        public val NO_RESPONSE: ClientAvailabilityIssue = ClientAvailabilityIssue("NO_RESPONSE")

        /** The server is reachable but has no MoeMusic server-side mod installed. */
        public val SERVER_MOD_MISSING: ClientAvailabilityIssue = ClientAvailabilityIssue("SERVER_MOD_MISSING")

        /** The server runs an incompatible MoeMusic protocol version. */
        public val PROTOCOL_MISMATCH: ClientAvailabilityIssue = ClientAvailabilityIssue("PROTOCOL_MISMATCH")

        /** The server explicitly rejected the MoeMusic handshake. */
        public val REJECTED: ClientAvailabilityIssue = ClientAvailabilityIssue("REJECTED")

        /** Values known to this build. New values may appear at runtime; always handle `else`. */
        public val entries: List<ClientAvailabilityIssue> =
            listOf(HANDSHAKE_UNAVAILABLE, NO_RESPONSE, SERVER_MOD_MISSING, PROTOCOL_MISMATCH, REJECTED)

        /** Returns the value for [id], creating an unknown-but-valid value when not recognized. */
        public fun of(id: String): ClientAvailabilityIssue = ClientAvailabilityIssue(id)
    }
}

/**
 * Server-provided search source descriptor from the connection handshake.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientSearchSource(
    val id: String,
    val displayName: String,
    val searchable: Boolean,
)

/**
 * Server-provided search source catalog from the connection handshake.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientSearchCatalog(
    val sources: List<ClientSearchSource>,
    val defaultSourceId: String,
)

/**
 * Generic client-side action feedback from a request/response exchange.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientActionFeedback(
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/**
 * Client-side wrapper for a routed search page returned by the server.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientSearchPage(
    val result: SearchResult,
    val failureMessage: String? = null,
)

/**
 * Client-side wrapper for a queue snapshot returned by the server.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientQueueSnapshot(
    val tracks: List<TrackInfo>,
    val failureMessage: String? = null,
)

/**
 * Client-side response for a direct track submission.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientTrackSubmitResult(
    val trackId: String,
    val trackTitle: String,
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/**
 * Client-side response for an identifier submission.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientIdentifierSubmitResult(
    val trackId: String? = null,
    val trackTitle: String? = null,
    val choices: List<SelectionEntry> = emptyList(),
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/**
 * Client-side response for a selection submission.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientSelectionSubmitResult(
    val trackId: String? = null,
    val trackTitle: String? = null,
    val choices: List<SelectionEntry> = emptyList(),
    val successMessage: String? = null,
    val failureMessage: String? = null,
)

/**
 * Target kind for a client-side content-filter mutation request.
 *
 * This is an **open** value set, not an enum: future API versions may add mutation targets, so
 * `when` over a [ContentFilterMutationTarget] cannot be exhaustive and must always include an
 * `else` branch.
 */
@JvmInline
public value class ContentFilterMutationTarget private constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val TRACK: ContentFilterMutationTarget = ContentFilterMutationTarget("TRACK")
        public val ARTIST: ContentFilterMutationTarget = ContentFilterMutationTarget("ARTIST")

        /** Values known to this build. New values may appear at runtime; always handle `else`. */
        public val entries: List<ContentFilterMutationTarget> = listOf(TRACK, ARTIST)

        /** Returns the value for [id], creating an unknown-but-valid value when not recognized. */
        public fun of(id: String): ContentFilterMutationTarget = ContentFilterMutationTarget(id)
    }
}

/**
 * Client-side response for a content-filter mutation request.
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class ClientContentFilterActionResult(
    val target: ContentFilterMutationTarget,
    val sourceId: String,
    val valueId: String,
    val blockedNow: Boolean,
    val successMessage: String? = null,
    val failureMessage: String? = null,
)
