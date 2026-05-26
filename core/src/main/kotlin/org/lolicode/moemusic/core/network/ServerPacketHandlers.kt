package org.lolicode.moemusic.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lolicode.moemusic.api.FilterBlockException
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.SearchableMusicSource
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.IdentifierSubmitOutcome
import org.lolicode.moemusic.api.service.PlaybackAction
import org.lolicode.moemusic.api.service.QueueRemoveResult
import org.lolicode.moemusic.api.service.SelectionSubmitOutcome
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuleEditor
import org.lolicode.moemusic.core.error.UserFacingErrors
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.playback.TimeSyncHandler
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.protocol.ProtocolViewMapper
import org.lolicode.moemusic.core.protocol.proto.*
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.slf4j.LoggerFactory
import java.util.UUID

interface ServerPacketSessionBridge {
    fun activate(sender: MoeMusicUser, locale: String): MoeMusicUser
    fun standby(sender: MoeMusicUser, locale: String): MoeMusicUser
    fun handleRegisteredClientLeave(userId: UUID)
}

/**
 * Registers all inbound (C→S) packet handlers into [PacketRegistry].
 *
 * Each handler decodes the Wire proto and delegates to core services. Platform code only
 * supplies session bookkeeping through [ServerPacketSessionBridge] and the raw [NetworkChannel].
 */
