package org.lolicode.moemusic.core.playback.autoplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.core.config.AutoplayConfig
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.playback.TrackQueue
import org.slf4j.LoggerFactory

/**
 * Manages server-side autoplay.
 *
 * On [initialize]:
 * 1. A background coroutine fetches autoplay tracks from **every** registered [MusicSource]
 *    by calling [MusicSource.getAutoplayTracks].
 * 2. Results are combined and shuffled into a non-repeating "deck".
 * 3. [TrackQueue.autoplaySupplier] is set to a supplier that pops from the deck one at a time.
 * 4. When the deck is exhausted another background fetch is automatically triggered.
 *
 * Thread-safety: the deck is protected by `synchronized(deck)` so that calls from the
 * server thread ([TrackQueue.autoplaySupplier] side) and from IO coroutines (refetch side) are safe.
 */
class AutoplayManager(
    private val config: AutoplayConfig,
    private val scope: CoroutineScope,
    private val minimumRefetchIntervalMs: Long = DEFAULT_MINIMUM_REFETCH_INTERVAL_MS,
) {
    private val logger = LoggerFactory.getLogger(AutoplayManager::class.java)

    private val deck: ArrayDeque<TrackInfo> = ArrayDeque()
    @Volatile private var closed: Boolean = false

    /** True while an async refetch is in progress — prevents stacking parallel fetches. */
    @Volatile private var refetchInProgress: Boolean = false

    /** Timestamp of the most recent completed refetch, used to throttle empty/all-fail loops. */
    @Volatile private var lastRefetchCompletedAtNanos: Long = 0L

    @Volatile private var onTracksAvailableCallback: (() -> Unit)? = null

    /**
     * Attaches this manager to [queue] and triggers the initial autoplay-track fetch.
     *
     * [sources] is captured as a snapshot at call time so that newly registered sources
     * added after initialization are not included in subsequent refreshes.  Re-initialize
     * if you need to pick up new sources.
     *
     * No-op (no supplier set, no fetch) when [AutoplayConfig.enabled] is false.
     */
    fun initialize(queue: TrackQueue, sources: List<MusicSource>, onTracksAvailable: (() -> Unit)? = null) {
        if (closed) return
        if (!config.enabled) {
            queue.autoplaySupplier = null
            onTracksAvailableCallback = null
            logger.debug("AutoplayManager: disabled by config.")
            return
        }

        val snapshot = sources.toList()
        onTracksAvailableCallback = onTracksAvailable
        queue.autoplaySupplier = { nextAutoplayTrack(snapshot) }
        triggerRefetch(snapshot, bypassThrottle = true)
        logger.debug("AutoplayManager: initialized with {} source(s).", snapshot.size)
    }

    fun close() {
        closed = true
        onTracksAvailableCallback = null
        synchronized(deck) {
            deck.clear()
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Called by [TrackQueue] (server thread) every time the user queue is empty.
     * Returns the next autoplay track, or null if the deck is currently empty (fetching).
     */
    private fun nextAutoplayTrack(sources: List<MusicSource>): TrackInfo? {
        if (closed) return null
        synchronized(deck) {
            if (deck.isEmpty()) {
                // Deck exhausted — trigger async refresh and signal nothing available yet.
                triggerRefetch(sources)
                return null
            }
            val track = deck.removeFirst()
            // Pre-emptively start refresh when the deck runs low (< 3 remaining).
            if (deck.size < 3) triggerRefetch(sources)
            return track
        }
    }

    private fun triggerRefetch(sources: List<MusicSource>, bypassThrottle: Boolean = false) {
        if (closed) return
        if (refetchInProgress) return
        if (!bypassThrottle && isRefetchThrottled()) return
        refetchInProgress = true
        scope.launch(Dispatchers.IO) { refetch(sources) }
    }

    /** Fetches autoplay tracks from all [sources], shuffles them, and replaces the deck. */
    private suspend fun refetch(sources: List<MusicSource>) {
        if (closed) {
            refetchInProgress = false
            return
        }
        var collectedAny = false
        try {
            val maxPerSource = config.maxTracksPerSource
            val collected = mutableListOf<TrackInfo>()
            for (source in sources) {
                val tracks = try {
                    source.getAutoplayTracks()
                } catch (e: Exception) {
                    logger.warn(
                        "AutoplayManager: source '{}' threw during getAutoplayTracks(): {}",
                        source.id, e.message,
                    )
                    emptyList()
                }
                collected += tracks
                    .take(maxPerSource)
                    .filter { ContentFilterRuntime.trackFilterVerdict(it) is FilterVerdict.Allow }
            }

            if (collected.isEmpty()) {
                logger.warn("AutoplayManager: no autoplay tracks from any source; autoplay will be silent.")
            } else {
                collectedAny = true
                collected.shuffle()
                logger.info(
                    "AutoplayManager: refreshed deck with {} track(s) from {} source(s).",
                    collected.size, sources.size,
                )
            }

            synchronized(deck) {
                // Replace the deck with the freshly fetched tracks.
                // The isEmpty() guard in nextAutoplayTrack() was only a hint; always replace here
                // to guarantee the deck is filled after a successful refetch.
                deck.clear()
                deck.addAll(collected)
            }
        } catch (e: Exception) {
            logger.error("AutoplayManager: refetch failed: {}", e.message, e)
        } finally {
            lastRefetchCompletedAtNanos = System.nanoTime()
            refetchInProgress = false
        }
        if (collectedAny && !closed) {
            onTracksAvailableCallback?.invoke()
        }
    }

    private fun isRefetchThrottled(nowNanos: Long = System.nanoTime()): Boolean {
        val lastCompleted = lastRefetchCompletedAtNanos
        if (lastCompleted == 0L) return false
        return nowNanos - lastCompleted < minimumRefetchIntervalMs * 1_000_000L
    }

    private companion object {
        const val DEFAULT_MINIMUM_REFETCH_INTERVAL_MS = 30_000L
    }
}
