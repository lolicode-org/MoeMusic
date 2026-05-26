package org.lolicode.moemusic.core.playback

import kotlinx.coroutines.*
import org.lolicode.moemusic.api.AlreadyQueuedException
import org.lolicode.moemusic.api.service.IPlaybackController
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.service.QueueRemoveResult
import org.lolicode.moemusic.api.TrackUnavailableException
import org.lolicode.moemusic.api.UserFacingException
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.event.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.core.error.UserFacingErrors
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.*
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.media.MediaUrlAccessPolicy
import org.lolicode.moemusic.core.media.MediaPolicyProfiles
import org.lolicode.moemusic.core.media.MediaUrlPolicy
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Server-side playback state machine.
 *
 * All methods are intended to be called from the server thread (or synchronized externally).
 * Broadcasts proto-encoded packets via [channel] and fires `:api` events via the injected
 * [eventBus] (defaulting to the shared [CoreEvents.bus] singleton).
 *
 * @param channel    Network abstraction for broadcasting to clients.
 * @param queue      Source of the next track to play.
 * @param eventBus   Event bus; default = [CoreEvents.bus].
 * @param onUserQueueTrackSkipped Optional platform callback for localized user-queue failure notices.
 * @param onTrackSubmitted Optional platform callback fired after a user track is accepted with its final add result.
 */
