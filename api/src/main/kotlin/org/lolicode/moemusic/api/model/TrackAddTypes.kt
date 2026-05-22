package org.lolicode.moemusic.api.model

import org.lolicode.moemusic.api.service.ITrackSubmissionService

/**
 * How a submitted track should be placed relative to the current queue and autoplay.
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
 */
public enum class TrackAddResult {
    /** Track was added to the queue; nothing was interrupted. */
    QUEUED,

    /** Track was added to the queue and an autoplay track was interrupted. */
    INTERRUPTING_AUTOPLAY,

    /** Track started playing immediately (submitted with [TrackAddMode.PLAY_NOW]). */
    PLAYING_NOW,
}
