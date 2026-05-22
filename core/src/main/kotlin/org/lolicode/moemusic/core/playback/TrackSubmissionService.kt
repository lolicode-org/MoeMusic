package org.lolicode.moemusic.core.playback

import org.lolicode.moemusic.api.FilterBlockException
import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.api.service.ITrackSubmissionService
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.service.SelectionSubmitOutcome
import org.lolicode.moemusic.api.service.SubmitOutcome
import org.lolicode.moemusic.api.TrackUnavailableException
import org.lolicode.moemusic.api.UserFacingException
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.event.OnTrackSubmitted
import org.lolicode.moemusic.api.model.SelectionResolveResult
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.isAvailable
import org.lolicode.moemusic.api.model.mergePreservingRuntimeMetadata
import org.lolicode.moemusic.api.model.unavailabilityMessage
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.permission.PermissionNodes.CONTENT_FILTER_BYPASS
import org.lolicode.moemusic.core.permission.PermissionNodes.DURATION_POLICY_BYPASS
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.source.builtin.HttpMusicSource

/**
 * Shared submission pipeline: resolves authoritative metadata, stamps submitter, validates
 * availability, then enqueues.
 *
 * Both the packet handler (TRACK_SUBMIT / IDENTIFIER_SUBMIT) and command paths delegate here
 * to avoid logic duplication. The service holds no mutable state.
 *
 * Duration is expected to be provided by the source's [IdentifierResolvableMusicSource.resolveIdentifier] or
 * [MusicSource.getTrackInfo] call. Duration probing has been intentionally removed from this
 * layer: [HttpMusicSource] probes duration inside
 * [IdentifierResolvableMusicSource.resolveIdentifier] before constructing
 * [TrackInfo], so by the time a track reaches here its duration is already set.
 *
 * Exposed to plugins as [ITrackSubmissionService] via
 * [org.lolicode.moemusic.api.plugin.ServerRuntimeContext.trackSubmissionService] and
 * [org.lolicode.moemusic.api.plugin.ServerSessionContext.trackSubmissionService].
 */
