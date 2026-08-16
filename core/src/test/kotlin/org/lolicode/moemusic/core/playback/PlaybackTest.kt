package org.lolicode.moemusic.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.ResourceLock
import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.event.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.QueueRemoveResult
import org.lolicode.moemusic.core.config.AutoplayConfig
import org.lolicode.moemusic.core.config.MediaPolicyConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.event.EventBusImpl
import org.lolicode.moemusic.core.playback.autoplay.AutoplayManager
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.PlaybackSnapshotPush
import org.lolicode.moemusic.core.protocol.proto.PlaybackSnapshotPushReason
import org.lolicode.moemusic.core.protocol.proto.PlaybackStateProto
import org.lolicode.moemusic.core.protocol.proto.StateUpdate
import org.lolicode.moemusic.core.plugin.PluginManager
import java.nio.file.Files
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

private const val SAMPLE_SOURCE_ID = "test-source"

private val SAMPLE_TRACK = TrackInfo(id = "test-1", title = "Test Track", artists = listOf("Test Artist").toArtistInfos(), durationMs = 180_000) { sourceId = SAMPLE_SOURCE_ID }

private const val RESOLVED_PLAYBACK_URL = "https://cdn.example.com/audio/test-1.mp3?sig=abc123"
private val RESOLVED_PLAYBACK = PlaybackResource(url = RESOLVED_PLAYBACK_URL) { headers = mapOf(
        "Referer" to "https://example.com/player",
        "User-Agent" to "MoeMusic-Test/1.0",
    ) }

// For HTTP-source tests, the id IS the URL.
private fun TrackInfo.directPlayback(): PlaybackResource = PlaybackResource(id.takeIf { it.startsWith("http") } ?: "https://example.com/audio.mp3")

/**
 * A test [MusicSource] that resolves any track belonging to [SAMPLE_SOURCE_ID] by returning
 * a [PlaybackResource] built from [TrackInfo.id] as a local test URL. Used to satisfy
 * [ServerPlaybackController.resolveTrackForPlayback] in tests that do not exercise resolution errors.
 */
private val SAMPLE_SOURCE = object : MusicSource {
    override val id: String = SAMPLE_SOURCE_ID
    override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
        PlaybackResolution(track.directPlayback())
}

private val SAMPLE_PLAYER = object : MoeMusicUser() {
    override val displayName: String = "tester"
    override val id: UUID = UUID.randomUUID()
    override val locale: String = "en_us"
    override fun hasPermission(permission: String, defaultLevel: Int): Boolean = false
}

private class CapturingChannel : NetworkChannel {
    data class Packet(val id: PacketId, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Packet

            if (id != other.id) return false
            if (!payload.contentEquals(other.payload)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    val broadcasts = CopyOnWriteArrayList<Packet>()
    val clientPackets = CopyOnWriteArrayList<Pair<MoeMusicUser, Packet>>()
    var throwOnBroadcast = false

    override fun sendToServer(packetId: PacketId, payload: ByteArray) = Unit
    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
        clientPackets += user to Packet(packetId, payload)
    }
    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        if (throwOnBroadcast) throw IllegalStateException("network closed")
        broadcasts += Packet(packetId, payload)
    }
}

private fun freshController(queue: TrackQueue = TrackQueue()): Pair<ServerPlaybackController, CapturingChannel> {
    val channel = CapturingChannel()
    val ctrl = ServerPlaybackController(
        channel = channel,
        queue = queue,
        eventBus = EventBusImpl(),
    )
    return ctrl to channel
}

/**
 * Runs [block] with [SAMPLE_SOURCE] registered in [PluginManager.musicSources], removing it after.
 */
private fun withSampleSource(block: () -> Unit) {
    PluginManager.musicSources += SAMPLE_SOURCE
    try {
        block()
    } finally {
        PluginManager.musicSources -= SAMPLE_SOURCE
    }
}

private suspend fun withSampleSourceSuspend(block: suspend () -> Unit) {
    PluginManager.musicSources += SAMPLE_SOURCE
    try {
        block()
    } finally {
        PluginManager.musicSources -= SAMPLE_SOURCE
    }
}

