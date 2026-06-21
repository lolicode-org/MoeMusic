package org.lolicode.moemusic.clientcore.playback

import kotlinx.coroutines.*
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.ClientRequestException
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.core.config.ClientConfig
import org.lolicode.moemusic.core.config.LoudnessNormalizationMode
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.*
import kotlin.test.*
import org.lolicode.moemusic.core.protocol.proto.SearchSourceInfo as SearchSourceInfoProto

class ClientPlaybackRuntimeTest {

    private val closeables = mutableListOf<RuntimeHarness>()

    @AfterTest
    fun tearDown() {
        closeables.forEach(RuntimeHarness::close)
        closeables.clear()
        InstancePlaybackLock.release()
    }

    @Test
    fun `accepted handshake stores source catalog and starts active participation`() {
        val harness = harness()

        harness.runtime.onConnectionJoined()
        val handshake = harness.platform.decodeLast(PacketIds.CLIENT_HANDSHAKE, ClientHandshake.ADAPTER::decode)
        assertEquals(ClientStateProto.CLIENT_STATE_ACTIVE, handshake.initial_state)

        val welcome = acceptedWelcome()
        harness.runtime.handleServerWelcome(welcome)

        val catalog = assertNotNull(harness.runtime.sourceCatalog)
        assertEquals("youtube", catalog.defaultSourceId)
        assertEquals(listOf("youtube", "local"), catalog.sources.map { it.id })
        assertEquals(UserParticipationState.ACTIVE, harness.runtime.currentParticipationState())
        assertSame(catalog, harness.listener.acceptedCatalogs.single())
        assertEquals(catalog, harness.listener.searchCatalogs.single())
    }

    @Test
    fun `rejected handshake clears catalog and fails pending requests`() = runBlocking {
        val harness = harness()
        harness.acceptWelcome()
        val deferred = assertNotNull(harness.runtime.beginSearchRequest("ambient", "youtube", limit = 20, offset = 0))
        assertNotNull(harness.platform.decodeLast(PacketIds.SEARCH_REQUEST, SearchRequest.ADAPTER::decode))

        harness.runtime.handleServerWelcome(
            ServerWelcome(
                accepted = false,
                failure = "protocol mismatch",
                server_protocol_version = harness.platform.clientProtocolVersion + 1,
                accepted_state = ClientStateProto.CLIENT_STATE_ACTIVE,
                reject_reason = ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_PROTOCOL_MISMATCH,
            )
        )

        assertNull(harness.runtime.sourceCatalog)
        assertEquals(ServerWelcomeRejectionReason.PROTOCOL_MISMATCH, harness.runtime.lastServerWelcomeRejection?.reason)
        assertEquals(UserParticipationState.STANDBY, harness.runtime.currentParticipationState())
        assertFailsWith<ClientRequestException> {
            deferred.await()
        }
        assertEquals(2, harness.listener.searchCatalogs.size)
        assertNull(harness.listener.searchCatalogs.last())
    }

    @Test
    fun `search response completes pending request and updates cached state`() = runBlocking {
        val harness = harness()
        harness.acceptWelcome()
        val deferred = assertNotNull(harness.runtime.beginSearchRequest("lofi", "youtube", limit = 20, offset = 0))
        val request = harness.platform.decodeLast(PacketIds.SEARCH_REQUEST, SearchRequest.ADAPTER::decode)
        val response = SearchResponse(
            request_id = request.request_id,
            source_id = "youtube",
            query = "lofi",
            offset = 0,
            total = 1,
            has_more = false,
            entries = listOf(
                SelectionEntryProto(
                    source_id = "youtube",
                    selection_id = "track-1",
                    kind = SelectionEntryKindProto.SELECTION_ENTRY_KIND_TRACK,
                    title = "Track One",
                    duration_ms = 180_000L,
                )
            ),
        )

        harness.runtime.handleSearchResponse(response)

        assertSame(response, deferred.await())
        assertSame(response, harness.runtime.lastSearchResponse)
        assertSame(response, harness.listener.searchResponses.single())
        val cached = assertNotNull(harness.runtime.cachedSearchState)
        assertEquals("lofi", cached.query)
        assertEquals("youtube", cached.sourceId)
        assertEquals(1, cached.total)
        assertEquals("track-1", cached.entries.single().selectionId)
    }

