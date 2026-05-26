package org.lolicode.moemusic.api.event

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.IdentifierResolutionOutcome

/** Playback participation state of a connected MoeMusic-capable client. */
public enum class UserParticipationState {
    ACTIVE,
    STANDBY,
}

/**
 * Fired on the server as soon as a player connection is established, before any MoeMusic-specific
 * handshake has necessarily happened.
 *
 * Unlike [OnUserSessionStarted], this event is about the raw Minecraft server connection itself and fires
 * for every player, including users without a MoeMusic-capable client.
 */
public data class OnServerPlayerConnected(
    val user: MoeMusicUser,
)

/**
 * Fired on the server as soon as a player connection is being torn down, regardless of whether
 * the player ever established a MoeMusic-compatible client session.
 *
 * Unlike [OnUserSessionEnded], this event is about the raw Minecraft server connection itself and is
 * suitable for compatibility plugins that must clean up per-player state even for non-native
 * clients.
 */
public data class OnServerPlayerDisconnected(
    val user: MoeMusicUser,
)

/**
 * Fired on the server when a compatible client session is first established for this connection.
 *
 * [state] is the client's initial MoeMusic participation state for the session. A client may join
 * directly in [UserParticipationState.STANDBY] when it is locally disabled or waiting on the
 * single-instance playback lock.
 */
public data class OnUserSessionStarted(
    val user: MoeMusicUser,
    val state: UserParticipationState,
)

/**
 * Fired on the server when a compatible client changes MoeMusic participation without disconnecting.
 *
 * This covers transitions such as `ACTIVE -> STANDBY` (local disable / lock wait) and
 * `STANDBY -> ACTIVE` (re-enabled / lock available again). It does not fire for the initial join
 * or the final disconnect.
 */
public data class OnUserParticipationChanged(
    val user: MoeMusicUser,
    val previousState: UserParticipationState,
    val newState: UserParticipationState,
)

/**
 * Fired on the server when a compatible client disconnects.
 *
 * [state] is the last known MoeMusic participation state before the connection closed.
 */
public data class OnUserSessionEnded(
    val user: MoeMusicUser,
    val state: UserParticipationState,
)

/**
 * Fired on the server when a single-source search request completes.
 *
 * [query] is the normalized request actually executed by core: source id, limit, and offset
 * have already been clamped/resolved to the effective values used for the search call.
 */
public data class OnSearchCompleted(
    val query: SearchQuery,
    val submitter: MoeMusicUser?,
    val sourceId: String,
    val entries: List<SelectionEntry>,
    val total: Int,
    val hasMore: Boolean,
    val failure: LocalizedText? = null,
)

/**
 * Fired on the server after an identifier/share-link resolution attempt is fully classified.
 */
public data class OnIdentifierResolved(
    val identifier: String,
    val submitter: MoeMusicUser?,
    val outcome: IdentifierResolutionOutcome,
)

/**
 * Fired on the server after a track has been authoritatively accepted by the submission pipeline.
 */
public data class OnTrackSubmitted(
    val track: TrackInfo,
    val submitter: MoeMusicUser?,
    val mode: TrackAddMode,
    val result: TrackAddResult,
)

/** Fired on the server after a queued user-submitted track is removed. */
public data class OnQueueTrackRemoved(
    val track: TrackInfo,
    val requester: MoeMusicUser?,
    val bypassOwnership: Boolean,
)

/**
 * Fired on the server when a new track begins playing.
 */
public data class OnPlaybackStarted(
    val track: TrackInfo,
    val playback: PlaybackResource,
    val fromAutoplay: Boolean,
)

/**
 * Fired on the server when a playback start attempt fails after the track was selected.
 */
public data class OnPlaybackStartFailed(
    val track: TrackInfo,
    val fromAutoplay: Boolean,
    val reason: LocalizedText,
)

/** Fired on the server when playback pauses. */
public data class OnPlaybackPaused(
    val track: TrackInfo,
    val positionMs: Long,
    val automatic: Boolean,
)

/** Fired on the server when playback resumes. */
public data class OnPlaybackResumed(
    val track: TrackInfo,
    val positionMs: Long,
    val automatic: Boolean,
)

/** Fired on the server when playback seeks. */
public data class OnPlaybackSeeked(
    val track: TrackInfo,
    val positionMs: Long,
    val wasPlaying: Boolean,
)

/** Fired on the server when playback stops explicitly or due to queue exhaustion. */
public data class OnPlaybackStopped(
    val track: TrackInfo,
    val manual: Boolean,
)

/** Fired on the client when playback starts or an existing server playback materializes locally. */
public data class OnClientPlaybackStarted(
    val track: TrackInfo,
    val playback: PlaybackResource,
    val positionMs: Long,
    /**
     * Historical 1.x compatibility flag.
     *
     * `false` means the server started or advanced to a new track. `true` means this client
     * applied an already-existing playback snapshot, such as initial active join or standby to
     * active catch-up. The name is historical; the protocol no longer has a SyncState packet.
     * This field may be replaced with a clearer start-cause enum in API 2.0.
     */
    val fromSyncState: Boolean,
)

/** Fired on the client when playback pauses. */
public data class OnClientPlaybackPaused(
    val track: TrackInfo,
    val positionMs: Long,
)

/** Fired on the client when playback resumes. */
public data class OnClientPlaybackResumed(
    val track: TrackInfo,
    val positionMs: Long,
)

/** Fired on the client when playback seeks while remaining on the same track. */
public data class OnClientPlaybackSeeked(
    val track: TrackInfo,
    val positionMs: Long,
)

/** Fired on the client when local playback stops for the current track. */
public data class OnClientPlaybackStopped(
    val track: TrackInfo,
)

/**
 * Fired on the client when the current Minecraft connection is established and MoeMusic starts
 * its local per-connection session/handshake state.
 *
 * This is a connection-scoped client event. It is suitable for plugins that need to initialize
 * per-server local state without depending on loader-specific connection hooks.
 */
public data object OnClientConnected

/**
 * Fired on the client when the current Minecraft connection disconnects and MoeMusic tears down
 * the local per-connection session state.
 *
 * This is a connection-scoped client event. Use [org.lolicode.moemusic.api.plugin.Plugin.onClientRuntimeUnload]
 * for final JVM/client shutdown cleanup instead.
 */
public data object OnClientDisconnected

/** Fired when the shared content-filter rules are re-applied at runtime after plugins can subscribe. */
public data class OnContentFilterRulesApplied(
    val rules: ContentFilterRules,
)

/** Fired when an exact shared track rule is changed through the runtime editor. */
public data class OnContentFilterTrackRuleChanged(
    val sourceId: String,
    val trackId: String,
    val action: ContentFilterRuleAction,
    val note: String? = null,
    val changed: Boolean,
    val nowBlocked: Boolean,
    val affectedCount: Int,
)

/** Fired when one or more exact shared artist rules are changed through the runtime editor. */
public data class OnContentFilterArtistRuleChanged(
    val sourceId: String,
    val artistIds: List<String>,
    val action: ContentFilterRuleAction,
    val note: String? = null,
    val changed: Boolean,
    val nowBlocked: Boolean,
    val affectedCount: Int,
)