class TrackSubmissionService(
    private val controller: ServerPlaybackController,
) : ITrackSubmissionService {

    override suspend fun submitBySourceAndId(
        sourceId: String,
        trackId: String,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SubmitOutcome {
        val source = PluginManager.musicSourceSnapshot().firstOrNull { it.id == sourceId }
            ?: throw UserFacingException(LocalizedText.key("error.moemusic.source.not_found", sourceId))

        val rawTrack = when (val result = source.getTrackInfo(trackId, submitter)) {
            is UserResult.Success -> result.value
                ?: throw UserFacingException(LocalizedText.key("error.moemusic.track.not_found", trackId))
            is UserResult.Error -> throw UserFacingException(result.message)
        }

        return finalizeAndEnqueue(rawTrack, submitter, mode)
    }

    override suspend fun submitBySelection(
        sourceId: String,
        selectionId: String,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SelectionSubmitOutcome {
        val source = PluginManager.musicSourceSnapshot().firstOrNull { it.id == sourceId }
            ?: throw UserFacingException(LocalizedText.key("error.moemusic.source.not_found", sourceId))

        val selection = when (val result = source.resolveSelection(selectionId, submitter)) {
            is UserResult.Success -> result.value
                ?: throw UserFacingException(LocalizedText.key("error.moemusic.selection.not_found"))
            is UserResult.Error -> throw UserFacingException(result.message)
        }

        return when (selection) {
            is SelectionResolveResult.Track -> {
                val outcome = finalizeAndEnqueue(selection.track, submitter, mode)
                SelectionSubmitOutcome.Submitted(outcome.track, outcome.result)
            }

            is SelectionResolveResult.Choices ->
                // Return raw entries without filter annotation; ServerPacketHandlers will apply
                // per-sender verdict checks at the wire boundary.
                SelectionSubmitOutcome.Choices(
                    entries = selection.entries
                        .map { entry -> entry.copy(sourceId = entry.sourceId ?: source.id) },
                    sourceId = source.id,
                )
        }
    }

    override suspend fun submitResolved(
        track: TrackInfo,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SubmitOutcome {
        val authoritative = authoritativeTrack(track, submitter)
        return finalizeAndEnqueue(authoritative, submitter, mode)
    }

    override suspend fun submitResolvedFromSource(
        track: TrackInfo,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SubmitOutcome =
        finalizeAndEnqueue(track, submitter, mode)

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Re-fetch authoritative metadata from the owning source when possible, forwarding
     * [submitter] so the source can apply per-player access control.
     */
    private suspend fun authoritativeTrack(track: TrackInfo, submitter: MoeMusicUser?): TrackInfo {
        if (track.sourceId == null || track.id.isBlank()) return track

        val source = PluginManager.musicSourceSnapshot().firstOrNull { it.id == track.sourceId } ?: return track
        return when (val result = source.getTrackInfo(track.id, submitter)) {
            is UserResult.Success -> result.value?.let(track::mergePreservingRuntimeMetadata) ?: track
            is UserResult.Error -> throw UserFacingException(result.message)
        }
    }

    /**
     * Stamp [submitter], validate availability (both inherent and content-filter), then enqueue via
     * [ServerPlaybackController.submitTrack].
     *
     * Filter enforcement is skipped when the submitter holds the `moemusic.privilege.bypass.filter`
     * permission.  Inherent source unavailability (set by the source itself in
     * [TrackInfo.unavailableReason]) is always enforced regardless of bypass privilege.
     */
    private suspend fun finalizeAndEnqueue(
        track: TrackInfo,
        submitter: MoeMusicUser?,
        mode: TrackAddMode,
    ): SubmitOutcome {
        val stamped = track.copy(
            submittedByUserName = track.submittedByUserName ?: submitter?.displayName,
        )

        enforceDurationPolicy(stamped, submitter)

        // Content-filter check — skipped for bypass-privileged submitters.
        if (!hasFilterBypass(submitter)) {
            when (val verdict = ContentFilterRuntime.trackFilterVerdict(stamped)) {
                is FilterVerdict.Reject -> throw FilterBlockException(verdict.reason)
                FilterVerdict.Allow -> Unit
            }
        }

        // Inherent source unavailability is always enforced.
        if (!stamped.isAvailable) {
            throw TrackUnavailableException(stamped.unavailabilityMessage())
        }

        val result = controller.submitTrack(stamped, submitter?.id, mode)
        CoreEvents.bus.fire(
            OnTrackSubmitted(
                track = stamped,
                submitter = submitter,
                mode = mode,
                result = result,
            )
        )
        return SubmitOutcome(stamped, result)
    }

    /**
     * Returns true when [submitter] holds the content-filter bypass privilege.
     *
     * Null submitter (server-internal submission) never bypasses the filter: internal submissions
     * are not player-initiated and should still be subject to admin policy.
     *
     * Uses [MoeMusicUser.hasPermission] — the platform-agnostic API — so `:core` stays free of
     * `:platform-common` dependencies.
     */
    private fun hasFilterBypass(submitter: MoeMusicUser?): Boolean {
        if (submitter == null) return false
        return submitter.hasPermission(CONTENT_FILTER_BYPASS.id, CONTENT_FILTER_BYPASS.defaultLevel())
    }

    private fun enforceDurationPolicy(track: TrackInfo, submitter: MoeMusicUser?) {
        if (submitter == null || hasDurationPolicyBypass(submitter)) return

        val durationMs = track.durationMs
        if (durationMs <= 0L) {
            throw UserFacingException(LocalizedText.key("error.moemusic.track.duration_unknown"))
        }

        val maxSeconds = ModConfigManager.config.media.maxPlayerTrackDurationSeconds
        val maxMs = maxSeconds * 1_000L
        if (durationMs > maxMs) {
            throw UserFacingException(
                LocalizedText.key("error.moemusic.track.duration_too_long", maxSeconds),
            )
        }
    }

    private fun hasDurationPolicyBypass(submitter: MoeMusicUser): Boolean =
        submitter.hasPermission(DURATION_POLICY_BYPASS.id, DURATION_POLICY_BYPASS.defaultLevel())
}
