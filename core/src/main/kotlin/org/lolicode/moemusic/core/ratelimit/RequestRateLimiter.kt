package org.lolicode.moemusic.core.ratelimit

import org.lolicode.moemusic.api.RateLimitedException
import org.lolicode.moemusic.core.config.ModConfigManager
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class RequestRateLimiter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun checkSearch(requesterId: String, bypass: Boolean = false) {
        check(RequestType.SEARCH, requesterId, bypass)
    }

    fun checkSubmit(requesterId: String, bypass: Boolean = false) {
        check(RequestType.SUBMIT, requesterId, bypass)
    }

    fun clear() {
        buckets.clear()
    }

    private fun check(type: RequestType, requesterId: String, bypass: Boolean) {
        val cfg = ModConfigManager.config.media.rateLimit
        if (!cfg.enabled || bypass) return

        val limit = when (type) {
            RequestType.SEARCH -> cfg.searchRequests
            RequestType.SUBMIT -> cfg.submitRequests
        }
        if (limit <= 0) return

        val windowMs = cfg.windowSeconds * 1_000L
        val bucket = buckets.computeIfAbsent("${type.name}:${requesterId.lowercase()}") { Bucket() }
        val now = nowMillis()
        val exceeded = synchronized(bucket) {
            bucket.trim(now, windowMs)
            if (bucket.timestamps.size >= limit) {
                true
            } else {
                bucket.timestamps.addLast(now)
                false
            }
        }
        if (exceeded) {
            throw RateLimitedException()
        }
    }

    private class Bucket {
        val timestamps: ArrayDeque<Long> = ArrayDeque()

        fun trim(now: Long, windowMs: Long) {
            val cutoff = now - windowMs
            while (timestamps.isNotEmpty() && timestamps.first() <= cutoff) {
                timestamps.removeFirst()
            }
        }
    }

    private enum class RequestType {
        SEARCH,
        SUBMIT,
    }
}
