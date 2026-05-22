package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.ServerSessionContext

/**
 * Outcome of an identifier resolution attempt via [IIdentifierResolutionService.resolve].
 *
 * Returned by [IIdentifierResolutionService] after querying all registered
 * [IdentifierResolvableMusicSource] implementations.
 */
public sealed interface IdentifierResolutionOutcome {
    /**
     * A source successfully interpreted the identifier and produced a [TrackInfo].
     *
     * @param track    The resolved track. [TrackInfo.sourceId] is guaranteed non-null.
     * @param sourceId The [MusicSource.id] of the source that resolved the identifier.
     */
    public data class Resolved(
        val track: TrackInfo,
        val sourceId: String,
    ) : IdentifierResolutionOutcome

    /**
     * A source recognized the identifier but it still needs one or more user selections before a
     * minimum playable track can be submitted.
     *
     * Each entry should be fed back into [ITrackSubmissionService.submitBySelection].
     */
    public data class Choices(
        val entries: List<SelectionEntry>,
        val sourceId: String,
    ) : IdentifierResolutionOutcome

    /**
     * A source recognized the identifier as belonging to it but rejected it (e.g. permission
     * denied, content filtered). The resolution process stops; [message] should be shown to the
     * player.
     *
     * @param message  Localized reason for the rejection.
     * @param sourceId The [MusicSource.id] of the blocking source.
     */
    public data class Blocked(
        val message: LocalizedText,
        val sourceId: String,
    ) : IdentifierResolutionOutcome

    /** No registered source could recognize the identifier. */
    public data object NotFound : IdentifierResolutionOutcome
}

/**
 * Raw identifier resolution service exposed to plugins via [ServerRuntimeContext] and
 * [ServerSessionContext].
 *
 * Resolves a raw user-supplied identifier or share-link to either a direct [TrackInfo] or a set
 * of source-owned [SelectionEntry] choices by querying all registered
 * [IdentifierResolvableMusicSource] implementations using a two-pass strategy
 * (specific sources first, fallback sources second).
 */
public interface IIdentifierResolutionService {

    /**
     * Resolve a raw [identifier] to either a direct track or a further choice list.
     *
     * @param identifier The raw string submitted by the user (URL, share link, native ID, …).
     * @param submitter  The user who submitted the identifier, or `null` for server-internal calls.
     *                   Forwarded to each source's [IdentifierResolvableMusicSource.resolveIdentifier]
     *                   so sources can gate on permissions before any network I/O.
     */
    public suspend fun resolve(
        identifier: String,
        submitter: MoeMusicUser? = null,
    ): IdentifierResolutionOutcome
}
