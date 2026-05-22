package org.lolicode.moemusic.core.playback.audience

import org.lolicode.moemusic.api.plugin.PlaybackAudienceLease
import org.lolicode.moemusic.core.playback.ServerPlaybackController

/**
 * Coordinates session-scoped audience leases from native MoeMusic clients and compatibility
 * plugins that proxy playback to other client implementations.
 */
internal class PlaybackAudienceLeaseCoordinator(
    private val playbackController: ServerPlaybackController,
) {

    private var sessionGeneration: Long = 0L
    private var sessionActive: Boolean = false
    private var nextToken: Long = 1L
    private val heldLeases: LinkedHashMap<Long, String> = linkedMapOf()

    fun beginSession() {
        sessionGeneration += 1
        sessionActive = true
        heldLeases.clear()
    }

    fun clearSession() {
        sessionActive = false
        heldLeases.clear()
    }

    fun hasAudience(): Boolean = heldLeases.isNotEmpty()

    fun acquire(owner: String): PlaybackAudienceLease {
        assert(owner.isNotBlank()) { "Lease owner must be a non-blank string." }
        check(sessionActive) {
            "Playback audience leases are only available during an active server session."
        }
        val token = nextToken++
        val generation = sessionGeneration
        val firstAudience = heldLeases.isEmpty()
        heldLeases[token] = owner
        if (firstAudience) {
            playbackController.autoResume()
            playbackController.startNextIfStopped()
        }
        return LeaseImpl(token, generation)
    }

    private fun release(token: Long, generation: Long) {
        if (!sessionActive || generation != sessionGeneration) return
        if (heldLeases.remove(token) == null) return
        if (heldLeases.isEmpty()) {
            playbackController.autoPause()
        }
    }

    private inner class LeaseImpl(
        private val token: Long,
        private val generation: Long,
    ) : PlaybackAudienceLease {

        private var released: Boolean = false

        override fun release() {
            if (released) return
            released = true
            this@PlaybackAudienceLeaseCoordinator.release(token, generation)
        }
    }
}
