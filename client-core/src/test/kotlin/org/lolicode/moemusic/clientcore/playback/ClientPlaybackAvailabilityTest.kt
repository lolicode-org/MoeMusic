package org.lolicode.moemusic.clientcore.playback

import org.lolicode.moemusic.core.config.ClientConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientPlaybackAvailabilityTest {

    @Test
    fun `global and per-server config both affect playback enablement`() {
        val server = ClientServerScope("server:test.example", "Test Server")

        assertTrue(
            ClientPlaybackAvailability.isPlaybackEnabledForServer(
                clientConfig = ClientConfig(playbackEnabled = true),
                serverScope = server,
            )
        )
        assertFalse(
            ClientPlaybackAvailability.isPlaybackEnabledForServer(
                clientConfig = ClientConfig(playbackEnabled = false),
                serverScope = server,
            )
        )
        assertFalse(
            ClientPlaybackAvailability.isPlaybackEnabledForServer(
                clientConfig = ClientConfig(
                    playbackEnabled = true,
                    disabledServers = listOf(server.key),
                ),
                serverScope = server,
            )
        )
    }

    @Test
    fun `availability issue only reports missing server handshake`() {
        assertNull(
            ClientPlaybackAvailability.availabilityIssue(
                hasConnection = false,
                serverHandshakeMissing = true,
            )
        )
        assertEquals(
            AvailabilityIssue.SERVER_MISSING,
            ClientPlaybackAvailability.availabilityIssue(
                hasConnection = true,
                serverHandshakeMissing = true,
            )
        )
        assertNull(
            ClientPlaybackAvailability.availabilityIssue(
                hasConnection = true,
                serverHandshakeMissing = false,
            )
        )
    }
}
