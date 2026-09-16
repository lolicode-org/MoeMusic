package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.RateLimitedException

/**
 * Shared request-rate-limit enforcement service exposed to plugins on the server side.
 *
 * Use this before expensive user-driven source work so plugin-specific submit/search flows
 * respect the same pre-I/O request budget as MoeMusic's built-in command and packet paths.
 *
 * Implementations apply MoeMusic's shared bypass permission automatically; callers should pass
 * the acting user directly instead of attempting to pre-check bypass on their own.
 *
 * TODO(v3): This (and other similar services) should not be implemented by plugins.
 */
public interface IRateLimitService {

    /**
     * Enforce the shared search request budget for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkSearch(submitter: MoeMusicUser?)

    /**
     * Enforce the shared submit-like request budget for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkSubmit(submitter: MoeMusicUser?)

    /**
     * Enforce the shared playback control request budget (pause/resume/seek/stop) for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkPlaybackControl(submitter: MoeMusicUser?) {}

    /**
     * Enforce the shared skip request budget for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkSkip(submitter: MoeMusicUser?) {}

    /**
     * Enforce the shared vote-to-skip request budget for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkVote(submitter: MoeMusicUser?) {}

    /**
     * Enforce the shared queue read request budget (UI bootstrap / queue polling) for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkQueueRead(submitter: MoeMusicUser?) {}

    /**
     * Enforce the shared queue mutation request budget (track removal / queue clear) for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkQueueMutation(submitter: MoeMusicUser?) {}

    /**
     * Enforce the shared selection choice pagination request budget for [submitter].
     *
     * Null submitters are treated as server-internal work and are never rate-limited.
     *
     * @throws RateLimitedException when the user has exceeded the configured limit.
     */
    public fun checkSelection(submitter: MoeMusicUser?) {}
}
