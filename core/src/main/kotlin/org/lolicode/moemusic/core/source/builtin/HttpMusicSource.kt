package org.lolicode.moemusic.core.source.builtin

import org.lolicode.moemusic.api.IdentifierResolutionResult
import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.toArtistInfos
import org.lolicode.moemusic.core.media.probe.ServerTrackProber
import org.lolicode.moemusic.core.media.probe.ServerTrackProber.ProbeResult
import org.lolicode.moemusic.core.permission.PermissionNodes

/**
 * Builtin [org.lolicode.moemusic.api.MusicSource] for direct HTTP/HTTPS URLs.
 */
object HttpMusicSource : IdentifierResolvableMusicSource {

    override val id: String = "http"
    override val displayName: LocalizedText = LocalizedText.key("source.moemusic.http")
    override val isFallbackResolver: Boolean = true

    override suspend fun resolveIdentifier(identifier: String, submitter: MoeMusicUser?): IdentifierResolutionResult {
        // Per-source permission should be checked in the source itself, not in the command or packet handler. The general PLAY, SEARCH, etc. nodes still apply there.
        if (!identifier.startsWith("http://") && !identifier.startsWith("https://")) {
            return IdentifierResolutionResult.Pass
        }

        if (submitter != null &&
            !submitter.hasPermission(PermissionNodes.SOURCE_HTTP_SUBMIT.id, PermissionNodes.SOURCE_HTTP_SUBMIT.defaultLevel())
        ) {
            return IdentifierResolutionResult.Pass
        }

        val probe = ServerTrackProber.probe(identifier)
        if (probe == ProbeResult.Unknown) {
            return IdentifierResolutionResult.Pass
        }

        val filename = identifier.substringAfterLast('/').ifBlank { identifier }
        val srcId = id
        return IdentifierResolutionResult.Resolved(
            TrackInfo(
                id = identifier,
                title = probe.title.ifBlank { filename },
                artists = listOf(probe.artist.ifBlank { "HTTP Source" }).toArtistInfos(),
                durationMs = probe.durationMs,
            ) {
                coverUrl = probe.artworkUrl
                sourceId = srcId
            },
        )
    }

    override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResource {
        val url = requireNotNull(track.id.takeIf { it.isNotBlank() }) {
            "HttpMusicSource: track id is blank for track '${track.title}'"
        }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "HttpMusicSource only supports http:// and https:// URLs, got: $url"
        }
        return PlaybackResource(url)
    }
}
