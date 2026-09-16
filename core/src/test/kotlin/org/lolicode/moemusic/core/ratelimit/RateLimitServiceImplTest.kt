package org.lolicode.moemusic.core.ratelimit

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.RateLimitedException
import org.lolicode.moemusic.core.config.MediaPolicyConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.config.RequestRateLimitConfig
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RateLimitServiceImplTest {

    init {
        ModConfigManager.load(Files.createTempDirectory("moemusic-rate-limit-service-test"))
    }

    @Test
    fun `service enforces submit limits for normal users`() {
        val limiter = RequestRateLimiter { 0L }
        val service = RateLimitServiceImpl(limiter)
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    rateLimit = RequestRateLimitConfig(
                        enabled = true,
                        windowSeconds = 10,
                        searchRequests = 0,
                        submitRequests = 1,
                    )
                )
            )
        )

        val player = fakePlayer(hasBypass = false)
        service.checkSubmit(player)
        assertFailsWith<RateLimitedException> {
            service.checkSubmit(player)
        }
    }

    @Test
    fun `service respects bypass permission and skips null submitters`() {
        val limiter = RequestRateLimiter { 0L }
        val service = RateLimitServiceImpl(limiter)
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    rateLimit = RequestRateLimitConfig(
                        enabled = true,
                        windowSeconds = 10,
                        searchRequests = 1,
                        submitRequests = 1,
                    )
                )
            )
        )

        service.checkSearch(null)
        service.checkSkip(null)
        service.checkVote(null)
        service.checkPlaybackControl(null)
        service.checkQueueRead(null)
        service.checkQueueMutation(null)
        service.checkSelection(null)

        val bypassPlayer = fakePlayer(hasBypass = true)
        service.checkSearch(bypassPlayer)
        service.checkSearch(bypassPlayer)
        service.checkSubmit(bypassPlayer)
        service.checkSubmit(bypassPlayer)
        service.checkSkip(bypassPlayer)
        service.checkVote(bypassPlayer)
        service.checkPlaybackControl(bypassPlayer)
        service.checkQueueRead(bypassPlayer)
        service.checkQueueMutation(bypassPlayer)
        service.checkSelection(bypassPlayer)
    }

    @Test
    fun `service enforces new limits for normal users`() {
        val limiter = RequestRateLimiter { 0L }
        val service = RateLimitServiceImpl(limiter)
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    rateLimit = RequestRateLimitConfig(
                        enabled = true,
                        windowSeconds = 10,
                        skipRequests = 1,
                        voteRequests = 1,
                        playbackControlRequests = 1,
                        queueReadRequests = 1,
                        queueMutationRequests = 1,
                        selectionRequests = 1,
                    )
                )
            )
        )

        val player = fakePlayer(hasBypass = false)

        service.checkSkip(player)
        assertFailsWith<RateLimitedException> { service.checkSkip(player) }

        service.checkVote(player)
        assertFailsWith<RateLimitedException> { service.checkVote(player) }

        service.checkPlaybackControl(player)
        assertFailsWith<RateLimitedException> { service.checkPlaybackControl(player) }

        service.checkQueueRead(player)
        assertFailsWith<RateLimitedException> { service.checkQueueRead(player) }

        service.checkQueueMutation(player)
        assertFailsWith<RateLimitedException> { service.checkQueueMutation(player) }

        service.checkSelection(player)
        assertFailsWith<RateLimitedException> { service.checkSelection(player) }
    }

    private fun fakePlayer(hasBypass: Boolean): MoeMusicUser = object : MoeMusicUser() {
        override val displayName: String = "tester"
        override val id: UUID = UUID.randomUUID()
        override val locale: String = "en_us"

        override fun hasPermission(permission: String, defaultLevel: Int): Boolean = hasBypass
    }
}
