package org.lolicode.moemusic.core.user

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.UserFacingException
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.permission.MoeMusicPermission
import org.lolicode.moemusic.api.service.*
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import java.util.UUID

/** Shared checked server action path used by plugins, commands, and packet handlers. */
class UserActionServiceImpl(
    private val permissionService: IPermissionService,
    private val requestRateLimiter: RequestRateLimiter,
    private val searchService: ISearchService,
    private val identifierResolutionService: IIdentifierResolutionService,
    private val trackSubmissionService: ITrackSubmissionService,
    private val playbackController: IPlaybackController,
    private val voteToSkipHandler: (UUID) -> PlaybackActionOutcome = {
        PlaybackActionOutcome(failure = LocalizedText.key("error.moemusic.permission_denied"))
    },
) : IUserActionService {

    override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): SearchResult {
        permissionService.require(MoeMusicPermission.SEARCH, submitter)
        checkSearchRateLimit(submitter)
        return searchService.search(query, submitter)
    }

    override suspend fun submitBySourceAndId(
        sourceId: String,
        trackId: String,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SubmitOutcome {
        requireSubmitAccess(submitter, mode)
        checkSubmitRateLimit(submitter)
        return trackSubmissionService.submitBySourceAndId(sourceId, trackId, submitter, mode)
    }

    override suspend fun submitBySelection(
        sourceId: String,
        selectionId: String,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SelectionSubmitOutcome {
        requireSubmitAccess(submitter, mode)
        checkSubmitRateLimit(submitter)
        return trackSubmissionService.submitBySelection(sourceId, selectionId, submitter, mode)
    }

    override suspend fun submitResolved(
        track: TrackInfo,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SubmitOutcome {
        requireSubmitAccess(submitter, mode)
        checkSubmitRateLimit(submitter)
        return trackSubmissionService.submitResolved(track, submitter, mode)
    }

    override suspend fun submitIdentifier(
        identifier: String,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): IdentifierSubmitOutcome {
        requireSubmitAccess(submitter, mode)
        checkSubmitRateLimit(submitter)
        return when (val outcome = identifierResolutionService.resolve(identifier, submitter)) {
            is IdentifierResolutionOutcome.Blocked -> throw UserFacingException(outcome.message)
            IdentifierResolutionOutcome.NotFound ->
                throw UserFacingException(LocalizedText.key("error.moemusic.identifier.not_found"))

            is IdentifierResolutionOutcome.Resolved -> {
                val submitted = trackSubmissionService.submitResolvedFromSource(outcome.track, submitter, mode)
                IdentifierSubmitOutcome.Submitted(submitted.track, submitted.result)
            }

            is IdentifierResolutionOutcome.Choices ->
                IdentifierSubmitOutcome.Choices(outcome.entries, outcome.sourceId)
        }
    }

    override fun removeQueuedTrack(
        sourceId: String,
        trackId: String,
        requester: MoeMusicUser?,
    ): QueueRemoveOutcome = removeQueuedTrackInternal(sourceId, trackId, null, requester)

    override fun removeQueuedTrackByEntryId(
        sourceId: String,
        trackId: String,
        queueEntryId: String?,
        requester: MoeMusicUser?,
    ): QueueRemoveOutcome = removeQueuedTrackInternal(sourceId, trackId, queueEntryId, requester)

    private fun removeQueuedTrackInternal(
        sourceId: String,
        trackId: String,
        queueEntryId: String?,
        requester: MoeMusicUser?,
    ): QueueRemoveOutcome {
        val bypassOwnership = requester == null || permissionService.has(MoeMusicPermission.QUEUE_CONTROL, requester)
        return when (playbackController.removeQueuedTrackByEntryId(sourceId, trackId, queueEntryId, requester, bypassOwnership)) {
            QueueRemoveResult.REMOVED -> QueueRemoveOutcome(QueueRemoveResult.REMOVED)
            QueueRemoveResult.NOT_FOUND ->
                QueueRemoveOutcome(
                    QueueRemoveResult.NOT_FOUND,
                    LocalizedText.key("error.moemusic.queue.track_not_found")
                )

            QueueRemoveResult.FORBIDDEN ->
                QueueRemoveOutcome(
                    QueueRemoveResult.FORBIDDEN,
                    LocalizedText.key("error.moemusic.queue.remove_forbidden")
                )

            else ->
                QueueRemoveOutcome(
                    QueueRemoveResult.UNKNOWN,
                    LocalizedText.key("error.moemusic.internal")
                )
        }
    }

    override fun clearQueue(
        targetUserId: UUID?,
        targetUserName: String?,
        requester: MoeMusicUser?,
    ): QueueClearOutcome {
        if (targetUserId == null && targetUserName != null && targetUserName.isBlank()) {
            return QueueClearOutcome(0, LocalizedText.key("error.moemusic.queue.clear_invalid_target"))
        }
        val normalizedTargetName = targetUserName?.trim()?.ifEmpty { null }
        val isTargetingSelf = (targetUserId != null && requester != null && targetUserId == requester.id) ||
                (normalizedTargetName != null && requester != null && normalizedTargetName.equals(requester.displayName, ignoreCase = true))

        val hasQueueControl = requester == null || permissionService.has(MoeMusicPermission.QUEUE_CONTROL, requester)
        if (!isTargetingSelf && !hasQueueControl) {
            return QueueClearOutcome(0, LocalizedText.key("error.moemusic.permission.queue_control"))
        }

        return playbackController.clearQueue(
            targetUserId = targetUserId,
            targetUserName = normalizedTargetName,
            requester = requester,
            bypassOwnership = hasQueueControl,
        )
    }

    override fun controlPlayback(
        action: PlaybackAction,
        requester: MoeMusicUser?,
        positionMs: Long,
    ): PlaybackActionOutcome {
        if (requester == null) {
            performPlaybackAction(action, positionMs)
            return PlaybackActionOutcome()
        }

        return when (action) {
            PlaybackAction.PAUSE -> {
                permissionService.require(MoeMusicPermission.PLAYBACK_CONTROL, requester)
                playbackController.pause()
                PlaybackActionOutcome()
            }

            PlaybackAction.RESUME -> {
                permissionService.require(MoeMusicPermission.PLAYBACK_CONTROL, requester)
                playbackController.resume()
                PlaybackActionOutcome()
            }

            PlaybackAction.STOP -> {
                permissionService.require(MoeMusicPermission.PLAYBACK_CONTROL, requester)
                playbackController.stop()
                PlaybackActionOutcome()
            }

            PlaybackAction.SEEK -> {
                permissionService.require(MoeMusicPermission.PLAYBACK_CONTROL, requester)
                playbackController.seek(positionMs)
                PlaybackActionOutcome()
            }

            PlaybackAction.SKIP -> {
                if (permissionService.has(MoeMusicPermission.QUEUE_CONTROL, requester)) {
                    playbackController.skip()
                    PlaybackActionOutcome()
                } else {
                    permissionService.require(MoeMusicPermission.VOTE, requester)
                    voteToSkipHandler(requester.id)
                }
            }
        }
    }

    private fun performPlaybackAction(action: PlaybackAction, positionMs: Long) {
        when (action) {
            PlaybackAction.PAUSE -> playbackController.pause()
            PlaybackAction.RESUME -> playbackController.resume()
            PlaybackAction.SKIP -> playbackController.skip()
            PlaybackAction.STOP -> playbackController.stop()
            PlaybackAction.SEEK -> playbackController.seek(positionMs)
        }
    }

    private fun requireSubmitAccess(submitter: MoeMusicUser?, mode: TrackAddMode) {
        permissionService.require(MoeMusicPermission.SUBMIT, submitter)
        when (mode) {
            TrackAddMode.NORMAL -> Unit
            TrackAddMode.SKIP_AUTOPLAY -> permissionService.require(MoeMusicPermission.SUBMIT_SKIP_AUTOPLAY, submitter)
            TrackAddMode.PLAY_NOW -> permissionService.require(MoeMusicPermission.QUEUE_CONTROL, submitter)
        }
    }

    private fun checkSearchRateLimit(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        requestRateLimiter.checkSearch(user.id.toString(), bypass = hasRateLimitBypass(user))
    }

    private fun checkSubmitRateLimit(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        requestRateLimiter.checkSubmit(user.id.toString(), bypass = hasRateLimitBypass(user))
    }

    private fun hasRateLimitBypass(user: MoeMusicUser): Boolean =
        permissionService.has(MoeMusicPermission.RATE_LIMIT_BYPASS, user)
}
