package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.SelectionEntry

/** Outcome of resolving and submitting a raw identifier through [IUserActionService]. */
public sealed interface IdentifierSubmitOutcome {
    public data class Submitted(
        val track: TrackInfo,
        val result: TrackAddResult,
    ) : IdentifierSubmitOutcome

    public data class Choices(
        val entries: List<SelectionEntry>,
        val sourceId: String,
    ) : IdentifierSubmitOutcome
}

/** Result of attempting to remove a queued track. */
public enum class QueueRemoveResult {
    REMOVED,
    NOT_FOUND,
    FORBIDDEN,
}

/** Queue removal outcome from [IUserActionService.removeQueuedTrack]. */
public data class QueueRemoveOutcome(
    val result: QueueRemoveResult,
    val failure: LocalizedText? = null,
)

/** Public playback actions accepted by [IUserActionService] and [org.lolicode.moemusic.api.client.IClientRequestService]. */
public enum class PlaybackAction {
    PAUSE,
    RESUME,
    SKIP,
    STOP,
    SEEK,
}

/** Optional user-visible feedback produced by a playback action. */
public data class PlaybackActionOutcome(
    val success: LocalizedText? = null,
    val failure: LocalizedText? = null,
)

/**
 * Checked common path for actions performed on a user's behalf.
 *
 * Implementations apply MoeMusic's shared permission and rate-limit rules before delegating to
 * the raw services.
 *
 * Current shared permission model:
 * - normal submit -> [org.lolicode.moemusic.api.permission.MoeMusicPermission.SUBMIT]
 * - `SKIP_AUTOPLAY` submit -> `SUBMIT` + `SUBMIT_SKIP_AUTOPLAY`
 * - `PLAY_NOW` submit -> `SUBMIT` + `QUEUE_CONTROL`
 * - direct skip / queue editing -> [org.lolicode.moemusic.api.permission.MoeMusicPermission.QUEUE_CONTROL]
 * - vote-skip fallback -> [org.lolicode.moemusic.api.permission.MoeMusicPermission.VOTE]
 * - pause/resume/seek/stop -> [org.lolicode.moemusic.api.permission.MoeMusicPermission.PLAYBACK_CONTROL]
 */
public interface IUserActionService {

    /** Checked search path for a user-visible query, including server-side query content filtering. */
    public suspend fun search(
        query: SearchQuery,
        submitter: MoeMusicUser? = null,
    ): SearchResult

    /** Checked submission by stable `(sourceId, trackId)` identity. */
    public suspend fun submitBySourceAndId(
        sourceId: String,
        trackId: String,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): SubmitOutcome

    /** Checked submission of a previously surfaced source-local selection. */
    public suspend fun submitBySelection(
        sourceId: String,
        selectionId: String,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): SelectionSubmitOutcome

    /** Checked submission of a resolved track. */
    public suspend fun submitResolved(
        track: TrackInfo,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): SubmitOutcome

    /** Checked identifier/share-link resolution followed by submission when possible. */
    public suspend fun submitIdentifier(
        identifier: String,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): IdentifierSubmitOutcome

    /** Checked queue removal path. */
    public fun removeQueuedTrack(
        sourceId: String,
        trackId: String,
        requester: MoeMusicUser? = null,
    ): QueueRemoveOutcome

    /** Checked playback control path. */
    public fun controlPlayback(
        action: PlaybackAction,
        requester: MoeMusicUser? = null,
        positionMs: Long = 0L,
    ): PlaybackActionOutcome
}
