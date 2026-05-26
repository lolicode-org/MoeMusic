package org.lolicode.moemusic.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.lolicode.moemusic.api.service.IMediaProbeService
import org.lolicode.moemusic.api.service.IPermissionService
import org.lolicode.moemusic.api.service.IUserActionService
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.plugin.PlaybackAudienceLease
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.core.config.AutoplayConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuleEditor
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.playback.audience.PlaybackAudienceLeaseCoordinator
import org.lolicode.moemusic.core.playback.autoplay.AutoplayManager
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.core.playback.ServerPlaybackController
import org.lolicode.moemusic.core.playback.TrackQueue
import org.lolicode.moemusic.core.playback.TrackSubmissionService
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.plugin.PluginManager.PluginConfigReloadReport
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.source.builtin.HttpMusicSource
import org.slf4j.LoggerFactory
import java.nio.file.Path

interface ServerRuntimeAdapter {
    fun onUserQueueTrackSkipped(track: TrackInfo, reason: LocalizedText?) {}
    fun onTrackSubmitted(track: TrackInfo, result: TrackAddResult) {}
    fun onServerSessionCleared() {}
}

data class ServerPluginServices(
    val permissionService: IPermissionService,
    val userActionService: IUserActionService,
    val mediaProbeService: IMediaProbeService,
)

data class ServerConfigReloadReport(
    val reloadedBuiltinSections: List<String>,
    val pluginConfigsProcessed: List<String>,
    val pluginConfigsNotified: List<String>,
    val pluginConfigFailures: Map<String, String>,
)

object ServerRuntimeCoordinator {

    private object NoopAdapter : ServerRuntimeAdapter

    private val logger = LoggerFactory.getLogger(ServerRuntimeCoordinator::class.java)

    lateinit var channel: NetworkChannel
        private set

    lateinit var configDir: Path
        private set

    lateinit var queue: TrackQueue
        private set

    lateinit var playbackController: ServerPlaybackController
        private set

    lateinit var trackSubmissionService: TrackSubmissionService
        private set

    lateinit var requestRateLimiter: RequestRateLimiter
        private set

    lateinit var permissionService: IPermissionService
        private set

    lateinit var userActionService: IUserActionService
        private set

    lateinit var mediaProbeService: IMediaProbeService
        private set

    lateinit var autoplayManager: AutoplayManager
        private set

    private lateinit var playbackAudienceLeaseCoordinator: PlaybackAudienceLeaseCoordinator
    private lateinit var pluginServicesFactory: (
        playbackController: ServerPlaybackController,
        trackSubmissionService: TrackSubmissionService,
        requestRateLimiter: RequestRateLimiter,
    ) -> ServerPluginServices
    private var nativeAudienceLease: PlaybackAudienceLease? = null

    private val serverScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var runtimeAdapter: ServerRuntimeAdapter = NoopAdapter
    private var autoplayConfig: AutoplayConfig? = null

    private val reloadableBuiltinSections: List<String> = listOf(
        "default_source_id",
        "default_language",
        "vote_required_percent",
        "permissions",
        "content_filter",
        "media",
        "autoplay",
    )

    var serverRuntimeInitialized: Boolean = false
        private set

    var serverSessionActive: Boolean = false
        private set

    fun serverInit(
        channel: NetworkChannel,
        configDir: Path,
        adapter: ServerRuntimeAdapter = NoopAdapter,
        pluginServicesFactory: (
            playbackController: ServerPlaybackController,
            trackSubmissionService: TrackSubmissionService,
            requestRateLimiter: RequestRateLimiter,
        ) -> ServerPluginServices,
    ) {
        this.channel = channel
        this.configDir = configDir
        runtimeAdapter = adapter
        this.pluginServicesFactory = pluginServicesFactory

        ModConfigManager.load(configDir)
        ContentFilterRuntime.applyConfig(ModConfigManager.config)

        PluginManager.initialize(configDir)
        Localization.validateConfiguredDefaultLanguage()
        ensureServerRuntimeInitialized(channel)
        refreshAutoplayRuntimeIfNeeded()
        requestRateLimiter.clear()
        playbackAudienceLeaseCoordinator.beginSession()
        nativeAudienceLease = null
        PluginManager.dispatchServerSessionLoad()
        serverSessionActive = true
        logger.info("Server runtime session initialized.")
    }

    fun serverShutdown(finalRuntime: Boolean) {
        if (serverSessionActive) {
            PluginManager.dispatchServerSessionUnload()
            clearServerSessionState()
            serverSessionActive = false
            logger.info("Server runtime session shutdown complete.")
        }
        if (finalRuntime) {
            shutdownPersistentServerRuntime()
        }
    }

    /**
     * Re-apply the builtin server-side config sections that have live runtime usage and do not
     * require a broader server restart.
     */
    fun applyReloadableServerConfig(): List<String> =
        applyReloadableServerConfig(refreshAutoplayIfNeeded = true)

    private fun applyReloadableServerConfig(refreshAutoplayIfNeeded: Boolean): List<String> {
        if (!serverRuntimeInitialized) return emptyList()
        Localization.validateConfiguredDefaultLanguage()
        ContentFilterRuleEditor.applyCurrentConfig()
        if (refreshAutoplayIfNeeded) {
            refreshAutoplayRuntimeIfNeeded()
        }
        if (::requestRateLimiter.isInitialized) {
            requestRateLimiter.clear()
        }
        return reloadableBuiltinSections
    }

