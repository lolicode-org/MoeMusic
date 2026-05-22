package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.FilterBlockException
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.ServerSessionContext

/**
 * Raw search routing interface exposed to plugins via [ServerRuntimeContext] and
 * [ServerSessionContext].
 *
 * The implementation routes a query to the selected [MusicSource], or the server's default
 * searchable source when [SearchQuery.sourceId] is not set.
 */
public interface ISearchService {

    /**
     * Search a single [MusicSource] for user-visible rows matching [query].
     *
     * This is a raw plugin -> core call. Shared permission and rate-limit checks are intentionally
     * not applied here. Plugins that want MoeMusic's checked user-behalf path should prefer
     * [org.lolicode.moemusic.api.service.IUserActionService.search].
     *
     * Respects [SearchQuery.sourceId] (selected source or server default), [SearchQuery.limit]
     * (max results per page), and [SearchQuery.offset] (pagination offset).
     * Server implementations may reject [query] with [FilterBlockException] when a content-filter
     * text rule scoped to `QUERY` matches before the music source is called.
     *
     * @param submitter The user who issued the search, or `null` for server-internal calls.
     */
    public suspend fun search(query: SearchQuery, submitter: MoeMusicUser? = null): SearchResult
}