class ServerPlaybackController(
    val channel: NetworkChannel,
    val queue: TrackQueue,
    val eventBus: EventBus = CoreEvents.bus,
    private val onUserQueueTrackSkipped: ((TrackInfo, LocalizedText?) -> Unit)? = null,
    private val onTrackSubmitted: ((TrackInfo, TrackAddResult) -> Unit)? = null,
) : IPlaybackController {
    private val logger = LoggerFactory.getLogger(ServerPlaybackController::class.java)

    /** Coroutine scope for the auto-advance timer. SupervisorJob so a cancelled job doesn't kill others. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Currently running auto-advance job, cancelled/rebuilt on track state changes. */
    private var advanceJob: Job? = null

    /** True when playback was paused automatically because all clients disconnected. */
    @Volatile
    var isAutoPaused: Boolean = false
        private set

    /** Current server-side playback snapshot. Null when nothing is loaded. */
    @Volatile
    override var currentContext: TrackContext? = null
        private set

    override fun userQueueSnapshot(): List<TrackInfo> =
        queue.userQueueSnapshot()

    @Volatile
    private var autoStartPolicy: AutoStartPolicy = AutoStartPolicy.ALLOWED

    @Volatile
    private var startGeneration: Long = 0L

    @Volatile
    private var currentTrackSource: TrackQueue.NextTrack.Source? = null

    /** Earliest monotonic time at which the current playback resource may be re-resolved again. */
    @Volatile
    private var currentPlaybackRefreshNotBeforeNanos: Long = 0L

    internal var playbackRefreshCooldownNanos: Long = DEFAULT_PLAYBACK_REFRESH_COOLDOWN_MS * 1_000_000L
    internal var playbackRefreshFailureBackoffNanos: Long = DEFAULT_PLAYBACK_REFRESH_FAILURE_BACKOFF_MS * 1_000_000L

    private val playbackRefreshLock = Any()

    /**
     * Monotonic id for the currently loaded track session.
     *
     * Increments when a new track starts and stays stable across pause/resume/seek, so
     * platform-side features can safely key temporary per-track state to it.
     */
    val currentTrackSessionId: Long
        get() = if (currentContext == null) 0L else startGeneration

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enqueue [track] in the user queue and immediately start playback if nothing is
     * currently playing.
     *
     * This is the single canonical entry-point for both command-driven and packet-driven
     * track requests — it replaces the duplicated enqueue+conditional-play logic that was
     * previously scattered across the packet handlers and command implementations.
     *
     * Playback resource resolution via [org.lolicode.moemusic.api.MusicSource.resolve] is performed just-in-time in a
     * coroutine so that URLs and required headers are always fresh at the moment playback begins.
     *
     * Rejects the submission when the same logical track is already pending in the user queue
     * or is the current playing track.
     */
    override fun enqueueAndPlay(track: TrackInfo) {
        enqueueAndPlay(track, requesterId = null)
    }

    fun enqueueAndPlay(track: TrackInfo, requesterId: UUID?) {
        ensureTrackAvailable(track)
        if (isCurrentTrack(track)) {
            throw AlreadyQueuedException()
        }
        if (!queue.enqueueUserIfAbsent(track, requesterId)) {
            throw AlreadyQueuedException()
        }
        startNextIfStopped()
    }

    suspend fun submitTrack(track: TrackInfo, requesterId: UUID?, mode: TrackAddMode): TrackAddResult {
        ensureTrackAvailable(track)

        val result = when (mode) {
            TrackAddMode.NORMAL -> {
                enqueueAndPlay(track, requesterId)
                TrackAddResult.QUEUED
            }

            TrackAddMode.SKIP_AUTOPLAY -> {
                enqueueAndPlay(track, requesterId)
                if (currentTrackSource == TrackQueue.NextTrack.Source.AUTOPLAY) {
                    skip()
                    TrackAddResult.INTERRUPTING_AUTOPLAY
                } else {
                    TrackAddResult.QUEUED
                }
            }

            TrackAddMode.PLAY_NOW -> playNow(track)
        }
        onTrackSubmitted?.invoke(track, result)
        return result
    }

    fun willAutoStartIfQueued(): Boolean =
        currentContext == null && autoStartPolicy == AutoStartPolicy.ALLOWED

    /**
     * Start the next queued track only when nothing is currently loaded.
     *
     * Used both by user-submitted enqueue flows and by the Autoplay bootstrap path:
     * the initial Autoplay fetch is asynchronous, so once tracks become available the Autoplay manager
     * needs a safe way to ask the controller to kick playback off.
     */
    fun startNextIfStopped() {
        if (!willAutoStartIfQueued()) return
        val generation = startGeneration
        scope.launch {
            startNextPlayableTrack(
                stopWhenExhausted = false,
                generation = generation,
                requireAutoStartPermission = true,
            )
        }
    }

    /**
     * Start playing [track] with [playback].
     */
    override fun play(track: TrackInfo, playback: PlaybackResource) {
        playInternal(track, playback, fromAutoplay = false)
    }

    override fun removeQueuedTrack(
        sourceId: String,
        trackId: String,
        requester: MoeMusicUser?,
        bypassOwnership: Boolean,
    ): QueueRemoveResult {
        val details = queue.removeUserTrackDetailed(
            sourceId = sourceId,
            trackId = trackId,
            requesterId = requester?.id,
            bypassOwnership = bypassOwnership,
        )
        if (details.result == TrackQueue.UserQueueRemovalResult.REMOVED) {
            eventBus.fire(
                OnQueueTrackRemoved(
                    track = requireNotNull(details.removedTrack),
                    requester = requester,
                    bypassOwnership = bypassOwnership,
                )
            )
        }
        return when (details.result) {
            TrackQueue.UserQueueRemovalResult.REMOVED -> QueueRemoveResult.REMOVED
            TrackQueue.UserQueueRemovalResult.NOT_FOUND -> QueueRemoveResult.NOT_FOUND
            TrackQueue.UserQueueRemovalResult.FORBIDDEN -> QueueRemoveResult.FORBIDDEN
        }
    }

    private fun playInternal(
        track: TrackInfo,
        playback: PlaybackResource,
        fromAutoplay: Boolean,
    ): PlayInternalResult {
        if (!track.isAvailable) {
            val reason = track.unavailabilityMessage()
            logger.warn("Refusing to play unavailable track '{}': {}", track.title, reason)
            return playFailed(track, fromAutoplay, reason)
        }
        cancelAdvanceJob()
        isAutoPaused = false  // explicit play clears any auto-pause state
        autoStartPolicy = AutoStartPolicy.ALLOWED

        val finalTrack = sanitizeTrackForClient(track)
        if (playback.url.isBlank()) {
            logger.error("Refusing to play '{}' because the final playback resource URL is blank.", finalTrack.title)
            return playFailed(finalTrack, fromAutoplay, LocalizedText.key("error.moemusic.internal"))
        }
        when (val playbackVerdict = MediaUrlPolicy.evaluate(playback.url, serverOutboundPolicy())) {
            MediaUrlPolicyResult.Allow -> Unit
            is MediaUrlPolicyResult.Reject -> {
                logger.warn(
                    "Refusing to play '{}' because server media policy blocked {}",
                    finalTrack.title,
                    playback.url,
                )
                return playFailed(finalTrack, fromAutoplay, playbackVerdict.reason)
            }
        }
        startGeneration += 1
        val serverStartMonotonic = System.nanoTime() + BUFFER_NS
        val msg = PlaybackSnapshotPush(
            snapshot = finalTrack.playbackSnapshot(
                playback = playback,
                state = PlaybackState.Playing(0L),
                positionMs = 0L,
                anchorServerMonotonic = serverStartMonotonic,
            ),
            reason = PlaybackSnapshotPushReason.PLAYBACK_SNAPSHOT_PUSH_REASON_NEW_TRACK,
        )
        channel.sendToAllClients(PacketIds.PLAYBACK_SNAPSHOT_PUSH, msg.encode())
        currentContext = TrackContext(
            track = finalTrack,
            playback = playback,
            state = PlaybackState.Playing(0L),
            serverStartMonotonic = serverStartMonotonic,
            serverResumeMonotonic = serverStartMonotonic,
        )
        markCurrentPlaybackFresh()
        eventBus.fire(
            OnPlaybackStarted(
                track = finalTrack,
                playback = playback,
                fromAutoplay = fromAutoplay,
            )
        )
        scheduleAdvance(finalTrack, positionMs = 0L, startDelayMs = BUFFER_NS / 1_000_000L)
        return PlayInternalResult.Started
    }

    private fun playFailed(
        track: TrackInfo,
        fromAutoplay: Boolean,
        reason: LocalizedText,
    ): PlayInternalResult {
        eventBus.fire(
            OnPlaybackStartFailed(
                track = track,
                fromAutoplay = fromAutoplay,
                reason = reason,
            )
        )
        return PlayInternalResult.Failed(reason)
    }

    /**
     * Pause playback. Computes current position from elapsed monotonic time and
     * broadcasts a [StateUpdate] with [PlaybackStateProto.PAUSED].
     */
    override fun pause() {
        pause(automatic = false)
    }

    private fun pause(automatic: Boolean) {
        val ctx = currentContext ?: return
        if (ctx.state !is PlaybackState.Playing) return
        val positionMs = ((System.nanoTime() - ctx.serverStartMonotonic) / 1_000_000L).coerceAtLeast(0L)
        cancelAdvanceJob()
        if (!automatic) {
            isAutoPaused = false
        }
        val msg = StateUpdate(
            state = PlaybackStateProto.PAUSED,
            position_ms = positionMs,
            position_anchor_server_monotonic = 0L,
        )
        channel.sendToAllClients(PacketIds.STATE_UPDATE, msg.encode())
        currentContext = ctx.copy(state = PlaybackState.Paused(positionMs))
        eventBus.fire(
            OnPlaybackPaused(
                track = ctx.track,
                positionMs = positionMs,
                automatic = automatic,
            )
        )
    }

    /**
     * Automatically pause playback when all tracked clients have disconnected.
     *
     * The same pause path is used as a manual pause, so the auto-advance timer is also
     * cancelled here and rebuilt only when playback resumes.
     */
    fun autoPause() {
        val ctx = currentContext ?: return
        if (ctx.state !is PlaybackState.Playing || isAutoPaused) return
        pause(automatic = true)
        isAutoPaused = true
        logger.debug("ServerPlaybackController: auto-paused (no clients).")
    }

    /**
     * Resume playback after an [autoPause]. No-op if not currently auto-paused.
     */
    fun autoResume() {
        if (!isAutoPaused) return
        isAutoPaused = false
        resume(automatic = true)
        logger.debug("ServerPlaybackController: auto-resumed.")
    }

    /**
     * Resume from pause. Computes a fresh position anchor so clients can land at the correct position,
     * then broadcasts [StateUpdate].
     */
    override fun resume() {
        resume(automatic = false)
    }

    private fun resume(automatic: Boolean) {
        val ctx = currentContext
        if (ctx == null) {
            allowAutoStart()
            startNextIfStopped()
            return
        }

        val refreshReadyContext = refreshCurrentPlaybackIfDue("resume") ?: return
        val pausePos = (refreshReadyContext.state as? PlaybackState.Paused)?.positionMs ?: return
        autoStartPolicy = AutoStartPolicy.ALLOWED
        val anchorServerMonotonic = System.nanoTime()
        val newStart = anchorServerMonotonic - pausePos * 1_000_000L
        val msg = StateUpdate(
            state = PlaybackStateProto.PLAYING,
            position_ms = pausePos,
            position_anchor_server_monotonic = anchorServerMonotonic,
            playback = refreshReadyContext.playback.toProto(),
        )
        channel.sendToAllClients(PacketIds.STATE_UPDATE, msg.encode())
        currentContext = refreshReadyContext.copy(
            state = PlaybackState.Playing(pausePos),
            serverStartMonotonic = newStart,
            serverResumeMonotonic = anchorServerMonotonic,
        )
        scheduleAdvance(refreshReadyContext.track, positionMs = pausePos)
        eventBus.fire(
            OnPlaybackResumed(
                track = refreshReadyContext.track,
                positionMs = pausePos,
                automatic = automatic,
            )
        )
    }

    /**
     * Seek to [positionMs]. Works whether playing or paused.
     * Broadcasts [StateUpdate] with an immediate position anchor when currently playing.
     */
    override fun seek(positionMs: Long) {
        val initialContext = currentContext ?: return
        val ctx = if (initialContext.state is PlaybackState.Playing) {
            refreshCurrentPlaybackIfDue("seek") ?: return
        } else {
            currentContext ?: return
        }
        val normalizedPositionMs = normalizePosition(positionMs, ctx.track.durationMs)
        val isPlaying = ctx.state is PlaybackState.Playing
        val anchorServerMonotonic = if (isPlaying) System.nanoTime() else 0L
        val newStart = if (isPlaying) {
            anchorServerMonotonic - normalizedPositionMs * 1_000_000L
        } else {
            ctx.serverStartMonotonic
        }
        val protoState = if (isPlaying) PlaybackStateProto.PLAYING else PlaybackStateProto.PAUSED
        val msg = StateUpdate(
            state = protoState,
            position_ms = normalizedPositionMs,
            position_anchor_server_monotonic = anchorServerMonotonic,
            playback = if (isPlaying) ctx.playback.toProto() else null,
        )
        channel.sendToAllClients(PacketIds.STATE_UPDATE, msg.encode())
        val newState =
            if (isPlaying) PlaybackState.Playing(normalizedPositionMs) else PlaybackState.Paused(normalizedPositionMs)
        currentContext = ctx.copy(
            state = newState,
            serverStartMonotonic = newStart,
            serverResumeMonotonic = if (isPlaying) anchorServerMonotonic else ctx.serverResumeMonotonic,
        )
        if (isPlaying) {
            scheduleAdvance(ctx.track, positionMs = normalizedPositionMs)
        } else {
            cancelAdvanceJob()
        }
        eventBus.fire(
            OnPlaybackSeeked(
                track = ctx.track,
                positionMs = normalizedPositionMs,
                wasPlaying = isPlaying,
            )
        )
    }

    /**
     * Skip the current track. Resolves the URL of the next track just-in-time then calls [play].
     * If the queue is empty, calls [stop] instead.
     */
    override fun skip() {
        val generation = startGeneration
        scope.launch {
            startNextPlayableTrack(
                stopWhenExhausted = true,
                generation = generation,
                requireAutoStartPermission = false,
            )
        }
    }

    /**
     * Stop playback. Broadcasts [StateUpdate] with [PlaybackStateProto.STOPPED] and clears state.
     */
    override fun stop() {
        stop(manual = true)
    }

    /**
     * Build a [PlaybackSnapshot] for a newly active or late-joining client, reflecting the current playback position.
     * Returns null if nothing is playing.
     */
    fun buildPlaybackSnapshot(): PlaybackSnapshot? =
        refreshCurrentPlaybackIfDue("playback_snapshot")?.toPlaybackSnapshot()

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Resolve [track] to a directly playable client resource.
     *
     * All playable tracks must have a [TrackInfo.sourceId] so the correct
     * [org.lolicode.moemusic.api.MusicSource.resolve]
     * implementation is dispatched. The source receives the full [TrackInfo]; it uses [TrackInfo.id]
     * to locate and sign the audio stream.
     */
    private suspend fun resolveTrackForPlayback(
        track: TrackInfo,
        refreshMetadata: Boolean = true,
    ): UserResult<ResolvedPlayback> {
        if (!track.isAvailable) {
            logger.info(
                "Refusing to resolve unavailable track '{}': {}",
                track.title,
                track.unavailabilityMessage().debugString()
            )
            return UserResult.Error(track.unavailabilityMessage())
        }
        val sourceId = track.sourceId
        if (sourceId == null) {
            logger.error(
                "Refusing to play '{}' because it has no sourceId; all queued tracks must be associated with a source.",
                track.title
            )
            return UserResult.Error(LocalizedText.key("error.moemusic.internal"))
        }

        val source = PluginManager.musicSourceSnapshot().firstOrNull { it.id == sourceId }
        if (source == null) {
            logger.error(
                "Refusing to play '{}' because source '{}' is not registered.",
                track.title, sourceId,
            )
            return UserResult.Error(LocalizedText.key("error.moemusic.internal"))
        }

        val resolvedPlayback = try {
            source.resolve(track)
        } catch (e: Exception) {
            logger.error(
                "Failed to resolve playback resource for '{}' (source='{}'): {}",
                track.title,
                sourceId,
                e.message,
                e
            )
            return UserResult.Error(UserFacingErrors.classify(e))
        }

        if (resolvedPlayback.url.isBlank()) {
            logger.error(
                "Refusing to play '{}' because source '{}' returned a blank playback URL.",
                track.title,
                sourceId
            )
            return UserResult.Error(LocalizedText.key("error.moemusic.internal"))
        }

        val metadataTrack = if (refreshMetadata && !track.lyricsFetched && track.id.isNotBlank()) {
            when (val metadata = source.getTrackInfo(track.id)) {
                is UserResult.Success -> metadata.value?.let(track::mergePreservingRuntimeMetadata) ?: track.copy(
                    lyricsFetched = true
                )

                is UserResult.Error -> {
                    logger.warn(
                        "Ignoring metadata refresh failure for '{}' (source='{}'): {}",
                        track.title,
                        sourceId,
                        metadata.message.debugString(),
                    )
                    track.copy(lyricsFetched = true)
                }
            }
        } else {
            track
        }

        return UserResult.Success(ResolvedPlayback(metadataTrack, resolvedPlayback))
    }

    private suspend fun playNow(track: TrackInfo): TrackAddResult {
        queue.removeMatchingUserTrack(track)
        val resolvedTrack = when (val result = resolveTrackForPlayback(track)) {
            is UserResult.Success -> result.value
            is UserResult.Error -> {
                playFailed(track, fromAutoplay = false, reason = result.message)
                throw UserFacingException(result.message)
            }
        }
        when (val playResult = playInternal(resolvedTrack.track, resolvedTrack.playback, fromAutoplay = false)) {
            PlayInternalResult.Started -> Unit
            is PlayInternalResult.Failed -> throw UserFacingException(playResult.reason)
        }
        currentTrackSource = null
        return TrackAddResult.PLAYING_NOW
    }

    private suspend fun startNextPlayableTrack(
        stopWhenExhausted: Boolean,
        generation: Long,
        requireAutoStartPermission: Boolean,
    ) {
        val startingFromStopped = currentContext == null
        repeat(MAX_START_ATTEMPTS) {
            if (!canContinueStart(generation, requireAutoStartPermission, startingFromStopped)) return

            val next = queue.nextTrack()
            if (next == null) {
                if (stopWhenExhausted) stop(manual = false)
                return
            }

            val resolvedTrack = when (val result = resolveTrackForPlayback(next.track)) {
                is UserResult.Success -> result.value
                is UserResult.Error -> {
                    logger.warn(
                        "Skipping unplayable track '{}' while selecting the next track: {}",
                        next.track.title,
                        result.message.debugString(),
                    )
                    playFailed(
                        track = next.track,
                        fromAutoplay = next.source == TrackQueue.NextTrack.Source.AUTOPLAY,
                        reason = result.message,
                    )
                    notifyUserQueueTrackSkipped(next, result.message)
                    return@repeat
                }
            }

            if (!canContinueStart(generation, requireAutoStartPermission, startingFromStopped)) {
                requeueIfUserTrack(next)
                return
            }

            currentTrackSource = next.source
            when (
                val playResult = playInternal(
                    resolvedTrack.track,
                    resolvedTrack.playback,
                    fromAutoplay = next.source == TrackQueue.NextTrack.Source.AUTOPLAY,
                )
            ) {
                PlayInternalResult.Started -> return
                is PlayInternalResult.Failed -> {
                    currentTrackSource = null
                    notifyUserQueueTrackSkipped(next, playResult.reason)
                }
            }
        }

        logger.warn("Failed to start a playable track after {} attempt(s).", MAX_START_ATTEMPTS)
        if (stopWhenExhausted) stop(manual = false)
    }

    private fun ensureTrackAvailable(track: TrackInfo) {
        if (!track.isAvailable) {
            throw TrackUnavailableException(track.unavailabilityMessage())
        }
    }

    /**
     * Skip the current track only when its stable identity matches [sourceId] and [trackId].
     *
     * Used by exact-track moderation flows after queue removal has already been attempted.
     */
    fun skipIfCurrentTrackMatches(sourceId: String, trackId: String): Boolean {
        val current = currentContext?.track ?: return false
        if (current.sourceId != sourceId || current.id != trackId) return false
        skip()
        return true
    }

    private fun stop(manual: Boolean) {
        val stoppedTrack = currentContext?.track ?: return
        cancelAdvanceJob()
        isAutoPaused = false
        if (manual) {
            autoStartPolicy = AutoStartPolicy.BLOCKED_BY_MANUAL_STOP
            startGeneration += 1
        } else {
            autoStartPolicy = AutoStartPolicy.ALLOWED
        }
        val msg = StateUpdate(
            state = PlaybackStateProto.STOPPED,
            position_ms = 0L,
            position_anchor_server_monotonic = 0L,
        )
        channel.sendToAllClients(PacketIds.STATE_UPDATE, msg.encode())
        currentContext = null
        currentTrackSource = null
        clearCurrentPlaybackRefreshState()
        eventBus.fire(OnPlaybackStopped(stoppedTrack, manual))
    }

    private fun allowAutoStart() {
        autoStartPolicy = AutoStartPolicy.ALLOWED
        startGeneration += 1
    }

    private fun canContinueStart(
        generation: Long,
        requireAutoStartPermission: Boolean,
        startingFromStopped: Boolean,
    ): Boolean =
        startGeneration == generation &&
                (!requireAutoStartPermission || autoStartPolicy == AutoStartPolicy.ALLOWED) &&
                (!startingFromStopped || currentContext == null)

    private fun requeueIfUserTrack(next: TrackQueue.NextTrack) {
        if (next.source != TrackQueue.NextTrack.Source.USER_QUEUE) return
        if (queue.containsUserTrack(next.track)) return
        queue.requeueUserFront(next.track, next.enqueuedBy)
    }

    private fun isCurrentTrack(track: TrackInfo): Boolean =
        currentContext?.track?.matchesQueueIdentity(track) == true

    private fun notifyUserQueueTrackSkipped(next: TrackQueue.NextTrack, reason: LocalizedText?) {
        if (next.source != TrackQueue.NextTrack.Source.USER_QUEUE) return
        onUserQueueTrackSkipped?.invoke(next.track, reason)
    }

    private fun scheduleAdvance(track: TrackInfo, positionMs: Long, startDelayMs: Long = 0L) {
        cancelAdvanceJob()
        if (track.durationMs <= 0L) return

        val remainingMs = (track.durationMs - positionMs).coerceAtLeast(0L) + startDelayMs
        advanceJob = scope.launch {
            delay(remainingMs.milliseconds)
            logger.debug(
                "Auto-advance: '{}' ended (remaining={}ms, startDelay={}ms), skipping to next.",
                track.title,
                remainingMs,
                startDelayMs,
            )
            skip()
        }
    }

    private fun cancelAdvanceJob() {
        advanceJob?.cancel()
        advanceJob = null
    }

    private fun refreshCurrentPlaybackIfDue(reason: String): TrackContext? {
        val ctx = currentContext ?: return null
        if (!canRefreshCurrentPlayback(ctx.track)) return ctx
        if (System.nanoTime() < currentPlaybackRefreshNotBeforeNanos) return ctx

        synchronized(playbackRefreshLock) {
            val latest = currentContext ?: return null
            if (!canRefreshCurrentPlayback(latest.track)) return latest

            val now = System.nanoTime()
            if (now < currentPlaybackRefreshNotBeforeNanos) return latest

            val trackSessionId = currentTrackSessionId
            val refreshed = runBlocking {
                resolveTrackForPlayback(latest.track, refreshMetadata = false)
            }
            val current = currentContext ?: return null
            if (currentTrackSessionId != trackSessionId) {
                return current
            }

            return when (refreshed) {
                is UserResult.Success -> {
                    val playback = refreshed.value.playback
                    when (MediaUrlPolicy.evaluate(playback.url, serverOutboundPolicy())) {
                        MediaUrlPolicyResult.Allow -> {
                            val updated = current.copy(playback = playback)
                            currentContext = updated
                            markCurrentPlaybackFresh()
                            logger.debug("Refreshed playback resource for '{}' due to {}.", current.track.title, reason)
                            updated
                        }

                        is MediaUrlPolicyResult.Reject -> {
                            markCurrentPlaybackRefreshFailed()
                            logger.warn(
                                "Keeping existing playback for '{}' because refreshed resource was blocked by server media policy: {}",
                                current.track.title,
                                playback.url,
                            )
                            current
                        }
                    }
                }

                is UserResult.Error -> {
                    markCurrentPlaybackRefreshFailed()
                    logger.debug(
                        "Keeping existing playback for '{}' after {} refresh failed: {}",
                        current.track.title,
                        reason,
                        refreshed.message.debugString(),
                    )
                    current
                }
            }
        }
    }

    private fun canRefreshCurrentPlayback(track: TrackInfo): Boolean =
        track.sourceId != null && track.id.isNotBlank()

    private fun markCurrentPlaybackFresh(now: Long = System.nanoTime()) {
        currentPlaybackRefreshNotBeforeNanos = now + playbackRefreshCooldownNanos
    }

    private fun markCurrentPlaybackRefreshFailed(now: Long = System.nanoTime()) {
        currentPlaybackRefreshNotBeforeNanos = now + playbackRefreshFailureBackoffNanos
    }

    private fun clearCurrentPlaybackRefreshState() {
        currentPlaybackRefreshNotBeforeNanos = 0L
    }

    private fun normalizePosition(positionMs: Long, durationMs: Long): Long {
        val nonNegative = positionMs.coerceAtLeast(0L)
        if (durationMs <= 0L) return nonNegative
        return nonNegative.coerceAtMost(durationMs)
    }

    private fun TrackContext.toPlaybackSnapshot(): PlaybackSnapshot? {
        val now = System.nanoTime()
        return when (val playbackState = state) {
            is PlaybackState.Playing -> track.playbackSnapshot(
                playback = playback,
                state = playbackState,
                positionMs = normalizePosition(
                    (now - serverStartMonotonic) / 1_000_000L,
                    track.durationMs,
                ),
                anchorServerMonotonic = now,
            )

            is PlaybackState.Paused -> track.playbackSnapshot(
                playback = playback,
                state = playbackState,
                positionMs = normalizePosition(playbackState.positionMs, track.durationMs),
                anchorServerMonotonic = 0L,
            )

            PlaybackState.Stopped -> null
        }
    }

    private fun TrackInfo.playbackSnapshot(
        playback: PlaybackResource,
        state: PlaybackState,
        positionMs: Long,
        anchorServerMonotonic: Long,
    ): PlaybackSnapshot = PlaybackSnapshot(
        track = toProto(),
        playback = playback.toProto(),
        state = state.toProto(),
        position_ms = normalizePosition(positionMs, durationMs),
        position_anchor_server_monotonic = anchorServerMonotonic,
        lyric_lrc = lyricLrc.orEmpty(),
        secondary_lyric_lrc = secondaryLyricLrc.orEmpty(),
    )

    private fun PlaybackState.toProto(): PlaybackStateProto = when (this) {
        is PlaybackState.Playing -> PlaybackStateProto.PLAYING
        is PlaybackState.Paused -> PlaybackStateProto.PAUSED
        PlaybackState.Stopped -> PlaybackStateProto.STOPPED
    }

    private fun sanitizeTrackForClient(track: TrackInfo): TrackInfo =
        track.copy(coverUrl = sanitizeCoverUrl(track.coverUrl))

    private fun sanitizeCoverUrl(coverUrl: String?): String? {
        val url = coverUrl?.takeIf { it.isNotBlank() } ?: return null
        return when (MediaUrlPolicy.evaluate(url, serverOutboundPolicy())) {
            MediaUrlPolicyResult.Allow -> url
            is MediaUrlPolicyResult.Reject -> {
                logger.debug("Dropping cover URL blocked by server media policy: {}", url)
                null
            }
        }
    }

    private fun serverOutboundPolicy(): MediaUrlAccessPolicy =
        MediaPolicyProfiles.sharedMediaFirewall()

    private enum class AutoStartPolicy {
        ALLOWED,
        BLOCKED_BY_MANUAL_STOP,
    }

    private sealed interface PlayInternalResult {
        data object Started : PlayInternalResult

        data class Failed(
            val reason: LocalizedText,
        ) : PlayInternalResult
    }

    private data class ResolvedPlayback(
        val track: TrackInfo,
        val playback: PlaybackResource,
    )

    private companion object {
        /** Buffer time in nanoseconds before playback starts. Gives clients time to pre-buffer. */
        const val BUFFER_NS = 2_000_000_000L // 2 seconds
        const val MAX_START_ATTEMPTS = 16
        const val DEFAULT_PLAYBACK_REFRESH_COOLDOWN_MS = 60_000L
        const val DEFAULT_PLAYBACK_REFRESH_FAILURE_BACKOFF_MS = 15_000L
    }
}

