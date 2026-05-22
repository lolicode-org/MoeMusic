package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.plugin.*
import org.lolicode.moemusic.api.service.*
import org.lolicode.moemusic.api.client.IClientPlaybackService
import org.lolicode.moemusic.api.client.IClientRequestService
import org.lolicode.moemusic.api.event.EventBus
import org.lolicode.moemusic.api.plugin.ClientRuntimeContext
import org.lolicode.moemusic.api.plugin.PluginRuntimeContext
import org.lolicode.moemusic.api.plugin.PluginScopedContext
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.service.IUserActionService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

internal abstract class BasePluginScopedContext(
    private val pluginId: String,
    private val configFile: Path,
    pluginDataDir: Path,
    i18nStore: I18nRegistry,
) : PluginScopedContext {

    override val logger: Logger = LoggerFactory.getLogger("MoeMusic/$pluginId")
    override val i18n: I18nRegistry = i18nStore
    override val pluginDataDir: Path = pluginDataDir.also(Files::createDirectories)

    override fun <T : Any> loadConfig(spec: PluginConfigSpec<T>): T =
        PluginConfigIO.load(configFile, spec.configClass, spec::createDefault)

    override fun <T : Any> saveConfig(spec: PluginConfigSpec<T>, value: T) {
        PluginConfigIO.save(configFile, value, spec.configClass)
        PluginManager.notifyConfigChanged(pluginId, value)
    }
}

internal abstract class BasePluginRuntimeContext(
    private val pluginId: String,
    configFile: Path,
    pluginDataDir: Path,
    i18nStore: I18nRegistry,
    override val eventBus: EventBus,
) : BasePluginScopedContext(pluginId, configFile, pluginDataDir, i18nStore), PluginRuntimeContext {
    override fun <T : Any> onConfigChanged(spec: PluginConfigSpec<T>, listener: (T) -> Unit) {
        PluginManager.registerConfigChangeListener(pluginId = pluginId, listener = listener)
    }
}

internal class ServerRuntimeContextImpl(
    private val pluginId: String,
    configFile: Path,
    pluginDataDir: Path,
    i18nStore: I18nRegistry,
    eventBus: EventBus,
    override val playbackController: IPlaybackController,
    override val searchService: ISearchService,
    override val identifierResolutionService: IIdentifierResolutionService,
    override val trackSubmissionService: ITrackSubmissionService,
    override val userActionService: IUserActionService,
    override val contentFilterService: IContentFilterService,
    override val rateLimitService: IRateLimitService,
    override val permissionService: IPermissionService,
    override val mediaProbeService: IMediaProbeService,
) : BasePluginRuntimeContext(pluginId, configFile, pluginDataDir, i18nStore, eventBus), ServerRuntimeContext {

    override fun registerMusicSource(source: MusicSource) {
        PluginManager.registerPluginMusicSource(pluginId, source)
    }
}

internal class ClientRuntimeContextImpl(
    private val pluginId: String,
    configFile: Path,
    pluginDataDir: Path,
    i18nStore: I18nRegistry,
    eventBus: EventBus,
    override val contentFilterService: IContentFilterService,
    override val clientPlaybackService: IClientPlaybackService,
    override val clientRequestService: IClientRequestService,
) : BasePluginRuntimeContext(pluginId, configFile, pluginDataDir, i18nStore, eventBus), ClientRuntimeContext
