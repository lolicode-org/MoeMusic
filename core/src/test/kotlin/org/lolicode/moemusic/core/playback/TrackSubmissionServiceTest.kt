package org.lolicode.moemusic.core.playback

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.ResourceLock
import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.event.OnTrackSubmitted
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.SelectionSubmitOutcome
import org.lolicode.moemusic.core.config.MediaPolicyConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.event.EventBusImpl
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.plugin.PluginManager
import java.nio.file.Files
import java.util.*
import kotlin.test.*

@ResourceLock("PluginManager")
class TrackSubmissionServiceTest {

    init {
        ModConfigManager.load(Files.createTempDirectory("moemusic-track-submission-test"))
    }

    @Test
    fun `submitBySelection returns child choices without queueing`() = runBlocking {
        val source = object : MusicSource {
            override val id: String = "source"

            override suspend fun resolveSelection(selectionId: String, submitter: MoeMusicUser?): UserResult<SelectionResolveResult?> =
                UserResult.Success(
                    SelectionResolveResult.Choices(
                        listOf(
                            SelectionEntry(selectionId = "track-1", title = "Track 1", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { kind = SelectionEntryKind.TRACK }
                        )
                    )
                )

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val service = TrackSubmissionService(freshController())
            val outcome = service.submitBySelection(source.id, "album-1", mode = TrackAddMode.NORMAL)

            val choices = assertIs<SelectionSubmitOutcome.Choices>(outcome)
            assertEquals(source.id, choices.sourceId)
            assertEquals(source.id, choices.entries.single().sourceId)
            assertEquals("track-1", choices.entries.single().selectionId)
        }
    }

    @Test
    fun `submitBySelection submits direct track choice`() = runBlocking {
        val source = object : MusicSource {
            override val id: String = "source"

            override suspend fun resolveSelection(selectionId: String, submitter: MoeMusicUser?): UserResult<SelectionResolveResult?> =
                UserResult.Success(
                    SelectionResolveResult.Track(
                        TrackInfo(id = selectionId, title = "Playable", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = "source" }
                    )
                )

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val service = TrackSubmissionService(freshController())
            val outcome = service.submitBySelection(source.id, "track-1", mode = TrackAddMode.NORMAL)

            val submitted = assertIs<SelectionSubmitOutcome.Submitted>(outcome)
            assertEquals("track-1", submitted.track.id)
            assertEquals(TrackAddResult.QUEUED, submitted.result)
            assertEquals(source.id, submitted.track.sourceId)
        }
    }

    @Test
    fun `submitResolved rejects unknown duration without bypass`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig(media = MediaPolicyConfig(maxPlayerTrackDurationSeconds = 60)))
        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val service = TrackSubmissionService(freshController())
            val error = assertFailsWith<UserFacingException> {
                service.submitResolved(
                    track = TrackInfo(id = "track-1", title = "Live Track", artists = listOf("Artist").toArtistInfos(), durationMs = -1) { sourceId = source.id },
                    submitter = fakePlayer(),
                    mode = TrackAddMode.NORMAL,
                )
            }
            assertEquals("error.moemusic.track.duration_unknown", (error.userMessage as LocalizedText.Key).key)
        }
    }

    @Test
    fun `submitResolved rejects overly long duration without bypass`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig(media = MediaPolicyConfig(maxPlayerTrackDurationSeconds = 60)))
        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val service = TrackSubmissionService(freshController())
            val error = assertFailsWith<UserFacingException> {
                service.submitResolved(
                    track = TrackInfo(id = "track-1", title = "Epic Mix", artists = listOf("Artist").toArtistInfos(), durationMs = 61_000) { sourceId = source.id },
                    submitter = fakePlayer(),
                    mode = TrackAddMode.NORMAL,
                )
            }
            assertEquals("error.moemusic.track.duration_too_long", (error.userMessage as LocalizedText.Key).key)
        }
    }

    @Test
    fun `submitResolved refreshes caller supplied metadata when source can provide it`() = runBlocking {
        var getTrackInfoCalls = 0
        val source = object : MusicSource {
            override val id: String = "source"

            override suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser?): UserResult<TrackInfo?> {
                getTrackInfoCalls++
                val srcId = id
                return UserResult.Success(
                    TrackInfo(
                        id = trackId,
                        title = "Authoritative",
                        artists = listOf("Artist").toArtistInfos(),
                        durationMs = 60_000,
                    ) {
                        sourceId = srcId
                        // Keep this test scoped to submitResolved's authoritative refresh.
                        // Auto-started playback may do its own lyric metadata refresh otherwise.
                        lyricsFetched = true
                    }
                )
            }

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val service = TrackSubmissionService(freshController())
            val outcome = service.submitResolved(
                track = TrackInfo(id = "track-1", title = "Caller Supplied", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                mode = TrackAddMode.NORMAL,
            )

            assertEquals(1, getTrackInfoCalls)
            assertEquals("Authoritative", outcome.track.title)
        }
    }

    @Test
    fun `submitResolvedFromSource skips authoritative metadata refresh`() = runBlocking {
        var getTrackInfoCalls = 0
        val source = object : MusicSource {
            override val id: String = "source"

            override suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser?): UserResult<TrackInfo?> {
                getTrackInfoCalls++
                return UserResult.Error(LocalizedText.plain("should not be called"))
            }

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val service = TrackSubmissionService(freshController())
            val outcome = service.submitResolvedFromSource(
                track = TrackInfo(id = "track-1", title = "Source Resolved", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                mode = TrackAddMode.NORMAL,
            )

            assertEquals(0, getTrackInfoCalls)
            assertEquals("Source Resolved", outcome.track.title)
        }
    }

    @Test
    fun `successful submission emits OnTrackSubmitted once`() = runBlocking {
        PluginManager.eventBus.clear()
        var event: OnTrackSubmitted? = null
        PluginManager.eventBus.subscribe(OnTrackSubmitted::class.java) { event = it }

        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val service = TrackSubmissionService(freshController())
            val submitter = fakePlayer()
            val outcome = service.submitResolved(
                track = TrackInfo(id = "track-1", title = "Playable", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                submitter = submitter,
                mode = TrackAddMode.NORMAL,
            )

            val submitted = assertNotNull(event)
            assertEquals(outcome.track, submitted.track)
            assertEquals(submitter, submitted.submitter)
            assertEquals(TrackAddMode.NORMAL, submitted.mode)
            assertEquals(TrackAddResult.QUEUED, submitted.result)
        }
    }

    @Test
    fun `submitResolved rejects when user active track count exceeds limit`() = runBlocking {
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    maxPlayerTotalQueuedTracks = 2,
                    maxPlayerTrackDurationSeconds = 3600,
                )
            )
        )
        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val controller = freshController()
            val service = TrackSubmissionService(controller)
            val player = fakePlayer()

            service.submitResolved(
                track = TrackInfo(id = "track-1", title = "Track 1", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                submitter = player,
                mode = TrackAddMode.NORMAL,
            )
            service.submitResolved(
                track = TrackInfo(id = "track-2", title = "Track 2", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                submitter = player,
                mode = TrackAddMode.NORMAL,
            )

            val error = assertFailsWith<UserFacingException> {
                service.submitResolved(
                    track = TrackInfo(id = "track-3", title = "Track 3", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                    submitter = player,
                    mode = TrackAddMode.NORMAL,
                )
            }
            assertEquals("error.moemusic.track.player_queue_limit_exceeded", (error.userMessage as LocalizedText.Key).key)
        }
    }

    @Test
    fun `submitResolved rejects when user total queued duration exceeds limit`() = runBlocking {
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    maxPlayerTotalQueuedTracks = 10,
                    maxPlayerTotalQueuedDurationSeconds = 120,
                    maxPlayerTrackDurationSeconds = 3600,
                )
            )
        )
        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val controller = freshController()
            val service = TrackSubmissionService(controller)
            val player = fakePlayer()

            service.submitResolved(
                track = TrackInfo(id = "track-1", title = "Track 1", artists = listOf("Artist").toArtistInfos(), durationMs = 70_000) { sourceId = source.id },
                submitter = player,
                mode = TrackAddMode.NORMAL,
            )

            val error = assertFailsWith<UserFacingException> {
                service.submitResolved(
                    track = TrackInfo(id = "track-2", title = "Track 2", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                    submitter = player,
                    mode = TrackAddMode.NORMAL,
                )
            }
            assertEquals("error.moemusic.track.player_queue_duration_exceeded", (error.userMessage as LocalizedText.Key).key)
        }
    }

    @Test
    fun `submitResolved bypasses count and duration limits with permission`() = runBlocking {
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    maxPlayerTotalQueuedTracks = 1,
                    maxPlayerTotalQueuedDurationSeconds = 30,
                    maxPlayerTrackDurationSeconds = 30,
                )
            )
        )
        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val controller = freshController()
            val service = TrackSubmissionService(controller)
            val bypassPlayer = fakePlayer(permissions = arrayOf(org.lolicode.moemusic.core.permission.PermissionNodes.DURATION_POLICY_BYPASS.id))

            val outcome1 = service.submitResolved(
                track = TrackInfo(id = "track-1", title = "Track 1", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                submitter = bypassPlayer,
                mode = TrackAddMode.NORMAL,
            )
            val outcome2 = service.submitResolved(
                track = TrackInfo(id = "track-2", title = "Track 2", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                submitter = bypassPlayer,
                mode = TrackAddMode.NORMAL,
            )
            assertEquals("track-1", outcome1.track.id)
            assertEquals("track-2", outcome2.track.id)
        }
    }

    @Test
    fun `submitResolved allows arbitrary submissions when limits are set to 0`() = runBlocking {
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    maxPlayerTotalQueuedTracks = 0,
                    maxPlayerTotalQueuedDurationSeconds = 0,
                    maxPlayerTrackDurationSeconds = 3600,
                )
            )
        )
        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val controller = freshController()
            val service = TrackSubmissionService(controller)
            val player = fakePlayer()

            repeat(15) { i ->
                service.submitResolved(
                    track = TrackInfo(id = "track-$i", title = "Track $i", artists = listOf("Artist").toArtistInfos(), durationMs = 100_000) { sourceId = source.id },
                    submitter = player,
                    mode = TrackAddMode.NORMAL,
                )
            }
            assertEquals(15, controller.currentUserTrackMetrics(player).count)
        }
    }

    @Test
    fun `PLAY_NOW replaces current playing user track within count limit`() = runBlocking {
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    maxPlayerTotalQueuedTracks = 1,
                    maxPlayerTotalQueuedDurationSeconds = 3600,
                    maxPlayerTrackDurationSeconds = 3600,
                )
            )
        )
        val source = object : MusicSource {
            override val id: String = "source"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.com/${track.id}.mp3"))
        }

        withMusicSource(source) {
            val controller = freshController()
            val service = TrackSubmissionService(controller)
            val player = fakePlayer()

            service.submitResolved(
                track = TrackInfo(id = "track-1", title = "Track 1", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                submitter = player,
                mode = TrackAddMode.PLAY_NOW,
            )
            assertTrue(controller.isCurrentTrackFromUser(player.id, player.displayName))
            assertEquals(1, controller.currentUserTrackMetrics(player).count)

            assertFailsWith<UserFacingException> {
                service.submitResolved(
                    track = TrackInfo(id = "track-2", title = "Track 2", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                    submitter = player,
                    mode = TrackAddMode.NORMAL,
                )
            }

            val outcome = service.submitResolved(
                track = TrackInfo(id = "track-2", title = "Track 2", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = source.id },
                submitter = player,
                mode = TrackAddMode.PLAY_NOW,
            )
            assertEquals("track-2", outcome.track.id)
            assertEquals(TrackAddResult.PLAYING_NOW, outcome.result)
            assertEquals(1, controller.currentUserTrackMetrics(player).count)
        }
    }

    private fun freshController(): ServerPlaybackController = ServerPlaybackController(
        channel = object : NetworkChannel {
            override fun sendToServer(packetId: PacketId, payload: ByteArray) = Unit
            override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) = Unit
            override fun sendToAllClients(packetId: PacketId, payload: ByteArray) = Unit
        },
        queue = TrackQueue(),
        eventBus = EventBusImpl(),
        onUserQueueTrackSkipped = { _, _ -> },
    )

    private suspend fun withMusicSource(source: MusicSource, block: suspend () -> Unit) {
        synchronized(PluginManager.musicSources) {
            PluginManager.musicSources += source
        }
        try {
            block()
        } finally {
            synchronized(PluginManager.musicSources) {
                PluginManager.musicSources.remove(source)
            }
        }
    }

    private fun fakePlayer(
        id: UUID = UUID.randomUUID(),
        name: String = "tester",
        vararg permissions: String,
    ): MoeMusicUser = object : MoeMusicUser() {
        override val displayName: String = name
        override val id: UUID = id
        override val locale: String = "en_us"
        override fun hasPermission(permission: String, defaultLevel: Int): Boolean = permission in permissions
    }
}
