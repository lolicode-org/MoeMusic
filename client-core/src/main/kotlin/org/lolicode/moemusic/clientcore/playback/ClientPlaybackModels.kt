package org.lolicode.moemusic.clientcore.playback

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.SelectionEntry

data class ClientServerScope(
    val key: String,
    val displayName: String,
) {
    constructor(
        key: String,
        displayName: String,
        keyAliases: Set<String>,
    ) : this(key, displayName) {
        this.keyAliases = keyAliases
    }

    var keyAliases: Set<String> = emptySet()
        private set

    fun matchingKeys(): Set<String> = buildSet {
        add(key)
        addAll(keyAliases)
    }
}

data class SearchSourceInfo(
    val id: String,
    val displayName: String,
    val searchable: Boolean,
)

data class SearchSourceCatalog(
    val sources: List<SearchSourceInfo>,
    val defaultSourceId: String,
)

data class CachedSearchTabState(
    val query: String,
    val sourceId: String,
    val entries: List<SelectionEntry>,
    val total: Int,
    val hasMore: Boolean,
    val failure: String? = null,
    val selectionSessionId: String? = null,
)

enum class AvailabilityIssue {
    SERVER_MISSING,
    SERVER_REJECTED,
}

enum class ServerWelcomeRejectionReason {
    PROTOCOL_MISMATCH,
    SERVER_ERROR,
    UNKNOWN,
}

data class ServerWelcomeRejection(
    val reason: ServerWelcomeRejectionReason,
    val clientProtocolVersion: Int,
    val serverProtocolVersion: Int,
    val detail: String? = null,
)

enum class ProtocolMismatchAction {
    UPDATE_CLIENT,
    UPDATE_SERVER,
    CHECK_BOTH_SIDES,
}

fun ServerWelcomeRejection.protocolMismatchAction(): ProtocolMismatchAction =
    when {
        clientProtocolVersion < serverProtocolVersion -> ProtocolMismatchAction.UPDATE_CLIENT
        clientProtocolVersion > serverProtocolVersion -> ProtocolMismatchAction.UPDATE_SERVER
        else -> ProtocolMismatchAction.CHECK_BOTH_SIDES
    }

fun ServerWelcomeRejection.toLocalizedText(): LocalizedText =
    when (reason) {
        ServerWelcomeRejectionReason.PROTOCOL_MISMATCH -> when (protocolMismatchAction()) {
            ProtocolMismatchAction.UPDATE_CLIENT -> LocalizedText.key(
                "screen.moemusic.unavailable.protocol_mismatch.update_client",
                clientProtocolVersion,
                serverProtocolVersion,
            )

            ProtocolMismatchAction.UPDATE_SERVER -> LocalizedText.key(
                "screen.moemusic.unavailable.protocol_mismatch.update_server",
                clientProtocolVersion,
                serverProtocolVersion,
            )

            ProtocolMismatchAction.CHECK_BOTH_SIDES -> LocalizedText.key(
                "screen.moemusic.unavailable.protocol_mismatch.check_both",
                clientProtocolVersion,
            )
        }

        ServerWelcomeRejectionReason.SERVER_ERROR ->
            LocalizedText.key("screen.moemusic.unavailable.rejected.server_error")

        ServerWelcomeRejectionReason.UNKNOWN ->
            LocalizedText.key("screen.moemusic.unavailable.rejected.body")
    }