/** Convert API [TrackInfo] to proto [TrackInfoProto]. */
fun TrackInfo.toProto(): TrackInfoProto = TrackInfoProto(
    id = id,
    title = title,
    duration_ms = durationMs,
    cover_url = coverUrl.orEmpty(),
    source_id = sourceId.orEmpty(),
    album = album.orEmpty(),
    submitted_by_player_name = submittedByUserName.orEmpty(),
    artists = artists.map(ArtistInfo::toProto),
    // the server will block the track if it's unavailable, so toProto() will never be called for them
    // the only case is a response to a search request, which is always sent to a single client
    // In this case, localization is done by core network response mapping for the recipient.
    // So just omit it here for safe
    unavailable_reason = "",
)

/** Convert API [SelectionEntry] to proto [SelectionEntryProto]. */
fun SelectionEntry.toProto(): SelectionEntryProto = SelectionEntryProto(
    selection_id = selectionId,
    title = title,
    duration_ms = durationMs,
    source_id = sourceId.orEmpty(),
    album = album.orEmpty(),
    unavailable_reason = "",
    kind = kind.toProto(),
    artists = artists.map(ArtistInfo::toProto),
)

private fun ArtistInfo.toProto(): ArtistInfoProto = ArtistInfoProto(
    id = id,
    name = name,
)

