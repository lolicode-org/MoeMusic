package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.service.ITrackSubmissionService

/**
 * How a submitted track should be placed relative to the current queue and autoplay.
 *
 * This is an input enum chosen by the caller. It is **non-exhaustive across API versions**: future
 * API versions may add modes, so consumers that branch on it should always include an `else`
 * branch rather than assuming the set is closed.
 */
public enum class TrackAddMode {
    /** Append to the user queue. If an autoplay track is currently playing,
     * the submitted track plays next after that track finishes. */
    NORMAL,

    /** Like [NORMAL] but skips any currently-playing autoplay track so the user queue takes over
     * immediately. No-op when a user track is already playing. */
    SKIP_AUTOPLAY,

    /** Interrupt whatever is currently playing and start this track immediately. */
    PLAY_NOW,
}

/**
 * Outcome of a successful [ITrackSubmissionService.submitBySourceAndId] or
 * [ITrackSubmissionService.submitResolved] / [ITrackSubmissionService.submitResolvedFromSource]
 * call.
 *
 * This is an **open** value set, not an enum: future API versions may introduce new results, so
 * `when` over a [TrackAddResult] cannot be exhaustive and must always include an `else`/unknown
 * branch. Compare against the [Companion] constants or [id]. Use [of] to obtain a value for an
 * id that may not be known to this build (forward-compatible parsing).
 */
@JvmInline
public value class TrackAddResult private constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** Track was added to the queue; nothing was interrupted. */
        public val QUEUED: TrackAddResult = TrackAddResult("QUEUED")

        /** Track was added to the queue and an autoplay track was interrupted. */
        public val INTERRUPTING_AUTOPLAY: TrackAddResult = TrackAddResult("INTERRUPTING_AUTOPLAY")

        /** Track started playing immediately (submitted with [TrackAddMode.PLAY_NOW]). */
        public val PLAYING_NOW: TrackAddResult = TrackAddResult("PLAYING_NOW")

        /** Values known to this build. New values may appear at runtime; always handle `else`. */
        public val entries: List<TrackAddResult> = listOf(QUEUED, INTERRUPTING_AUTOPLAY, PLAYING_NOW)

        /** Returns the value for [id], creating an unknown-but-valid value when not recognized. */
        public fun of(id: String): TrackAddResult = TrackAddResult(id)
    }
}