    @Test
    fun `playback snapshot loads current context and starts audio`() {
        val harness = harness()
        harness.acceptWelcome()
        val push = PlaybackSnapshotPush(
            reason = PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
            snapshot = PlaybackSnapshot(
                track = TrackInfoProto(
                    source_id = "youtube",
                    id = "track-1",
                    title = "Track One",
                    duration_ms = 180_000L,
                    loudness = LoudnessInfoProto(
                        integrated_lufs = -8.0,
                    ),
                ),
                playback = PlaybackResourceProto(
                    url = "https://example.test/audio.mp3",
                    headers = mapOf("User-Agent" to "MoeMusicTest"),
                ),
                state = PlaybackStateProto.PLAYING,
                position_ms = 1_234L,
                position_anchor_server_monotonic = 0L,
            ),
        )

        harness.runtime.handlePlaybackSnapshotPush(push)

        val context = assertNotNull(harness.runtime.currentContext)
        assertEquals("track-1", context.track.id)
        assertEquals("youtube", context.track.sourceId)
        val state = assertIs<PlaybackState.Playing>(context.state)
        assertEquals(1_234L, state.positionMs)
        assertEquals(1, harness.platform.audio.plays.size)
        assertEquals("https://example.test/audio.mp3", harness.platform.audio.plays.single().playback.url)
        assertEquals(1_234L, harness.platform.audio.plays.single().seekMs)
        assertEquals(0.5011872f, harness.platform.audio.normalizationGains.last(), 0.000001f)
        assertTrue(harness.platform.stoppedBlockedSounds)
        assertEquals(1, harness.listener.snapshotsApplied)
        assertTrue(harness.listener.playbackStateChanges >= 1)
    }

    @Test
    fun `refreshTrackNormalization disables attenuation when client config turns normalization off`() {
        val harness = harness()
        harness.acceptWelcome()
        harness.runtime.handlePlaybackSnapshotPush(
            PlaybackSnapshotPush(
                reason = PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
                snapshot = PlaybackSnapshot(
                    track = TrackInfoProto(
                        source_id = "youtube",
                        id = "track-1",
                        title = "Track One",
                        duration_ms = 180_000L,
                        loudness = LoudnessInfoProto(
                            integrated_lufs = -8.0,
                        ),
                    ),
                    playback = PlaybackResourceProto(url = "https://example.test/audio.mp3"),
                    state = PlaybackStateProto.PLAYING,
                    position_ms = 0L,
                    position_anchor_server_monotonic = 0L,
                ),
            )
        )

        harness.platform.config = harness.platform.config.copy(
            loudnessNormalization = harness.platform.config.loudnessNormalization.copy(
                mode = LoudnessNormalizationMode.OFF
            )
        )

        harness.runtime.refreshTrackNormalization()

        assertEquals(1.0f, harness.platform.audio.normalizationGains.last())
    }

    @Test
    fun `refreshTrackNormalization can conservatively boost with valid peak data`() {
        val harness = harness()
        harness.acceptWelcome()
        harness.platform.config = harness.platform.config.copy(
            loudnessNormalization = harness.platform.config.loudnessNormalization.copy(
                mode = LoudnessNormalizationMode.CONSERVATIVE_BOOST,
                targetLufs = -14.0,
            )
        )
        harness.runtime.handlePlaybackSnapshotPush(
            PlaybackSnapshotPush(
                reason = PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
                snapshot = PlaybackSnapshot(
                    track = TrackInfoProto(
                        source_id = "youtube",
                        id = "track-boost",
                        title = "Quiet Track",
                        duration_ms = 180_000L,
                        loudness = LoudnessInfoProto(
                            integrated_lufs = -20.0,
                            peak = PeakInfoProto(
                                amplitude_linear = 0.4,
                                kind = PeakKindProto.PEAK_KIND_TRUE,
                            ),
                        ),
                    ),
                    playback = PlaybackResourceProto(url = "https://example.test/audio.mp3"),
                    state = PlaybackStateProto.PLAYING,
                    position_ms = 0L,
                    position_anchor_server_monotonic = 0L,
                ),
            )
        )

        assertEquals(1.9952623f, harness.platform.audio.normalizationGains.last(), 0.000001f)
    }

