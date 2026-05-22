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
}