class ServerPacketHandlers(
    private val channel: NetworkChannel,
    private val sessions: ServerPacketSessionBridge,
) {

    private val logger = LoggerFactory.getLogger(ServerPacketHandlers::class.java)
    private val timeSyncHandler = TimeSyncHandler()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun registerAll(registry: PacketRegistry) {
        // CLIENT_HANDSHAKE — registers the user's locale plus initial playback participation.
        registry.register(
            PacketIds.CLIENT_HANDSHAKE,
            { ClientHandshake.ADAPTER.decode(it) },
        ) { msg, sender ->
            val serverRecvMonotonic = System.nanoTime()
            if (sender == null) return@register
            if (msg.protocol_version != MoeMusicProtocol.VERSION) {
                val welcome = ServerWelcome(
                    accepted = false,
                    failure = "MoeMusic protocol mismatch: client=${msg.protocol_version}, server=${MoeMusicProtocol.VERSION}",
                    server_protocol_version = MoeMusicProtocol.VERSION,
                    initial_time_sync = buildSyncResponse(msg.client_send_monotonic, serverRecvMonotonic),
                    accepted_state = msg.initial_state,
                )
                channel.sendToClient(sender, PacketIds.SERVER_WELCOME, welcome.encode())
                logger.debug(
                    "Rejected client handshake from {} (mod={}, protocol={} serverProtocol={})",
                    sender.displayName,
                    msg.mod_version,
                    msg.protocol_version,
                    MoeMusicProtocol.VERSION,
                )
                return@register
            }

            val normalizedLocale = Localization.normalizeLocale(msg.locale)
            val initialParticipation = msg.initial_state.toParticipation()
            val user = when (initialParticipation) {
                UserSessionRegistry.Participation.ACTIVE ->
                    sessions.activate(sender, normalizedLocale)
                UserSessionRegistry.Participation.STANDBY ->
                    sessions.standby(sender, normalizedLocale)
            }
            logger.debug(
                "Client handshake from {} (locale={}, state={}, mod={}, protocol={} serverProtocol={})",
                user.displayName,
                user.locale,
                initialParticipation,
                msg.mod_version,
                msg.protocol_version,
                MoeMusicProtocol.VERSION,
            )

            val initialPlayback = if (initialParticipation == UserSessionRegistry.Participation.ACTIVE) {
                // If the controller was auto-paused while no clients were connected, resume now.
                // This restores the exact position + restarts the advance timer for remaining time.
                ServerRuntimeCoordinator.ensureNativeAudienceLease()
                ServerRuntimeCoordinator.playbackController.buildPlaybackSnapshot()
            } else {
                null
            }

            val searchService = PluginManager.searchService
            val sources = searchService.sourceSnapshot().map { source ->
                SearchSourceInfo(
                    id = source.id,
                    display_name = Localization.render(user.locale, source.displayName),
                    searchable = source is SearchableMusicSource,
                )
            }
            val defaultSourceId = searchService.defaultSearchSourceId()
            val initialTimeSync = buildSyncResponse(msg.client_send_monotonic, serverRecvMonotonic)
            val welcome = ServerWelcome(
                accepted = true,
                failure = "",
                server_protocol_version = MoeMusicProtocol.VERSION,
                initial_time_sync = initialTimeSync,
                accepted_state = msg.initial_state,
                sources = sources,
                default_source_id = defaultSourceId,
                initial_playback = initialPlayback,
            )
            channel.sendToClient(user, PacketIds.SERVER_WELCOME, welcome.encode())
        }

        registry.register(
            PacketIds.CLIENT_STATE_CHANGE,
            { ClientStateChange.ADAPTER.decode(it) },
        ) { msg, sender ->
            val serverRecvMonotonic = System.nanoTime()
            if (sender == null) return@register
            when (msg.state.toParticipation()) {
                UserSessionRegistry.Participation.ACTIVE -> {
                    val locale = Localization.normalizeLocale(
                        UserSessionRegistry.localeFor(sender.id) ?: sender.locale
                    )
                    val user = sessions.activate(sender, locale)
                    logger.debug("Client state change from {} -> ACTIVE", user.displayName)
                    ServerRuntimeCoordinator.ensureNativeAudienceLease()
                    val snapshot = ServerRuntimeCoordinator.playbackController.buildPlaybackSnapshot()
                    val timeSync = buildSyncResponse(msg.client_send_monotonic, serverRecvMonotonic)
                    val update = PlaybackSnapshotUpdate(
                        time_sync = timeSync,
                        snapshot = snapshot,
                    )
                    channel.sendToClient(user, PacketIds.PLAYBACK_SNAPSHOT_UPDATE, update.encode())
                }

                UserSessionRegistry.Participation.STANDBY -> {
                    sessions.handleRegisteredClientLeave(sender.id)
                    logger.debug("Client state change from {} -> STANDBY", sender.displayName)
                }
            }
        }

        // SYNC_REQUEST — respond with server-side timestamps via TimeSyncHandler
        registry.register(
            PacketIds.SYNC_REQUEST,
            { SyncRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            val response = timeSyncHandler.handleSyncRequest(msg)
            channel.sendToClient(sender, PacketIds.SYNC_RESPONSE, response.encode())
            logger.debug("SyncRequest from {} → responded (clientSend={})", sender.displayName, msg.client_send_monotonic)
        }

        // TRACK_SUBMIT — enqueue by (source_id, track_id); permission checks then authoritative lookup
        registry.register(
            PacketIds.TRACK_SUBMIT,
            { TrackSubmitRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            // Basic sanity check before any work
            if (msg.source_id.isBlank() || msg.track_id.isBlank()) {
                channel.sendToClient(
                    sender,
                    PacketIds.TRACK_SUBMIT_RESPONSE,
                    TrackSubmitResponse(
                        failure = render(sender, LocalizedText.key("error.moemusic.track.bad_request")),
                        request_id = msg.request_id,
                    ).encode(),
                )
                return@register
            }
            val mode = msg.mode.toTrackAddMode()
            logger.debug("TrackSubmit from {}: source={}, id={}", sender.displayName, msg.source_id, msg.track_id)
            scope.launch {
                val response = try {
                    val outcome = ServerRuntimeCoordinator.userActionService.submitBySourceAndId(
                        sourceId = msg.source_id,
                        trackId = msg.track_id,
                        submitter = sender,
                        mode = mode,
                    )
                    TrackSubmitResponse(
                        track_id = outcome.track.id,
                        track_title = outcome.track.title,
                        success = render(sender, successMessage(outcome.track, outcome.result)),
                        request_id = msg.request_id,
                    )
                } catch (e: Exception) {
                    logHandledFailure("TrackSubmit", sender, e)
                    TrackSubmitResponse(
                        failure = render(sender, classifyForSender(sender, e)),
                        track_id = msg.track_id,
                        request_id = msg.request_id,
                    )
                }
                channel.sendToClient(sender, PacketIds.TRACK_SUBMIT_RESPONSE, response.encode())
            }
        }

        registry.register(
            PacketIds.SELECTION_SUBMIT,
            { SelectionSubmitRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            if (msg.source_id.isBlank() || msg.selection_id.isBlank()) {
                channel.sendToClient(
                    sender,
                    PacketIds.SELECTION_SUBMIT_RESPONSE,
                    SelectionSubmitResponse(
                        failure = render(sender, LocalizedText.key("error.moemusic.selection.bad_request")),
                        request_id = msg.request_id,
                    ).encode(),
                )
                return@register
            }

            val mode = msg.mode.toTrackAddMode()
            scope.launch {
                val response = try {
                    val canBypass = senderHasFilterBypass(sender)
                    val canSeeDetail = senderHasFilterManage(sender)
                    when (val outcome = ServerRuntimeCoordinator.userActionService.submitBySelection(
                        sourceId = msg.source_id,
                        selectionId = msg.selection_id,
                        submitter = sender,
                        mode = mode,
                    )) {
                        is SelectionSubmitOutcome.Submitted -> SelectionSubmitResponse(
                            track_id = outcome.track.id,
                            track_title = outcome.track.title,
                            success = render(sender, successMessage(outcome.track, outcome.result)),
                            request_id = msg.request_id,
                        )

                        is SelectionSubmitOutcome.Choices -> SelectionSubmitResponse(
                            success = render(sender, selectionPrompt()),
                            choices = outcome.entries.map { entry ->
                                ProtocolViewMapper.selectionToClientProto(entry, canBypass, canSeeDetail) { render(sender, it) }
                            },
                            request_id = msg.request_id,
                        )
                    }
                } catch (e: Exception) {
                    logHandledFailure("SelectionSubmit", sender, e)
                    SelectionSubmitResponse(
                        failure = render(sender, classifyForSender(sender, e)),
                        request_id = msg.request_id,
                    )
                }
                channel.sendToClient(sender, PacketIds.SELECTION_SUBMIT_RESPONSE, response.encode())
            }
        }

        registry.register(
            PacketIds.IDENTIFIER_SUBMIT,
            { IdentifierSubmitRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            val mode = msg.mode.toTrackAddMode()
            scope.launch {
                val response = try {
                    val canBypass = senderHasFilterBypass(sender)
                    val canSeeDetail = senderHasFilterManage(sender)
                    when (val outcome = ServerRuntimeCoordinator.userActionService.submitIdentifier(
                        identifier = msg.identifier,
                        submitter = sender,
                        mode = mode,
                    )) {
                        is IdentifierSubmitOutcome.Submitted -> IdentifierSubmitResponse(
                            track_id = outcome.track.id,
                            track_title = outcome.track.title,
                            success = render(sender, successMessage(outcome.track, outcome.result)),
                            request_id = msg.request_id,
                        )

                        is IdentifierSubmitOutcome.Choices -> IdentifierSubmitResponse(
                            success = render(sender, selectionPrompt()),
                            choices = outcome.entries.map { entry ->
                                ProtocolViewMapper.selectionToClientProto(entry, canBypass, canSeeDetail) { render(sender, it) }
                            },
                            request_id = msg.request_id,
                        )
                    }
                } catch (e: Exception) {
                    logHandledFailure("IdentifierSubmit", sender, e)
                    IdentifierSubmitResponse(
                        failure = render(sender, classifyForSender(sender, e)),
                        request_id = msg.request_id,
                    )
                }
                channel.sendToClient(sender, PacketIds.IDENTIFIER_SUBMIT_RESPONSE, response.encode())
            }
        }

        // SEARCH_REQUEST — run search via SearchService and respond to the requesting client only
        registry.register(
            PacketIds.SEARCH_REQUEST,
            { SearchRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            val effectiveLimit = if (msg.limit > 0) msg.limit else 20
            val query = SearchQuery(
                query = msg.query,
                sourceId = msg.source_id.ifEmpty { null },
                limit = effectiveLimit,
                offset = msg.offset,
            )
            logger.debug("SearchRequest from {}: query='{}' limit={} offset={}", sender.displayName, query.query, query.limit, query.offset)
            scope.launch {
                val response = try {
                    val outcome = ServerRuntimeCoordinator.userActionService.search(query, sender)
                    val canBypass = senderHasFilterBypass(sender)
                    val canSeeDetail = senderHasFilterManage(sender)
                    SearchResponse(
                        entries = outcome.entries.map { entry ->
                            ProtocolViewMapper.selectionToClientProto(entry, canBypass, canSeeDetail) { render(sender, it) }
                        },
                        source_id = outcome.sourceId,
                        query = msg.query,
                        offset = msg.offset,
                        total = outcome.total,
                        failure = outcome.failure?.let { render(sender, it) }.orEmpty(),
                        has_more = outcome.hasMore,
                        request_id = msg.request_id,
                    )
                } catch (e: Exception) {
                    logHandledFailure("SearchRequest", sender, e)
                    SearchResponse(
                        source_id = msg.source_id,
                        query = msg.query,
                        offset = msg.offset,
                        failure = render(sender, classifyForSender(sender, e)),
                        has_more = false,
                        request_id = msg.request_id,
                    )
                }
                channel.sendToClient(sender, PacketIds.SEARCH_RESPONSE, response.encode())
                logger.debug("SearchResponse → {}: {} results failure='{}'", sender.displayName, response.entries.size, response.failure)
            }
        }

        // QUEUE_REQUEST — send the current user queue to the requesting client
        registry.register(
            PacketIds.QUEUE_REQUEST,
            { QueueRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            if (!hasPermission(sender, PermissionNodes.QUEUE_VIEW)) {
                channel.sendToClient(
                    sender,
                    PacketIds.QUEUE_RESPONSE,
                    QueueResponse(
                        failure = render(sender, PermissionNodes.QUEUE_VIEW.deniedMessage),
                        request_id = msg.request_id,
                    ).encode()
                )
                return@register
            }
            val response = try {
                val snapshot = ServerRuntimeCoordinator.queue.userQueueSnapshot()
                val canBypass = senderHasFilterBypass(sender)
                val canSeeDetail = senderHasFilterManage(sender)
                QueueResponse(
                    tracks = snapshot.map { track ->
                        ProtocolViewMapper.trackToClientProto(track, canBypass, canSeeDetail) { render(sender, it) }
                    },
                    request_id = msg.request_id,
                )
            } catch (e: Exception) {
                logHandledFailure("QueueRequest", sender, e)
                QueueResponse(
                    failure = render(sender, UserFacingErrors.classify(e)),
                    request_id = msg.request_id,
                )
            }
            channel.sendToClient(sender, PacketIds.QUEUE_RESPONSE, response.encode())
            logger.debug("QueueResponse → {}: {} tracks failure='{}'", sender.displayName, response.tracks.size, response.failure)
        }

        // QUEUE_REMOVE_REQUEST — remove a track from the user queue by (source_id, track_id)
        registry.register(
            PacketIds.QUEUE_REMOVE_REQUEST,
            { QueueRemoveRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            val response = try {
                when (
                    ServerRuntimeCoordinator.userActionService.removeQueuedTrack(
                        sourceId = msg.source_id,
                        trackId = msg.track_id,
                        requester = sender,
                    ).result
                ) {
                    QueueRemoveResult.REMOVED -> QueueRemoveResponse(request_id = msg.request_id)
                    QueueRemoveResult.NOT_FOUND ->
                        QueueRemoveResponse(
                            failure = render(sender, LocalizedText.key("error.moemusic.queue.track_not_found")),
                            request_id = msg.request_id,
                        )
                    QueueRemoveResult.FORBIDDEN ->
                        QueueRemoveResponse(
                            failure = render(sender, LocalizedText.key("error.moemusic.queue.remove_forbidden")),
                            request_id = msg.request_id,
                        )
                }
            } catch (e: Exception) {
                logHandledFailure("QueueRemoveRequest", sender, e)
                QueueRemoveResponse(
                    failure = render(sender, UserFacingErrors.classify(e)),
                    request_id = msg.request_id,
                )
            }
            channel.sendToClient(sender, PacketIds.QUEUE_REMOVE_RESPONSE, response.encode())
            logger.debug(
                "QueueRemoveRequest from {} source={} trackId={} failure='{}'",
                sender.displayName,
                msg.source_id,
                msg.track_id,
                response.failure,
            )
        }

        // PLAYBACK_CONTROL_REQUEST — pause/resume/skip/stop/seek via packet (GUI-driven)
        registry.register(
            PacketIds.PLAYBACK_CONTROL_REQUEST,
            { PlaybackControlRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            val response = try {
                val outcome = ServerRuntimeCoordinator.userActionService.controlPlayback(
                    action = msg.action.toApi(),
                    requester = sender,
                    positionMs = msg.position_ms,
                )
                PlaybackControlResponse(
                    failure = outcome.failure?.let { render(sender, it) }.orEmpty(),
                    success = outcome.success?.let { render(sender, it) }.orEmpty(),
                    request_id = msg.request_id,
                )
            } catch (e: Exception) {
                logHandledFailure("PlaybackControlRequest", sender, e)
                PlaybackControlResponse(
                    failure = render(sender, UserFacingErrors.classify(e)),
                    request_id = msg.request_id,
                )
            }
            channel.sendToClient(sender, PacketIds.PLAYBACK_CONTROL_RESPONSE, response.encode())
            logger.debug("PlaybackControlRequest from {}: action={} posMs={}", sender.displayName, msg.action, msg.position_ms)
        }

        registry.register(
            PacketIds.CONTENT_FILTER_ACTION_REQUEST,
            { ContentFilterActionRequest.ADAPTER.decode(it) },
        ) { msg, sender ->
            if (sender == null) return@register
            if (!hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE)) {
                channel.sendToClient(
                    sender,
                    PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
                    ContentFilterActionResponse(
                        failure = render(sender, PermissionNodes.CONTENT_FILTER_MANAGE.deniedMessage),
                        target = msg.target,
                        source_id = msg.source_id,
                        value_id = msg.value_id,
                        request_id = msg.request_id,
                    ).encode()
                )
                return@register
            }

            val sourceId = msg.source_id.trim()
            val valueId = msg.value_id.trim()
            val note = msg.note.trim().ifEmpty { null }
            if (sourceId.isBlank() || valueId.isBlank()) {
                channel.sendToClient(
                    sender,
                    PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
                    ContentFilterActionResponse(
                        failure = render(sender, LocalizedText.key("error.moemusic.track.bad_request")),
                        target = msg.target,
                        source_id = sourceId,
                        value_id = valueId,
                        request_id = msg.request_id,
                    ).encode()
                )
                return@register
            }

            val action = when (msg.action) {
                ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN -> ContentFilterRuleAction.BAN
                ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN -> ContentFilterRuleAction.UNBAN
            }
            val response = try {
                when (msg.target) {
                    ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK -> {
                        val result = ContentFilterRuleEditor.updateTrackRule(sourceId, valueId, action, note)
                        if (result.nowBlocked) {
                            val removal = ServerRuntimeCoordinator.playbackController.removeQueuedTrack(
                                sourceId = sourceId,
                                trackId = valueId,
                                requester = null,
                                bypassOwnership = true,
                            )
                            if (removal == QueueRemoveResult.NOT_FOUND) {
                                ServerRuntimeCoordinator.playbackController.skipIfCurrentTrackMatches(sourceId, valueId)
                            }
                        }
                        ContentFilterActionResponse(
                            success = render(sender, trackFilterMessage(action, result.nowBlocked, valueId, result.changed)),
                            target = msg.target,
                            source_id = sourceId,
                            value_id = valueId,
                            blocked_now = result.nowBlocked,
                            request_id = msg.request_id,
                        )
                    }

                    ContentFilterTargetProto.CONTENT_FILTER_TARGET_ARTIST -> {
                        val result = ContentFilterRuleEditor.updateArtistRules(sourceId, listOf(valueId), action, note)
                        ContentFilterActionResponse(
                            success = render(sender, artistFilterMessage(action, result.nowBlocked, valueId, result.changed)),
                            target = msg.target,
                            source_id = sourceId,
                            value_id = valueId,
                            blocked_now = result.nowBlocked,
                            request_id = msg.request_id,
                        )
                    }
                }
            } catch (e: Exception) {
                logHandledFailure("ContentFilterActionRequest", sender, e)
                ContentFilterActionResponse(
                    failure = render(sender, UserFacingErrors.classify(e)),
                    target = msg.target,
                    source_id = sourceId,
                    value_id = valueId,
                    request_id = msg.request_id,
                )
            }
            channel.sendToClient(sender, PacketIds.CONTENT_FILTER_ACTION_RESPONSE, response.encode())
        }
    }

    private fun hasPermission(sender: MoeMusicUser, permission: PermissionNodes.Node): Boolean {
        return sender.hasPermission(permission.id, permission.defaultLevel())
    }

    private fun render(sender: MoeMusicUser, text: LocalizedText): String =
        Localization.render(sender.locale, text)

    private fun successMessage(track: TrackInfo, result: TrackAddResult): LocalizedText {
        val title = track.title.ifBlank { track.id.ifBlank { "track" } }
        return LocalizedText.key("action.moemusic.track.queued", title)
    }

    private fun selectionPrompt(): LocalizedText =
        LocalizedText.key("action.moemusic.selection.choose_prompt")

    private fun trackFilterMessage(
        action: ContentFilterRuleAction,
        nowBlocked: Boolean,
        label: String,
        changed: Boolean,
    ): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.track_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.track_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key("action.moemusic.filter.track_unbanned", label)
        else -> LocalizedText.key("action.moemusic.filter.track_already_unbanned", label)
    }

    private fun artistFilterMessage(
        action: ContentFilterRuleAction,
        nowBlocked: Boolean,
        label: String,
        changed: Boolean,
    ): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.artist_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.artist_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key("action.moemusic.filter.artist_unbanned", label)
        else -> LocalizedText.key("action.moemusic.filter.artist_already_unbanned", label)
    }

    /**
     * Classify an exception for packet output, applying filter-detail masking.
     *
     * [FilterBlockException] reveals the full rejection reason only to [senderHasFilterManage]
     * users; everyone else receives the generic managed message.
     */
    private fun classifyForSender(sender: MoeMusicUser, e: Exception): LocalizedText =
        if (e is FilterBlockException && !senderHasFilterManage(sender)) e.maskedReason
        else UserFacingErrors.classify(e)

    private fun logHandledFailure(action: String, sender: MoeMusicUser, error: Exception) {
        if (UserFacingErrors.isExpected(error)) {
            logger.debug("{} rejected for {}: {}", action, sender.displayName, error.message)
        } else {
            logger.error("{} failed for {}: {}", action, sender.displayName, error.message, error)
        }
    }

    /**
     * Whether [sender] holds the filter bypass privilege (`moemusic.privilege.bypass.filter`).
     *
     * When true, filter-blocked tracks appear as available to this sender (no `unavailable_reason`
     * is set for filter hits). The submission gate still enforces bypass at enqueue time.
     */
    private fun senderHasFilterBypass(sender: MoeMusicUser): Boolean =
        sender.hasPermission(PermissionNodes.CONTENT_FILTER_BYPASS.id, PermissionNodes.CONTENT_FILTER_BYPASS.defaultLevel())

    /**
     * Whether [sender] holds the content-filter manage privilege.
     *
     * When true the full filter-rule detail (e.g. matched pattern) is shown in search and queue
     * responses. When false, filtered items display a generic "managed by server policy" message.
     */
    private fun senderHasFilterManage(sender: MoeMusicUser): Boolean =
        sender.hasPermission(PermissionNodes.CONTENT_FILTER_MANAGE.id, PermissionNodes.CONTENT_FILTER_MANAGE.defaultLevel())

    private fun ClientStateProto.toParticipation(): UserSessionRegistry.Participation =
        when (this) {
            ClientStateProto.CLIENT_STATE_ACTIVE -> UserSessionRegistry.Participation.ACTIVE
            ClientStateProto.CLIENT_STATE_STANDBY -> UserSessionRegistry.Participation.STANDBY
        }

    private fun PlaybackControlAction.toApi(): PlaybackAction =
        when (this) {
            PlaybackControlAction.PAUSE -> PlaybackAction.PAUSE
            PlaybackControlAction.RESUME -> PlaybackAction.RESUME
            PlaybackControlAction.SKIP -> PlaybackAction.SKIP
            PlaybackControlAction.STOP -> PlaybackAction.STOP
            PlaybackControlAction.SEEK -> PlaybackAction.SEEK
        }

    private fun TrackAddModeProto.toTrackAddMode(): TrackAddMode =
        when (this) {
            TrackAddModeProto.TRACK_ADD_MODE_NORMAL -> TrackAddMode.NORMAL
            TrackAddModeProto.TRACK_ADD_MODE_SKIP_AUTOPLAY -> TrackAddMode.SKIP_AUTOPLAY
            TrackAddModeProto.TRACK_ADD_MODE_PLAY_NOW -> TrackAddMode.PLAY_NOW
        }

    private fun buildSyncResponse(clientSendMonotonic: Long, serverRecvMonotonic: Long): SyncResponse =
        SyncResponse(
            client_send_monotonic = clientSendMonotonic,
            server_recv_monotonic = serverRecvMonotonic,
            server_send_monotonic = System.nanoTime(),
        )
}