    private fun harness(): RuntimeHarness =
        RuntimeHarness().also(closeables::add)

    private class RuntimeHarness : AutoCloseable {
        val platform = FakePlatform()
        val listener = RecordingListener()
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val runtime = ClientPlaybackRuntime(
            platform = platform,
            listener = listener,
            scope = scope,
            syncIntervalMs = 60_000L,
            standbyLockPollIntervalMs = 60_000L,
        )

        fun acceptWelcome() {
            runtime.onConnectionJoined()
            runtime.handleServerWelcome(acceptedWelcome())
        }

        override fun close() {
            runtime.onConnectionDisconnected()
            scope.cancel()
        }
    }

    private class RecordingListener : ClientPlaybackRuntimeListener {
        val searchCatalogs = mutableListOf<SearchSourceCatalog?>()
        val acceptedCatalogs = mutableListOf<SearchSourceCatalog>()
        val searchResponses = mutableListOf<SearchResponse>()
        var snapshotsApplied = 0
        var playbackStateChanges = 0

        override fun onSearchSourcesChanged(catalog: SearchSourceCatalog?) {
            searchCatalogs += catalog
        }

        override fun onServerWelcomeAccepted(catalog: SearchSourceCatalog) {
            acceptedCatalogs += catalog
        }

        override fun onSearchResponse(response: SearchResponse) {
            searchResponses += response
        }

        override fun onPlaybackSnapshotApplied() {
            snapshotsApplied += 1
        }

        override fun onPlaybackStateChanged() {
            playbackStateChanges += 1
        }
    }

    private class FakePlatform : ClientPlaybackPlatform {
        data class SentPacket(val packetId: PacketId, val payload: ByteArray)

        override val name: String = "test"
        override val clientModVersion: String = "test-client"
        override var clientProtocolVersion: Int = 1
        override val audio = FakeAudio()
        val sentPackets = mutableListOf<SentPacket>()
        var connected = true
        var stoppedBlockedSounds = false
        var config = ClientConfig(globalInstancePlaybackLock = false)

        override fun hasConnection(): Boolean = connected

        override fun currentServerScope(): ClientServerScope = ClientServerScope("server:test", "Test Server")

        override fun currentLocale(): String = "en_us"

        override fun clientConfig(): ClientConfig = config

        override fun sendToServer(packetId: PacketId, payload: ByteArray) {
            sentPackets += SentPacket(packetId, payload)
        }

        override fun executeOnClientThread(block: () -> Unit) {
            block()
        }

        override fun render(text: LocalizedText): String = text.debugString()

        override fun stopBlockedPlatformSoundsIfNeeded() {
            stoppedBlockedSounds = true
        }

        fun <T> decodeLast(packetId: PacketId, decoder: (ByteArray) -> T): T =
            decoder(sentPackets.last { it.packetId == packetId }.payload)
    }

    private class FakeAudio : ClientPlaybackAudioAdapter {
        data class Play(val playback: PlaybackResource, val seekMs: Long)

        val plays = mutableListOf<Play>()
        val normalizationGains = mutableListOf<Float>()
        var paused = false
        var stopped = false

        override fun play(playback: PlaybackResource, seekMs: Long) {
            plays += Play(playback, seekMs)
            paused = false
            stopped = false
        }

        override fun pause() {
            paused = true
        }

        override fun stop() {
            stopped = true
        }

        override fun setNormalizationGain(gain: Float) {
            normalizationGains += gain
        }

        override fun currentPositionMs(): Long = plays.lastOrNull()?.seekMs ?: 0L
    }

    private companion object {
        fun acceptedWelcome(): ServerWelcome = ServerWelcome(
            accepted = true,
            failure = "",
            server_protocol_version = 1,
            accepted_state = ClientStateProto.CLIENT_STATE_ACTIVE,
            sources = listOf(
                SearchSourceInfoProto(id = "youtube", display_name = "YouTube", searchable = true),
                SearchSourceInfoProto(id = "local", display_name = "Local", searchable = false),
            ),
            default_source_id = "youtube",
        )
    }
}
