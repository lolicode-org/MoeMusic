package org.lolicode.moemusic.api.plugin

import org.lolicode.moemusic.api.service.IContentFilterService
import org.lolicode.moemusic.api.service.IIdentifierResolutionService
import org.lolicode.moemusic.api.service.IMediaProbeService
import org.lolicode.moemusic.api.service.IPermissionService
import org.lolicode.moemusic.api.service.IPlaybackController
import org.lolicode.moemusic.api.service.IUserActionService
import org.lolicode.moemusic.api.service.IRateLimitService
import org.lolicode.moemusic.api.service.ISearchService
import org.lolicode.moemusic.api.service.ITrackSubmissionService

/**
 * Services exposed to a [Plugin] for a concrete Minecraft server session.
 *
 * This context is passed only to [Plugin.onServerSessionLoad], which may run multiple times in
 * one JVM on integrated singleplayer. It intentionally omits runtime-registration APIs such as
 * [ServerRuntimeContext.registerMusicSource], event-bus subscription, and config-change listeners so
 * repeated session starts cannot accumulate long-lived registrations by accident.
 */
public interface ServerSessionContext : PluginScopedContext {

    /** Unchecked server-side playback controller for the active logical server runtime. */
    public val playbackController: IPlaybackController

    /** Unchecked search routing service for the active logical server runtime. */
    public val searchService: ISearchService

    /** Unchecked identifier resolution service for the active logical server runtime. */
    public val identifierResolutionService: IIdentifierResolutionService

    /** Unchecked server-side submission pipeline for the active logical server runtime. */
    public val trackSubmissionService: ITrackSubmissionService

    /** Checked common path for user-behalf actions. */
    public val userActionService: IUserActionService

    /** Shared content-filter rules and helpers for the active logical server runtime. */
    public val contentFilterService: IContentFilterService

    /** Shared request rate-limit enforcement service for the active logical server runtime. */
    public val rateLimitService: IRateLimitService

    /** Shared built-in permission checks for the active logical server runtime. */
    public val permissionService: IPermissionService

    /** Shared HTTP(S) metadata probe service for the active logical server runtime. */
    public val mediaProbeService: IMediaProbeService

    /**
     * Acquire a session-scoped audience lease for some external playback consumer.
     *
     * Compatibility or bridge plugins should hold one lease while they have at least one active
     * non-native listener. The first held lease resumes auto-paused playback and allows autoplay or
     * queued playback to start; once the final lease is released, MoeMusic may auto-pause.
     *
     * The returned lease is automatically invalidated on server-session shutdown.
     */
    public fun acquirePlaybackAudienceLease(): PlaybackAudienceLease

}