    /**
     * Reload shared server config and reloadable plugin configs from disk.
     *
     * Root config parse failures abort the reload and keep the current in-memory state intact.
     * Plugin config failures are isolated per plugin and reported in the returned summary.
     */
    fun reloadServerConfigFromDisk(): ServerConfigReloadReport {
        check(serverRuntimeInitialized) { "Server runtime is not initialized." }
        ModConfigManager.reload(configDir)
        Localization.validateConfiguredDefaultLanguage()
        val pluginReport = PluginManager.reloadConfigFilesFromDisk()
        val builtinSections = applyReloadableServerConfig(refreshAutoplayIfNeeded = false)
        refreshAutoplayRuntime()
        return pluginReloadReport(builtinSections, pluginReport)
    }

    /** Rebuild the current autoplay deck from the latest in-memory autoplay config. */
    fun refreshAutoplayRuntime() {
        check(serverRuntimeInitialized) { "Server runtime is not initialized." }
        installAutoplayManager(ModConfigManager.config.autoplay)
    }

    private fun ensureServerRuntimeInitialized(channel: NetworkChannel) {
        if (serverRuntimeInitialized) return

        queue = TrackQueue()
        playbackController = ServerPlaybackController(
            channel = channel,
            queue = queue,
            onUserQueueTrackSkipped = runtimeAdapter::onUserQueueTrackSkipped,
            onTrackSubmitted = runtimeAdapter::onTrackSubmitted,
        )
        trackSubmissionService = TrackSubmissionService(playbackController)
        requestRateLimiter = RequestRateLimiter()
        playbackAudienceLeaseCoordinator = PlaybackAudienceLeaseCoordinator(playbackController)
        val pluginServices = pluginServicesFactory(playbackController, trackSubmissionService, requestRateLimiter)
        permissionService = pluginServices.permissionService
        userActionService = pluginServices.userActionService
        mediaProbeService = pluginServices.mediaProbeService

        PluginManager.activateServerRuntime(
            playbackController = playbackController,
            trackSubmissionService = trackSubmissionService,
            requestRateLimiter = requestRateLimiter,
            acquireAudienceLease = playbackAudienceLeaseCoordinator::acquire,
            permissionService = permissionService,
            userActionService = userActionService,
            mediaProbeService = mediaProbeService,
        )
        PluginManager.registerMusicSource(HttpMusicSource)
        PluginManager.dispatchServerRuntimeLoad()
        installAutoplayManager(ModConfigManager.config.autoplay)
        serverRuntimeInitialized = true
        logger.info("Persistent server runtime initialized.")
    }

    private fun refreshAutoplayRuntimeIfNeeded() {
        val currentConfig = ModConfigManager.config.autoplay
        if (!::autoplayManager.isInitialized || autoplayConfig != currentConfig) {
            installAutoplayManager(currentConfig)
        }
    }

    private fun installAutoplayManager(config: AutoplayConfig) {
        if (::autoplayManager.isInitialized) {
            autoplayManager.close()
        }
        autoplayManager = AutoplayManager(config, serverScope)
        autoplayManager.initialize(queue, PluginManager.musicSources) {
            if (playbackAudienceLeaseCoordinator.hasAudience()) {
                playbackController.startNextIfStopped()
            }
        }
        autoplayConfig = config
    }

    fun ensureNativeAudienceLease() {
        if (!serverSessionActive || nativeAudienceLease != null) return
        nativeAudienceLease = playbackAudienceLeaseCoordinator.acquire("native_moemusic_client")
    }

    fun releaseNativeAudienceLeaseIfHeld() {
        nativeAudienceLease?.release()
        nativeAudienceLease = null
    }

    private fun clearServerSessionState() {
        UserSessionRegistry.clear()
        runtimeAdapter.onServerSessionCleared()
        nativeAudienceLease = null
        if (::playbackAudienceLeaseCoordinator.isInitialized) {
            playbackAudienceLeaseCoordinator.clearSession()
        }
        if (::requestRateLimiter.isInitialized) {
            requestRateLimiter.clear()
        }
        if (::playbackController.isInitialized) {
            playbackController.autoPause()
        }
    }

    private fun shutdownPersistentServerRuntime() {
        if (!serverRuntimeInitialized) return
        PluginManager.dispatchServerRuntimeUnload()
        serverRuntimeInitialized = false
        if (::autoplayManager.isInitialized) {
            autoplayManager.close()
        }
        autoplayConfig = null
        runtimeAdapter = NoopAdapter
        PluginManager.reset()
        logger.info("Persistent server runtime shutdown complete.")
    }

    private fun pluginReloadReport(
        builtinSections: List<String>,
        pluginReport: PluginConfigReloadReport,
    ): ServerConfigReloadReport =
        ServerConfigReloadReport(
            reloadedBuiltinSections = builtinSections,
            pluginConfigsProcessed = pluginReport.processedPluginIds,
            pluginConfigsNotified = pluginReport.notifiedPluginIds,
            pluginConfigFailures = pluginReport.failures,
        )
}
