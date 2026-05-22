package org.lolicode.moemusic.core.media.probe

import org.lolicode.moemusic.api.service.IMediaProbeService
import org.lolicode.moemusic.api.service.MediaProbeResult
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.core.media.probe.ServerTrackProber.ProbeResult
import org.lolicode.moemusic.core.media.MediaPolicyProfiles
import org.lolicode.moemusic.core.media.MediaUrlPolicy
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult

/** Server-side implementation of [IMediaProbeService]. */
class MediaProbeServiceImpl : IMediaProbeService {

    override suspend fun probeHttp(url: String, headers: Map<String, String>): UserResult<MediaProbeResult?> {
        return when (val verdict = MediaUrlPolicy.evaluate(url, MediaPolicyProfiles.sharedMediaFirewall())) {
            MediaUrlPolicyResult.Allow -> {
                val probe = ServerTrackProber.probe(PlaybackResource(url = url, headers = headers))
                if (probe == ProbeResult.Unknown) {
                    UserResult.Success(null)
                } else {
                    UserResult.Success(
                        MediaProbeResult(
                            durationMs = probe.durationMs,
                            title = probe.title,
                            artist = probe.artist,
                            artworkUrl = probe.artworkUrl,
                        )
                    )
                }
            }

            is MediaUrlPolicyResult.Reject -> UserResult.Error(verdict.reason)
        }
    }
}