/** Convert API [PlaybackResource] to proto [PlaybackResourceProto]. */
fun PlaybackResource.toProto(): PlaybackResourceProto = PlaybackResourceProto(
    url = url,
    headers = headers,
)

/** Convert proto [TrackInfoProto] to API [TrackInfo]. */
fun TrackInfoProto.toApi(): TrackInfo = TrackInfo(
    id = id,
    title = title,
    artists = artists.map(ArtistInfoProto::toApi),
    durationMs = duration_ms,
    coverUrl = cover_url.ifEmpty { null },
    sourceId = source_id.ifEmpty { null },
    album = album.ifEmpty { null },
    submittedByUserName = submitted_by_player_name.ifEmpty { null },
    unavailableReason = unavailable_reason.ifEmpty { null }?.let(LocalizedText::plain),
    lyricsFetched = false,
)

/** Convert proto [SelectionEntryProto] to API [SelectionEntry]. */
fun SelectionEntryProto.toApi(): SelectionEntry = SelectionEntry(
    selectionId = selection_id,
    title = title,
    artists = artists.map(ArtistInfoProto::toApi),
    durationMs = duration_ms,
    sourceId = source_id.ifEmpty { null },
    album = album.ifEmpty { null },
    unavailableReason = unavailable_reason.ifEmpty { null }?.let(LocalizedText::plain),
    kind = kind.toApi(),
)

