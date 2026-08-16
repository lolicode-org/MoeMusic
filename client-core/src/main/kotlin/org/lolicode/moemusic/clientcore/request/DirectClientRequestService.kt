package org.lolicode.moemusic.clientcore.request

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.lolicode.moemusic.api.client.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.PlaybackAction
import org.lolicode.moemusic.core.playback.toApi
import org.lolicode.moemusic.core.protocol.proto.*
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L

interface ClientRequestTransport {
    fun ensureDirectRequestSessionReady()
    fun beginSearchRequest(query: String, sourceId: String, limit: Int, offset: Int): Deferred<SearchResponse>?
    fun beginQueueRequest(limit: Int = 0, offset: Int = 0): Deferred<QueueResponse>?
    fun beginQueueRemoveRequest(sourceId: String, trackId: String): Deferred<QueueRemoveResponse>?
    fun beginTrackSubmitRequest(track: TrackInfo, mode: TrackAddMode): Deferred<TrackSubmitResponse>?
    fun beginTrackSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<TrackSubmitResponse>?
    fun beginIdentifierSubmitRequest(identifier: String, mode: TrackAddMode): Deferred<IdentifierSubmitResponse>?
    fun beginSelectionSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<SelectionSubmitResponse>?
    fun beginSelectionPageRequest(sessionId: String, offset: Int, limit: Int): Deferred<SelectionPageResponse>?
    fun beginPlaybackControlRequest(action: PlaybackControlAction, positionMs: Long): Deferred<PlaybackControlResponse>?
    fun beginContentFilterTrackActionRequest(
        sourceId: String,
        trackId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>?

    fun beginContentFilterArtistActionRequest(
        sourceId: String,
        artistId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>?
}

class DirectClientRequestService(
    private val transport: ClientRequestTransport,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : IClientRequestService {

    override suspend fun search(query: SearchQuery): ClientSearchPage {
        val response = awaitResponse {
            transport.beginSearchRequest(
                query = query.query,
                sourceId = query.sourceId.orEmpty(),
                limit = query.limit,
                offset = query.offset,
            )
        }
        val entries = response.entries.map { it.toApi() }
        val effectiveTotal = if (response.total == 0 && entries.isNotEmpty()) {
            (response.offset + entries.size).coerceAtLeast(entries.size)
        } else {
            response.total
        }
        return ClientSearchPage(
            result = SearchResult(
                entries = entries,
                sourceId = response.source_id,
                total = effectiveTotal,
            ) {
                hasMore = response.has_more
            },
            failureMessage = response.failure.ifEmpty { null },
        )
    }

    companion object {
        private const val MAX_UNPAGINATED_QUEUE_TRACKS = 1000
        private const val MAX_UNPAGINATED_QUEUE_PAGES = 50
    }

    override suspend fun requestFullQueue(): ClientQueueSnapshot {
        val firstPage = requestQueue(offset = 0, limit = 0)
        if (!firstPage.hasMore || firstPage.tracks.isEmpty() || firstPage.failureMessage != null) {
            return firstPage
        }
        val allTracks = firstPage.tracks.toMutableList()
        var currentOffset = firstPage.tracks.size
        var hasMore = firstPage.hasMore
        var lastFailure = firstPage.failureMessage
        var pagesFetched = 1

        while (hasMore && allTracks.size < MAX_UNPAGINATED_QUEUE_TRACKS && pagesFetched < MAX_UNPAGINATED_QUEUE_PAGES) {
            val page = requestQueue(offset = currentOffset, limit = 0)
            pagesFetched++
            if (page.tracks.isEmpty() || page.failureMessage != null) {
                lastFailure = page.failureMessage
                break
            }
            allTracks.addAll(page.tracks)
            currentOffset += page.tracks.size
            hasMore = page.hasMore
        }
        return ClientQueueSnapshot(
            tracks = allTracks,
            offset = 0,
            total = allTracks.size,
            hasMore = false,
            failureMessage = lastFailure,
        )
    }

    @Deprecated("Use requestFullQueue()", ReplaceWith("requestFullQueue()"))
    override suspend fun requestQueue(): ClientQueueSnapshot = requestFullQueue()

    override suspend fun requestQueue(offset: Int, limit: Int): ClientQueueSnapshot {
        val response = awaitResponse { transport.beginQueueRequest(limit = limit, offset = offset) }
        val tracks = response.tracks.map { it.toApi() }
        val effectiveTotal = if (response.total == 0 && tracks.isNotEmpty()) {
            (response.offset + tracks.size).coerceAtLeast(tracks.size)
        } else {
            response.total
        }
        return ClientQueueSnapshot(
            tracks = tracks,
            offset = response.offset,
            total = effectiveTotal,
            hasMore = response.has_more,
            failureMessage = response.failure.ifEmpty { null },
        )
    }

    override suspend fun requestSelectionPage(
        sessionId: String,
        offset: Int,
        limit: Int,
    ): ClientSelectionPage {
        val response = awaitResponse { transport.beginSelectionPageRequest(sessionId, offset, limit) }
        return ClientSelectionPage(
            sessionId = response.session_id.ifEmpty { sessionId },
            choices = response.choices.map { it.toApi() },
            offset = response.offset,
            total = response.total,
            hasMore = response.has_more,
            failureMessage = response.failure.ifEmpty { null },
        )
    }

    override suspend fun removeQueuedTrack(sourceId: String, trackId: String): ClientActionFeedback {
        val response = awaitResponse {
            transport.beginQueueRemoveRequest(sourceId, trackId)
        }
        return response.toActionFeedback()
    }

    override suspend fun submitTrack(track: TrackInfo, mode: TrackAddMode): ClientTrackSubmitResult {
        val response = awaitResponse {
            transport.beginTrackSubmitRequest(track, mode)
        }
        return ClientTrackSubmitResult(
            trackId = response.track_id.ifEmpty { track.id },
            trackTitle = response.track_title.ifEmpty { track.title },
            successMessage = response.success.ifEmpty { null },
            failureMessage = response.failure.ifEmpty { null },
        )
    }

    override suspend fun submitIdentifier(
        identifier: String,
        mode: TrackAddMode,
    ): ClientIdentifierSubmitResult {
        val response = awaitResponse {
            transport.beginIdentifierSubmitRequest(identifier, mode)
        }
        return response.toClientResult()
    }

    override suspend fun submitSelection(
        entry: SelectionEntry,
        mode: TrackAddMode,
    ): ClientSelectionSubmitResult {
        if (entry.isDirectTrack) {
            val response = awaitResponse {
                transport.beginTrackSubmitRequest(entry, mode)
            }
            return ClientSelectionSubmitResult(
                trackId = response.track_id.ifEmpty { entry.directTrackId },
                trackTitle = response.track_title.ifEmpty { entry.title },
                successMessage = response.success.ifEmpty { null },
                failureMessage = response.failure.ifEmpty { null },
            )
        }
        val response = awaitResponse {
            transport.beginSelectionSubmitRequest(entry, mode)
        }
        return response.toClientResult()
    }

    override suspend fun controlPlayback(
        action: PlaybackAction,
        positionMs: Long,
    ): ClientActionFeedback {
        val response = awaitResponse {
            transport.beginPlaybackControlRequest(action.toProto(), positionMs)
        }
        return response.toActionFeedback()
    }

    override suspend fun updateContentFilter(
        target: ContentFilterMutationTarget,
        sourceId: String,
        valueId: String,
        note: String?,
        ban: Boolean,
    ): ClientContentFilterActionResult {
        val response = awaitResponse {
            when (target) {
                ContentFilterMutationTarget.TRACK ->
                    transport.beginContentFilterTrackActionRequest(sourceId, valueId, note, ban)

                ContentFilterMutationTarget.ARTIST ->
                    transport.beginContentFilterArtistActionRequest(sourceId, valueId, note, ban)

                else -> error("Unsupported content filter target: $target")
            }
        }
        return ClientContentFilterActionResult(
            target = when (response.target) {
                ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK -> ContentFilterMutationTarget.TRACK
                ContentFilterTargetProto.CONTENT_FILTER_TARGET_ARTIST -> ContentFilterMutationTarget.ARTIST
            },
            sourceId = response.source_id.ifEmpty { sourceId },
            valueId = response.value_id.ifEmpty { valueId },
            blockedNow = response.blocked_now,
            successMessage = response.success.ifEmpty { null },
            failureMessage = response.failure.ifEmpty { null },
        )
    }

    private suspend fun <T> awaitResponse(begin: () -> Deferred<T>?): T {
        transport.ensureDirectRequestSessionReady()
        val deferred = begin()
            ?: throw ClientRequestException("MoeMusic client request could not be sent.")
        return try {
            withTimeout(requestTimeoutMs.milliseconds) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            deferred.cancelPending("Timed out waiting for MoeMusic server response.", e)
            throw ClientRequestException("Timed out waiting for MoeMusic server response.", e)
        } catch (e: CancellationException) {
            deferred.cancelPending("MoeMusic client request was cancelled.", e)
            throw e
        } catch (e: ClientRequestException) {
            deferred.cancelPending("MoeMusic client request failed.", e)
            throw e
        } catch (e: Exception) {
            deferred.cancelPending("MoeMusic client request failed.", e)
            throw ClientRequestException("MoeMusic client request failed.", e)
        }
    }

    private fun Deferred<*>.cancelPending(message: String, cause: Throwable) {
        val cancellation = CancellationException(message)
        cancellation.initCause(cause)
        cancel(cancellation)
    }

    private fun QueueRemoveResponse.toActionFeedback(): ClientActionFeedback =
        ClientActionFeedback(
            successMessage = null,
            failureMessage = failure.ifEmpty { null },
        )

    private fun PlaybackControlResponse.toActionFeedback(): ClientActionFeedback =
        ClientActionFeedback(
            successMessage = success.ifEmpty { null },
            failureMessage = failure.ifEmpty { null },
        )

    private fun IdentifierSubmitResponse.toClientResult(): ClientIdentifierSubmitResult {
        val mappedChoices = choices.map { it.toApi() }
        val effectiveTotal = if (total == 0 && mappedChoices.isNotEmpty()) {
            (offset + mappedChoices.size).coerceAtLeast(mappedChoices.size)
        } else {
            total
        }
        return ClientIdentifierSubmitResult(
            trackId = track_id.ifEmpty { null },
            trackTitle = track_title.ifEmpty { null },
            choices = mappedChoices,
            offset = offset,
            total = effectiveTotal,
            hasMore = has_more,
            sessionId = session_id.ifEmpty { null },
            successMessage = success.ifEmpty { null },
            failureMessage = failure.ifEmpty { null },
        )
    }

    private fun SelectionSubmitResponse.toClientResult(): ClientSelectionSubmitResult {
        val mappedChoices = choices.map { it.toApi() }
        val effectiveTotal = if (total == 0 && mappedChoices.isNotEmpty()) {
            (offset + mappedChoices.size).coerceAtLeast(mappedChoices.size)
        } else {
            total
        }
        return ClientSelectionSubmitResult(
            trackId = track_id.ifEmpty { null },
            trackTitle = track_title.ifEmpty { null },
            choices = mappedChoices,
            offset = offset,
            total = effectiveTotal,
            hasMore = has_more,
            sessionId = session_id.ifEmpty { null },
            successMessage = success.ifEmpty { null },
            failureMessage = failure.ifEmpty { null },
        )
    }

    private fun PlaybackAction.toProto(): PlaybackControlAction =
        when (this) {
            PlaybackAction.PAUSE -> PlaybackControlAction.PAUSE
            PlaybackAction.RESUME -> PlaybackControlAction.RESUME
            PlaybackAction.SKIP -> PlaybackControlAction.SKIP
            PlaybackAction.STOP -> PlaybackControlAction.STOP
            PlaybackAction.SEEK -> PlaybackControlAction.SEEK
        }
}
