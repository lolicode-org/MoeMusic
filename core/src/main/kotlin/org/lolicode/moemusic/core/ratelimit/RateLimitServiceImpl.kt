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

    override fun checkPlaybackControl(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkPlaybackControl(user.id.toString(), bypass = hasBypass(user))
    }

    override fun checkSkip(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkSkip(user.id.toString(), bypass = hasBypass(user))
    }

    override fun checkVote(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkVote(user.id.toString(), bypass = hasBypass(user))
    }

    override fun checkQueueRead(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkQueueRead(user.id.toString(), bypass = hasBypass(user))
    }

    override fun checkQueueMutation(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkQueueMutation(user.id.toString(), bypass = hasBypass(user))
    }

    override fun checkSelection(submitter: MoeMusicUser?) {
        val user = submitter ?: return
        limiter.checkSelection(user.id.toString(), bypass = hasBypass(user))
    }

    private fun hasBypass(user: MoeMusicUser): Boolean =
        user.hasPermission(
            PermissionNodes.RATE_LIMIT_BYPASS.id,
            PermissionNodes.RATE_LIMIT_BYPASS.defaultLevel(),
        )
}