private fun ArtistInfoProto.toApi(): ArtistInfo = ArtistInfo(
    id = id,
    name = name,
)

/** Convert proto [PlaybackResourceProto] to API [PlaybackResource]. */
fun PlaybackResourceProto.toApi(): PlaybackResource = PlaybackResource(
    url = url,
    headers = headers,
)

private fun SelectionEntryKind.toProto(): SelectionEntryKindProto = when (this) {
    SelectionEntryKind.TRACK -> SelectionEntryKindProto.SELECTION_ENTRY_KIND_TRACK
    SelectionEntryKind.CONTAINER -> SelectionEntryKindProto.SELECTION_ENTRY_KIND_CONTAINER
    SelectionEntryKind.UNKNOWN -> SelectionEntryKindProto.SELECTION_ENTRY_KIND_UNKNOWN
}

private fun SelectionEntryKindProto.toApi(): SelectionEntryKind = when (this) {
    SelectionEntryKindProto.SELECTION_ENTRY_KIND_TRACK -> SelectionEntryKind.TRACK
    SelectionEntryKindProto.SELECTION_ENTRY_KIND_CONTAINER -> SelectionEntryKind.CONTAINER
    SelectionEntryKindProto.SELECTION_ENTRY_KIND_UNKNOWN -> SelectionEntryKind.UNKNOWN
}
