package org.lolicode.moemusic.clientcore.playback

import org.lolicode.moemusic.api.model.SelectionEntry

data class ClientServerScope(
    val key: String,
    val displayName: String,
)

data class SearchSourceInfo(
    val id: String,
    val displayName: String,
    val searchable: Boolean,
)

data class SearchSourceCatalog(
    val sources: List<SearchSourceInfo>,
    val defaultSourceId: String,
)

data class CachedSearchState(
    val query: String,
    val sourceId: String,
    val entries: List<SelectionEntry>,
    val total: Int,
    val hasMore: Boolean,
    val failure: String? = null,
)

enum class AvailabilityIssue {
    SERVER_MISSING,
}
