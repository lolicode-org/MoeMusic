package org.lolicode.moemusic.clientcore.playback

import kotlinx.coroutines.*
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.ClientRequestException
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.event.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.clientcore.audio.ClientAudioFailure
import org.lolicode.moemusic.clientcore.audio.ClientAudioFailureRecoverability
import org.lolicode.moemusic.clientcore.media.ClientMediaFirewall
import org.lolicode.moemusic.clientcore.request.ClientRequestTransport
import org.lolicode.moemusic.clientcore.transport.ClientChunkAssembler
import org.lolicode.moemusic.core.config.ClientConfig
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult
import org.lolicode.moemusic.core.playback.*
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.ProtocolPayloadValidator
import org.lolicode.moemusic.core.protocol.proto.*
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

interface ClientPlaybackAudioAdapter {
    fun play(playback: PlaybackResource, seekMs: Long, onError: (ClientAudioFailure) -> Unit = {})
    fun pause()
    fun stop()
    fun setNormalizationGain(gain: Float) {}
    fun currentPositionMs(): Long
    fun clearSavedState() {}
}

interface ClientPlaybackPlatform {
    val name: String
    val clientModVersion: String
    val clientProtocolVersion: Int
        get() = MoeMusicProtocol.VERSION

    fun hasConnection(): Boolean
    fun currentServerScope(): ClientServerScope?
    fun currentLocale(): String
    fun clientConfig(): ClientConfig
    fun sendToServer(packetId: PacketId, payload: ByteArray)
    fun executeOnClientThread(block: () -> Unit)
    fun render(text: LocalizedText): String
    fun showPersistentWarning(title: LocalizedText, message: String) {}
    fun showLocalPlaybackBlocked(title: LocalizedText, message: String) {}
    fun showLocalPlaybackFailed(title: LocalizedText, message: String) {
        showLocalPlaybackBlocked(title, message)
    }
    fun onLocalPlaybackFailureFinal(track: TrackInfo, message: String) {}
    fun showInstanceLockStandby(message: String) {}
    fun stopBlockedPlatformSoundsIfNeeded() {}

    val audio: ClientPlaybackAudioAdapter
}

interface ClientPlaybackRuntimeListener {
    fun onSearchSourcesChanged(catalog: SearchSourceCatalog?) {}
    fun onSearchResponse(response: SearchResponse) {}
    fun onUiBootstrapResponse(response: UiBootstrapResponse) {}
    fun onTrackSubmitResponse(response: TrackSubmitResponse) {}
    fun onIdentifierSubmitResponse(response: IdentifierSubmitResponse) {}
    fun onSelectionSubmitResponse(response: SelectionSubmitResponse) {}
    fun onSelectionPageResponse(response: SelectionPageResponse) {}
    fun onQueueResponse(response: QueueResponse) {}
    fun onQueueRemoveResponse(response: QueueRemoveResponse) {}
    fun onQueueClearResponse(response: QueueClearResponse) {}
    fun onPlaybackControlResponse(response: PlaybackControlResponse) {}
    fun onContentFilterActionResponse(response: ContentFilterActionResponse) {}
    fun onLocalPlaybackBlocked(message: String) {}
    fun onLocalPlaybackRetrying(message: String) {}
    fun onLocalPlaybackRecovered(track: TrackInfo) {}
    fun onLocalPlaybackFailed(message: String) {}
    fun onInstancePlaybackStandby(message: String?) {}
    fun onPlaybackStateChanged() {}
    fun onServerWelcomeAccepted(catalog: SearchSourceCatalog) {}
    fun onPlaybackSnapshotApplied() {}
}

