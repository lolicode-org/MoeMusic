package org.lolicode.moemusic.core.ratelimit

import org.lolicode.moemusic.api.service.IRateLimitService
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.permission.PermissionNodes

internal class RateLimitServiceImpl(
    private val limiter: RequestRateLimiter,
) : IRateLimitService {

    override fun checkSearch(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkSearch(user.id.toString(), bypass = hasBypass(user))
    }

    override fun checkSubmit(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkSubmit(user.id.toString(), bypass = hasBypass(user))
    }

    private fun hasBypass(user: MoeMusicUser): Boolean =
        user.hasPermission(
            PermissionNodes.RATE_LIMIT_BYPASS.id,
            PermissionNodes.RATE_LIMIT_BYPASS.defaultLevel(),
        )
}
