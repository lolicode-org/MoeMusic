package org.lolicode.moemusic.api.permission

/**
 * Built-in MoeMusic permission nodes understood by [org.lolicode.moemusic.api.service.IPermissionService].
 *
 * Plugins may continue to use [org.lolicode.moemusic.api.MoeMusicUser.hasPermission] directly for custom permission nodes that
 * MoeMusic does not own. Source-private built-in nodes (for example the builtin HTTP source's
 * direct-URL gate) are intentionally excluded from this enum.
 *
 * This enum is **non-exhaustive across API versions**: future versions may add nodes. Plugins
 * typically reference individual constants explicitly, but any `when` over a [MoeMusicPermission]
 * must include an `else` branch and must not assume the set is closed.
 */
public enum class MoeMusicPermission(
    public val nodeId: String,
) {
    /** Basic user-driven track submission / queueing. */
    SUBMIT("moemusic.common.submit"),

    /** Submission mode that interrupts only the current autoplay track. */
    SUBMIT_SKIP_AUTOPLAY("moemusic.common.submit.skip_autoplay"),

    /** Vote-based skip participation. */
    VOTE("moemusic.common.vote"),

    /** Read the current user queue. */
    QUEUE_VIEW("moemusic.common.view_queue"),

    /** User-visible source search. */
    SEARCH("moemusic.common.search"),

    /** Privileged queue/order control such as direct skip, play-now, and queue editing. */
    QUEUE_CONTROL("moemusic.moderation.queue_control"),

    /** Pause/resume/seek/stop style playback control. */
    PLAYBACK_CONTROL("moemusic.moderation.playback_control"),

    /** Manage shared content-filter rules and view full rule-detail failures. */
    CONTENT_FILTER_MANAGE("moemusic.moderation.filter_manage"),

    /** Bypass shared content-filter enforcement at the submission gate. */
    CONTENT_FILTER_BYPASS("moemusic.privilege.bypass.filter"),

    /** Bypass max-duration / unknown-duration submission policy. */
    DURATION_POLICY_BYPASS("moemusic.privilege.bypass.duration_policy"),

    /** Bypass the shared pre-I/O per-player request budget. */
    RATE_LIMIT_BYPASS("moemusic.privilege.bypass.rate_limit"),

    /** Bypass the duplicate-track guard, allowing a track already in the queue or playing to be queued again. */
    SUBMIT_DUPLICATE("moemusic.privilege.bypass.duplicate"),
}