private fun awaitCondition(timeoutMs: Long = 1_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

// ---------------------------------------------------------------------------
// TrackQueue tests
// ---------------------------------------------------------------------------

@ResourceLock("PluginManager")
class TrackQueueTest {

    @Test
    fun `user queue takes priority over autoplay supplier`() {
        val queue = TrackQueue()
        val autoplay = TrackInfo("autoplay", "Autoplay", listOf("Bot").toArtistInfos(), 60_000)
        val userTrack = TrackInfo("player", "Player", listOf("Human").toArtistInfos(), 60_000)

        queue.autoplaySupplier = { autoplay }
        queue.enqueueUser(userTrack)

        val next = queue.nextTrack()?.track
        assertEquals(userTrack.id, next?.id, "User track should come first")
        assertEquals(autoplay.id, queue.nextTrack()?.track?.id, "Autoplay track should come second via supplier")
    }

    @Test
    fun `nextTrack returns null when both queues empty`() {
        val queue = TrackQueue()
        assertNull(queue.nextTrack())
    }

    @Test
    fun `multiple user tracks preserve FIFO order`() {
        val queue = TrackQueue()
        val t1 = SAMPLE_TRACK.copy { id = "a" }
        val t2 = SAMPLE_TRACK.copy { id = "b" }
        val t3 = SAMPLE_TRACK.copy { id = "c" }
        queue.enqueueUser(t1)
        queue.enqueueUser(t2)
        queue.enqueueUser(t3)
        assertEquals(listOf("a", "b", "c"), listOf(queue.nextTrack()?.track?.id, queue.nextTrack()?.track?.id, queue.nextTrack()?.track?.id))
    }

    @Test
    fun `enqueueUserIfAbsent rejects duplicate source track already queued`() {
        val queue = TrackQueue()
        val queued = SAMPLE_TRACK.copy { id = "same"; sourceId = "ncm" }
        val duplicate = queued.copy { title = "Renamed" }

        assertTrue(queue.enqueueUserIfAbsent(queued))
        assertFalse(queue.enqueueUserIfAbsent(duplicate))
        assertEquals(listOf(queued.id), queue.userQueueSnapshot().map { it.id })
    }

    @Test
    fun `enqueueUserIfAbsent tracks without source identity are never deduplicated`() {
        val queue = TrackQueue()
        // Both have no sourceId — matchesQueueIdentity returns false, so both are allowed
        val a = SAMPLE_TRACK.copy { id = ""; sourceId = null }
        val b = a.copy { title = "Another Title" }

        assertTrue(queue.enqueueUserIfAbsent(a))
        assertTrue(queue.enqueueUserIfAbsent(b), "no source identity — dedup is not possible")
        assertEquals(2, queue.userQueueSize())
    }

    @Test
    fun `enqueueAndPlay rejects duplicate of current track`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())

        assertFailsWith<AlreadyQueuedException> {
            ctrl.enqueueAndPlay(SAMPLE_TRACK.copy { title = "Renamed" })
        }
    }

    @Test
    fun `removeUserTrack allows the original enqueuer without bypass permission`() {
        val queue = TrackQueue()
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val queued = SAMPLE_TRACK.copy { id = "owned-track" }
        queue.enqueueUser(queued, enqueuedBy = owner)

        assertEquals(
            TrackQueue.UserQueueRemovalResult.FORBIDDEN,
            queue.removeUserTrack(sourceId = SAMPLE_SOURCE_ID, trackId = "owned-track", requesterId = other, bypassOwnership = false),
        )
        assertEquals(
            TrackQueue.UserQueueRemovalResult.REMOVED,
            queue.removeUserTrack(sourceId = SAMPLE_SOURCE_ID, trackId = "owned-track", requesterId = owner, bypassOwnership = false),
        )
        assertTrue(queue.userQueueSnapshot().isEmpty())
    }

    @Test
    fun `removeUserTrack allows privileged removal of another users track`() {
        val queue = TrackQueue()
        val owner = UUID.randomUUID()
        val moderator = UUID.randomUUID()
        val queued = SAMPLE_TRACK.copy { id = "moderated-track" }
        queue.enqueueUser(queued, enqueuedBy = owner)

        assertEquals(
            TrackQueue.UserQueueRemovalResult.REMOVED,
            queue.removeUserTrack(sourceId = SAMPLE_SOURCE_ID, trackId = "moderated-track", requesterId = moderator, bypassOwnership = true),
        )
        assertTrue(queue.userQueueSnapshot().isEmpty())
    }

    @Test
    fun `removeUserTrack matches the exact queued source and track identity`() {
        val queue = TrackQueue()
        val first = SAMPLE_TRACK.copy { sourceId = "alpha"; id = "shared" }
        val second = SAMPLE_TRACK.copy { sourceId = "beta"; id = "shared"; title = "Other Source" }
        queue.enqueueUser(first)
        queue.enqueueUser(second)

        assertEquals(
            TrackQueue.UserQueueRemovalResult.REMOVED,
            queue.removeUserTrack(sourceId = "beta", trackId = "shared", requesterId = null, bypassOwnership = true),
        )
        assertEquals(listOf(first.id), queue.userQueueSnapshot().map { it.id })
    }

    @Test
    fun `enqueueUser stamps unique queueEntryId when missing`() {
        val queue = TrackQueue()
        val track1 = SAMPLE_TRACK.copy { id = "t1" }
        val track2 = SAMPLE_TRACK.copy { id = "t2" }
        queue.enqueueUser(track1)
        queue.enqueueUser(track2)

        val snapshot = queue.userQueueSnapshot()
        assertEquals(2, snapshot.size)
        assertNotNull(snapshot[0].queueEntryId)
        assertNotNull(snapshot[1].queueEntryId)
        assertNotEquals(snapshot[0].queueEntryId, snapshot[1].queueEntryId)
    }

    @Test
    fun `enqueueUserIfAbsent restamps caller supplied queueEntryId`() {
        val queue = TrackQueue()
        val supplied = SAMPLE_TRACK.copy { id = "conditional-entry"; queueEntryId = "caller-supplied" }

        assertTrue(queue.enqueueUserIfAbsent(supplied))
        assertNotEquals("caller-supplied", queue.userQueueSnapshot().single().queueEntryId)
    }

    @Test
    fun `removeUserTrack with queueEntryId removes exact instance among duplicates`() {
        val queue = TrackQueue()
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()
        val track1 = SAMPLE_TRACK.copy {
            id = "duplicate-track"
            title = "Submission 1"
            queueEntryId = "caller-supplied"
        }
        val track2 = SAMPLE_TRACK.copy {
            id = "duplicate-track"
            title = "Submission 2"
            queueEntryId = "caller-supplied"
        }
        queue.enqueueUser(track1, enqueuedBy = user1)
        queue.enqueueUser(track2, enqueuedBy = user2)

        val snapshot = queue.userQueueSnapshot()
        val entry1 = snapshot[0].queueEntryId!!
        val entry2 = snapshot[1].queueEntryId!!

        // User 2 removes their own track via queueEntryId
        val details = queue.removeUserTrackDetailed(
            sourceId = SAMPLE_SOURCE_ID,
            trackId = "duplicate-track",
            queueEntryId = entry2,
            requesterId = user2,
            bypassOwnership = false,
        )
        assertEquals(TrackQueue.UserQueueRemovalResult.REMOVED, details.result)
        assertEquals("Submission 2", details.removedTrack?.title)

        val remaining = queue.userQueueSnapshot()
        assertEquals(1, remaining.size)
        assertEquals(entry1, remaining[0].queueEntryId)
        assertEquals("Submission 1", remaining[0].title)
    }

    @Test
    fun `removeUserTrack without queueEntryId prefers requesters own instance when duplicates exist`() {
        val queue = TrackQueue()
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()
        val track1 = SAMPLE_TRACK.copy { id = "duplicate-track"; title = "User 1 Track" }
        val track2 = SAMPLE_TRACK.copy { id = "duplicate-track"; title = "User 2 Track" }
        queue.enqueueUser(track1, enqueuedBy = user1)
        queue.enqueueUser(track2, enqueuedBy = user2)

        // User 2 removes without queueEntryId, non-bypass: should target track2 (User 2's own track) instead of failing on track1
        val details = queue.removeUserTrackDetailed(
            sourceId = SAMPLE_SOURCE_ID,
            trackId = "duplicate-track",
            queueEntryId = null,
            requesterId = user2,
            bypassOwnership = false,
        )
        assertEquals(TrackQueue.UserQueueRemovalResult.REMOVED, details.result)
        assertEquals("User 2 Track", details.removedTrack?.title)

        val remaining = queue.userQueueSnapshot()
        assertEquals(1, remaining.size)
        assertEquals("User 1 Track", remaining[0].title)
    }

    @Test
    fun `removeUserTrack with queueEntryId rejects removal when non-moderator targets another users entry`() {
        val queue = TrackQueue()
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()
        val track1 = SAMPLE_TRACK.copy { id = "track-1" }
        queue.enqueueUser(track1, enqueuedBy = user1)

        val entry1 = queue.userQueueSnapshot()[0].queueEntryId!!

        val details = queue.removeUserTrackDetailed(
            sourceId = SAMPLE_SOURCE_ID,
            trackId = "track-1",
            queueEntryId = entry1,
            requesterId = user2,
            bypassOwnership = false,
        )
        assertEquals(TrackQueue.UserQueueRemovalResult.FORBIDDEN, details.result)
        assertEquals(1, queue.userQueueSnapshot().size)
    }

    @Test
    fun `clearUserQueue clears only requested user tracks for self`() {
        val queue = TrackQueue()
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()
        queue.enqueueUser(SAMPLE_TRACK.copy { id = "t1"; title = "T1" }, enqueuedBy = user1)
        queue.enqueueUser(SAMPLE_TRACK.copy { id = "t2"; title = "T2" }, enqueuedBy = user2)
        queue.enqueueUser(SAMPLE_TRACK.copy { id = "t3"; title = "T3" }, enqueuedBy = user1)

        val outcome = queue.clearUserQueue(targetUserId = user1, targetUserName = null, requesterId = user1, bypassOwnership = false)
        assertEquals(2, outcome.removedCount)
        assertEquals(listOf("t1", "t3"), outcome.removedTracks.map { it.id })
        val remaining = queue.userQueueSnapshot()
        assertEquals(1, remaining.size)
        assertEquals("t2", remaining[0].id)
    }

    @Test
    fun `clearUserQueue clears all tracks when target is null and bypass is true`() {
        val queue = TrackQueue()
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()
        val mod = UUID.randomUUID()
        queue.enqueueUser(SAMPLE_TRACK.copy { id = "t1"; title = "T1" }, enqueuedBy = user1)
        queue.enqueueUser(SAMPLE_TRACK.copy { id = "t2"; title = "T2" }, enqueuedBy = user2)

        val outcome = queue.clearUserQueue(targetUserId = null, targetUserName = null, requesterId = mod, bypassOwnership = true)
        assertEquals(2, outcome.removedCount)
        assertTrue(queue.userQueueSnapshot().isEmpty())
    }

    @Test
    fun `clearUserQueue rejects clearing all tracks when bypass is false`() {
        val queue = TrackQueue()
        val user1 = UUID.randomUUID()
        queue.enqueueUser(SAMPLE_TRACK.copy { id = "t1"; title = "T1" }, enqueuedBy = user1)

        val outcome = queue.clearUserQueue(targetUserId = null, targetUserName = null, requesterId = user1, bypassOwnership = false)
        assertEquals(0, outcome.removedCount)
        assertEquals(1, queue.userQueueSnapshot().size)
    }

    @Test
    fun `clearUserQueue rejects clearing another users tracks without bypass`() {
        val queue = TrackQueue()
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()
        queue.enqueueUser(SAMPLE_TRACK.copy { id = "t1"; title = "T1" }, enqueuedBy = user2)

        val outcome = queue.clearUserQueue(targetUserId = user2, targetUserName = null, requesterId = user1, bypassOwnership = false)
        assertEquals(0, outcome.removedCount)
        assertEquals(1, queue.userQueueSnapshot().size)
    }

    @Test
    fun `clearUserQueue matches offline player by targetUserName`() {
        val queue = TrackQueue()
        val mod = UUID.randomUUID()
        val track = SAMPLE_TRACK.copy {
            id = "offline-t1"
            title = "Offline Track"
            submittedByUserName = "OfflineUser"
        }
        queue.enqueueUser(track)

        val outcome = queue.clearUserQueue(targetUserId = null, targetUserName = "offlineuser", requesterId = mod, bypassOwnership = true)
        assertEquals(1, outcome.removedCount)
        assertEquals("offline-t1", outcome.removedTracks[0].id)
        assertTrue(queue.userQueueSnapshot().isEmpty())
    }

    @Test
    fun `clearUserQueue does not clear tracks when targetUserName is blank even if bypass is true`() {
        val queue = TrackQueue()
        val mod = UUID.randomUUID()
        val track = SAMPLE_TRACK.copy {
            id = "offline-t1"
            title = "Offline Track"
            submittedByUserName = "OfflineUser"
        }
        queue.enqueueUser(track)

        val outcome = queue.clearUserQueue(targetUserId = null, targetUserName = "   ", requesterId = mod, bypassOwnership = true)
        assertEquals(0, outcome.removedCount)
        assertEquals(1, queue.userQueueSnapshot().size)
    }

    @Test
    fun `autoplay manager notifies when initial async deck becomes available`() {
        val queue = TrackQueue()
        val latch = CountDownLatch(1)
        val manager = AutoplayManager(
            config = AutoplayConfig(enabled = true, maxTracksPerSource = 10),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
        val source = object : MusicSource {
            override val id: String = "test-autoplay"

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()

            override suspend fun getAutoplayTracks(): List<TrackInfo> = listOf(
                SAMPLE_TRACK.copy { id = "autoplay-1"; sourceId = "test-autoplay" },
            )
        }

        manager.initialize(queue, listOf(source)) { latch.countDown() }

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Initial autoplay refetch should notify when tracks become available")
        assertEquals("autoplay-1", queue.nextTrack()?.track?.id)
    }

    @Test
    fun `autoplay manager throttles repeated empty refetch attempts`() {
        val queue = TrackQueue()
        var fetchCount = 0
        val manager = AutoplayManager(
            config = AutoplayConfig(enabled = true, maxTracksPerSource = 10),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            minimumRefetchIntervalMs = 5_000L,
        )
        val source = object : MusicSource {
            override val id: String = "empty-autoplay"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()

            override suspend fun getAutoplayTracks(): List<TrackInfo> {
                fetchCount += 1
                return emptyList()
            }
        }

        manager.initialize(queue, listOf(source))
        assertTrue(awaitCondition(timeoutMs = 1_000) { fetchCount == 1 })

        repeat(5) {
            assertNull(queue.nextTrack())
        }
        Thread.sleep(100)

        assertEquals(1, fetchCount, "empty autoplay decks should not retrigger immediate refetch loops")
    }

    @Test
    fun `autoplay failure does not trigger repeated immediate refetches`() {
        val queue = TrackQueue()
        val controller = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = queue,
            eventBus = EventBusImpl(),
        )
        var autoplayFetchCount = 0
        val source = object : MusicSource {
            override val id: String = "broken-autoplay"

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = throw SourceNetworkException()

            override suspend fun getAutoplayTracks(): List<TrackInfo> {
                autoplayFetchCount += 1
                return listOf(
                    SAMPLE_TRACK.copy { id = "broken-autoplay-$autoplayFetchCount"; sourceId = "broken-autoplay" },
                )
            }
        }
        PluginManager.musicSources += source
        try {
            val manager = AutoplayManager(
                config = AutoplayConfig(enabled = true, maxTracksPerSource = 10),
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                minimumRefetchIntervalMs = 5_000L,
            )

            manager.initialize(queue, listOf(source), controller::startNextIfStopped)

            assertTrue(awaitCondition(timeoutMs = 1_000) { autoplayFetchCount >= 1 })
            Thread.sleep(200)

            assertTrue(autoplayFetchCount <= 2, "all-fail autoplay tracks should not hammer autoplay refetches")
            assertNull(controller.currentContext)
        } finally {
            PluginManager.musicSources.clear()
        }
    }
}

