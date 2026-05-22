package org.lolicode.moemusic.core.ratelimit

import org.lolicode.moemusic.api.RateLimitedException
import org.lolicode.moemusic.core.config.MediaPolicyConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.config.RequestRateLimitConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RequestRateLimiterTest {

    init {
        ModConfigManager.load(Files.createTempDirectory("moemusic-rate-limit-test"))
    }

    @Test
    fun `search requests are limited within window`() {
        var now = 0L
        val limiter = RequestRateLimiter { now }
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    rateLimit = RequestRateLimitConfig(
                        enabled = true,
                        windowSeconds = 10,
                        searchRequests = 2,
                        submitRequests = 0,
                    )
                )
            )
        )

        limiter.checkSearch("player")
        limiter.checkSearch("player")
        assertFailsWith<RateLimitedException> {
            limiter.checkSearch("player")
        }
        now = 11_000L
        limiter.checkSearch("player")
    }

    @Test
    fun `bypass skips submit limits`() {
        val limiter = RequestRateLimiter { 0L }
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

        limiter.checkSubmit("player", bypass = true)
        limiter.checkSubmit("player", bypass = true)
    }
}
