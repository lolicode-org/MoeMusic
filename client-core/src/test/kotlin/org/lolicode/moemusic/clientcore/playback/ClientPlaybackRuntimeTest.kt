package org.lolicode.moemusic.clientcore.playback

import kotlinx.coroutines.*
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.ClientRequestException
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.clientcore.audio.ClientAudioFailure
import org.lolicode.moemusic.core.config.ClientConfig
import org.lolicode.moemusic.core.config.LoudnessNormalizationMode
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.*
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import org.lolicode.moemusic.core.protocol.proto.SearchSourceInfo as SearchSourceInfoProto

class ClientPlaybackRuntimeTest {

    private val closeables = mutableListOf<RuntimeHarness>()

    @AfterTest
    fun tearDown() {
        closeables.forEach(RuntimeHarness::close)
        closeables.clear()
        InstancePlaybackLock.release()
        ContentFilterRuntime.applyConfig(MoeMusicConfig())
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
    fun `non-handshake server packets require an accepted server handshake`() {
        val harness = harness()
        harness.runtime.onConnectionJoined()

        assertTrue(harness.runtime.acceptsServerPacket(PacketIds.SERVER_WELCOME))
        assertFalse(harness.runtime.acceptsServerPacket(PacketIds.SYNC_RESPONSE))
        assertFalse(harness.runtime.acceptsServerPacket(PacketIds.PLAYBACK_SNAPSHOT_PUSH))

        harness.runtime.handleServerWelcome(acceptedWelcome())

        assertTrue(harness.runtime.acceptsServerPacket(PacketIds.SYNC_RESPONSE))
        assertTrue(harness.runtime.acceptsServerPacket(PacketIds.PLAYBACK_SNAPSHOT_PUSH))
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
    fun `rejected handshake with legacy protocol v2 automatically retries and connects in compatibility mode`() {
        val harness = harness()
        harness.runtime.onConnectionJoined()

        // 1. Initial handshake sent with v3
        val firstHandshake = harness.platform.decodeLast(PacketIds.CLIENT_HANDSHAKE, ClientHandshake.ADAPTER::decode)
        assertEquals(3, firstHandshake.protocol_version)

        // 2. Legacy server rejects with protocol_version = 2
        harness.runtime.handleServerWelcome(
            ServerWelcome(
                accepted = false,
                failure = "protocol mismatch",
                server_protocol_version = 2,
                accepted_state = ClientStateProto.CLIENT_STATE_ACTIVE,
                reject_reason = ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_PROTOCOL_MISMATCH,
            )
        )

        // 3. Client should have automatically sent a second handshake with protocol_version = 2
        val secondHandshake = harness.platform.decodeLast(PacketIds.CLIENT_HANDSHAKE, ClientHandshake.ADAPTER::decode)
        assertEquals(2, secondHandshake.protocol_version)
        assertEquals(2, harness.runtime.activeProtocolVersion)
        assertFalse(harness.runtime.isFramingEnabled)

        // 4. Server accepts the second handshake
        harness.runtime.handleServerWelcome(
            ServerWelcome(
                accepted = true,
                failure = "",
                server_protocol_version = 2,
                accepted_state = ClientStateProto.CLIENT_STATE_ACTIVE,
                sources = listOf(SearchSourceInfoProto(id = "youtube", display_name = "YouTube", searchable = true)),
                default_source_id = "youtube",
            )
        )

        assertTrue(harness.runtime.serverSessionAccepted)
        assertEquals(UserParticipationState.ACTIVE, harness.runtime.currentParticipationState())
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
    fun `ui bootstrap response updates cached queue snapshot and capabilities`() {
        val harness = harness()
        harness.acceptWelcome()

        val requestId = assertNotNull(harness.runtime.sendUiBootstrapRequest())
        val request = harness.platform.decodeLast(PacketIds.UI_BOOTSTRAP_REQUEST, UiBootstrapRequest.ADAPTER::decode)
        assertEquals(requestId, request.request_id)

        val first = UiBootstrapResponse(
            request_id = request.request_id,
            tracks = listOf(
                TrackInfoProto(
                    source_id = "youtube",
                    id = "track-1",
                    title = "Track One",
                    duration_ms = 180_000L,
                )
            ),
            capabilities = UiCapabilitySnapshot(
                has_search_permission = true,
                has_queue_view_permission = true,
                has_submit_permission = true,
                has_submit_skip_autoplay_permission = false,
                has_queue_control_permission = false,
                has_vote_permission = true,
                has_playback_control_permission = false,
                has_content_filter_manage_permission = false,
                has_submit_duplicate_permission = false,
            ),
        )
        harness.runtime.handleUiBootstrapResponse(first)

        assertSame(first, harness.runtime.lastUiBootstrapResponse)
        assertEquals("track-1", harness.runtime.lastUiBootstrapResponse?.tracks?.single()?.id)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_search_permission)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_queue_view_permission)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_submit_permission)
        assertEquals(false, harness.runtime.uiCapabilitySnapshot?.has_submit_skip_autoplay_permission)
        assertEquals(false, harness.runtime.uiCapabilitySnapshot?.has_queue_control_permission)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_vote_permission)
        assertEquals(false, harness.runtime.uiCapabilitySnapshot?.has_playback_control_permission)
        assertEquals(false, harness.runtime.uiCapabilitySnapshot?.has_content_filter_manage_permission)
        assertEquals(false, harness.runtime.uiCapabilitySnapshot?.has_submit_duplicate_permission)
        assertSame(first, harness.listener.uiBootstrapResponses.single())