// ---------------------------------------------------------------------------
// ServerPlaybackController tests
// ---------------------------------------------------------------------------

@ResourceLock("PluginManager")
class ServerPlaybackControllerTest {

    @Test
    fun `play broadcasts PlaybackSnapshotPush packet`() {
        val (ctrl, channel) = freshController()
        ctrl.play(SAMPLE_TRACK, RESOLVED_PLAYBACK)

        val pkt = channel.broadcasts.singleOrNull { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH }
        assertNotNull(pkt, "PlaybackSnapshotPush broadcast expected")

        val decoded = PlaybackSnapshotPush.ADAPTER.decode(pkt.payload)
        assertEquals(
            PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
            decoded.reason,
        )
        val snapshot = assertNotNull(decoded.snapshot)
        assertEquals(SAMPLE_TRACK.id, snapshot.track?.id)
        assertEquals(RESOLVED_PLAYBACK_URL, snapshot.playback?.url)
        assertEquals(RESOLVED_PLAYBACK.headers, snapshot.playback?.headers)
        assertEquals("", snapshot.lyric_lrc)
        assertEquals("", snapshot.secondary_lyric_lrc)
        assertTrue(snapshot.position_anchor_server_monotonic > System.nanoTime() - 3_000_000_000L,
            "position anchor should be recent + 2s buffer")
    }

    @Test
    fun `play rejects file playback URLs unless server config opts in`() {
        ModConfigManager.load(Files.createTempDirectory("moemusic-playback-policy-test"))
        ModConfigManager.save(MoeMusicConfig(media = MediaPolicyConfig(allowLocalFiles = false)))

        val (ctrl, channel) = freshController()
        ctrl.play(SAMPLE_TRACK, PlaybackResource("file:///tmp/test.mp3"))

        assertNull(channel.broadcasts.singleOrNull { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH })
        assertNull(ctrl.currentContext)
    }

    @Test
    fun `play broadcasts lyric payload when track has lyrics`() {
        val (ctrl, channel) = freshController()
        val track = SAMPLE_TRACK.copy { lyricLrc = "[00:01.00]Hello"; secondaryLyricLrc = "[00:01.00]你好"; lyricsFetched = true }

        ctrl.play(track, RESOLVED_PLAYBACK)

        val pkt = channel.broadcasts.single { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH }
        val decoded = PlaybackSnapshotPush.ADAPTER.decode(pkt.payload)
        val snapshot = assertNotNull(decoded.snapshot)
        assertEquals("[00:01.00]Hello", snapshot.lyric_lrc)
        assertEquals("[00:01.00]你好", snapshot.secondary_lyric_lrc)
    }