class ClientPlaybackRuntime(
    private val platform: ClientPlaybackPlatform,
    private val listener: ClientPlaybackRuntimeListener = object : ClientPlaybackRuntimeListener {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val syncIntervalMs: Long = DEFAULT_SYNC_INTERVAL_MS,
    private val standbyLockPollIntervalMs: Long = DEFAULT_STANDBY_LOCK_POLL_INTERVAL_MS,
    private val localPlaybackRetryDelaysMs: List<Long> = DEFAULT_LOCAL_PLAYBACK_RETRY_DELAYS_MS,
) : ClientRequestTransport {

    private data class DesiredParticipation(
        val state: ClientStateProto,
        val waitForLock: Boolean,
    )

    private data class LocalPlaybackIdentity(
        val sourceId: String?,
        val trackId: String,
        val playbackUrl: String,
    ) {
        fun matches(ctx: TrackContext): Boolean =
            sourceId == ctx.track.sourceId &&
                    trackId == ctx.track.id &&
                    playbackUrl == ctx.playback.url

        companion object {
            fun of(track: TrackInfo, playback: PlaybackResource): LocalPlaybackIdentity =
                LocalPlaybackIdentity(track.sourceId, track.id, playback.url)
        }
    }

    private class PendingRequestRegistry<T> {
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<T>>()

        fun register(requestId: Long): CompletableDeferred<T> =
            CompletableDeferred<T>().also { deferred ->
                pending[requestId] = deferred
                deferred.invokeOnCompletion {
                    pending.remove(requestId, deferred)
                }
            }

        fun complete(requestId: Long, response: T) {
            pending.remove(requestId)?.complete(response)
        }

        fun remove(requestId: Long): CompletableDeferred<T>? =
            pending.remove(requestId)

        fun failAll(cause: Throwable) {
            val entries = pending.values.toList()
            pending.clear()
            entries.forEach { it.completeExceptionally(cause) }
        }
    }

    private val logger = LoggerFactory.getLogger(ClientPlaybackRuntime::class.java)
    private val timeSyncHandler = TimeSyncHandler()
    private val requestIdCounter = AtomicLong(1L)

    init {
        // Re-check the local content filter on the currently playing track whenever rules change,
        // so that a quick-block or config-screen edit takes effect immediately.
        CoreEvents.bus.subscribe<OnContentFilterRulesApplied> {
            platform.executeOnClientThread { recheckLocalContentFilter() }
        }
    }
    private val pendingSearchResponses = PendingRequestRegistry<SearchResponse>()
    private val pendingQueueResponses = PendingRequestRegistry<QueueResponse>()
    private val pendingUiBootstrapResponses = PendingRequestRegistry<UiBootstrapResponse>()
    private val pendingTrackSubmitResponses = PendingRequestRegistry<TrackSubmitResponse>()
    private val pendingIdentifierSubmitResponses = PendingRequestRegistry<IdentifierSubmitResponse>()
    private val pendingSelectionSubmitResponses = PendingRequestRegistry<SelectionSubmitResponse>()
    private val pendingSelectionPageResponses = PendingRequestRegistry<SelectionPageResponse>()
    private val pendingQueueRemoveResponses = PendingRequestRegistry<QueueRemoveResponse>()
    private val pendingQueueClearResponses = PendingRequestRegistry<QueueClearResponse>()
    private val pendingPlaybackControlResponses = PendingRequestRegistry<PlaybackControlResponse>()
    private val pendingContentFilterActionResponses = PendingRequestRegistry<ContentFilterActionResponse>()

    private var syncJob: Job? = null
    private var standbyPollJob: Job? = null
    private var localPlaybackRetryJob: Job? = null

    private val localPlaybackFailureLock = Any()
    private var localPlaybackFailureIdentity: LocalPlaybackIdentity? = null
    private var localPlaybackFailureAttempts: Int = 0

    @Volatile
    private var participationRequested: Boolean = false

    @Volatile
    private var playbackRegistrationActive: Boolean = false

    @Volatile
    private var standbyWaitingForLock: Boolean = false

    private val chunkAssembler = ClientChunkAssembler()
    private val SUPPORTED_LEGACY_PROTOCOL_VERSIONS = setOf(2)

    @Volatile
    var activeProtocolVersion: Int = MoeMusicProtocol.VERSION
        private set

    @Volatile
    private var downgradeAttempted: Boolean = false

    val isFramingEnabled: Boolean
        get() = activeProtocolVersion >= 3

    @Volatile
    private var instanceLockWaitNotified: Boolean = false

    @Volatile
    private var latestSearchResponseRequestId: Long = 0L

    @Volatile
    private var latestQueueResponseRequestId: Long = 0L

    @Volatile
    private var latestUiBootstrapResponseRequestId: Long = 0L

    @Volatile
    private var latestTrackSubmitResponseRequestId: Long = 0L

    @Volatile
    private var latestQueueRemoveResponseRequestId: Long = 0L

    @Volatile
    private var latestQueueClearResponseRequestId: Long = 0L

    @Volatile
    private var latestPlaybackControlResponseRequestId: Long = 0L

    @Volatile
    private var latestContentFilterActionResponseRequestId: Long = 0L

    @Volatile
    private var latestSelectionPageResponseRequestId: Long = 0L

    @Volatile
    var serverClockOffset: Long = 0L
        private set

    @Volatile
    private var timeSyncEstablished: Boolean = false

    @Volatile
    var currentContext: TrackContext? = null
        private set

    @Volatile
    var lastSearchResponse: SearchResponse? = null
        private set

    @Volatile
    var lastSelectionPageResponse: SelectionPageResponse? = null
        private set

    @Volatile
    var cachedSearchTabState: CachedSearchTabState? = null
        private set

    @Volatile
    var sourceCatalog: SearchSourceCatalog? = null
        private set

    @Volatile
    var handshakeRequestedAtNanos: Long = 0L
        private set

    @Volatile
    private var serverHandshakeMissingLogged: Boolean = false

    @Volatile
    var serverHandshakeReceived: Boolean = false
        private set

    @Volatile
    var serverSessionAccepted: Boolean = false
        private set

    @Volatile
    var lastServerWelcomeRejection: ServerWelcomeRejection? = null
        private set

    @Volatile
    var lastQueueResponse: QueueResponse? = null
        private set

    @Volatile
    var lastUiBootstrapResponse: UiBootstrapResponse? = null
        private set

    @Volatile
    var uiCapabilitySnapshot: UiCapabilitySnapshot? = null
        private set

    @Volatile
    var lastTrackSubmitResponse: TrackSubmitResponse? = null
        private set

    @Volatile
    var lastQueueRemoveResponse: QueueRemoveResponse? = null
        private set

    @Volatile
    var lastQueueClearResponse: QueueClearResponse? = null
        private set

    @Volatile
    var lastPlaybackControlResponse: PlaybackControlResponse? = null
        private set

    @Volatile
    var lastContentFilterActionResponse: ContentFilterActionResponse? = null
        private set

    @Volatile
    var lastLocalPlaybackBlockedMessage: String? = null
        private set

    @Volatile
    var lastLocalPlaybackFailureMessage: String? = null
        private set

    @Volatile
    var lastInstanceLockMessage: String? = null
        private set

    @Volatile
    var currentLyrics: ParsedLyrics? = null
        private set

    @Volatile
    var currentSecondaryLyrics: ParsedLyrics? = null
        private set

    fun currentParticipationState(): UserParticipationState? = when {
        !participationRequested -> null
        playbackRegistrationActive -> UserParticipationState.ACTIVE
        else -> UserParticipationState.STANDBY
    }

    fun isPlaybackEnabledForCurrentServer(): Boolean =
        ClientPlaybackAvailability.isPlaybackEnabledForServer(
            clientConfig = platform.clientConfig(),
            serverScope = platform.currentServerScope(),
        )

    fun currentAvailabilityIssue(): AvailabilityIssue? =
        ClientPlaybackAvailability.availabilityIssue(
            hasConnection = platform.hasConnection(),
            serverHandshakeMissing = isServerHandshakeMissing(),
            serverHandshakeRejected = lastServerWelcomeRejection != null,
        )

    fun refreshTrackNormalization() {
        val track = currentContext?.track
        if (track != null) {
            applyTrackNormalization(track, "config refresh")
        } else {
            clearTrackNormalization("config refresh without active track")
        }
    }

    /**
     * Re-evaluate the local content filter on the currently playing track.
     *
     * Called automatically when [OnContentFilterRulesApplied] fires (e.g. after a quick-block
     * action or config-screen edit) so that newly blocked tracks are stopped immediately instead
     * of continuing until the next track transition.
     */
    fun recheckLocalContentFilter() {
        val ctx = currentContext ?: return
        if (applyLocalContentFilter(ctx.track) == null) {
            stopActivePlayback(fireEvent = true, preserveLocalPlaybackNotice = true)
        }
    }

    fun onConnectionJoined() {
        logger.info(
            "{} connection joined (scope={}); starting MoeMusic client session.",
            platform.name,
            platform.currentServerScope()?.displayName ?: "unknown",
        )
        activeProtocolVersion = platform.clientProtocolVersion
        downgradeAttempted = false
        chunkAssembler.clear()
        startSession()
        CoreEvents.bus.fire(OnClientConnected)
    }

    fun onConnectionDisconnected() {
        logger.info("{} connection disconnected; clearing MoeMusic client session.", platform.name)
        CoreEvents.bus.fire(OnClientDisconnected)
        activeProtocolVersion = platform.clientProtocolVersion
        downgradeAttempted = false
        chunkAssembler.clear()
        stopStandbyPolling()
        stopSyncLoop()
        InstancePlaybackLock.release()
        platform.audio.stop()
        clearTrackNormalization("connection disconnected")
        platform.audio.clearSavedState()
        clearContext()
    }

    fun syncParticipationWithCurrentConfig() {
        if (!platform.hasConnection()) return
        if (!participationRequested) {
            startSession()
            return
        }

        val desired = desiredParticipation()
        if (desired.state == ClientStateProto.CLIENT_STATE_ACTIVE &&
            playbackRegistrationActive &&
            isGlobalInstancePlaybackLockEnabled()
        ) {
            currentContext?.let { ctx ->
                if (ctx.state != PlaybackState.Stopped && !ensurePlaybackLock()) return
            }
        }

        applyDesiredParticipation(desired)
    }

    fun receiveFromServer(packetId: PacketId, payload: ByteArray) {
        if (!acceptsServerPacket(packetId)) {
            logger.debug("Ignoring {} S2C packet {} before the server handshake is accepted", platform.name, packetId)
            return
        }
        if (!ProtocolPayloadValidator.acceptsServerToClient(
                packetId = packetId,
                payload = payload,
                activeProtocolVersion = activeProtocolVersion,
                serverSessionAccepted = serverSessionAccepted,
            )
        ) {
            logger.debug(
                "Dropping {} S2C packet {} because its payload does not match the active protocol version {}",
                platform.name,
                packetId,
                activeProtocolVersion,
            )
            return
        }

        val completePayload = chunkAssembler.process(payload) ?: return
        when (packetId) {
            PacketIds.PLAYBACK_SNAPSHOT_PUSH -> handlePlaybackSnapshotPush(PlaybackSnapshotPush.ADAPTER.decode(completePayload))
            PacketIds.STATE_UPDATE -> handleStateUpdate(StateUpdate.ADAPTER.decode(completePayload))
            PacketIds.SYNC_RESPONSE -> handleSyncResponse(SyncResponse.ADAPTER.decode(completePayload))
            PacketIds.SERVER_WELCOME -> {
                val welcome = ServerWelcome.ADAPTER.decode(completePayload)
                if (!ProtocolPayloadValidator.acceptsServerWelcome(
                        payload = payload,
                        welcome = welcome,
                        activeProtocolVersion = activeProtocolVersion,
                        serverSessionAccepted = serverSessionAccepted,
                    )
                ) {
                    logger.debug(
                        "Dropping {} ServerWelcome because accepted={} does not match protocol v{} transport",
                        platform.name,
                        welcome.accepted,
                        activeProtocolVersion,
                    )
                    return
                }
                handleServerWelcome(welcome)
            }
            PacketIds.SEARCH_RESPONSE -> handleSearchResponse(SearchResponse.ADAPTER.decode(completePayload))
            PacketIds.UI_BOOTSTRAP_RESPONSE -> handleUiBootstrapResponse(UiBootstrapResponse.ADAPTER.decode(completePayload))
            PacketIds.TRACK_SUBMIT_RESPONSE -> handleTrackSubmitResponse(TrackSubmitResponse.ADAPTER.decode(completePayload))
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE -> handleIdentifierSubmitResponse(IdentifierSubmitResponse.ADAPTER.decode(completePayload))
            PacketIds.SELECTION_SUBMIT_RESPONSE -> handleSelectionSubmitResponse(SelectionSubmitResponse.ADAPTER.decode(completePayload))
            PacketIds.SELECTION_PAGE_RESPONSE -> handleSelectionPageResponse(SelectionPageResponse.ADAPTER.decode(completePayload))
            PacketIds.QUEUE_RESPONSE -> handleQueueResponse(QueueResponse.ADAPTER.decode(completePayload))
            PacketIds.QUEUE_REMOVE_RESPONSE -> handleQueueRemoveResponse(QueueRemoveResponse.ADAPTER.decode(completePayload))
            PacketIds.QUEUE_CLEAR_RESPONSE -> handleQueueClearResponse(QueueClearResponse.ADAPTER.decode(completePayload))
            PacketIds.PLAYBACK_CONTROL_RESPONSE -> handlePlaybackControlResponse(PlaybackControlResponse.ADAPTER.decode(completePayload))
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE ->
                handleContentFilterActionResponse(ContentFilterActionResponse.ADAPTER.decode(completePayload))

            else -> logger.debug("Ignoring unsupported {} S2C packet {}", platform.name, packetId)
        }
    }
    /** Returns whether an S→C packet may be processed before the server session is accepted. */
    fun acceptsServerPacket(packetId: PacketId): Boolean =
        packetId == PacketIds.SERVER_WELCOME || serverSessionAccepted


    fun handlePlaybackSnapshotPush(msg: PlaybackSnapshotPush) {
        val snapshot = msg.snapshot ?: run {
            logger.warn("Ignoring {} PlaybackSnapshotPush without a snapshot (reason={}).", platform.name, msg.reason)
            return
        }
        logger.debug(
            "{} PlaybackSnapshotPush received: reason={} state={} source={} id={} title='{}'",
            platform.name,
            msg.reason,
            snapshot.state,
            snapshot.track?.source_id.orEmpty(),
            snapshot.track?.id.orEmpty(),
            snapshot.track?.title.orEmpty(),
        )
        if (!canHandlePlaybackPackets()) {
            logIgnoredPlaybackPacket("PlaybackSnapshotPush", "reason=${msg.reason} state=${snapshot.state}")
            return
        }
        val fromSyncState = msg.reason != PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK
        if (!fromSyncState) {
            lastQueueResponse = null
            lastUiBootstrapResponse = null
        }
        applyPlaybackSnapshot(snapshot, fromSyncState = fromSyncState)
        listener.onPlaybackSnapshotApplied()
    }

    fun handleStateUpdate(msg: StateUpdate) {
        if (!canHandlePlaybackPackets()) {
            logIgnoredPlaybackPacket("StateUpdate", "state=${msg.state}")
            return
        }
        val ctx = currentContext ?: run {
            logger.warn("Ignoring {} StateUpdate {} because no playback context is loaded.", platform.name, msg.state)
            return
        }

        when (msg.state) {
            PlaybackStateProto.PAUSED -> {
                val positionMs = normalizeClientPosition(msg.position_ms, ctx.track.durationMs)
                logInvalidServerPosition("StateUpdate PAUSED", msg.position_ms, positionMs, ctx.track)
                logger.info(
                    "{} playback paused by server: source={} id={} title='{}' positionMs={}",
                    platform.name,
                    ctx.track.sourceId.orEmpty(),
                    ctx.track.id,
                    ctx.track.title,
                    positionMs,
                )
                platform.audio.pause()
                currentContext = ctx.copy(state = PlaybackState.Paused(positionMs))
                CoreEvents.bus.fire(OnClientPlaybackPaused(ctx.track, positionMs))
            }

            PlaybackStateProto.PLAYING -> {
                if (!ensurePlaybackLock()) return
                val serverNow = currentServerMonotonicNow()
                val wasPaused = ctx.state is PlaybackState.Paused
                val playback = msg.playback?.toApi() ?: ctx.playback
                val seekMs = anchoredPlaybackPositionMs(
                    positionMs = msg.position_ms,
                    anchorServerMonotonic = msg.position_anchor_server_monotonic,
                    durationMs = ctx.track.durationMs,
                )
                logInvalidServerPosition("StateUpdate PLAYING", msg.position_ms, seekMs, ctx.track)
                logger.info(
                    "{} playback {} by server: source={} id={} title='{}' positionMs={}",
                    platform.name,
                    if (wasPaused) "resumed" else "seeked",
                    ctx.track.sourceId.orEmpty(),
                    ctx.track.id,
                    ctx.track.title,
                    seekMs,
                )
                if (msg.playback != null) {
                    logger.debug(
                        "{} StateUpdate playback resource for '{}' url='{}' headers={}",
                        platform.name,
                        ctx.track.title,
                        playback.url,
                        playback.headers.keys,
                    )
                }
                val newStart = if (msg.position_anchor_server_monotonic != 0L) {
                    msg.position_anchor_server_monotonic - msg.position_ms.coerceAtLeast(0L) * 1_000_000L
                } else {
                    ctx.serverStartMonotonic
                }
                val updatedContext = ctx.copy(
                    playback = playback,
                    state = PlaybackState.Playing(seekMs),
                    serverStartMonotonic = newStart,
                    serverResumeMonotonic = serverNow,
                )
                currentContext = updatedContext
                startPlatformPlayback(updatedContext, seekMs, pauseAfterStart = false, preserveFailureState = false)
                if (LocalPlaybackIdentity.of(ctx.track, playback).matches(currentContext ?: return)) {
                    platform.stopBlockedPlatformSoundsIfNeeded()
                    if (wasPaused) {
                        CoreEvents.bus.fire(OnClientPlaybackResumed(ctx.track, seekMs))
                    } else {
                        CoreEvents.bus.fire(OnClientPlaybackSeeked(ctx.track, seekMs))
                    }
                }
            }

            PlaybackStateProto.STOPPED -> {
                logger.info(
                    "{} playback stopped by server: source={} id={} title='{}'",
                    platform.name,
                    ctx.track.sourceId.orEmpty(),
                    ctx.track.id,
                    ctx.track.title,
                )
                releasePlaybackLock(clearMessage = true)
                stopActivePlayback(fireEvent = true)
            }
        }
        listener.onPlaybackStateChanged()
    }

    fun handleSyncResponse(resp: SyncResponse) {
        if (!canHandleSessionPackets()) {
            logIgnoredSessionPacket("SyncResponse", debugOnly = true)
            return
        }
        applyTimeSync(resp)
        logger.debug(
            "{} clock offset updated: {} ns (clientSend={} serverRecv={} serverSend={})",
            platform.name,
            serverClockOffset,
            resp.client_send_monotonic,
            resp.server_recv_monotonic,
            resp.server_send_monotonic,
        )
    }

    fun handleServerWelcome(msg: ServerWelcome) {
        if (!participationRequested) {
            logger.warn("Ignoring ServerWelcome because no {} client session was requested.", platform.name)
            return
        }

        // Automatic Handshake Downgrade Fallback for legacy v2 servers
        if (!msg.accepted &&
            !downgradeAttempted &&
            msg.reject_reason == ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_PROTOCOL_MISMATCH &&
            msg.server_protocol_version in SUPPORTED_LEGACY_PROTOCOL_VERSIONS
        ) {
            downgradeAttempted = true
            activeProtocolVersion = msg.server_protocol_version
            logger.info(
                "{} server rejected protocol v{}, falling back to legacy protocol v{}",
                platform.name,
                platform.clientProtocolVersion,
                msg.server_protocol_version,
            )
            val desired = desiredParticipation()
            sendHandshake(
                locale = platform.currentLocale(),
                initialState = desired.state,
                protocolVersion = msg.server_protocol_version,
            )
            return
        }

        msg.initial_time_sync?.let(::applyTimeSync)
        serverHandshakeReceived = true
        serverSessionAccepted = msg.accepted
        if (!msg.accepted) {
            val rejection = serverWelcomeRejection(msg)
            lastServerWelcomeRejection = rejection
            val failure = renderServerWelcomeRejection(rejection)
            logger.warn(
                "{} ServerWelcome rejected: reason={} clientProtocol={} serverProtocol={} detail='{}'",
                platform.name,
                rejection.reason,
                rejection.clientProtocolVersion,
                rejection.serverProtocolVersion,
                rejection.detail.orEmpty(),
            )
            sourceCatalog = null
            playbackRegistrationActive = false
            stopSyncLoop()
            releasePlaybackLock(clearMessage = true)
            stopActivePlayback()
            failPendingRequests(ClientRequestException(failure))
            platform.showPersistentWarning(
                LocalizedText.key("screen.moemusic.unavailable.rejected.title"),
                failure,
            )
            listener.onSearchSourcesChanged(null)
            return
        }
        lastServerWelcomeRejection = null

        if (activeProtocolVersion < platform.clientProtocolVersion) {
            logger.info(
                "{} connected in compatibility mode (activeProtocol=v{}, clientProtocol=v{})",
                platform.name,
                activeProtocolVersion,
                platform.clientProtocolVersion,
            )
            platform.showPersistentWarning(
                LocalizedText.key("screen.moemusic.unavailable.compatibility.title"),
                platform.render(
                    LocalizedText.key(
                        "screen.moemusic.unavailable.compatibility.body",
                        activeProtocolVersion,
                    )
                ),
            )
        }

        val catalog = SearchSourceCatalog(
            sources = msg.sources.map { info ->
                SearchSourceInfo(
                    id = info.id,
                    displayName = info.display_name.ifBlank { info.id },
                    searchable = info.searchable,
                )
            },
            defaultSourceId = msg.default_source_id,
        )
        sourceCatalog = catalog
        playbackRegistrationActive = msg.accepted_state == ClientStateProto.CLIENT_STATE_ACTIVE
        logger.info(
            "{} server handshake accepted (sources={}, defaultSource='{}', acceptedState={}, active={}, serverProtocol={})",
            platform.name,
            catalog.sources.size,
            catalog.defaultSourceId,
            msg.accepted_state,
            playbackRegistrationActive,
            msg.server_protocol_version,
        )
        startSyncLoop()
        if (playbackRegistrationActive && !isPlaybackEnabledForCurrentServer()) {
            enterStandbyParticipation(waitForLock = false)
        }
        if (standbyWaitingForLock) {
            updateInstanceLockStandby(notifyUser = true)
            startStandbyPolling()
        }
        listener.onSearchSourcesChanged(catalog)
        listener.onServerWelcomeAccepted(catalog)
    }

    fun handleSearchResponse(msg: SearchResponse) {
        if (!canHandleDirectResponses("SearchResponse")) return
        logger.debug(
            "{} SearchResponse: query='{}' offset={} results={} total={} hasMore={} failure='{}'",
            platform.name,
            msg.query,
            msg.offset,
            msg.entries.size,
            msg.total,
            msg.has_more,
            msg.failure,
        )
        pendingSearchResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestSearchResponseRequestId) {
            latestSearchResponseRequestId = msg.request_id
            lastSearchResponse = msg
            if (msg.offset == 0 || cachedSearchTabState?.query != msg.query || cachedSearchTabState?.sourceId != msg.source_id) {
                cachedSearchTabState = CachedSearchTabState(
                    query = msg.query,
                    sourceId = msg.source_id,
                    entries = msg.entries.map { it.toApi() },
                    total = msg.total,
                    hasMore = msg.has_more,
                    failure = msg.failure.ifEmpty { null },
                )
            }
        }
        listener.onSearchResponse(msg)
    }

    fun handleTrackSubmitResponse(msg: TrackSubmitResponse) {
        if (!canHandleDirectResponses("TrackSubmitResponse")) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("{} TrackSubmitResponse failure: {}", platform.name, msg.failure)
        }
        pendingTrackSubmitResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestTrackSubmitResponseRequestId) {
            latestTrackSubmitResponseRequestId = msg.request_id
            lastTrackSubmitResponse = msg
        }
        listener.onTrackSubmitResponse(msg)
    }

    fun handleUiBootstrapResponse(msg: UiBootstrapResponse) {
        if (!canHandleDirectResponses("UiBootstrapResponse")) return
        logger.debug(
            "{} UiBootstrapResponse: {} tracks failure='{}' capabilities={}",
            platform.name,
            msg.tracks.size,
            msg.failure,
            msg.capabilities?.toString(),
        )
        pendingUiBootstrapResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestUiBootstrapResponseRequestId) {
            latestUiBootstrapResponseRequestId = msg.request_id
            lastUiBootstrapResponse = msg
            uiCapabilitySnapshot = msg.capabilities
        }
        listener.onUiBootstrapResponse(msg)
    }

    fun handleIdentifierSubmitResponse(msg: IdentifierSubmitResponse) {
        if (!canHandleDirectResponses("IdentifierSubmitResponse")) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("{} IdentifierSubmitResponse failure: {}", platform.name, msg.failure)
        }
        pendingIdentifierSubmitResponses.complete(msg.request_id, msg)
        listener.onIdentifierSubmitResponse(msg)
    }

    fun handleSelectionSubmitResponse(msg: SelectionSubmitResponse) {
        if (!canHandleDirectResponses("SelectionSubmitResponse")) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("{} SelectionSubmitResponse failure: {}", platform.name, msg.failure)
        }
        pendingSelectionSubmitResponses.complete(msg.request_id, msg)
        listener.onSelectionSubmitResponse(msg)
    }

    fun handleSelectionPageResponse(msg: SelectionPageResponse) {
        if (!canHandleDirectResponses("SelectionPageResponse")) return
        logger.debug(
            "{} SelectionPageResponse: session={} {} choices (offset={}, total={}, hasMore={}) failure='{}'",
            platform.name,
            msg.session_id,
            msg.choices.size,
            msg.offset,
            msg.total,
            msg.has_more,
            msg.failure,
        )
        pendingSelectionPageResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestSelectionPageResponseRequestId) {
            latestSelectionPageResponseRequestId = msg.request_id
            lastSelectionPageResponse = msg
        }
        listener.onSelectionPageResponse(msg)
    }

    fun handleQueueResponse(msg: QueueResponse) {
        if (!canHandleDirectResponses("QueueResponse")) return
        logger.debug("{} QueueResponse: {} tracks failure='{}'", platform.name, msg.tracks.size, msg.failure)
        pendingQueueResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestQueueResponseRequestId) {
            latestQueueResponseRequestId = msg.request_id
            lastQueueResponse = msg
        }
        listener.onQueueResponse(msg)
    }

    fun handleQueueRemoveResponse(msg: QueueRemoveResponse) {
        if (!canHandleDirectResponses("QueueRemoveResponse")) return
        logger.debug("{} QueueRemoveResponse: failure='{}'", platform.name, msg.failure)
        pendingQueueRemoveResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestQueueRemoveResponseRequestId) {
            latestQueueRemoveResponseRequestId = msg.request_id
            lastQueueRemoveResponse = msg
        }
        listener.onQueueRemoveResponse(msg)
    }

    fun handleQueueClearResponse(msg: QueueClearResponse) {
        if (!canHandleDirectResponses("QueueClearResponse")) return
        logger.debug("{} QueueClearResponse: removedCount={} failure='{}' success='{}'", platform.name, msg.removed_count, msg.failure, msg.success)
        pendingQueueClearResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestQueueClearResponseRequestId) {
            latestQueueClearResponseRequestId = msg.request_id
            lastQueueClearResponse = msg
        }
        listener.onQueueClearResponse(msg)
    }

    fun handlePlaybackControlResponse(msg: PlaybackControlResponse) {
        if (!canHandleDirectResponses("PlaybackControlResponse")) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("{} PlaybackControlResponse failure: {}", platform.name, msg.failure)
        }
        pendingPlaybackControlResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestPlaybackControlResponseRequestId) {
            latestPlaybackControlResponseRequestId = msg.request_id
            lastPlaybackControlResponse = msg
        }
        listener.onPlaybackControlResponse(msg)
    }

    fun handleContentFilterActionResponse(msg: ContentFilterActionResponse) {
        if (!canHandleDirectResponses("ContentFilterActionResponse")) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("{} ContentFilterActionResponse failure: {}", platform.name, msg.failure)
        }
        pendingContentFilterActionResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestContentFilterActionResponseRequestId) {
            latestContentFilterActionResponseRequestId = msg.request_id
            lastContentFilterActionResponse = msg
        }
        listener.onContentFilterActionResponse(msg)
    }

    fun sendSyncRequest() {
        if (!canHandleSessionPackets()) return
        trySendToServer(PacketIds.SYNC_REQUEST) {
            SyncRequest(client_send_monotonic = System.nanoTime()).encode()
        }
    }

    fun sendClientStateChange(state: ClientStateProto) {
        val blockedReason = sessionPacketBlockReason()
        if (blockedReason != null) {
            logger.warn("Cannot send {} client participation change {}: {}", platform.name, state, blockedReason)
            return
        }
        if (trySendToServer(PacketIds.CLIENT_STATE_CHANGE) {
                ClientStateChange(state = state).encode()
            } != null
        ) {
            return
        }
        playbackRegistrationActive = state == ClientStateProto.CLIENT_STATE_ACTIVE
        logger.info("{} client participation change sent: {}", platform.name, state)
    }

    fun sendSearchRequest(query: String, sourceId: String = "", limit: Int = 20, offset: Int = 0): Long? =
        sendCorrelatedRequest(PacketIds.SEARCH_REQUEST) { requestId ->
            SearchRequest(query = query, source_id = sourceId, limit = limit, offset = offset, request_id = requestId).encode()
        }

    fun sendQueueRequest(limit: Int = 20, offset: Int = 0): Long? =
        sendCorrelatedRequest(PacketIds.QUEUE_REQUEST) { requestId ->
            QueueRequest(limit = limit, offset = offset, request_id = requestId).encode()
        }

    fun sendUiBootstrapRequest(queueLimit: Int = 20): Long? =
        sendCorrelatedRequest(PacketIds.UI_BOOTSTRAP_REQUEST) { requestId ->
            UiBootstrapRequest(queue_limit = queueLimit, request_id = requestId).encode()
        }

    fun sendSelectionPageRequest(sessionId: String, offset: Int = 0, limit: Int = 20): Long? =
        sendCorrelatedRequest(PacketIds.SELECTION_PAGE_REQUEST) { requestId ->
            SelectionPageRequest(session_id = sessionId, offset = offset, limit = limit, request_id = requestId).encode()
        }

    fun sendQueueRemoveRequest(track: TrackInfo): Long? {
        val sourceId = track.sourceId ?: return null
        if (sourceId.isBlank() || track.id.isBlank()) return null
        return sendCorrelatedRequest(PacketIds.QUEUE_REMOVE_REQUEST) { requestId ->
            QueueRemoveRequest(
                source_id = sourceId,
                track_id = track.id,
                queue_entry_id = track.queueEntryId.orEmpty(),
                request_id = requestId,
            ).encode()
        }
    }

    fun sendQueueClearRequest(scope: QueueClearScopeProto, targetUserId: String? = null): Long? {
        if (activeProtocolVersion < 3) return null
        return sendCorrelatedRequest(PacketIds.QUEUE_CLEAR_REQUEST) { requestId ->
            QueueClearRequest(
                scope = scope,
                target_user_id = targetUserId.orEmpty(),
                request_id = requestId,
            ).encode()
        }
    }

    fun sendTrackSubmit(track: TrackInfo, mode: TrackAddMode = TrackAddMode.NORMAL): Long? =
        sendCorrelatedRequest(PacketIds.TRACK_SUBMIT) { requestId ->
            TrackSubmitRequest(
                source_id = track.sourceId.orEmpty(),
                track_id = track.id,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    fun sendTrackSubmit(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Long? =
        entry.toDirectTrackSubmitTrack()?.let { track -> sendTrackSubmit(track, mode) }

    fun sendIdentifierSubmit(identifier: String, mode: TrackAddMode): Long? =
        sendCorrelatedRequest(PacketIds.IDENTIFIER_SUBMIT) { requestId ->
            IdentifierSubmitRequest(identifier = identifier, mode = mode.toProto(), request_id = requestId).encode()
        }

    fun sendSelectionSubmit(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Long? =
        sendCorrelatedRequest(PacketIds.SELECTION_SUBMIT) { requestId ->
            SelectionSubmitRequest(
                source_id = entry.sourceId.orEmpty(),
                selection_id = entry.selectionId,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    fun sendPlaybackControl(action: PlaybackControlAction, positionMs: Long = 0L): Long? {
        logInvalidPlaybackControl(action, positionMs)
        return sendCorrelatedRequest(PacketIds.PLAYBACK_CONTROL_REQUEST) { requestId ->
            PlaybackControlRequest(action = action, position_ms = positionMs, request_id = requestId).encode()
        }
    }

    fun sendContentFilterTrackAction(sourceId: String, trackId: String, note: String?, ban: Boolean): Long? =
        sendCorrelatedRequest(PacketIds.CONTENT_FILTER_ACTION_REQUEST) { requestId ->
            ContentFilterActionRequest(
                action = if (ban) {
                    ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN
                } else {
                    ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN
                },
                target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK,
                source_id = sourceId,
                value_id = trackId,
                note = note.orEmpty(),
                request_id = requestId,
            ).encode()
        }

    fun cacheSearchTabState(state: CachedSearchTabState?) {
        cachedSearchTabState = state
    }

    fun clearContext() {
        val disconnect = ClientRequestException("Disconnected from MoeMusic session.")
        failPendingRequests(disconnect)
        participationRequested = false
        playbackRegistrationActive = false
        standbyWaitingForLock = false
        instanceLockWaitNotified = false
        latestSearchResponseRequestId = 0L
        latestQueueResponseRequestId = 0L
        latestUiBootstrapResponseRequestId = 0L
        latestTrackSubmitResponseRequestId = 0L
        latestQueueRemoveResponseRequestId = 0L
        latestQueueClearResponseRequestId = 0L
        latestPlaybackControlResponseRequestId = 0L
        latestContentFilterActionResponseRequestId = 0L
        serverClockOffset = 0L
        timeSyncEstablished = false
        stopStandbyPolling()
        cancelLocalPlaybackRetry()
        currentContext = null
        currentLyrics = null
        currentSecondaryLyrics = null
        cachedSearchTabState = null
        lastSearchResponse = null
        lastQueueResponse = null
        lastUiBootstrapResponse = null
        lastTrackSubmitResponse = null
        lastQueueRemoveResponse = null
        lastQueueClearResponse = null
        lastPlaybackControlResponse = null
        lastContentFilterActionResponse = null
        lastLocalPlaybackBlockedMessage = null
        lastLocalPlaybackFailureMessage = null
        lastInstanceLockMessage = null
        sourceCatalog = null
        uiCapabilitySnapshot = null
        handshakeRequestedAtNanos = 0L
        serverHandshakeReceived = false
        serverSessionAccepted = false
        serverHandshakeMissingLogged = false
        lastServerWelcomeRejection = null
    }

    fun currentPositionMs(ctx: TrackContext): Long =
        when (val state = ctx.state) {
            is PlaybackState.Playing -> platform.audio.currentPositionMs()
            is PlaybackState.Paused -> state.positionMs
            PlaybackState.Stopped -> 0L
            else -> 0L
        }.coerceAtMost(ctx.track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    fun sourceDisplayName(sourceId: String?): String {
        if (sourceId.isNullOrBlank()) return ""
        return sourceCatalog?.sources?.firstOrNull { it.id == sourceId }?.displayName ?: sourceId
    }

    fun isServerHandshakeMissing(nowNanos: Long = System.nanoTime()): Boolean {
        val missing = participationRequested &&
            handshakeRequestedAtNanos != 0L &&
            !serverHandshakeReceived &&
            nowNanos - handshakeRequestedAtNanos >= HANDSHAKE_GRACE_NANOS
        if (missing && !serverHandshakeMissingLogged) {
            serverHandshakeMissingLogged = true
            logger.warn(
                "No MoeMusic server handshake response after {} ms; the server may not have MoeMusic installed or its packet channel is unavailable.",
                HANDSHAKE_GRACE_NANOS / 1_000_000L,
            )
        }
        return missing
    }

    fun currentLyricLine(positionMs: Long): LyricLine? = currentLyrics?.lineAt(positionMs)

    fun currentSecondaryLyricLine(positionMs: Long): LyricLine? = currentSecondaryLyrics?.lineAt(positionMs)

    private fun startSession() {
        stopStandbyPolling()
        val desired = desiredParticipation()
        standbyWaitingForLock = desired.waitForLock
        if (!desired.waitForLock) {
            clearInstanceLockStandby()
        }
        stopSyncLoop()
        sendHandshake(platform.currentLocale(), desired.state)
        if (desired.waitForLock) {
            updateInstanceLockStandby(notifyUser = false)
        }
    }

    private fun wrapClientPayload(payload: ByteArray, protocolVersion: Int = activeProtocolVersion): ByteArray {
        if (protocolVersion < 3) return payload
        return FramedPayloadCodec.encodeSingle(payload)
    }

    private fun trySendToServer(
        packetId: PacketId,
        protocolVersion: Int = activeProtocolVersion,
        framed: Boolean = true,
        payloadFactory: () -> ByteArray,
    ): Exception? = try {
        val payload = payloadFactory()
        val wirePayload = if (framed) wrapClientPayload(payload, protocolVersion) else payload
        if (!FramedPayloadCodec.isFramed(wirePayload)) {
            require(wirePayload.size <= FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES) {
                "Unframed C2S payload size ${wirePayload.size} exceeds maximum " +
                    FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES
            }
        }
        platform.sendToServer(packetId, wirePayload)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("{} failed to send packet {}: {}", platform.name, packetId, e.message, e)
        e
    }

    private fun sendCorrelatedRequest(packetId: PacketId, payloadFactory: (Long) -> ByteArray): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        return if (trySendToServer(packetId) { payloadFactory(requestId) } == null) requestId else null
    }

    private fun sendHandshake(
        locale: String,
        initialState: ClientStateProto,
        protocolVersion: Int = activeProtocolVersion,
    ) {
        participationRequested = true
        playbackRegistrationActive = false
        val now = System.nanoTime()
        handshakeRequestedAtNanos = now
        serverHandshakeReceived = false
        serverSessionAccepted = false
        serverHandshakeMissingLogged = false
        lastServerWelcomeRejection = null
        sourceCatalog = null
        val handshakeBytes = ClientHandshake(
            locale = locale,
            mod_version = platform.clientModVersion.ifBlank { "unknown" },
            protocol_version = protocolVersion,
            initial_state = initialState,
            client_send_monotonic = now,
        ).encode()
        // Handshake packet is sent unframed so legacy v2 servers can decode it directly and negotiate downgrade.
        if (trySendToServer(PacketIds.CLIENT_HANDSHAKE, protocolVersion, framed = false) { handshakeBytes } != null) return
        logger.info(
            "{} client handshake sent (locale={}, initialState={}, mod={}, protocol={})",
            platform.name,
            locale,
            initialState,
            platform.clientModVersion,
            protocolVersion,
        )
    }

    private fun applyPlaybackSnapshot(snapshot: PlaybackSnapshot, fromSyncState: Boolean) {
        if (!canHandlePlaybackPackets()) {
            logIgnoredPlaybackPacket("PlaybackSnapshot", "state=${snapshot.state}")
            return
        }
        val trackProto = snapshot.track ?: run {
            logger.warn("Ignoring {} playback snapshot without track metadata (state={}).", platform.name, snapshot.state)
            return
        }
        val playback = snapshot.playback?.toApi() ?: run {
            logger.error("{} playback snapshot for '{}' is missing playback details; refusing to start audio.", platform.name, trackProto.title)
            return
        }
        val track = trackProto.toApi().withLyrics(snapshot.lyric_lrc, snapshot.secondary_lyric_lrc)

        if (playback.url.isBlank()) {
            logger.error("{} playback snapshot for '{}' is missing a playable URL; refusing to start audio.", platform.name, track.title)
            stopActivePlayback()
            return
        }
        if (!applyClientMediaPolicy(track, playback.url)) {
            stopActivePlayback(preserveLocalPlaybackNotice = true)
            return
        }
        val locallyAllowedTrack = applyLocalContentFilter(track) ?: run {
            stopActivePlayback(preserveLocalPlaybackNotice = true)
            return
        }
        currentLyrics = parseLyrics(locallyAllowedTrack.lyricLrc)
        currentSecondaryLyrics = parseLyrics(locallyAllowedTrack.secondaryLyricLrc)

        when (snapshot.state) {
            PlaybackStateProto.PLAYING -> {
                if (!ensurePlaybackLock()) return
                val serverNow = currentServerMonotonicNow()
                val seekMs = anchoredPlaybackPositionMs(
                    positionMs = snapshot.position_ms,
                    anchorServerMonotonic = snapshot.position_anchor_server_monotonic,
                    durationMs = locallyAllowedTrack.durationMs,
                )
                logInvalidServerPosition("PlaybackSnapshot PLAYING", snapshot.position_ms, seekMs, locallyAllowedTrack)
                logger.info(
                    "{} playback started from snapshot: source={} id={} title='{}' positionMs={} fromSyncState={}",
                    platform.name,
                    locallyAllowedTrack.sourceId.orEmpty(),
                    locallyAllowedTrack.id,
                    locallyAllowedTrack.title,
                    seekMs,
                    fromSyncState,
                )
                logger.debug(
                    "{} playback snapshot resource for '{}' url='{}' headers={}",
                    platform.name,
                    locallyAllowedTrack.title,
                    playback.url,
                    playback.headers.keys,
                )
                applyTrackNormalization(locallyAllowedTrack, "snapshot PLAYING")
                val newContext = TrackContext(
                    track = locallyAllowedTrack,
                    playback = playback,
                    state = PlaybackState.Playing(seekMs),
                    serverStartMonotonic = if (snapshot.position_anchor_server_monotonic != 0L) {
                        snapshot.position_anchor_server_monotonic - snapshot.position_ms.coerceAtLeast(0L) * 1_000_000L
                    } else {
                        serverNow - seekMs * 1_000_000L
                    },
                    serverResumeMonotonic = serverNow,
                )
                currentContext = newContext
                startPlatformPlayback(newContext, seekMs, pauseAfterStart = false, preserveFailureState = false)
                if (LocalPlaybackIdentity.of(locallyAllowedTrack, playback).matches(currentContext ?: return)) {
                    platform.stopBlockedPlatformSoundsIfNeeded()
                    CoreEvents.bus.fire(
                        OnClientPlaybackStarted(
                            track = locallyAllowedTrack,
                            playback = playback,
                            positionMs = seekMs,
                            startCause = if (fromSyncState) PlaybackStartCause.CATCH_UP else PlaybackStartCause.NEW_TRACK,
                        )
                    )
                }
            }

            PlaybackStateProto.PAUSED -> {
                if (!ensurePlaybackLock()) return
                val posMs = normalizeClientPosition(snapshot.position_ms, locallyAllowedTrack.durationMs)
                val serverNow = currentServerMonotonicNow()
                logInvalidServerPosition("PlaybackSnapshot PAUSED", snapshot.position_ms, posMs, locallyAllowedTrack)
                logger.info(
                    "{} playback loaded paused snapshot: source={} id={} title='{}' positionMs={} fromSyncState={}",
                    platform.name,
                    locallyAllowedTrack.sourceId.orEmpty(),
                    locallyAllowedTrack.id,
                    locallyAllowedTrack.title,
                    posMs,
                    fromSyncState,
                )
                logger.debug(
                    "{} paused playback snapshot resource for '{}' url='{}' headers={}",
                    platform.name,
                    locallyAllowedTrack.title,
                    playback.url,
                    playback.headers.keys,
                )
                applyTrackNormalization(locallyAllowedTrack, "snapshot PAUSED")
                val newContext = TrackContext(
                    track = locallyAllowedTrack,
                    playback = playback,
                    state = PlaybackState.Paused(posMs),
                    serverStartMonotonic = serverNow - posMs * 1_000_000L,
                    serverResumeMonotonic = serverNow,
                )
                currentContext = newContext
                startPlatformPlayback(newContext, posMs, pauseAfterStart = true, preserveFailureState = false)
                if (LocalPlaybackIdentity.of(locallyAllowedTrack, playback).matches(currentContext ?: return)) {
                    CoreEvents.bus.fire(
                        OnClientPlaybackStarted(
                            track = locallyAllowedTrack,
                            playback = playback,
                            positionMs = posMs,
                            startCause = if (fromSyncState) PlaybackStartCause.CATCH_UP else PlaybackStartCause.NEW_TRACK,
                        )
                    )
                }
            }

            PlaybackStateProto.STOPPED -> {
                logger.info("Received stopped {} playback snapshot; clearing local playback context.", platform.name)
                releasePlaybackLock(clearMessage = true)
                stopActivePlayback(fireEvent = true)
            }
        }
        listener.onPlaybackStateChanged()
    }

    private fun applyTimeSync(resp: SyncResponse) {
        serverClockOffset = timeSyncHandler.computeClientOffset(resp)
        timeSyncEstablished = true
    }

    private fun anchoredPlaybackPositionMs(
        positionMs: Long,
        anchorServerMonotonic: Long,
        durationMs: Long,
    ): Long {
        val basePositionMs = positionMs.coerceAtLeast(0L)
        if (!timeSyncEstablished || anchorServerMonotonic == 0L) {
            return normalizeClientPosition(basePositionMs, durationMs)
        }
        val elapsedMs = (currentServerMonotonicNow() - anchorServerMonotonic) / 1_000_000L
        return normalizeClientPosition(basePositionMs + elapsedMs, durationMs)
    }

    private fun normalizeClientPosition(positionMs: Long, durationMs: Long): Long {
        val nonNegative = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) nonNegative.coerceAtMost(durationMs) else nonNegative
    }

    private fun currentServerMonotonicNow(): Long = System.nanoTime() + serverClockOffset

    private fun canHandlePlaybackPackets(): Boolean =
        canHandleSessionPackets() && playbackRegistrationActive && isPlaybackEnabledForCurrentServer()

    private fun canHandleSessionPackets(): Boolean =
        participationRequested && serverSessionAccepted

    private fun canHandleDirectResponses(packetName: String): Boolean {
        val reason = sessionPacketBlockReason()
        if (reason != null) {
            logger.warn("Ignoring {} {}: {}", platform.name, packetName, reason)
            return false
        }
        return true
    }

    private fun sessionPacketBlockReason(): String? = when {
        !participationRequested -> "client session has not been requested"
        !serverHandshakeReceived -> "server handshake has not completed"
        !serverSessionAccepted -> lastServerWelcomeRejection?.let(::renderServerWelcomeRejection)
            ?: "server handshake was rejected"
        else -> null
    }

    private fun playbackPacketBlockReason(): String? {
        sessionPacketBlockReason()?.let { return it }
        if (!playbackRegistrationActive) {
            return if (standbyWaitingForLock) {
                "client is in standby waiting for the local playback lock"
            } else {
                "client is in standby"
            }
        }
        if (!isPlaybackEnabledForCurrentServer()) {
            return "playback is disabled for the current server"
        }
        return null
    }

    private fun logIgnoredSessionPacket(packetName: String, debugOnly: Boolean = false) {
        val reason = sessionPacketBlockReason() ?: return
        if (debugOnly) {
            logger.debug("Ignoring {} {}: {}", platform.name, packetName, reason)
        } else {
            logger.warn("Ignoring {} {}: {}", platform.name, packetName, reason)
        }
    }

    private fun logIgnoredPlaybackPacket(packetName: String, detail: String = "") {
        val reason = playbackPacketBlockReason() ?: return
        val detailSuffix = if (detail.isBlank()) "" else " ($detail)"
        logger.warn("Ignoring {} {}{}: {}", platform.name, packetName, detailSuffix, reason)
    }

    private fun applyDesiredParticipation(desired: DesiredParticipation) {
        standbyWaitingForLock = desired.waitForLock
        if (!desired.waitForLock) {
            stopStandbyPolling()
            clearInstanceLockStandby()
        }
        when (desired.state) {
            ClientStateProto.CLIENT_STATE_ACTIVE -> enterActiveParticipation()
            ClientStateProto.CLIENT_STATE_STANDBY -> enterStandbyParticipation(desired.waitForLock)
        }
    }

    private fun desiredParticipation(): DesiredParticipation {
        val playbackEnabled = isPlaybackEnabledForCurrentServer()
        if (!playbackEnabled) {
            return DesiredParticipation(
                state = ClientStateProto.CLIENT_STATE_STANDBY,
                waitForLock = false,
            )
        }
        val waitingForLock = isGlobalInstancePlaybackLockEnabled() && !InstancePlaybackLock.probeAvailable()
        return DesiredParticipation(
            state = if (waitingForLock) ClientStateProto.CLIENT_STATE_STANDBY else ClientStateProto.CLIENT_STATE_ACTIVE,
            waitForLock = waitingForLock,
        )
    }

    private fun enterActiveParticipation() {
        stopStandbyPolling()
        standbyWaitingForLock = false
        clearInstanceLockStandby()
        if (playbackRegistrationActive) {
            logger.debug("{} client participation is already ACTIVE.", platform.name)
            return
        }
        if (!serverSessionAccepted) {
            logger.debug("Deferring {} ACTIVE participation request until the server handshake is accepted.", platform.name)
            return
        }
        logger.info("Requesting {} ACTIVE MoeMusic participation.", platform.name)
        sendClientStateChange(ClientStateProto.CLIENT_STATE_ACTIVE)
    }

    private fun enterStandbyParticipation(waitForLock: Boolean) {
        val hadActiveRegistration = playbackRegistrationActive
        playbackRegistrationActive = false
        releasePlaybackLock(clearMessage = !waitForLock)
        stopActivePlayback()
        if (hadActiveRegistration) {
            logger.info("Requesting {} STANDBY MoeMusic participation (waitForLock={}).", platform.name, waitForLock)
            sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
        } else {
            logger.info("{} client is in STANDBY MoeMusic participation (waitForLock={}).", platform.name, waitForLock)
        }
        if (waitForLock) {
            updateInstanceLockStandby(notifyUser = serverHandshakeReceived)
            startStandbyPolling()
        } else {
            stopStandbyPolling()
        }
    }

    private fun TrackInfo.withLyrics(primary: String, secondary: String): TrackInfo = copy {
        lyricLrc = primary.ifEmpty { null }
        secondaryLyricLrc = secondary.ifEmpty { null }
        lyricsFetched = primary.isNotEmpty() || secondary.isNotEmpty()
    }

    private fun applyClientMediaPolicy(track: TrackInfo, url: String): Boolean =
        when (val verdict = ClientMediaFirewall.evaluate(url)) {
            MediaUrlPolicyResult.Allow -> true
            is MediaUrlPolicyResult.Reject -> {
                val message = platform.render(
                    LocalizedText.key(
                        "screen.moemusic.playback.local_media_blocked",
                        track.title.ifBlank { track.id },
                        verdict.reason,
                    )
                )
                lastLocalPlaybackBlockedMessage = message
                lastLocalPlaybackFailureMessage = null
                logger.info("{} track '{}' blocked by local media policy: {}", platform.name, track.title, verdict.reason.debugString())
                listener.onLocalPlaybackBlocked(message)
                platform.showLocalPlaybackBlocked(
                    LocalizedText.key("screen.moemusic.playback.toast.title"),
                    message,
                )
                false
            }
        }

    private fun applyLocalContentFilter(track: TrackInfo): TrackInfo? {
        if (!ContentFilterRuntime.clientFilterEnabled()) return track
        val reason = ContentFilterRuntime.trackBlockReason(track) ?: return track
        val message = platform.render(
            LocalizedText.key(
                "screen.moemusic.playback.local_filter_blocked",
                track.title.ifBlank { track.id },
                reason,
            )
        )
        lastLocalPlaybackBlockedMessage = message
        lastLocalPlaybackFailureMessage = null
        logger.info("{} track '{}' blocked by local content filter: {}", platform.name, track.title, reason.debugString())
        listener.onLocalPlaybackBlocked(message)
        platform.showLocalPlaybackBlocked(
            LocalizedText.key("screen.moemusic.filter.toast.title"),
            message,
        )
        return null
    }

    private fun isGlobalInstancePlaybackLockEnabled(): Boolean =
        platform.clientConfig().globalInstancePlaybackLock

    private fun ensurePlaybackLock(): Boolean {
        if (!isGlobalInstancePlaybackLockEnabled()) {
            releasePlaybackLock(clearMessage = true)
            return true
        }
        if (InstancePlaybackLock.tryAcquire()) {
            stopStandbyPolling()
            standbyWaitingForLock = false
            clearInstanceLockStandby()
            return true
        }

        enterStandbyForInstanceLock()
        return false
    }

    private fun enterStandbyForInstanceLock() {
        if (!participationRequested) return

        standbyWaitingForLock = true
        val hadActiveRegistration = playbackRegistrationActive
        playbackRegistrationActive = false
        if (hadActiveRegistration) {
            logger.info("{} local playback lock unavailable; requesting STANDBY MoeMusic participation.", platform.name)
            sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
        }
        releasePlaybackLock(clearMessage = false)
        stopActivePlayback()
        updateInstanceLockStandby(notifyUser = true)
        startStandbyPolling()
    }

    private fun releasePlaybackLock(clearMessage: Boolean) {
        InstancePlaybackLock.release()
        if (clearMessage) {
            clearInstanceLockStandby()
        }
    }

    private fun updateInstanceLockStandby(notifyUser: Boolean) {
        val message = renderInstanceLockWaitMessage()
        lastInstanceLockMessage = message
        listener.onInstancePlaybackStandby(message)
        if (!notifyUser || instanceLockWaitNotified) return
        instanceLockWaitNotified = true
        platform.showInstanceLockStandby(message)
    }

    private fun clearInstanceLockStandby() {
        instanceLockWaitNotified = false
        lastInstanceLockMessage = null
        listener.onInstancePlaybackStandby(null)
    }

    private fun renderInstanceLockWaitMessage(): String =
        platform.render(LocalizedText.key("screen.moemusic.playback.instance_lock_waiting"))

    private fun startStandbyPolling() {
        if (
            !participationRequested ||
            !serverHandshakeReceived ||
            !standbyWaitingForLock ||
            playbackRegistrationActive ||
            !isGlobalInstancePlaybackLockEnabled()
        ) {
            return
        }
        if (standbyPollJob?.isActive == true) return

        standbyPollJob = scope.launch {
            while (isActive) {
                delay(standbyLockPollIntervalMs.milliseconds)
                if (!participationRequested || !standbyWaitingForLock || playbackRegistrationActive) return@launch
                if (!isPlaybackEnabledForCurrentServer()) return@launch

                if (InstancePlaybackLock.probeAvailable()) {
                    logger.info("{} local playback lock became available; requesting ACTIVE MoeMusic participation.", platform.name)
                    platform.executeOnClientThread {
                        if (!participationRequested || !standbyWaitingForLock || playbackRegistrationActive) return@executeOnClientThread
                        if (!isPlaybackEnabledForCurrentServer()) return@executeOnClientThread
                        applyDesiredParticipation(desiredParticipation())
                    }
                    return@launch
                }
            }
        }
    }

    private fun stopStandbyPolling() {
        standbyPollJob?.cancel()
        standbyPollJob = null
    }

    private fun startPlatformPlayback(
        ctx: TrackContext,
        positionMs: Long,
        pauseAfterStart: Boolean,
        preserveFailureState: Boolean,
    ) {
        val identity = LocalPlaybackIdentity.of(ctx.track, ctx.playback)
        synchronized(localPlaybackFailureLock) {
            if (!preserveFailureState || (localPlaybackFailureIdentity != null && localPlaybackFailureIdentity != identity)) {
                clearLocalPlaybackFailureStateLocked()
            }
        }
        if (!preserveFailureState) {
            lastLocalPlaybackBlockedMessage = null
            lastLocalPlaybackFailureMessage = null
        }
        platform.audio.play(ctx.playback, positionMs) { error ->
            handleLocalPlaybackFailure(identity, error)
        }
        if (pauseAfterStart && identity.matches(currentContext ?: return)) {
            platform.audio.pause()
        }
    }

    private fun handleLocalPlaybackFailure(identity: LocalPlaybackIdentity, failure: ClientAudioFailure) {
        platform.executeOnClientThread {
            val ctx = currentContext ?: return@executeOnClientThread
            if (!identity.matches(ctx)) return@executeOnClientThread
            val message = failure.message.ifBlank { platform.render(LocalizedText.key("error.moemusic.internal")) }
            val attempt = synchronized(localPlaybackFailureLock) {
                if (localPlaybackFailureIdentity != identity) {
                    localPlaybackFailureIdentity = identity
                    localPlaybackFailureAttempts = 0
                }
                if (shouldRetryLocalPlaybackFailure(failure) &&
                    localPlaybackFailureAttempts < localPlaybackRetryDelaysMs.size
                ) {
                    localPlaybackFailureAttempts += 1
                    localPlaybackFailureAttempts
                } else {
                    0
                }
            }

            if (attempt > 0) {
                scheduleLocalPlaybackRetry(identity, ctx, message, attempt)
                return@executeOnClientThread
            }

            finishLocalPlaybackFailure(identity, ctx, message)
        }
    }

    private fun scheduleLocalPlaybackRetry(
        identity: LocalPlaybackIdentity,
        ctx: TrackContext,
        error: String,
        attempt: Int,
    ) {
        val trackTitle = ctx.track.title.ifBlank { ctx.track.id }
        val message = platform.render(
            LocalizedText.key(
                "screen.moemusic.playback.local_retrying",
                trackTitle,
                attempt,
                localPlaybackRetryDelaysMs.size,
            )
        )
        lastLocalPlaybackFailureMessage = message
        lastLocalPlaybackBlockedMessage = null
        logger.warn(
            "{} local playback failed for '{}' (attempt {}/{}); retrying: {}",
            platform.name,
            trackTitle,
            attempt,
            localPlaybackRetryDelaysMs.size,
            error,
        )
        listener.onLocalPlaybackRetrying(message)

        localPlaybackRetryJob?.cancel()
        localPlaybackRetryJob = scope.launch {
            delay(localPlaybackRetryDelaysMs[attempt - 1].milliseconds)
            platform.executeOnClientThread {
                retryLocalPlayback(identity)
            }
        }
    }

    private fun retryLocalPlayback(identity: LocalPlaybackIdentity) {
        val ctx = currentContext ?: return
        if (!identity.matches(ctx)) return
        val positionMs = estimatedPlaybackPositionMs(ctx)
        logger.debug(
            "{} retrying local playback: source={} id={} title='{}' positionMs={}",
            platform.name,
            ctx.track.sourceId.orEmpty(),
            ctx.track.id,
            ctx.track.title,
            positionMs,
        )
        startPlatformPlayback(
            ctx = ctx,
            positionMs = positionMs,
            pauseAfterStart = ctx.state is PlaybackState.Paused,
            preserveFailureState = true,
        )
        if (ctx.state is PlaybackState.Playing && identity.matches(currentContext ?: return)) {
            platform.stopBlockedPlatformSoundsIfNeeded()
        }
        if (identity.matches(currentContext ?: return)) {
            lastLocalPlaybackFailureMessage = null
            listener.onLocalPlaybackRecovered(ctx.track)
        }
    }

    private fun finishLocalPlaybackFailure(identity: LocalPlaybackIdentity, ctx: TrackContext, error: String) {
        if (!identity.matches(currentContext ?: return)) return
        val trackTitle = ctx.track.title.ifBlank { ctx.track.id }
        val message = platform.render(
            LocalizedText.key(
                "screen.moemusic.playback.local_failed",
                trackTitle,
                error,
            )
        )
        lastLocalPlaybackFailureMessage = message
        lastLocalPlaybackBlockedMessage = null
        logger.warn("{} local playback failed permanently for '{}': {}", platform.name, trackTitle, error)
        listener.onLocalPlaybackFailed(message)
        releasePlaybackLock(clearMessage = true)
        stopActivePlayback(fireEvent = true, notifyStateChange = false, preserveLocalPlaybackNotice = true)
        platform.showLocalPlaybackFailed(
            LocalizedText.key("screen.moemusic.playback.failure.toast.title"),
            message,
        )
        platform.onLocalPlaybackFailureFinal(ctx.track, message)
    }

    private fun shouldRetryLocalPlaybackFailure(failure: ClientAudioFailure): Boolean =
        failure.recoverability == ClientAudioFailureRecoverability.RETRYABLE

    private fun estimatedPlaybackPositionMs(ctx: TrackContext): Long =
        when (val state = ctx.state) {
            is PlaybackState.Playing ->
                normalizeClientPosition((currentServerMonotonicNow() - ctx.serverStartMonotonic) / 1_000_000L, ctx.track.durationMs)

            is PlaybackState.Paused -> state.positionMs
            PlaybackState.Stopped -> 0L
            else -> 0L
        }

    private fun cancelLocalPlaybackRetry() {
        synchronized(localPlaybackFailureLock) {
            clearLocalPlaybackFailureStateLocked()
        }
    }

    private fun clearLocalPlaybackFailureStateLocked() {
        localPlaybackRetryJob?.cancel()
        localPlaybackRetryJob = null
        localPlaybackFailureIdentity = null
        localPlaybackFailureAttempts = 0
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                try {
                    sendSyncRequest()
                } catch (e: Exception) {
                    logger.debug("{} SyncRequest failed: {}", platform.name, e.message)
                }
                delay(syncIntervalMs.milliseconds)
            }
        }
    }

    private fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
    }

    private fun stopActivePlayback(
        fireEvent: Boolean = false,
        notifyStateChange: Boolean = true,
        preserveLocalPlaybackNotice: Boolean = false,
    ) {
        val stoppedTrack = currentContext?.track
        cancelLocalPlaybackRetry()
        if (!preserveLocalPlaybackNotice) {
            lastLocalPlaybackBlockedMessage = null
            lastLocalPlaybackFailureMessage = null
        }
        platform.audio.stop()
        clearTrackNormalization("stop active playback")
        currentContext = null
        currentLyrics = null
        currentSecondaryLyrics = null
        if (fireEvent && stoppedTrack != null) {
            CoreEvents.bus.fire(OnClientPlaybackStopped(stoppedTrack))
        }
        if (notifyStateChange) {
            listener.onPlaybackStateChanged()
        }
    }

    private fun logInvalidPlaybackControl(action: PlaybackControlAction, positionMs: Long) {
        if (action != PlaybackControlAction.SEEK) return
        val durationMs = currentContext?.track?.durationMs ?: 0L
        val invalid = positionMs < 0L || (durationMs in 1..<positionMs)
        if (invalid) {
            logger.warn(
                "Sending {} SEEK with out-of-range position: requestedMs={} durationMs={}",
                platform.name,
                positionMs,
                durationMs,
            )
        }
    }

    private fun logInvalidServerPosition(origin: String, requestedMs: Long, normalizedMs: Long, track: TrackInfo) {
        val durationMs = track.durationMs
        val invalid = requestedMs < 0L || (durationMs in 1..<requestedMs)
        if (invalid) {
            logger.warn(
                "{} {} carried an out-of-range playback position: requestedMs={} normalizedMs={} durationMs={} source={} id={} title='{}'",
                platform.name,
                origin,
                requestedMs,
                normalizedMs,
                durationMs,
                track.sourceId.orEmpty(),
                track.id,
                track.title,
            )
        }
    }

    private fun applyTrackNormalization(track: TrackInfo, reason: String) {
        val normalization = platform.clientConfig().loudnessNormalization.normalized()
        val decision = normalization.decisionForTrack(track.loudness)
        logger.debug(
            "{} normalization {}: source={} id={} title='{}' mode={} targetLufs={} rawLoudness={} effectiveLoudness={} usedIntegratedLufs={} fallbackAssumedLoudness={} appliedGain={}",
            platform.name,
            reason,
            track.sourceId.orEmpty(),
            track.id,
            track.title,
            normalization.mode,
            normalization.targetLufs,
            describeLoudness(track.loudness),
            describeLoudness(decision.effectiveLoudness),
            describeIntegratedLufs(decision.usedIntegratedLufs),
            decision.usedFallbackIntegratedLufs,
            decision.gain,
        )
        platform.audio.setNormalizationGain(decision.gain)
    }

    private fun clearTrackNormalization(reason: String) {
        logger.debug("{} normalization {}: appliedGain=1.0", platform.name, reason)
        platform.audio.setNormalizationGain(1.0f)
    }

    private fun describeLoudness(loudness: LoudnessInfo?): String {
        if (loudness == null) return "none"
        val lufs = loudness.integratedLufs
            ?.let { String.format(Locale.ROOT, "%.4f LUFS", it) }
            ?: "none"
        val peak = loudness.peak?.let { peak ->
            "amplitude=${String.format(Locale.ROOT, "%.4f", peak.amplitudeLinear)} kind=${peak.kind}"
        } ?: "none"
        return "lufs=$lufs peak=$peak"
    }

    private fun describeIntegratedLufs(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.4f LUFS", it) } ?: "none"

    override fun ensureDirectRequestSessionReady() {
        if (!platform.hasConnection()) {
            throw ClientRequestException("Not connected to a server.")
        }
        if (!participationRequested) {
            throw ClientRequestException("MoeMusic client session is not initialized for this connection.")
        }
        if (!serverHandshakeReceived) {
            throw ClientRequestException("MoeMusic server handshake has not completed yet.")
        }
        if (!serverSessionAccepted) {
            val rejection = lastServerWelcomeRejection
            throw ClientRequestException(
                if (rejection != null) renderServerWelcomeRejection(rejection)
                else platform.render(LocalizedText.key("screen.moemusic.unavailable.rejected.body"))
            )
        }
    }

    private fun nextCorrelatedRequestId(): Long? {
        sessionPacketBlockReason()?.let { reason ->
            logger.warn("Cannot send {} MoeMusic request: {}", platform.name, reason)
            return null
        }
        return requestIdCounter.getAndIncrement().coerceAtLeast(1L)
    }

    private fun failPendingRequests(cause: Throwable) {
        pendingSearchResponses.failAll(cause)
        pendingQueueResponses.failAll(cause)
        pendingUiBootstrapResponses.failAll(cause)
        pendingTrackSubmitResponses.failAll(cause)
        pendingIdentifierSubmitResponses.failAll(cause)
        pendingSelectionSubmitResponses.failAll(cause)
        pendingSelectionPageResponses.failAll(cause)
        pendingQueueRemoveResponses.failAll(cause)
        pendingQueueClearResponses.failAll(cause)
        pendingPlaybackControlResponses.failAll(cause)
        pendingContentFilterActionResponses.failAll(cause)
    }

    private fun <T> beginCorrelatedRequest(
        registry: PendingRequestRegistry<T>,
        packetId: PacketId,
        payloadFactory: (Long) -> ByteArray,
    ): Deferred<T>? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val deferred = registry.register(requestId)
        trySendToServer(packetId) { payloadFactory(requestId) }?.let { failure ->
            registry.remove(requestId)
            deferred.completeExceptionally(ClientRequestException("Failed to send packet $packetId.", failure))
        }
        return deferred
    }

    override fun beginSearchRequest(query: String, sourceId: String, limit: Int, offset: Int): Deferred<SearchResponse>? =
        beginCorrelatedRequest(pendingSearchResponses, PacketIds.SEARCH_REQUEST) { requestId ->
            SearchRequest(query = query, source_id = sourceId, limit = limit, offset = offset, request_id = requestId).encode()
        }

    override fun beginQueueRequest(limit: Int, offset: Int): Deferred<QueueResponse>? =
        beginCorrelatedRequest(pendingQueueResponses, PacketIds.QUEUE_REQUEST) { requestId ->
            QueueRequest(limit = limit, offset = offset, request_id = requestId).encode()
        }

    internal fun beginUiBootstrapRequest(queueLimit: Int = 20): Deferred<UiBootstrapResponse>? =
        beginCorrelatedRequest(pendingUiBootstrapResponses, PacketIds.UI_BOOTSTRAP_REQUEST) { requestId ->
            UiBootstrapRequest(queue_limit = queueLimit, request_id = requestId).encode()
        }

    override fun beginSelectionPageRequest(sessionId: String, offset: Int, limit: Int): Deferred<SelectionPageResponse>? =
        beginCorrelatedRequest(pendingSelectionPageResponses, PacketIds.SELECTION_PAGE_REQUEST) { requestId ->
            SelectionPageRequest(session_id = sessionId, offset = offset, limit = limit, request_id = requestId).encode()
        }

    override fun beginQueueRemoveRequest(
        sourceId: String,
        trackId: String,
        queueEntryId: String?,
    ): Deferred<QueueRemoveResponse>? {
        if (sourceId.isBlank() || trackId.isBlank()) return null
        return beginCorrelatedRequest(pendingQueueRemoveResponses, PacketIds.QUEUE_REMOVE_REQUEST) { requestId ->
            QueueRemoveRequest(
                source_id = sourceId,
                track_id = trackId,
                queue_entry_id = queueEntryId.orEmpty(),
                request_id = requestId,
            ).encode()
        }
    }

    override fun beginQueueClearRequest(
        scope: QueueClearScopeProto,
        targetUserId: String?,
    ): Deferred<QueueClearResponse>? {
        if (activeProtocolVersion < 3) return null
        return beginCorrelatedRequest(pendingQueueClearResponses, PacketIds.QUEUE_CLEAR_REQUEST) { requestId ->
            QueueClearRequest(
                scope = scope,
                target_user_id = targetUserId.orEmpty(),
                request_id = requestId,
            ).encode()
        }
    }

    override fun beginTrackSubmitRequest(track: TrackInfo, mode: TrackAddMode): Deferred<TrackSubmitResponse>? =
        beginCorrelatedRequest(pendingTrackSubmitResponses, PacketIds.TRACK_SUBMIT) { requestId ->
            TrackSubmitRequest(
                source_id = track.sourceId.orEmpty(),
                track_id = track.id,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    override fun beginTrackSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<TrackSubmitResponse>? =
        entry.toDirectTrackSubmitTrack()?.let { track -> beginTrackSubmitRequest(track, mode) }

    override fun beginIdentifierSubmitRequest(identifier: String, mode: TrackAddMode): Deferred<IdentifierSubmitResponse>? =
        beginCorrelatedRequest(pendingIdentifierSubmitResponses, PacketIds.IDENTIFIER_SUBMIT) { requestId ->
            IdentifierSubmitRequest(
                identifier = identifier,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    override fun beginSelectionSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<SelectionSubmitResponse>? =
        beginCorrelatedRequest(pendingSelectionSubmitResponses, PacketIds.SELECTION_SUBMIT) { requestId ->
            SelectionSubmitRequest(
                source_id = entry.sourceId.orEmpty(),
                selection_id = entry.selectionId,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    override fun beginPlaybackControlRequest(action: PlaybackControlAction, positionMs: Long): Deferred<PlaybackControlResponse>? =
        beginCorrelatedRequest(pendingPlaybackControlResponses, PacketIds.PLAYBACK_CONTROL_REQUEST) { requestId ->
            logInvalidPlaybackControl(action, positionMs)
            PlaybackControlRequest(action = action, position_ms = positionMs, request_id = requestId).encode()
        }

    override fun beginContentFilterTrackActionRequest(
        sourceId: String,
        trackId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        beginCorrelatedRequest(pendingContentFilterActionResponses, PacketIds.CONTENT_FILTER_ACTION_REQUEST) { requestId ->
            ContentFilterActionRequest(
                action = if (ban) {
                    ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN
                } else {
                    ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN
                },
                target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK,
                source_id = sourceId,
                value_id = trackId,
                note = note.orEmpty(),
                request_id = requestId,
            ).encode()
        }

    override fun beginContentFilterArtistActionRequest(
        sourceId: String,
        artistId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        beginCorrelatedRequest(pendingContentFilterActionResponses, PacketIds.CONTENT_FILTER_ACTION_REQUEST) { requestId ->
            ContentFilterActionRequest(
                action = if (ban) {
                    ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN
                } else {
                    ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN
                },
                target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_ARTIST,
                source_id = sourceId,
                value_id = artistId,
                note = note.orEmpty(),
                request_id = requestId,
            ).encode()
        }

    private fun TrackAddMode.toProto(): TrackAddModeProto = when (this) {
        TrackAddMode.NORMAL -> TrackAddModeProto.TRACK_ADD_MODE_NORMAL
        TrackAddMode.SKIP_AUTOPLAY -> TrackAddModeProto.TRACK_ADD_MODE_SKIP_AUTOPLAY
        TrackAddMode.PLAY_NOW -> TrackAddModeProto.TRACK_ADD_MODE_PLAY_NOW
    }

    private fun SelectionEntry.toDirectTrackSubmitTrack(): TrackInfo? {
        val trackId = directTrackId?.takeIf(String::isNotBlank) ?: return null
        val entry = this
        return TrackInfo(
            id = trackId,
            title = entry.title,
            artists = entry.artists,
            durationMs = entry.durationMs,
        ) {
            sourceId = entry.sourceId
            album = entry.album
            unavailableReason = entry.unavailableReason
        }
    }

    private fun serverWelcomeRejection(msg: ServerWelcome): ServerWelcomeRejection =
        ServerWelcomeRejection(
            reason = when (msg.reject_reason) {
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_PROTOCOL_MISMATCH ->
                    ServerWelcomeRejectionReason.PROTOCOL_MISMATCH
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_SERVER_ERROR ->
                    ServerWelcomeRejectionReason.SERVER_ERROR
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_UNSPECIFIED ->
                    if (msg.server_protocol_version != 0 && msg.server_protocol_version != platform.clientProtocolVersion) {
                        ServerWelcomeRejectionReason.PROTOCOL_MISMATCH
                    } else {
                        ServerWelcomeRejectionReason.UNKNOWN
                    }
            },
            clientProtocolVersion = platform.clientProtocolVersion,
            serverProtocolVersion = msg.server_protocol_version,
            detail = msg.failure.ifBlank { null },
        )

    private fun renderServerWelcomeRejection(rejection: ServerWelcomeRejection): String =
        platform.render(rejection.toLocalizedText())

    private companion object {
        private const val HANDSHAKE_GRACE_NANOS = 3_000_000_000L
        private const val DEFAULT_SYNC_INTERVAL_MS = 30_000L
        private const val DEFAULT_STANDBY_LOCK_POLL_INTERVAL_MS = 3_000L
        private val DEFAULT_LOCAL_PLAYBACK_RETRY_DELAYS_MS = listOf(750L, 1_500L)
    }
}
