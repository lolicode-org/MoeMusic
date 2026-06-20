package org.lolicode.moemusic.core.source

import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.service.IdentifierResolutionOutcome
import org.lolicode.moemusic.api.IdentifierResolutionResult
import org.lolicode.moemusic.api.service.IIdentifierResolutionService
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.event.OnIdentifierResolved
import org.lolicode.moemusic.api.model.copy
import org.lolicode.moemusic.api.model.isAvailable
import org.lolicode.moemusic.api.model.unavailabilityMessage
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.source.builtin.HttpMusicSource

class IdentifierResolutionService(
    private val sources: List<MusicSource>,
) : IIdentifierResolutionService {
    fun sourceSnapshot(): List<MusicSource> = ArrayList(sources)

    fun orderedSources(defaultSourceId: String = ModConfigManager.config.defaultSourceId): List<MusicSource> {
        val snapshot = sourceSnapshot()
        if (defaultSourceId.isBlank()) return snapshot
        val preferred = snapshot.firstOrNull { it.id == defaultSourceId } ?: return snapshot
        return buildList(snapshot.size) {
            add(preferred)
            snapshot.forEach { source ->
                if (source.id != preferred.id) add(source)
            }
        }
    }

    /**
     * Resolve a raw [identifier] to a track.
     *
     * Uses a two-pass strategy:
     * 1. All [IdentifierResolvableMusicSource] implementations where [IdentifierResolvableMusicSource.isFallbackResolver]
     *    is `false` are tried first (platform-specific sources that know their own URL patterns).
     * 2. Only if every specific source returns [IdentifierResolutionResult.Pass], fallback sources
     *    (e.g. [HttpMusicSource]) are tried.
     *
     * This ensures that platform share-links or CDN URLs are always claimed by the appropriate
     * source before the generic HTTP resolver gets a chance to blindly probe them.
     *
     * @param submitter The player who submitted the identifier, or `null` for server-internal calls.
     *                  Forwarded verbatim to [IdentifierResolvableMusicSource.resolveIdentifier] so
     *                  sources can perform permission checks before any network I/O.
     */
    override suspend fun resolve(identifier: String, submitter: MoeMusicUser?): IdentifierResolutionOutcome {
        val input = identifier.trim()
        if (input.isBlank()) return complete(input, submitter, IdentifierResolutionOutcome.NotFound)

        val resolvers = orderedSources().filterIsInstance<IdentifierResolvableMusicSource>()
        val (specific, fallback) = resolvers.partition { !it.isFallbackResolver }

        for (pass in listOf(specific, fallback)) {
            for (source in pass) {
                when (val result = source.resolveIdentifier(input, submitter)) {
                    IdentifierResolutionResult.Pass -> Unit
                    is IdentifierResolutionResult.Resolved -> {
                        val track = result.track.copy { this.sourceId = result.track.sourceId ?: source.id }
                        // Inherent source unavailability (set by the source itself) is
                        // enforced here so callers see a Blocked outcome directly.
                        // Content-filter enforcement is deferred to TrackSubmissionService
                        // where the submitter identity and bypass privilege are in scope.
                        if (!track.isAvailable) {
                            return complete(
                                input,
                                submitter,
                                IdentifierResolutionOutcome.Blocked(
                                message = track.unavailabilityMessage(),
                                sourceId = source.id,
                            )
                            )
                        }
                        return complete(
                            input,
                            submitter,
                            IdentifierResolutionOutcome.Resolved(
                                track = track,
                                sourceId = source.id,
                            )
                        )
                    }
                    is IdentifierResolutionResult.Choices -> {
                        // Return raw entries; ServerPacketHandlers applies per-sender filter verdicts.
                        return complete(
                            input,
                            submitter,
                            IdentifierResolutionOutcome.Choices(
                                entries = result.entries
                                    .map { entry -> entry.copy { this.sourceId = entry.sourceId ?: source.id } },
                                sourceId = source.id,
                            )
                        )
                    }
                    is IdentifierResolutionResult.Blocked -> {
                        return complete(
                            input,
                            submitter,
                            IdentifierResolutionOutcome.Blocked(
                                message = result.message,
                                sourceId = source.id,
                            )
                        )
                    }
                }
            }
        }

        return complete(input, submitter, IdentifierResolutionOutcome.NotFound)
    }

    private fun complete(
        identifier: String,
        submitter: MoeMusicUser?,
        outcome: IdentifierResolutionOutcome,
    ): IdentifierResolutionOutcome {
        CoreEvents.bus.fire(
            OnIdentifierResolved(
                identifier = identifier,
                submitter = submitter,
                outcome = outcome,
            )
        )
        return outcome
    }
}