        val second = UiBootstrapResponse(
            request_id = request.request_id + 1,
            capabilities = UiCapabilitySnapshot(
                has_search_permission = true,
                has_queue_view_permission = true,
                has_submit_permission = true,
                has_submit_skip_autoplay_permission = true,
                has_queue_control_permission = true,
                has_vote_permission = true,
                has_playback_control_permission = true,
                has_content_filter_manage_permission = true,
                has_submit_duplicate_permission = true,
            ),
        )
        harness.runtime.handleUiBootstrapResponse(second)

        assertSame(second, harness.runtime.lastUiBootstrapResponse)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_submit_skip_autoplay_permission)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_queue_control_permission)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_playback_control_permission)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_content_filter_manage_permission)
        assertEquals(true, harness.runtime.uiCapabilitySnapshot?.has_submit_duplicate_permission)
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
        assertEquals(0.39810717f, harness.platform.audio.normalizationGains.last(), 0.000001f)
        assertTrue(harness.platform.stoppedBlockedSounds)
        assertEquals(1, harness.listener.snapshotsApplied)
        assertTrue(harness.listener.playbackStateChanges >= 1)
    }

    @Test
    fun `local playback load failure retries current track`() = runBlocking {
        val harness = harness(localPlaybackRetryDelaysMs = listOf(1L))
        harness.acceptWelcome()
        harness.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-1", title = "Track One"))

        harness.platform.audio.failLast(ClientAudioFailure.network("temporary network timeout"))
        delay(25L.milliseconds)

        assertNotNull(harness.runtime.currentContext)
        assertEquals("track-1", harness.runtime.currentContext?.track?.id)
        assertEquals(2, harness.platform.audio.plays.size)
        assertEquals(1, harness.listener.localPlaybackRetryingMessages.size)
        assertTrue(harness.listener.localPlaybackFailedMessages.isEmpty())
        assertTrue(harness.platform.localPlaybackFailureFinals.isEmpty())
    }

    @Test
    fun `local playback load failure ignores stale callback after track changes`() {
        val harness = harness(localPlaybackRetryDelaysMs = emptyList())
        harness.acceptWelcome()
        harness.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-1", title = "Track One"))
        harness.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-2", title = "Track Two"))

        harness.platform.audio.failAt(0, ClientAudioFailure.network("temporary network timeout"))

        assertEquals("track-2", harness.runtime.currentContext?.track?.id)
        assertEquals(2, harness.platform.audio.plays.size)
        assertTrue(harness.listener.localPlaybackFailedMessages.isEmpty())
        assertTrue(harness.platform.localPlaybackFailureFinals.isEmpty())
    }

    @Test
    fun `permanent local playback load failure stops local context without retry`() {
        val harness = harness(localPlaybackRetryDelaysMs = listOf(1L))
        harness.acceptWelcome()
        harness.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-1", title = "Track One"))

        harness.platform.audio.failLast(ClientAudioFailure.noMatches("https://example.test/audio.mp3"))

        assertNull(harness.runtime.currentContext)
        assertTrue(harness.platform.audio.stopped)
        assertTrue(harness.listener.localPlaybackRetryingMessages.isEmpty())
        assertEquals(1, harness.listener.localPlaybackFailedMessages.size)
        assertEquals(1, harness.platform.localPlaybackFailureFinals.size)
        assertEquals(1, harness.platform.localPlaybackFailedWarnings.size)
    }

    @Test
    fun `final local playback failure message is cleared by next playable track`() {
        val harness = harness(localPlaybackRetryDelaysMs = emptyList())
        harness.acceptWelcome()
        harness.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-1", title = "Track One"))

        harness.platform.audio.failLast(ClientAudioFailure.network("temporary network timeout"))

        assertNotNull(harness.runtime.lastLocalPlaybackFailureMessage)

        harness.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-2", title = "Track Two"))

        assertNull(harness.runtime.lastLocalPlaybackFailureMessage)
        assertEquals("track-2", harness.runtime.currentContext?.track?.id)
    }

    @Test
    fun `platform final failure hook can request skip through existing playback control packet`() {
        val harness = harness(localPlaybackRetryDelaysMs = emptyList(), autoSkipOnFinalFailure = true)
        harness.acceptWelcome()
        harness.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-1", title = "Track One"))

        harness.platform.audio.failLast(ClientAudioFailure.network("temporary network timeout"))

        val request = harness.platform.decodeLast(PacketIds.PLAYBACK_CONTROL_REQUEST, PlaybackControlRequest.ADAPTER::decode)
        assertEquals(PlaybackControlAction.SKIP, request.action)
    }

    @Test
    fun `http status failures use typed retry classification`() = runBlocking {
        val retryable = harness(localPlaybackRetryDelaysMs = listOf(1L))
        retryable.acceptWelcome()
        retryable.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-1", title = "Track One"))

        retryable.platform.audio.failLast(ClientAudioFailure.httpStatus("HTTP 503", 503))
        delay(25L.milliseconds)

        assertEquals(2, retryable.platform.audio.plays.size)
        assertEquals(1, retryable.listener.localPlaybackRetryingMessages.size)

        val permanent = harness(localPlaybackRetryDelaysMs = listOf(1L))
        permanent.acceptWelcome()
        permanent.runtime.handlePlaybackSnapshotPush(playbackPush(trackId = "track-2", title = "Track Two"))

        permanent.platform.audio.failLast(ClientAudioFailure.httpStatus("HTTP 404", 404))

        assertNull(permanent.runtime.currentContext)
        assertTrue(permanent.listener.localPlaybackRetryingMessages.isEmpty())
        assertEquals(1, permanent.listener.localPlaybackFailedMessages.size)
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

    @Test
    fun `recheckLocalContentFilter stops playback when current track matches new block rule`() {
        val harness = harness()
        harness.acceptWelcome()

        // Start playback with a track from source "youtube", id "track-1"
        harness.runtime.handlePlaybackSnapshotPush(
            PlaybackSnapshotPush(
                reason = PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
                snapshot = PlaybackSnapshot(
                    track = TrackInfoProto(
                        source_id = "youtube",
                        id = "track-1",
                        title = "Blocked Song",
                        duration_ms = 180_000L,
                    ),
                    playback = PlaybackResourceProto(url = "https://example.test/audio.mp3"),
                    state = PlaybackStateProto.PLAYING,
                    position_ms = 0L,
                    position_anchor_server_monotonic = 0L,
                ),
            )
        )
        assertNotNull(harness.runtime.currentContext)

        // Apply a filter rule that blocks this exact track
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    exactTrackRules = listOf(ExactTrackFilterRule(sourceId = "youtube", trackId = "track-1")),
                ),
            )
        )

        harness.runtime.recheckLocalContentFilter()

        assertNull(harness.runtime.currentContext)
        assertTrue(harness.platform.audio.stopped)
    }

    @Test
    fun `recheckLocalContentFilter does not stop playback when rule does not match current track`() {
        val harness = harness()
        harness.acceptWelcome()

        harness.runtime.handlePlaybackSnapshotPush(
            PlaybackSnapshotPush(
                reason = PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
                snapshot = PlaybackSnapshot(
                    track = TrackInfoProto(
                        source_id = "youtube",
                        id = "track-1",
                        title = "Safe Song",
                        duration_ms = 180_000L,
                    ),
                    playback = PlaybackResourceProto(url = "https://example.test/audio.mp3"),
                    state = PlaybackStateProto.PLAYING,
                    position_ms = 0L,
                    position_anchor_server_monotonic = 0L,
                ),
            )
        )
        assertNotNull(harness.runtime.currentContext)

        // Block a different track
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    exactTrackRules = listOf(ExactTrackFilterRule(sourceId = "youtube", trackId = "other-track")),
                ),
            )
        )

        harness.runtime.recheckLocalContentFilter()

        assertNotNull(harness.runtime.currentContext)
        assertFalse(harness.platform.audio.stopped)
    }

    @Test
    fun `recheckLocalContentFilter is no-op without active playback`() {
        val harness = harness()
        harness.acceptWelcome()
        assertNull(harness.runtime.currentContext)

        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    exactTrackRules = listOf(ExactTrackFilterRule(sourceId = "youtube", trackId = "track-1")),
                ),
            )
        )

        // Should not throw or cause side effects
        harness.runtime.recheckLocalContentFilter()

        assertNull(harness.runtime.currentContext)
        assertFalse(harness.platform.audio.stopped)
    }

    @Test
    fun `initial handshake packet is sent completely unframed for backward compatibility`() {
        val harness = harness()
        harness.runtime.onConnectionJoined()

        val rawHandshake = harness.platform.sentPackets.last { it.packetId == PacketIds.CLIENT_HANDSHAKE }.payload
        // Protobuf message begins directly with field tag 1 wire type 2 (0x0a), NOT framing flags 0x00/0x01/0x02/0x03
        assertNotEquals(FramedPayloadCodec.FLAG_RAW, rawHandshake[0])
        assertNotEquals(FramedPayloadCodec.FLAG_COMPRESSED, rawHandshake[0])
        assertNotEquals(FramedPayloadCodec.FLAG_CHUNK_RAW, rawHandshake[0])
        assertNotEquals(FramedPayloadCodec.FLAG_CHUNK_COMPRESSED, rawHandshake[0])

        // Verify it directly decodes with Wire without any framing stripping
        val decoded = ClientHandshake.ADAPTER.decode(rawHandshake)
        assertEquals(ClientStateProto.CLIENT_STATE_ACTIVE, decoded.initial_state)
        assertEquals(MoeMusicProtocol.VERSION, decoded.protocol_version)
    }

    @Test
    fun `C2S request packets are framed but reject chunking when payload exceeds single frame limit`() {
        val harness = harness()
        harness.acceptWelcome()

        // 1. Normal C2S request is sent framed
        harness.runtime.sendPlaybackControl(PlaybackControlAction.PAUSE)
        val normalPacket = harness.platform.sentPackets.last { it.packetId == PacketIds.PLAYBACK_CONTROL_REQUEST }.payload
        assertTrue(normalPacket[0] == FramedPayloadCodec.FLAG_RAW || normalPacket[0] == FramedPayloadCodec.FLAG_COMPRESSED)

        // 2. Oversized C2S request (exceeding single frame limit even with compression) must fail immediately without sending chunks
        val randomBytes = ByteArray(40 * 1024).also { java.util.Random(42).nextBytes(it) }
        val oversizedQuery = String(randomBytes, Charsets.ISO_8859_1)
        assertFailsWith<IllegalArgumentException> {
            harness.runtime.beginSearchRequest(query = oversizedQuery, sourceId = "youtube", limit = 20, offset = 0)
        }

        // 3. Normal request after failure must succeed and not be blocked by leaked pending state
        val validDeferred = harness.runtime.beginSearchRequest(query = "valid query", sourceId = "youtube", limit = 20, offset = 0)
        assertNotNull(validDeferred)
        val searchReqPacket = harness.platform.sentPackets.last { it.packetId == PacketIds.SEARCH_REQUEST }.payload
        assertTrue(searchReqPacket[0] == FramedPayloadCodec.FLAG_RAW || searchReqPacket[0] == FramedPayloadCodec.FLAG_COMPRESSED)
    }

    private fun harness(
        localPlaybackRetryDelaysMs: List<Long> = listOf(750L, 1_500L),
        autoSkipOnFinalFailure: Boolean = false,
    ): RuntimeHarness =
        RuntimeHarness(localPlaybackRetryDelaysMs, autoSkipOnFinalFailure).also(closeables::add)

    private class RuntimeHarness(
        localPlaybackRetryDelaysMs: List<Long>,
        autoSkipOnFinalFailure: Boolean,
    ) : AutoCloseable {
        val platform = FakePlatform()
        val listener = RecordingListener()
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val runtime = ClientPlaybackRuntime(
            platform = platform,
            listener = listener,
            scope = scope,
            syncIntervalMs = 60_000L,
            standbyLockPollIntervalMs = 60_000L,
            localPlaybackRetryDelaysMs = localPlaybackRetryDelaysMs,
        )

        init {
            if (autoSkipOnFinalFailure) {
                platform.finalFailureHandler = { _, _ ->
                    runtime.sendPlaybackControl(PlaybackControlAction.SKIP)
                }
            }
        }

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
        val uiBootstrapResponses = mutableListOf<UiBootstrapResponse>()
        val localPlaybackRetryingMessages = mutableListOf<String>()
        val localPlaybackFailedMessages = mutableListOf<String>()
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

        override fun onUiBootstrapResponse(response: UiBootstrapResponse) {
            uiBootstrapResponses += response
        }

        override fun onPlaybackSnapshotApplied() {
            snapshotsApplied += 1
        }

        override fun onLocalPlaybackRetrying(message: String) {
            localPlaybackRetryingMessages += message
        }

        override fun onLocalPlaybackFailed(message: String) {
            localPlaybackFailedMessages += message
        }

        override fun onPlaybackStateChanged() {
            playbackStateChanges += 1
        }
    }

    private class FakePlatform : ClientPlaybackPlatform {
        data class SentPacket(val packetId: PacketId, val payload: ByteArray)

        override val name: String = "test"
        override val clientModVersion: String = "test-client"
        override var clientProtocolVersion: Int = MoeMusicProtocol.VERSION
        override val audio = FakeAudio()
        val sentPackets = mutableListOf<SentPacket>()
        val localPlaybackFailedWarnings = mutableListOf<String>()
        val localPlaybackFailureFinals = mutableListOf<Pair<TrackInfo, String>>()
        var finalFailureHandler: (TrackInfo, String) -> Unit = { _, _ -> }
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

        override fun showLocalPlaybackFailed(title: LocalizedText, message: String) {
            localPlaybackFailedWarnings += message
        }

        override fun onLocalPlaybackFailureFinal(track: TrackInfo, message: String) {
            localPlaybackFailureFinals += track to message
            finalFailureHandler(track, message)
        }

        fun <T> decodeLast(packetId: PacketId, decoder: (ByteArray) -> T): T =
            decoder(FramedPayloadCodec.unwrapServerInbound(sentPackets.last { it.packetId == packetId }.payload))
    }

    private class FakeAudio : ClientPlaybackAudioAdapter {
        data class Play(
            val playback: PlaybackResource,
            val seekMs: Long,
            val onError: (ClientAudioFailure) -> Unit,
        )

        val plays = mutableListOf<Play>()
        val normalizationGains = mutableListOf<Float>()
        var paused = false
        var stopped = false

        override fun play(playback: PlaybackResource, seekMs: Long, onError: (ClientAudioFailure) -> Unit) {
            plays += Play(playback, seekMs, onError)
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

        fun failLast(failure: ClientAudioFailure) {
            plays.last().onError(failure)
        }

        fun failAt(index: Int, failure: ClientAudioFailure) {
            plays[index].onError(failure)
        }
    }

    private companion object {
        fun acceptedWelcome(): ServerWelcome = ServerWelcome(
            accepted = true,
            failure = "",
            server_protocol_version = MoeMusicProtocol.VERSION,
            accepted_state = ClientStateProto.CLIENT_STATE_ACTIVE,
            sources = listOf(
                SearchSourceInfoProto(id = "youtube", display_name = "YouTube", searchable = true),
                SearchSourceInfoProto(id = "local", display_name = "Local", searchable = false),
            ),
            default_source_id = "youtube",
        )

        fun playbackPush(
            trackId: String,
            title: String,
            url: String = "https://example.test/audio.mp3",
        ): PlaybackSnapshotPush = PlaybackSnapshotPush(
            reason = PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
            snapshot = PlaybackSnapshot(
                track = TrackInfoProto(
                    source_id = "youtube",
                    id = trackId,
                    title = title,
                    duration_ms = 180_000L,
                ),
                playback = PlaybackResourceProto(url = url),
                state = PlaybackStateProto.PLAYING,
                position_ms = 0L,
                position_anchor_server_monotonic = 0L,
            ),
        )
    }
}
