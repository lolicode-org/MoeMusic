package org.lolicode.moemusic.core.ratelimit

import org.lolicode.moemusic.api.RateLimitedException
import org.lolicode.moemusic.core.config.ModConfigManager
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RequestRateLimiter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val requestCount = AtomicInteger(0)

    fun checkSearch(requesterId: String, bypass: Boolean = false) {
        check(RequestType.SEARCH, requesterId, bypass)
    }

    fun checkSubmit(requesterId: String, bypass: Boolean = false) {
        check(RequestType.SUBMIT, requesterId, bypass)
    }

    fun checkSkip(requesterId: String, bypass: Boolean = false) {
        check(RequestType.SKIP, requesterId, bypass)
    }

    fun checkVote(requesterId: String, bypass: Boolean = false) {
        check(RequestType.VOTE, requesterId, bypass)
    }

    fun checkPlaybackControl(requesterId: String, bypass: Boolean = false) {
        check(RequestType.PLAYBACK_CONTROL, requesterId, bypass)
    }

    fun checkQueueRead(requesterId: String, bypass: Boolean = false) {
        check(RequestType.QUEUE_READ, requesterId, bypass)
    }

    fun checkQueueMutation(requesterId: String, bypass: Boolean = false) {
        check(RequestType.QUEUE_MUTATION, requesterId, bypass)
    }

    fun checkSelection(requesterId: String, bypass: Boolean = false) {
        check(RequestType.SELECTION, requesterId, bypass)
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
            RequestType.SKIP -> cfg.skipRequests
            RequestType.VOTE -> cfg.voteRequests
            RequestType.PLAYBACK_CONTROL -> cfg.playbackControlRequests
            RequestType.QUEUE_READ -> cfg.queueReadRequests
            RequestType.QUEUE_MUTATION -> cfg.queueMutationRequests
            RequestType.SELECTION -> cfg.selectionRequests
        }
        if (limit <= 0) return

        val windowMs = cfg.windowSeconds * 1_000L
        val now = nowMillis()

        if (requestCount.incrementAndGet() % 1000 == 0) {
            cleanupStaleBuckets(now, windowMs)
        }

        val bucket = buckets.computeIfAbsent("${type.name}:${requesterId.lowercase()}") { Bucket() }
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

    private fun cleanupStaleBuckets(now: Long, windowMs: Long) {
        val iterator = buckets.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val bucket = entry.value
            synchronized(bucket) {
                bucket.trim(now, windowMs)
                if (bucket.timestamps.isEmpty()) {
                    iterator.remove()
                }
            }
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
        SKIP,
        VOTE,
        PLAYBACK_CONTROL,
        QUEUE_READ,
        QUEUE_MUTATION,
        SELECTION,
    }
}
