package org.lolicode.moemusic.api.model

/**
 * Metric summary of active tracks submitted by a specific user:
 * queued pending tracks plus the currently playing track (if submitted by that user).
 *
 * @property count Total number of matching tracks.
 * @property totalDurationMs Sum of durations for matching tracks in milliseconds (tracks with unknown/negative duration contribute 0).
 */
public data class UserTrackMetrics(
    val count: Int,
    val totalDurationMs: Long,
)
