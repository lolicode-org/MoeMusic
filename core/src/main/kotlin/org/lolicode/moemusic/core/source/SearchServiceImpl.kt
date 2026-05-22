package org.lolicode.moemusic.core.source

import org.lolicode.moemusic.api.FilterBlockException
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.SearchableMusicSource
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.event.OnSearchCompleted
import org.lolicode.moemusic.api.model.ContentFilterTextRuleScope
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.api.service.ISearchService
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.error.UserFacingErrors
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.permission.PermissionNodes.CONTENT_FILTER_BYPASS
import org.lolicode.moemusic.core.plugin.PluginManager
import org.slf4j.LoggerFactory

/**
 * Routes search requests to a single registered [MusicSource].
 *
 * Respects [SearchQuery.sourceId] (selected source or server default), [SearchQuery.limit]
 * (max results per page), and [SearchQuery.offset] (pagination). Server-side content-filter
 * query text rules are enforced before the selected source's search API is called.
 *
 * @param sources Live reference to the mutable list owned by [PluginManager]. A defensive
 *                copy is taken when resolving a source so the lookup stays stable for the
 *                lifetime of a search call.
 */
class SearchServiceImpl(
    private val sources: List<MusicSource>,
) : ISearchService {

    private val logger = LoggerFactory.getLogger(SearchServiceImpl::class.java)

    override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): SearchResult =
        searchWithOutcome(query, submitter)

    fun sourceSnapshot(): List<MusicSource> = ArrayList(sources)

    fun defaultSearchSourceId(): String {
        val snapshot = sourceSnapshot()
        val configured = ModConfigManager.config.defaultSourceId
        if (configured.isNotBlank()) {
            val preferred = snapshot.firstOrNull { it.id == configured && it is SearchableMusicSource }
            if (preferred != null) return preferred.id
        }
        return snapshot.firstOrNull { it is SearchableMusicSource }?.id.orEmpty()
    }

    suspend fun searchWithOutcome(query: SearchQuery, submitter: MoeMusicUser? = null): SearchResult {
        val configuredMax = ModConfigManager.config.media.maxSearchResultsPerPage
        val effectiveLimit = (if (query.limit > 0) query.limit else 20)
            .coerceAtMost(configuredMax)
        val effectiveOffset = query.offset.coerceAtLeast(0)
        val normalizedQuery = query.copy(
            sourceId = query.sourceId?.takeIf(String::isNotBlank),
            limit = effectiveLimit,
            offset = effectiveOffset,
        )
        val resolvedSource = resolveSource(normalizedQuery.sourceId)
            ?: return complete(
                normalizedQuery,
                entries = emptyList(),
                total = 0,
                hasMore = false,
                sourceId = normalizedQuery.sourceId.orEmpty(),
                failure = LocalizedText.key("error.moemusic.source.bad_format"),
                submitter = submitter,
            )

        if (resolvedSource !is SearchableMusicSource) {
            return complete(
                normalizedQuery.copy(sourceId = resolvedSource.id),
                entries = emptyList(),
                total = 0,
                hasMore = false,
                sourceId = resolvedSource.id,
                failure = LocalizedText.key("error.moemusic.source.bad_format"),
                submitter = submitter,
            )
        }

        val effectiveQuery = normalizedQuery.copy(
            sourceId = resolvedSource.id,
        )

        enforceQueryContentFilter(effectiveQuery, submitter)

        val result = runCatching { resolvedSource.search(effectiveQuery, submitter) }.getOrElse { e ->
            logger.warn("Search failed for source '{}': {}", resolvedSource.id, e.message)
            return complete(
                effectiveQuery,
                entries = emptyList(),
                total = 0,
                hasMore = false,
                sourceId = resolvedSource.id,
                failure = UserFacingErrors.classify(e),
                submitter = submitter,
            )
        }

        val searchResult = when (result) {
            is UserResult.Success -> result.value
            is UserResult.Error -> {
                return complete(
                    effectiveQuery,
                    entries = emptyList(),
                    total = 0,
                    hasMore = false,
                    sourceId = resolvedSource.id,
                    failure = result.message,
                    submitter = submitter,
                )
            }
        }

        val page = searchResult.entries
            .take(effectiveLimit)
            .map { entry -> entry.copy(sourceId = entry.sourceId ?: resolvedSource.id) }
            // Filter annotation is deferred to ServerPacketHandlers so it can be applied per-sender.
            // with bypass-privilege awareness. Do not annotate here.
        val reportedTotal = searchResult.total.coerceAtLeast(0)
        val total = if (page.isEmpty()) reportedTotal else maxOf(reportedTotal, effectiveOffset + page.size)
        val hasMore = effectiveOffset + page.size < total
        return complete(
            effectiveQuery,
            entries = page,
            total = total,
            hasMore = hasMore,
            sourceId = searchResult.sourceId.ifBlank { resolvedSource.id },
            submitter = submitter,
        )
    }

    private fun complete(
        query: SearchQuery,
        entries: List<SelectionEntry>,
        total: Int,
        hasMore: Boolean,
        sourceId: String,
        failure: LocalizedText? = null,
        submitter: MoeMusicUser? = null,
    ): SearchResult {
        val outcome = SearchResult(
            entries = entries,
            total = total,
            hasMore = hasMore,
            sourceId = sourceId,
            failure = failure,
        )
        CoreEvents.bus.fire(
            OnSearchCompleted(
                query = query,
                submitter = submitter,
                sourceId = sourceId,
                entries = entries,
                total = total,
                hasMore = hasMore,
                failure = failure,
            )
        )
        return outcome
    }

    private fun resolveSource(sourceId: String?): MusicSource? {
        val snapshot = sourceSnapshot()
        return when {
            !sourceId.isNullOrBlank() -> snapshot.firstOrNull { it.id == sourceId }
            else -> snapshot.firstOrNull { it.id == defaultSearchSourceId() }
                ?: snapshot.firstOrNull { it is SearchableMusicSource }
        }
    }

    private fun enforceQueryContentFilter(query: SearchQuery, submitter: MoeMusicUser?) {
        if (hasFilterBypass(submitter)) return

        when (val verdict = ContentFilterRuntime.textFilterVerdict(ContentFilterTextRuleScope.QUERY, listOf(query.query))) {
            is FilterVerdict.Reject -> throw FilterBlockException(verdict.reason)
            FilterVerdict.Allow -> Unit
        }
    }

    private fun hasFilterBypass(submitter: MoeMusicUser?): Boolean {
        if (submitter == null) return false
        return submitter.hasPermission(CONTENT_FILTER_BYPASS.id, CONTENT_FILTER_BYPASS.defaultLevel())
    }
}
