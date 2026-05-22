package org.lolicode.moemusic.api.plugin

import org.lolicode.moemusic.api.I18nRegistry
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.client.IClientPlaybackService
import org.lolicode.moemusic.api.client.IClientRequestService
import org.lolicode.moemusic.api.event.EventBus
import org.lolicode.moemusic.api.service.IContentFilterService
import org.lolicode.moemusic.api.service.IIdentifierResolutionService
import org.lolicode.moemusic.api.service.IMediaProbeService
import org.lolicode.moemusic.api.service.IPermissionService
import org.lolicode.moemusic.api.service.IPlaybackController
import org.lolicode.moemusic.api.service.IUserActionService
import org.lolicode.moemusic.api.service.IRateLimitService
import org.lolicode.moemusic.api.service.ISearchService
import org.lolicode.moemusic.api.service.ITrackSubmissionService
import org.slf4j.Logger
import java.nio.file.Path

/**
 * Common plugin-scoped facilities available on both client and server runtime contexts.
 *
 * [pluginDataDir] is a plugin-owned directory created before the callback runs. Plugins may keep
 * custom state files there. Typed config I/O still uses the dedicated TOML file derived from
 * [Plugin.configId].
 */
public interface PluginScopedContext {

    /** SLF4J logger scoped to the owning plugin's [Plugin.id]. */
    public val logger: Logger

    /** Shared localization registry. */
    public val i18n: I18nRegistry

    /** Plugin-owned filesystem directory for custom persistent state files. */
    public val pluginDataDir: Path

    /**
     * Load a typed configuration object described by [spec].
     *
     * [Plugin.configSpec] should usually point at the same spec so platform modules can
     * auto-generate a config UI from the same metadata that core uses for file I/O. If the
     * plugin exposes [Plugin.configSpec], MoeMusic creates the default TOML file during plugin
     * manager initialization before runtime callbacks are dispatched.
     */
    public fun <T : Any> loadConfig(spec: PluginConfigSpec<T>): T

    /** Persist a typed configuration object described by [spec]. */
    public fun <T : Any> saveConfig(spec: PluginConfigSpec<T>, value: T)
}

/**
 * Long-lived runtime context shared by the client and logical-server runtime callbacks.
 *
 * Runtime contexts may register long-lived event listeners and config change listeners.
 */
public interface PluginRuntimeContext : PluginScopedContext {

    /**
     * Shared observational event bus.
     * Subscribe to event types here; handlers are notified but cannot mutate the workflow.
     */
    public val eventBus: EventBus

    /**
     * Register a listener for saves of this plugin's config file.
     *
     * The listener is invoked when [saveConfig] is called or when the generated config screen
     * saves this plugin's config successfully in the same JVM. Platform-side config reload paths
     * may also invoke it after re-reading the plugin config file from disk in the same JVM.
     */
    public fun <T : Any> onConfigChanged(spec: PluginConfigSpec<T>, listener: (T) -> Unit)
}

/**
 * Services and registrations made available to a [Plugin] during logical-server runtime load.
 *
 * A fresh [ServerRuntimeContext] instance is created per plugin per runtime-load phase. Plugins
 * receive it in [Plugin.onServerRuntimeLoad] and may retain it for the duration of that logical
 * server runtime lifetime.
 */
public interface ServerRuntimeContext : PluginRuntimeContext {

    /** Unchecked server-side playback controller. */
    public val playbackController: IPlaybackController

    /** Unchecked search routing service. */
    public val searchService: ISearchService

    /** Unchecked identifier resolution service. */
    public val identifierResolutionService: IIdentifierResolutionService

    /** Unchecked server-side submission pipeline. */
    public val trackSubmissionService: ITrackSubmissionService

    /** Checked common path for user-behalf actions. */
    public val userActionService: IUserActionService

    /** Shared content-filter rules and helpers. */
    public val contentFilterService: IContentFilterService

    /** Shared request rate-limit enforcement service. */
    public val rateLimitService: IRateLimitService

    /** Shared built-in permission checks. */
    public val permissionService: IPermissionService

    /** Shared HTTP(S) metadata probe service. */
    public val mediaProbeService: IMediaProbeService

    /**
     * Register a [MusicSource] implementation.
     *
     * The source becomes available for search routing and autoplay contribution immediately
     * after registration. Registrations remain active until [Plugin.onServerRuntimeUnload] or
     * process shutdown.
     *
     * Valid only during [Plugin.onServerRuntimeLoad].
     */
    public fun registerMusicSource(source: MusicSource)
}

/**
 * Services and registrations made available to a [Plugin] during client runtime load.
 *
 * A fresh [ClientRuntimeContext] instance is created per plugin per client runtime-load phase.
 * Plugins receive it in [Plugin.onClientRuntimeLoad] and may retain it for the duration of that
 * client runtime lifetime.
 */
public interface ClientRuntimeContext : PluginRuntimeContext {

    /** Shared content-filter rules and helpers for local client decisions. */
    public val contentFilterService: IContentFilterService

    /** Local client playback state and controls. */
    public val clientPlaybackService: IClientPlaybackService

    /** Typed built-in client -> server request API. */
    public val clientRequestService: IClientRequestService
}
