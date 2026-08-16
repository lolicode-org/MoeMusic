package org.lolicode.moemusic.api.event

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.IdentifierResolutionOutcome
import java.util.UUID

/**
 * Playback participation state of a connected MoeMusic-capable client.
 *
 * This is an **open** value set, not an enum: future API versions may add participation states, so
 * `when` over a [UserParticipationState] cannot be exhaustive and must always include an `else`
 * branch.
 */
@JvmInline
public value class UserParticipationState private constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val ACTIVE: UserParticipationState = UserParticipationState("ACTIVE")
        public val STANDBY: UserParticipationState = UserParticipationState("STANDBY")

        /** Values known to this build. New values may appear at runtime; always handle `else`. */
        public val entries: List<UserParticipationState> = listOf(ACTIVE, STANDBY)

        /** Returns the value for [id], creating an unknown-but-valid value when not recognized. */
        public fun of(id: String): UserParticipationState = UserParticipationState(id)
    }
}

/**
 * Fired on the server as soon as a player connection is established, before any MoeMusic-specific
 * handshake has necessarily happened.
 *
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
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
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
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
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
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
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
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
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
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
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
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
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnIdentifierResolved(
    val identifier: String,
    val submitter: MoeMusicUser?,
    val outcome: IdentifierResolutionOutcome,
)

/**
 * Fired on the server after a track has been authoritatively accepted by the submission pipeline.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnTrackSubmitted(
    val track: TrackInfo,
    val submitter: MoeMusicUser?,
    val mode: TrackAddMode,
    val result: TrackAddResult,
)

/**
 * Fired on the server after a queued user-submitted track is removed.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnQueueTrackRemoved(
    val track: TrackInfo,
    val requester: MoeMusicUser?,
    val bypassOwnership: Boolean,
)

/**
 * Fired on the server after tracks are removed by a queue clear operation.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnQueueCleared(
    val removedTracks: List<TrackInfo>,
    val requester: MoeMusicUser?,
    val targetUserId: UUID?,
    val targetUserName: String?,
    val bypassOwnership: Boolean,
)

/**
 * Fired on the server when a new track begins playing.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnPlaybackStarted(
    val track: TrackInfo,
    val playback: PlaybackResource,
    val fromAutoplay: Boolean,
)

/**
 * Fired on the server when a playback start attempt fails after the track was selected.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnPlaybackStartFailed(
    val track: TrackInfo,
    val fromAutoplay: Boolean,
    val reason: LocalizedText,
)

/**
 * Fired on the server when playback pauses.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnPlaybackPaused(
    val track: TrackInfo,
    val positionMs: Long,
    val automatic: Boolean,
)

/**
 * Fired on the server when playback resumes.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnPlaybackResumed(
    val track: TrackInfo,
    val positionMs: Long,
    val automatic: Boolean,
)

/**
 * Fired on the server when playback seeks.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnPlaybackSeeked(
    val track: TrackInfo,
    val positionMs: Long,
    val wasPlaying: Boolean,
)

/**
 * Fired on the server when playback stops explicitly or due to queue exhaustion.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnPlaybackStopped(
    val track: TrackInfo,
    val manual: Boolean,
)

/**
 * Why a local [OnClientPlaybackStarted] fired.
 *
 * This is an **open** value set, not an enum: future API versions may distinguish more start
 * causes, so `when` over a [PlaybackStartCause] cannot be exhaustive and must always include an
 * `else` branch.
 */
@JvmInline
public value class PlaybackStartCause private constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The server started, or advanced to, a new track. */
        public val NEW_TRACK: PlaybackStartCause = PlaybackStartCause("NEW_TRACK")

        /**
         * This client applied an already-existing server playback snapshot, e.g. an initial active
         * join or a standby-to-active catch-up, rather than the server beginning a new track.
         */
        public val CATCH_UP: PlaybackStartCause = PlaybackStartCause("CATCH_UP")

        /** Values known to this build. New values may appear at runtime; always handle `else`. */
        public val entries: List<PlaybackStartCause> = listOf(NEW_TRACK, CATCH_UP)

        /** Returns the value for [id], creating an unknown-but-valid value when not recognized. */
        public fun of(id: String): PlaybackStartCause = PlaybackStartCause(id)
    }
}

/**
 * Fired on the client when playback starts or an existing server playback materializes locally.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnClientPlaybackStarted(
    val track: TrackInfo,
    val playback: PlaybackResource,
    val positionMs: Long,
    /**
     * Why this start fired: [PlaybackStartCause.NEW_TRACK] when the server started/advanced to a new
     * track, or [PlaybackStartCause.CATCH_UP] when this client applied an already-existing playback
     * snapshot (initial active join or standby-to-active catch-up).
     */
    val startCause: PlaybackStartCause,
)

/**
 * Fired on the client when playback pauses.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnClientPlaybackPaused(
    val track: TrackInfo,
    val positionMs: Long,
)

/**
 * Fired on the client when playback resumes.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnClientPlaybackResumed(
    val track: TrackInfo,
    val positionMs: Long,
)

/**
 * Fired on the client when playback seeks while remaining on the same track.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnClientPlaybackSeeked(
    val track: TrackInfo,
    val positionMs: Long,
)

/**
 * Fired on the client when local playback stops for the current track.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
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

/**
 * Fired when the shared content-filter rules are re-applied at runtime after plugins can subscribe.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnContentFilterRulesApplied(
    val rules: ContentFilterRules,
)

/**
 * Fired when an exact shared track rule is changed through the runtime editor.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnContentFilterTrackRuleChanged(
    val sourceId: String,
    val trackId: String,
    val action: ContentFilterRuleAction,
    val note: String? = null,
    val changed: Boolean,
    val nowBlocked: Boolean,
    val affectedCount: Int,
)

/**
 * Fired when one or more exact shared artist rules are changed through the runtime editor.
 * Read-only host-produced event. Do not construct, destructure, or copy.
 * Appending fields is binary-safe for read-only consumers.
 */
public data class OnContentFilterArtistRuleChanged(
    val sourceId: String,
    val artistIds: List<String>,
    val action: ContentFilterRuleAction,
    val note: String? = null,
    val changed: Boolean,
    val nowBlocked: Boolean,
    val affectedCount: Int,
)
