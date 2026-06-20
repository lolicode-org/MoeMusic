package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.UserFacingException
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.ServerSessionContext

/**
 * Outcome of a successful submission via [ITrackSubmissionService].
 *
 * Read-only host-produced type. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 *
 * Contains the [TrackInfo] accepted by the server after submitter stamping and validation, plus
 * the [TrackAddResult] indicating queue placement. Calls that perform an authoritative metadata
 * refresh return the refreshed track; trusted source-resolved calls return the source-provided
 * track after runtime annotations are applied.
 */
public data class SubmitOutcome(
    /** The final [TrackInfo] that was actually enqueued or played. */
    val track: TrackInfo,
    /** How the track was placed in the queue. */
    val result: TrackAddResult,
)

/**
 * Outcome of submitting a previously surfaced selection row.
 *
 * A selection may already point at a direct track, or it may need one more user-visible narrowing
 * step before a minimum playable track can be enqueued.
 */
public sealed interface SelectionSubmitOutcome {
    /**
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     */
    public data class Submitted(
        val track: TrackInfo,
        val result: TrackAddResult,
    ) : SelectionSubmitOutcome

    /**
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     */
    public data class Choices(
        val entries: List<SelectionEntry>,
        val sourceId: String,
    ) : SelectionSubmitOutcome
}

/**
 * Centralised raw track submission pipeline exposed to plugins via [ServerRuntimeContext] and
 * [ServerSessionContext].
 *
 * Handles: source metadata trust boundaries, submitter stamping, availability validation, opaque
 * selection resolution, and enqueue.
 *
 * Shared permission and rate-limit checks are intentionally not applied here. Plugins that want
 * MoeMusic's checked user-behalf path should prefer [org.lolicode.moemusic.api.service.IUserActionService].
 */
public interface ITrackSubmissionService {

    /**
     * Submit a track by its (sourceId, trackId) pair.
     *
     * Performs an authoritative [MusicSource.getTrackInfo] lookup (forwarding [submitter] for
     * per-user access control), stamps the submitter, validates availability, then enqueues.
     *
     * @param sourceId  The [MusicSource.id] of the source that owns the track.
     * @param trackId   The source-local track identifier ([TrackInfo.id]).
     * @param submitter The user submitting the track, or `null` for server-internal submissions.
     * @param mode      Enqueue mode.
     * @throws UserFacingException if the source is not found, the track does not exist, or is
     *   unavailable.
     */
    public suspend fun submitBySourceAndId(
        sourceId: String,
        trackId: String,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): SubmitOutcome

    /**
     * Submit a previously surfaced source-local [selectionId].
     *
     * The source may resolve the selection directly to a track, or may return a further list of
     * [SelectionEntry] choices when the selected row still represents a container or intermediate
     * object.
     *
     * @param sourceId    The [MusicSource.id] that owns the selection.
     * @param selectionId Opaque source-local identifier previously surfaced in search results or
     *   identifier-resolution choices.
     * @param submitter   The user submitting the selection, or `null` for server-internal use.
     * @param mode        Enqueue mode.
     * @throws UserFacingException if the source is not found, the selection no longer exists, or
     *   the source rejects it with a user-facing error.
     */
    public suspend fun submitBySelection(
        sourceId: String,
        selectionId: String,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): SelectionSubmitOutcome

    /**
     * Submit a track object supplied by a caller outside the trusted source-resolution pipeline.
     *
     * Treats [track] as caller-supplied metadata. Re-fetches authoritative metadata from the
     * owning [MusicSource.getTrackInfo] when possible, stamps submitter, validates availability,
     * then enqueues. Use [submitResolvedFromSource] instead when the track has just been returned
     * directly by the owning source inside the same server-side flow.
     *
     * @param track     The partially-resolved track.
     * @param submitter The user submitting the track, or `null` for server-internal use.
     * @param mode      Enqueue mode.
     */
    public suspend fun submitResolved(
        track: TrackInfo,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): SubmitOutcome

    /**
     * Submit a track that was just resolved by its owning [MusicSource].
     *
     * This path intentionally skips the extra [MusicSource.getTrackInfo] refresh because the
     * metadata is already source-owned and has not crossed an untrusted client boundary. It still
     * stamps submitter, validates duration, content filters, and inherent availability, then
     * enqueues. Use this from source-side identifier or selection resolution flows; use
     * [submitResolved] for arbitrary caller-provided [TrackInfo].
     *
     * @param track     The source-resolved track.
     * @param submitter The user submitting the track, or `null` for server-internal use.
     * @param mode      Enqueue mode.
     */
    public suspend fun submitResolvedFromSource(
        track: TrackInfo,
        submitter: MoeMusicUser? = null,
        mode: TrackAddMode = TrackAddMode.NORMAL,
    ): SubmitOutcome
}
