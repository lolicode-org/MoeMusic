package org.lolicode.moemusic.core.playback.audience

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.toArtistInfos
import org.lolicode.moemusic.core.event.EventBusImpl
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.playback.ServerPlaybackController
import org.lolicode.moemusic.core.playback.TrackQueue
import org.lolicode.moemusic.core.plugin.PluginManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class LeaseCapturingChannel : NetworkChannel {
    override fun sendToServer(packetId: PacketId, payload: ByteArray) = Unit
    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) = Unit
    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) = Unit
}

class PlaybackAudienceLeaseCoordinatorTest {

    @BeforeTest
    fun resetBeforeTest() {
        PluginManager.reset()
    }

    @AfterTest
    fun resetAfterTest() {
        PluginManager.reset()
    }

    private val sampleTrack = TrackInfo(
        id = "lease-track",
        title = "Lease Track",
        artists = listOf("Lease Artist").toArtistInfos(),
        durationMs = 120_000,
        sourceId = "lease-source",
    )

    private val sampleSource = object : MusicSource {
        override val id: String = "lease-source"

        override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResource =
            PlaybackResource("https://example.com/${track.id}.mp3")
    }

    private fun controller(queue: TrackQueue = TrackQueue()): ServerPlaybackController =
        ServerPlaybackController(
            channel = LeaseCapturingChannel(),
            queue = queue,
            eventBus = EventBusImpl(),
        )

    @Test
    fun `first lease resumes auto paused playback and last release pauses it`() {
        val ctrl = controller()
        val coordinator = PlaybackAudienceLeaseCoordinator(ctrl)
        coordinator.beginSession()

        ctrl.play(sampleTrack, PlaybackResource("https://example.com/lease.mp3"))
        ctrl.autoPause()
        assertTrue(ctrl.currentContext?.state is PlaybackState.Paused)

        val first = coordinator.acquire("compat-a")
        val second = coordinator.acquire("compat-b")

        assertTrue(ctrl.currentContext?.state is PlaybackState.Playing)

        first.release()
        assertTrue(ctrl.currentContext?.state is PlaybackState.Playing)

        second.release()
        assertTrue(ctrl.currentContext?.state is PlaybackState.Paused)
    }

    @Test
    fun `first lease can start autoplay when nothing is loaded`() {
        val queue = TrackQueue().apply {
            autoplaySupplier = { sampleTrack.copy(id = "autoplay-lease-track", title = "Autoplay Lease Track") }
        }
        val ctrl = controller(queue)
        val coordinator = PlaybackAudienceLeaseCoordinator(ctrl)
        coordinator.beginSession()
        PluginManager.musicSources += sampleSource

        coordinator.acquire("compat-a")

        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline && ctrl.currentContext == null) {
            Thread.sleep(10)
        }

        assertEquals("autoplay-lease-track", ctrl.currentContext?.track?.id)
    }
}
