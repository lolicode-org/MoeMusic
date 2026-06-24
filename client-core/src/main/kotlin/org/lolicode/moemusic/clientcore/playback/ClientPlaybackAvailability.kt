package org.lolicode.moemusic.clientcore.playback

import org.lolicode.moemusic.core.config.ClientConfig

/**
 * Pure client-side participation/availability policy shared by screens and runtime adapters.
 */
object ClientPlaybackAvailability {

    fun isPlaybackEnabledForServer(
        clientConfig: ClientConfig,
        serverScope: ClientServerScope?,
    ): Boolean {
        if (!clientConfig.playbackEnabled) return false
        return serverScope?.matchingKeys()?.none { it in clientConfig.disabledServers } ?: true
    }

    fun availabilityIssue(
        hasConnection: Boolean,
        serverHandshakeMissing: Boolean,
        serverHandshakeRejected: Boolean = false,
    ): AvailabilityIssue? {
        if (!hasConnection) return null
        return when {
            serverHandshakeRejected -> AvailabilityIssue.SERVER_REJECTED
            serverHandshakeMissing -> AvailabilityIssue.SERVER_MISSING
            else -> null
        }
    }
}
