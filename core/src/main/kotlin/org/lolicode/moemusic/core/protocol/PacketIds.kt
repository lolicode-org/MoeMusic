package org.lolicode.moemusic.core.protocol

/**
 * Canonical registry of all MoeMusic packet identifiers.
 *
 * Platform adapters iterate this object to register channel receivers.
 * The namespace is always `"moemusic"`.
 */
object PacketIds {
    private const val NS = "moemusic"

    // -------------------------------------------------------------------------
    // C→S (client to server)
    // -------------------------------------------------------------------------

    /** Sent once by the client on join: locale + initial state + mod version. */
    val CLIENT_HANDSHAKE = PacketId(NS, "client_handshake")

    /** Sent when the client explicitly changes playback participation mid-connection. */
    val CLIENT_STATE_CHANGE = PacketId(NS, "client_state_change")

    /** Monotonic clock sync round-trip — sent by client, echoed back by server. */
    val SYNC_REQUEST = PacketId(NS, "sync_request")

    /** Client requests a track to be added to the queue. */
    val TRACK_SUBMIT = PacketId(NS, "track_submit")

    /** Client submits a raw identifier or share link. */
    val IDENTIFIER_SUBMIT = PacketId(NS, "identifier_submit")

    /** Client submits a previously surfaced opaque selection. */
    val SELECTION_SUBMIT = PacketId(NS, "selection_submit")

    /** Server's response to a TrackSubmitRequest. */
    val TRACK_SUBMIT_RESPONSE = PacketId(NS, "track_submit_response")

    /** Server's response to an IdentifierSubmitRequest. */
    val IDENTIFIER_SUBMIT_RESPONSE = PacketId(NS, "identifier_submit_response")

    /** Server's response to a SelectionSubmitRequest. */
    val SELECTION_SUBMIT_RESPONSE = PacketId(NS, "selection_submit_response")

    /** Client submits a text search query. */
    val SEARCH_REQUEST = PacketId(NS, "search_request")

    /** Client requests the current user queue. */
    val QUEUE_REQUEST = PacketId(NS, "queue_request")

    /** Client requests removal of a track from the queue by (source_id, track_id). */
    val QUEUE_REMOVE_REQUEST = PacketId(NS, "queue_remove_request")

    /** Client requests a playback control action (pause/resume/skip/stop/seek). */
    val PLAYBACK_CONTROL_REQUEST = PacketId(NS, "playback_control_request")

    /** Client requests an exact content-filter rule mutation on the server. */
    val CONTENT_FILTER_ACTION_REQUEST = PacketId(NS, "content_filter_action_request")

    // -------------------------------------------------------------------------
    // S→C (server to client)
    // -------------------------------------------------------------------------

    /** Server's response to a SyncRequest. */
    val SYNC_RESPONSE = PacketId(NS, "sync_response")

    /** Server response that accepts/rejects the client handshake and provides initial state. */
    val SERVER_WELCOME = PacketId(NS, "server_welcome")

    /** Sent when an active client should apply a full anchored playback snapshot. */
    val PLAYBACK_SNAPSHOT_PUSH = PacketId(NS, "playback_snapshot_push")

    /** Broadcast when playback state changes (pause, resume, seek, stop). */
    val STATE_UPDATE = PacketId(NS, "state_update")

    /** Server's response to a SearchRequest. */
    val SEARCH_RESPONSE = PacketId(NS, "search_response")

    /** Server's response to a QueueRequest. */
    val QUEUE_RESPONSE = PacketId(NS, "queue_response")

    /** Server's response to a QueueRemoveRequest. */
    val QUEUE_REMOVE_RESPONSE = PacketId(NS, "queue_remove_response")

    /** Server's response to a PlaybackControlRequest. */
    val PLAYBACK_CONTROL_RESPONSE = PacketId(NS, "playback_control_response")

    /** Server's response to a ContentFilterActionRequest. */
    val CONTENT_FILTER_ACTION_RESPONSE = PacketId(NS, "content_filter_action_response")

    /** All registered IDs, in definition order — convenient for bulk registration. */
    val ALL: List<PacketId> = listOf(
        CLIENT_HANDSHAKE, CLIENT_STATE_CHANGE, SYNC_REQUEST, TRACK_SUBMIT, IDENTIFIER_SUBMIT, SELECTION_SUBMIT, SEARCH_REQUEST,
        QUEUE_REQUEST, QUEUE_REMOVE_REQUEST, PLAYBACK_CONTROL_REQUEST, CONTENT_FILTER_ACTION_REQUEST,
        TRACK_SUBMIT_RESPONSE, IDENTIFIER_SUBMIT_RESPONSE, SELECTION_SUBMIT_RESPONSE, SYNC_RESPONSE, SERVER_WELCOME, PLAYBACK_SNAPSHOT_PUSH, STATE_UPDATE,
        SEARCH_RESPONSE,
        QUEUE_RESPONSE, QUEUE_REMOVE_RESPONSE, PLAYBACK_CONTROL_RESPONSE, CONTENT_FILTER_ACTION_RESPONSE,
    )
}