    @Test
    fun `play updates currentContext to Playing`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK, RESOLVED_PLAYBACK)
        assertNotNull(ctrl.currentContext)
        assertTrue(ctrl.currentContext?.state is PlaybackState.Playing)
        assertEquals(SAMPLE_TRACK.id, ctrl.currentContext?.track?.id)
        assertEquals(RESOLVED_PLAYBACK, ctrl.currentContext?.playback)
    }

    @Test
    fun `submitTrack preserves resolve time metadata patch across getTrackInfo refresh`() = runBlocking {
        var getTrackInfoCalls = 0
        val source = object : MusicSource {
            override val id: String = SAMPLE_SOURCE_ID

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=resolve")) {
                    trackPatch = ResolvedTrackPatch {
                        loudness = LoudnessInfo { integratedLufs = -9.5 }
                        album = "Resolve Album"
                    }
                }

            override suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser?): UserResult<TrackInfo?> {
                getTrackInfoCalls += 1
                return UserResult.Success(
                    TrackInfo(
                        id = trackId,
                        title = "Authoritative",
                        artists = listOf("Artist").toArtistInfos(),
                        durationMs = 180_000,
                    ) {
                        sourceId = id
                    }
                )
            }
        }
        PluginManager.musicSources += source
        try {
            val (ctrl, _) = freshController()
            val result = ctrl.submitTrack(
                track = SAMPLE_TRACK.copy { id = "resolve-metadata-track"; sourceId = source.id },
                requesterId = SAMPLE_PLAYER.id,
                mode = TrackAddMode.PLAY_NOW,
            )

            assertEquals(TrackAddResult.PLAYING_NOW, result)
            assertEquals(1, getTrackInfoCalls)
            assertEquals("Authoritative", ctrl.currentContext?.track?.title)
            assertEquals("Resolve Album", ctrl.currentContext?.track?.album)
            assertEquals(-9.5, ctrl.currentContext?.track?.loudness?.integratedLufs)
        } finally {
            PluginManager.musicSources -= source
        }
    }

    @Test
    fun `submitTrack merges lufs from metadata refresh with peak from resolve patch`() = runBlocking {
        val source = object : MusicSource {
            override val id: String = SAMPLE_SOURCE_ID

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=resolve")) {
                    trackPatch = ResolvedTrackPatch {
                        loudness = LoudnessInfo {
                            peak = PeakInfo(0.42) { kind = PeakKind.UNKNOWN }
                        }
                    }
                }

            override suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser?): UserResult<TrackInfo?> =
                UserResult.Success(
                    TrackInfo(
                        id = trackId,
                        title = "Authoritative",
                        artists = listOf("Artist").toArtistInfos(),
                        durationMs = 180_000,
                    ) {
                        sourceId = id
                        loudness = LoudnessInfo { integratedLufs = -11.5 }
                    }
                )
        }
        PluginManager.musicSources += source
        try {
            val (ctrl, _) = freshController()
            val result = ctrl.submitTrack(
                track = SAMPLE_TRACK.copy { id = "resolve-loudness-merge-track"; sourceId = source.id },
                requesterId = SAMPLE_PLAYER.id,
                mode = TrackAddMode.PLAY_NOW,
            )

            assertEquals(TrackAddResult.PLAYING_NOW, result)
            assertEquals(-11.5, ctrl.currentContext?.track?.loudness?.integratedLufs)
            assertEquals(0.42, ctrl.currentContext?.track?.loudness?.peak?.amplitudeLinear)
            assertEquals(PeakKind.UNKNOWN, ctrl.currentContext?.track?.loudness?.peak?.kind)
        } finally {
            PluginManager.musicSources -= source
        }
    }

    @Test
    fun `resolve lyricsFetched suppresses extra getTrackInfo refresh`() = runBlocking {
        var getTrackInfoCalls = 0
        val source = object : MusicSource {
            override val id: String = SAMPLE_SOURCE_ID

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=resolve")) {
                    trackPatch = ResolvedTrackPatch {
                        lyricLrc = "[00:01.00]Hello"
                        secondaryLyricLrc = "[00:01.00]你好"
                        lyricsFetched = true
                    }
                }

            override suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser?): UserResult<TrackInfo?> {
                getTrackInfoCalls += 1
                return UserResult.Error(LocalizedText.plain("should not be called"))
            }
        }
        PluginManager.musicSources += source
        try {
            val (ctrl, channel) = freshController()
            val result = ctrl.submitTrack(
                track = SAMPLE_TRACK.copy { id = "resolve-lyrics-track"; sourceId = source.id },
                requesterId = SAMPLE_PLAYER.id,
                mode = TrackAddMode.PLAY_NOW,
            )

            val pkt = channel.broadcasts.single { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH }
            val decoded = PlaybackSnapshotPush.ADAPTER.decode(pkt.payload)
            val snapshot = assertNotNull(decoded.snapshot)
            assertEquals(TrackAddResult.PLAYING_NOW, result)
            assertEquals(0, getTrackInfoCalls)
            assertEquals("[00:01.00]Hello", snapshot.lyric_lrc)
            assertEquals("[00:01.00]你好", snapshot.secondary_lyric_lrc)
            assertTrue(ctrl.currentContext?.track?.lyricsFetched == true)
        } finally {
            PluginManager.musicSources -= source
        }
    }

    @Test
    fun `generic proto conversion omits unavailable reason`() {
        val track = SAMPLE_TRACK.copy {
            unavailableReason = LocalizedText.plain("Requires VIP")
            loudness = LoudnessInfo { integratedLufs = -13.5 }
        }

        val roundTrip = track.toProto().toApi()

        assertTrue(roundTrip.isAvailable)
        assertEquals(null, roundTrip.unavailableReason)
        assertEquals(-13.5, roundTrip.loudness?.integratedLufs)
    }

    @Test
    fun `pause broadcasts StateUpdate PAUSED and updates state`() {
        val (ctrl, channel) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        Thread.sleep(50) // let a little time pass so position > 0
        ctrl.pause()

        val pkt = channel.broadcasts.last { it.id == PacketIds.STATE_UPDATE }
        val decoded = StateUpdate.ADAPTER.decode(pkt.payload)
        assertEquals(PlaybackStateProto.PAUSED, decoded.state)
        assertTrue(ctrl.currentContext?.state is PlaybackState.Paused)
    }

    @Test
    fun `broadcast failure does not block playback state transitions`() {
        val (ctrl, channel) = freshController()
        channel.throwOnBroadcast = true

        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        ctrl.pause()

        assertIs<PlaybackState.Paused>(ctrl.currentContext?.state)
    }

    @Test
    fun `resume after pause broadcasts StateUpdate PLAYING`() {
        val (ctrl, channel) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        ctrl.pause()
        ctrl.resume()

        val updates = channel.broadcasts.filter { it.id == PacketIds.STATE_UPDATE }
        val last = StateUpdate.ADAPTER.decode(updates.last().payload)
        assertEquals(PlaybackStateProto.PLAYING, last.state)
        assertNotNull(last.playback)
        assertTrue(last.position_anchor_server_monotonic <= System.nanoTime(),
            "resume should use an immediate position anchor")
        assertTrue(ctrl.currentContext?.state is PlaybackState.Playing)
    }

    @Test
    fun `seek while playing rebases state update to the requested position`() {
        val (ctrl, channel) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())

        ctrl.seek(42_000L)

        val updates = channel.broadcasts.filter { it.id == PacketIds.STATE_UPDATE }
        val last = StateUpdate.ADAPTER.decode(updates.last().payload)
        assertEquals(PlaybackStateProto.PLAYING, last.state)
        assertEquals(42_000L, last.position_ms)
        assertNotNull(last.playback)
        assertTrue(last.position_anchor_server_monotonic <= System.nanoTime(),
            "seek should use an immediate position anchor")
        assertEquals(42_000L, (ctrl.currentContext?.state as? PlaybackState.Playing)?.positionMs)
    }

    @Test
    fun `seek while playing resets auto advance to the new remaining duration`() {
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "next-track"; title = "Next Track" })
        }
        val (ctrl, channel) = freshController(queue)
        val shortTrack = SAMPLE_TRACK.copy { id = "short-track"; title = "Short Track"; durationMs = 180L }

        withSampleSource {
            ctrl.play(shortTrack, shortTrack.directPlayback())
            ctrl.seek(150L)

            assertTrue(awaitCondition(timeoutMs = 1_000) {
                channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH } >= 2
            }, "seek should rebuild the advance timer based on the new remaining duration")
            assertEquals("next-track", ctrl.currentContext?.track?.id)
        }
    }

    @Test
    fun `pause cancels auto advance until playback resumes`() {
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "next-track"; title = "Next Track" })
        }
        val (ctrl, channel) = freshController(queue)
        val shortTrack = SAMPLE_TRACK.copy { id = "short-track"; title = "Short Track"; durationMs = 160L }

        withSampleSource {
            ctrl.play(shortTrack, shortTrack.directPlayback())
            ctrl.seek(120L)
            ctrl.pause()

            Thread.sleep(120)
            assertEquals(1, channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH },
                "pause should cancel the in-flight advance timer")

            ctrl.resume()

            assertTrue(awaitCondition(timeoutMs = 1_000) {
                channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH } >= 2
            }, "resume should rebuild the advance timer from the paused position")
            assertEquals("next-track", ctrl.currentContext?.track?.id)
        }
    }

    @Test
    fun `stop broadcasts StateUpdate STOPPED and clears context`() {
        val (ctrl, channel) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        ctrl.stop()

        val pkt = channel.broadcasts.last { it.id == PacketIds.STATE_UPDATE }
        val decoded = StateUpdate.ADAPTER.decode(pkt.payload)
        assertEquals(PlaybackStateProto.STOPPED, decoded.state)
        assertNull(ctrl.currentContext)
    }

    @Test
    fun `playback lifecycle emits runtime events exactly once`() {
        val events = mutableListOf<String>()
        var started: OnPlaybackStarted? = null
        var paused: OnPlaybackPaused? = null
        var resumed: OnPlaybackResumed? = null
        var seeked: OnPlaybackSeeked? = null
        var stopped: OnPlaybackStopped? = null
        val eventBus = EventBusImpl().apply {
            subscribe(OnPlaybackStarted::class.java) { started = it; events += "started" }
            subscribe(OnPlaybackPaused::class.java) { paused = it; events += "paused" }
            subscribe(OnPlaybackResumed::class.java) { resumed = it; events += "resumed" }
            subscribe(OnPlaybackSeeked::class.java) { seeked = it; events += "seeked" }
            subscribe(OnPlaybackStopped::class.java) { stopped = it; events += "stopped" }
        }
        val ctrl = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = TrackQueue(),
            eventBus = eventBus,
        )

        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        ctrl.pause()
        ctrl.resume()
        ctrl.seek(12_345L)
        ctrl.stop()

        assertEquals(listOf("started", "paused", "resumed", "seeked", "stopped"), events)
        assertEquals(false, assertNotNull(started).fromAutoplay)
        assertEquals(false, assertNotNull(paused).automatic)
        assertEquals(false, assertNotNull(resumed).automatic)
        assertEquals(true, assertNotNull(seeked).wasPlaying)
        assertEquals(true, assertNotNull(stopped).manual)
    }

    @Test
    fun `queue removal emits runtime event`() {
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "queued-1"; title = "Queued 1" }, enqueuedBy = SAMPLE_PLAYER.id)
        }
        var removedEvent: OnQueueTrackRemoved? = null
        val eventBus = EventBusImpl().apply {
            subscribe(OnQueueTrackRemoved::class.java) { removedEvent = it }
        }
        val ctrl = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = queue,
            eventBus = eventBus,
        )

        val removal = ctrl.removeQueuedTrack(
            sourceId = SAMPLE_SOURCE_ID,
            trackId = "queued-1",
            requester = SAMPLE_PLAYER,
            bypassOwnership = false,
        )

        assertEquals(QueueRemoveResult.REMOVED, removal)
        val removed = assertNotNull(removedEvent)
        assertEquals("queued-1", removed.track.id)
        assertEquals(SAMPLE_PLAYER, removed.requester)
        assertEquals(false, removed.bypassOwnership)
    }

    @Test
    fun `clearQueue emits OnQueueCleared event and preserves currently playing track`() {
        val user1 = object : MoeMusicUser() {
            override val displayName: String = "User1"
            override val id: UUID = UUID.randomUUID()
            override val locale: String = "en_us"
            override fun hasPermission(permission: String, defaultLevel: Int): Boolean = false
        }
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "q-1"; title = "Queued 1" }, enqueuedBy = user1.id)
            enqueueUser(SAMPLE_TRACK.copy { id = "q-2"; title = "Queued 2" }, enqueuedBy = user1.id)
        }
        var clearedEvent: OnQueueCleared? = null
        val eventBus = EventBusImpl().apply {
            subscribe(OnQueueCleared::class.java) { clearedEvent = it }
        }
        val ctrl = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = queue,
            eventBus = eventBus,
        )

        withSampleSource {
            val playingTrack = SAMPLE_TRACK.copy { id = "playing-now"; title = "Currently Playing" }
            ctrl.play(playingTrack, playingTrack.directPlayback())
            assertTrue(ctrl.currentContext?.state is PlaybackState.Playing)

            val outcome = ctrl.clearQueue(
                targetUserId = user1.id,
                requester = user1,
                bypassOwnership = false,
            )

            assertEquals(2, outcome.removedCount)
            assertNull(outcome.failure)
            assertTrue(queue.userQueueSnapshot().isEmpty())

            // Playing track remains active and unaffected
            val current = assertNotNull(ctrl.currentContext)
            assertEquals("playing-now", current.track.id)
            assertTrue(current.state is PlaybackState.Playing)

            // Event verification
            val evt = assertNotNull(clearedEvent)
            assertEquals(2, evt.removedTracks.size)
            assertEquals(listOf("q-1", "q-2"), evt.removedTracks.map { it.id })
            assertEquals(user1, evt.requester)
            assertEquals(user1.id, evt.targetUserId)
            assertFalse(evt.bypassOwnership)
        }
    }

    @Test
    fun `skip to next track does not broadcast STOPPED between tracks`() {
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "next-track"; title = "Next Track" })
        }
        val (ctrl, channel) = freshController(queue)

        withSampleSource {
            ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
            channel.broadcasts.clear()

            ctrl.skip()

            assertTrue(awaitCondition(timeoutMs = 1_000) {
                channel.broadcasts.any { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH }
            })
            assertFalse(
                channel.broadcasts.any { packet ->
                    packet.id == PacketIds.STATE_UPDATE &&
                        StateUpdate.ADAPTER.decode(packet.payload).state == PlaybackStateProto.STOPPED
                },
                "normal track advance should switch directly to PlaybackSnapshotPush without a transient STOPPED packet",
            )
            assertEquals("next-track", ctrl.currentContext?.track?.id)
        }
    }

    @Test
    fun `manual stop blocks autoplay start until resume`() {
        val queue = TrackQueue().apply {
            autoplaySupplier = { SAMPLE_TRACK.copy { id = "autoplay-track"; title = "Autoplay Track" } }
        }
        val (ctrl, channel) = freshController(queue)

        withSampleSource {
            ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
            ctrl.stop()

            ctrl.startNextIfStopped()
            Thread.sleep(100)

            assertEquals(1, channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH })
            assertNull(ctrl.currentContext)

            ctrl.resume()

            assertTrue(awaitCondition(timeoutMs = 1_000) {
                channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH } >= 2
            })
            assertEquals("autoplay-track", ctrl.currentContext?.track?.id)
        }
    }

    @Test
    fun `manual stop keeps submitted track queued without starting playback`() {
        val (ctrl, channel) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        ctrl.stop()
        val queuedTrack = SAMPLE_TRACK.copy { id = "queued-after-stop"; title = "Queued After Stop" }

        ctrl.enqueueAndPlay(queuedTrack)
        Thread.sleep(100)

        assertEquals(1, channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH })
        assertNull(ctrl.currentContext)
        assertEquals(listOf("queued-after-stop"), ctrl.queue.userQueueSnapshot().map { it.id })
    }

    @Test
    fun `resume after manual stop starts queued track`() {
        val (ctrl, channel) = freshController()

        withSampleSource {
            ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
            ctrl.stop()
            val queuedTrack = SAMPLE_TRACK.copy { id = "queued-after-stop"; title = "Queued After Stop" }
            ctrl.enqueueAndPlay(queuedTrack)

            ctrl.resume()

            assertTrue(awaitCondition(timeoutMs = 1_000) {
                channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH } >= 2
            })
            assertEquals("queued-after-stop", ctrl.currentContext?.track?.id)
        }
    }

    @Test
    fun `auto pause does not arm on manual pause`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        ctrl.pause()

        ctrl.autoPause()
        ctrl.autoResume()

        assertTrue(ctrl.currentContext?.state is PlaybackState.Paused)
    }

    @Test
    fun `auto pause followed by stop does not auto resume`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())
        ctrl.autoPause()
        ctrl.stop()

        ctrl.autoResume()

        assertNull(ctrl.currentContext)
    }

    @Test
    fun `natural exhaustion still allows later autoplay start`() {
        val queue = TrackQueue()
        val (ctrl, channel) = freshController(queue)
        val shortTrack = SAMPLE_TRACK.copy { id = "short-track"; title = "Short Track"; durationMs = 120L }

        withSampleSource {
            ctrl.play(shortTrack, shortTrack.directPlayback())
            ctrl.seek(100L)

            assertTrue(awaitCondition(timeoutMs = 1_000) {
                ctrl.currentContext == null
            })

            queue.autoplaySupplier = { SAMPLE_TRACK.copy { id = "autoplay-after-exhaust"; title = "Autoplay After Exhaust" } }
            ctrl.startNextIfStopped()

            assertTrue(awaitCondition(timeoutMs = 1_000) {
                channel.broadcasts.count { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH } >= 2
            })
            assertEquals("autoplay-after-exhaust", ctrl.currentContext?.track?.id)
        }
    }

    @Test
    fun `play failure emits start failed event and prevents PlaybackSnapshotPush broadcast`() {
        ModConfigManager.load(Files.createTempDirectory("moemusic-playback-start-failed-test"))
        ModConfigManager.save(MoeMusicConfig(media = MediaPolicyConfig(allowLocalFiles = false)))
        val channel = CapturingChannel()
        var failure: OnPlaybackStartFailed? = null
        val eventBus = EventBusImpl().apply {
            subscribe(OnPlaybackStartFailed::class.java) { failure = it }
        }

        val ctrl = ServerPlaybackController(
            channel = channel,
            queue = TrackQueue(),
            eventBus = eventBus,
        )
        ctrl.play(SAMPLE_TRACK, PlaybackResource("file:///tmp/test.mp3"))

        val event = assertNotNull(failure)
        assertEquals(SAMPLE_TRACK.id, event.track.id)
        assertEquals(false, event.fromAutoplay)
        assertEquals(LocalizedText.key("error.moemusic.media_policy.local_file_disabled"), event.reason)
        assertTrue(channel.broadcasts.none { it.id == PacketIds.PLAYBACK_SNAPSHOT_PUSH })
        assertNull(ctrl.currentContext)
    }

    @Test
    fun `user queue failure callback is invoked when resolve fails`() {
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "bad-track"; title = "Bad Track"; sourceId = "broken" })
            enqueueUser(SAMPLE_TRACK.copy { id = "good-track"; title = "Good Track" })
        }
        val incidents = mutableListOf<Pair<String, LocalizedText?>>()
        val ctrl = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = queue,
            eventBus = EventBusImpl(),
            onUserQueueTrackSkipped = { track, reason -> incidents += track.title to reason },
        )
        val source = object : MusicSource {
            override val id: String = "broken"

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = throw SourceNetworkException()
        }
        PluginManager.musicSources += source
        PluginManager.musicSources += SAMPLE_SOURCE
        try {
            ctrl.startNextIfStopped()

            assertTrue(awaitCondition(timeoutMs = 1_000) { incidents.isNotEmpty() && ctrl.currentContext?.track?.id == "good-track" })
            assertEquals(listOf("Bad Track"), incidents.map { it.first })
            assertEquals(LocalizedText.key("error.moemusic.source.network"), incidents.single().second)
        } finally {
            PluginManager.musicSources.clear()
        }
    }

    @Test
    fun `user queue failure callback is invoked when playback resource is blocked`() {
        ModConfigManager.load(Files.createTempDirectory("moemusic-playback-policy-failure-test"))
        ModConfigManager.save(MoeMusicConfig(media = MediaPolicyConfig(allowLocalFiles = false)))
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "blocked-track"; title = "Blocked Track"; sourceId = "blocked" })
            enqueueUser(SAMPLE_TRACK.copy { id = "good-track"; title = "Good Track" })
        }
        val incidents = mutableListOf<Pair<String, LocalizedText?>>()
        val ctrl = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = queue,
            eventBus = EventBusImpl(),
            onUserQueueTrackSkipped = { track, reason -> incidents += track.title to reason },
        )
        val blockedSource = object : MusicSource {
            override val id: String = "blocked"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("file:///tmp/${track.id}.mp3"))
        }

        PluginManager.musicSources += blockedSource
        PluginManager.musicSources += SAMPLE_SOURCE
        try {
            ctrl.startNextIfStopped()

            assertTrue(awaitCondition(timeoutMs = 1_000) { incidents.isNotEmpty() && ctrl.currentContext?.track?.id == "good-track" })
            assertEquals(listOf("Blocked Track"), incidents.map { it.first })
            assertEquals(LocalizedText.key("error.moemusic.media_policy.local_file_disabled"), incidents.single().second)
        } finally {
            PluginManager.musicSources.clear()
        }
    }

    @Test
    fun `enqueueAndPlay rejects unavailable tracks before queueing`() {
        val (ctrl, _) = freshController()
        val blocked = SAMPLE_TRACK.copy { unavailableReason = LocalizedText.plain("Copyright restricted") }

        val error = kotlin.runCatching { ctrl.enqueueAndPlay(blocked) }.exceptionOrNull()

        val unavailable = assertIs<TrackUnavailableException>(error)
        assertEquals(LocalizedText.key("error.moemusic.track_unavailable.reason", LocalizedText.plain("Copyright restricted")), unavailable.userMessage)
        assertTrue(ctrl.queue.userQueueSnapshot().isEmpty())
        assertNull(ctrl.currentContext)
    }

    @Test
    fun `play now surfaces typed source failures as user facing exceptions`() = runBlocking {
        val source = object : MusicSource {
            override val id: String = "ncm"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = throw SourceNetworkException()
        }
        PluginManager.musicSources += source
        try {
            val (ctrl, _) = freshController()
            val track = SAMPLE_TRACK.copy { id = "net-1"; sourceId = source.id }

            val error = kotlin.runCatching { ctrl.submitTrack(track, null, TrackAddMode.PLAY_NOW) }.exceptionOrNull()

            val userFacing = assertIs<UserFacingException>(error)
            assertEquals(LocalizedText.key("error.moemusic.source.network"), userFacing.userMessage)
            assertNull(ctrl.currentContext)
        } finally {
            PluginManager.musicSources.clear()
        }
    }

    @Test
    fun `play now converts unexpected source failures to internal user feedback`() = runBlocking {
        val source = object : MusicSource {
            override val id: String = "ncm"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = throw IllegalStateException("boom")
        }
        PluginManager.musicSources += source
        try {
            val (ctrl, _) = freshController()
            val track = SAMPLE_TRACK.copy { id = "broken-1"; sourceId = source.id }

            val error = kotlin.runCatching { ctrl.submitTrack(track, null, TrackAddMode.PLAY_NOW) }.exceptionOrNull()

            val userFacing = assertIs<UserFacingException>(error)
            assertEquals(LocalizedText.key("error.moemusic.internal"), userFacing.userMessage)
            assertNull(ctrl.currentContext)
        } finally {
            PluginManager.musicSources.clear()
        }
    }

    @Test
    fun `buildPlaybackSnapshot returns null when stopped`() {
        val (ctrl, _) = freshController()
        assertNull(ctrl.buildPlaybackSnapshot())
    }

    @Test
    fun `buildPlaybackSnapshot returns snapshot when playing`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK.copy { sourceId = "ncm" }, RESOLVED_PLAYBACK)
        val snapshot = ctrl.buildPlaybackSnapshot()
        assertNotNull(snapshot)
        assertEquals("", snapshot.lyric_lrc)
        assertEquals("", snapshot.secondary_lyric_lrc)
    }

    @Test
    fun `buildPlaybackSnapshot includes playing position for unsynced clients`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK.copy { sourceId = "ncm" }, RESOLVED_PLAYBACK)
        ctrl.seek(65_000L)

        val snapshot = ctrl.buildPlaybackSnapshot()

        assertNotNull(snapshot)
        assertEquals(PlaybackStateProto.PLAYING, snapshot.state)
        assertTrue(snapshot.position_ms in 65_000L..66_000L)
        assertTrue(snapshot.position_anchor_server_monotonic > 0L)
    }

    @Test
    fun `buildPlaybackSnapshot includes lyrics for late joiners`() {
        val (ctrl, _) = freshController()
        ctrl.play(
            SAMPLE_TRACK.copy { sourceId = "ncm"; lyricLrc = "[00:01.00]Hello"; secondaryLyricLrc = "[00:01.00]你好"; lyricsFetched = true },
            RESOLVED_PLAYBACK,
        )

        val snapshot = ctrl.buildPlaybackSnapshot()
        assertNotNull(snapshot)
        assertEquals("[00:01.00]Hello", snapshot.lyric_lrc)
        assertEquals("[00:01.00]你好", snapshot.secondary_lyric_lrc)
    }

    @Test
    fun `buildPlaybackSnapshot refreshes playback at most once per cooldown window`() {
        val refreshSourceId = "refresh-cooldown-source"
        val source = object : MusicSource {
            override val id: String = refreshSourceId
            var resolveCalls: Int = 0

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
                resolveCalls += 1
                return PlaybackResolution(PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=$resolveCalls"))
            }
        }
        PluginManager.musicSources += source
        try {
            val ctrl = ServerPlaybackController(
                channel = CapturingChannel(),
                queue = TrackQueue(),
                eventBus = EventBusImpl(),
            ).apply {
                playbackRefreshCooldownNanos = 0L
                playbackRefreshFailureBackoffNanos = TimeUnit.MINUTES.toNanos(1)
            }
            val track = SAMPLE_TRACK.copy { id = "refreshable-track"; sourceId = source.id }
            ctrl.play(track, PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=initial"))
            ctrl.playbackRefreshCooldownNanos = TimeUnit.MINUTES.toNanos(1)

            val first = assertNotNull(ctrl.buildPlaybackSnapshot())
            val second = assertNotNull(ctrl.buildPlaybackSnapshot())

            assertEquals(1, source.resolveCalls)
            assertEquals("https://cdn.example.com/audio/${track.id}.mp3?sig=1", first.playback?.url)
            assertEquals(first.playback?.url, second.playback?.url)
            assertEquals(first.playback?.url, ctrl.currentContext?.playback?.url)
        } finally {
            PluginManager.musicSources -= source
        }
    }

    @Test
    fun `buildPlaybackSnapshot refresh updates current track metadata from resolve`() {
        val refreshSourceId = "refresh-metadata-source"
        val source = object : MusicSource {
            override val id: String = refreshSourceId
            var resolveCalls: Int = 0

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
                resolveCalls += 1
                return PlaybackResolution(
                    PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=$resolveCalls")
                ) {
                    trackPatch = ResolvedTrackPatch {
                        loudness = LoudnessInfo {
                            integratedLufs = -12.0
                            peak = PeakInfo(0.85) { kind = PeakKind.TRUE }
                        }
                        lyricLrc = "[00:01.00]Hello"
                        secondaryLyricLrc = "[00:01.00]你好"
                        lyricsFetched = true
                    }
                }
            }
        }
        PluginManager.musicSources += source
        try {
            val ctrl = ServerPlaybackController(
                channel = CapturingChannel(),
                queue = TrackQueue(),
                eventBus = EventBusImpl(),
            ).apply {
                playbackRefreshCooldownNanos = 0L
                playbackRefreshFailureBackoffNanos = TimeUnit.MINUTES.toNanos(1)
            }
            val track = SAMPLE_TRACK.copy { id = "refresh-metadata-track"; sourceId = source.id }
            ctrl.play(track, PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=initial"))

            val snapshot = assertNotNull(ctrl.buildPlaybackSnapshot())

            assertEquals(1, source.resolveCalls)
            assertEquals("https://cdn.example.com/audio/${track.id}.mp3?sig=1", snapshot.playback?.url)
            assertEquals(-12.0, snapshot.track?.loudness?.integrated_lufs)
            assertEquals(0.85, snapshot.track?.loudness?.peak?.amplitude_linear)
            assertEquals("[00:01.00]Hello", snapshot.lyric_lrc)
            assertEquals("[00:01.00]你好", snapshot.secondary_lyric_lrc)
            assertEquals(-12.0, ctrl.currentContext?.track?.loudness?.integratedLufs)
            assertEquals(0.85, ctrl.currentContext?.track?.loudness?.peak?.amplitudeLinear)
            assertEquals("[00:01.00]Hello", ctrl.currentContext?.track?.lyricLrc)
            assertTrue(ctrl.currentContext?.track?.lyricsFetched == true)
        } finally {
            PluginManager.musicSources -= source
        }
    }

    @Test
    fun `buildPlaybackSnapshot backs off repeated refresh failures and keeps existing playback`() {
        val refreshSourceId = "failing-refresh-source"
        val source = object : MusicSource {
            override val id: String = refreshSourceId
            var resolveCalls: Int = 0

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
                resolveCalls += 1
                throw SourceNetworkException()
            }
        }
        PluginManager.musicSources += source
        try {
            val ctrl = ServerPlaybackController(
                channel = CapturingChannel(),
                queue = TrackQueue(),
                eventBus = EventBusImpl(),
            ).apply {
                playbackRefreshCooldownNanos = 0L
                playbackRefreshFailureBackoffNanos = TimeUnit.MINUTES.toNanos(1)
            }
            val track = SAMPLE_TRACK.copy { id = "failing-refresh-track"; sourceId = source.id }
            val initialPlayback = PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=initial")
            ctrl.play(track, initialPlayback)

            val first = assertNotNull(ctrl.buildPlaybackSnapshot())
            val second = assertNotNull(ctrl.buildPlaybackSnapshot())

            assertEquals(1, source.resolveCalls)
            assertEquals(initialPlayback.url, first.playback?.url)
            assertEquals(initialPlayback.url, second.playback?.url)
            assertEquals(initialPlayback.url, ctrl.currentContext?.playback?.url)
        } finally {
            PluginManager.musicSources -= source
        }
    }

    @Test
    fun `resume refreshes playback and includes it in state update`() {
        val refreshSourceId = "resume-refresh-source"
        val source = object : MusicSource {
            override val id: String = refreshSourceId
            var resolveCalls: Int = 0

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
                resolveCalls += 1
                return PlaybackResolution(PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=$resolveCalls"))
            }
        }
        PluginManager.musicSources += source
        try {
            val channel = CapturingChannel()
            val ctrl = ServerPlaybackController(
                channel = channel,
                queue = TrackQueue(),
                eventBus = EventBusImpl(),
            ).apply {
                playbackRefreshCooldownNanos = 0L
                playbackRefreshFailureBackoffNanos = 50L * 1_000_000L
            }
            val track = SAMPLE_TRACK.copy { id = "resume-refresh-track"; sourceId = source.id }
            ctrl.play(track, PlaybackResource("https://cdn.example.com/audio/${track.id}.mp3?sig=initial"))
            ctrl.pause()

            ctrl.resume()

            val updates = channel.broadcasts.filter { it.id == PacketIds.STATE_UPDATE }
            val last = StateUpdate.ADAPTER.decode(updates.last().payload)
            assertEquals(1, source.resolveCalls)
            assertEquals("https://cdn.example.com/audio/${track.id}.mp3?sig=1", last.playback?.url)
            assertEquals(last.playback?.url, ctrl.currentContext?.playback?.url)
        } finally {
            PluginManager.musicSources -= source
        }
    }

    @Test
    fun `skip autoplay mode interrupts only autoplay`() = runBlocking {
        val queue = TrackQueue().apply {
            autoplaySupplier = { SAMPLE_TRACK.copy { id = "autoplay-track"; title = "Autoplay Track" } }
        }
        val autoplayStarted = CountDownLatch(1)
        val releaseAutoplayStart = CountDownLatch(1)
        val eventBus = EventBusImpl().apply {
            subscribe(OnPlaybackStarted::class.java) { event ->
                if (event.track.id == "autoplay-track") {
                    autoplayStarted.countDown()
                    releaseAutoplayStart.await(1, TimeUnit.SECONDS)
                }
            }
        }
        val ctrl = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = queue,
            eventBus = eventBus,
        )

        withSampleSourceSuspend {
            ctrl.startNextIfStopped()
            assertTrue(autoplayStarted.await(1, TimeUnit.SECONDS), "autoplay should reach OnPlaybackStarted before the source marker is finalized")
            assertEquals("autoplay-track", ctrl.currentContext?.track?.id)

            val result = try {
                ctrl.submitTrack(SAMPLE_TRACK.copy { id = "queued-track"; title = "Queued Track" }, null, TrackAddMode.SKIP_AUTOPLAY)
            } finally {
                releaseAutoplayStart.countDown()
            }

            assertEquals(TrackAddResult.INTERRUPTING_AUTOPLAY, result)
            assertTrue(awaitCondition { ctrl.currentContext?.track?.id == "queued-track" })
        }
    }

    @Test
    fun `submit track invokes success callback with final result`() = runBlocking {
        val submissions = mutableListOf<Pair<TrackInfo, TrackAddResult>>()
        val ctrl = ServerPlaybackController(
            channel = CapturingChannel(),
            queue = TrackQueue(),
            eventBus = EventBusImpl(),
            onTrackSubmitted = { track, result -> submissions += track to result },
        )

        val track = SAMPLE_TRACK.copy { id = "queued-track"; title = "Queued Track" }
        val result = ctrl.submitTrack(track, null, TrackAddMode.NORMAL)

        assertEquals(TrackAddResult.QUEUED, result)
        assertEquals(listOf(track to TrackAddResult.QUEUED), submissions)
    }

    @Test
    fun `skip autoplay mode does not interrupt user playback`() = runBlocking {
        val (ctrl, _) = freshController()
        val currentTrack = SAMPLE_TRACK.copy { id = "current-track"; title = "Current Track" }
        ctrl.play(currentTrack, currentTrack.directPlayback())

        ctrl.submitTrack(SAMPLE_TRACK.copy { id = "queued-track"; title = "Queued Track" }, null, TrackAddMode.SKIP_AUTOPLAY)

        Thread.sleep(100)
        assertEquals("current-track", ctrl.currentContext?.track?.id)
        assertEquals(listOf("queued-track"), ctrl.queue.userQueueSnapshot().map { it.id })
    }

    @Test
    fun `play now replaces current playback immediately and preserves older queue`() = runBlocking {
        val queue = TrackQueue().apply {
            enqueueUser(SAMPLE_TRACK.copy { id = "older-queued"; title = "Older Queued" })
        }
        val (ctrl, _) = freshController(queue)
        val currentTrack = SAMPLE_TRACK.copy { id = "current-track"; title = "Current Track" }
        ctrl.play(currentTrack, currentTrack.directPlayback())

        withSampleSourceSuspend {
            ctrl.submitTrack(
                SAMPLE_TRACK.copy { id = "play-now-track"; title = "Play Now Track" },
                null,
                TrackAddMode.PLAY_NOW,
            )

            assertEquals("play-now-track", ctrl.currentContext?.track?.id)
            assertEquals(listOf("older-queued"), ctrl.queue.userQueueSnapshot().map { it.id })
        }
    }

    @Test
    fun `play now removes duplicate queued copy`() = runBlocking {
        val duplicate = SAMPLE_TRACK.copy { id = "dup-track"; title = "Queued Copy" }
        val queue = TrackQueue().apply {
            enqueueUser(duplicate)
        }
        val (ctrl, _) = freshController(queue)
        val currentTrack = SAMPLE_TRACK.copy { id = "current-track"; title = "Current Track" }
        ctrl.play(currentTrack, currentTrack.directPlayback())

        withSampleSourceSuspend {
            ctrl.submitTrack(
                duplicate,
                null,
                TrackAddMode.PLAY_NOW,
            )

            assertEquals("dup-track", ctrl.currentContext?.track?.id)
            assertTrue(ctrl.queue.userQueueSnapshot().isEmpty())
        }
    }

    @Test
    fun `enqueueAndPlay without bypass rejects duplicate of current track`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())

        assertFailsWith<AlreadyQueuedException> {
            ctrl.enqueueAndPlay(SAMPLE_TRACK.copy { title = "Renamed" }, requesterId = null, allowDuplicate = false)
        }
    }

    @Test
    fun `enqueueAndPlay with allowDuplicate bypasses current-track check`() {
        val (ctrl, _) = freshController()
        ctrl.play(SAMPLE_TRACK, SAMPLE_TRACK.directPlayback())

        // Should not throw even though the track is currently playing
        ctrl.enqueueAndPlay(SAMPLE_TRACK.copy { title = "Renamed" }, requesterId = null, allowDuplicate = true)

        assertEquals(1, ctrl.queue.userQueueSize(), "Duplicate should be force-enqueued when bypass is active")
    }

    @Test
    fun `enqueueAndPlay with allowDuplicate bypasses already-queued check`() {
        val queue = TrackQueue().apply { enqueueUser(SAMPLE_TRACK) }
        val (ctrl, _) = freshController(queue)
        ctrl.play(SAMPLE_TRACK.copy { id = "manual-stop-guard"; sourceId = null }, SAMPLE_TRACK.directPlayback())
        ctrl.stop()

        // No current track, and manual stop keeps startNextIfStopped from asynchronously draining the queue.
        assertFailsWith<AlreadyQueuedException> {
            ctrl.enqueueAndPlay(SAMPLE_TRACK.copy { title = "Renamed" }, requesterId = null, allowDuplicate = false)
        }

        // With bypass the second copy is accepted
        ctrl.enqueueAndPlay(SAMPLE_TRACK.copy { title = "Renamed" }, requesterId = null, allowDuplicate = true)
        assertEquals(2, ctrl.queue.userQueueSize(), "Both copies should be in queue when bypass is active")
    }

    @Test
    fun `userQueueSlice returns paginated slices with accurate metadata`() {
        val queue = TrackQueue()
        val tracks = (1..15).map { i -> SAMPLE_TRACK.copy { id = "track-$i"; title = "Title $i" } }
        tracks.forEach { queue.enqueueUser(it) }

        // First page
        val slice1 = queue.userQueueSlice(offset = 0, limit = 6)
        assertEquals(6, slice1.size)
        assertEquals("track-1", slice1.first().id)
        assertEquals("track-6", slice1.last().id)

        // Second page
        val slice2 = queue.userQueueSlice(offset = 6, limit = 6)
        assertEquals(6, slice2.size)
        assertEquals("track-7", slice2.first().id)
        assertEquals("track-12", slice2.last().id)

        // Third page
        val slice3 = queue.userQueueSlice(offset = 12, limit = 6)
        assertEquals(3, slice3.size)
        assertEquals("track-13", slice3.first().id)
        assertEquals("track-15", slice3.last().id)

        // Out-of-bounds offset
        val sliceOob = queue.userQueueSlice(offset = 50, limit = 6)
        assertTrue(sliceOob.isEmpty())
    }
}
