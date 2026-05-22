package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.I18nRegistry
import org.lolicode.moemusic.api.plugin.PlaybackAudienceLease
import org.lolicode.moemusic.api.plugin.ServerSessionContext
import org.lolicode.moemusic.api.service.*
import java.nio.file.Path

internal class ServerSessionContextImpl(
    private val pluginId: String,
    configFile: Path,
    pluginDataDir: Path,
    i18nStore: I18nRegistry,
    override val playbackController: IPlaybackController,
    override val searchService: ISearchService,
    override val identifierResolutionService: IIdentifierResolutionService,
    override val trackSubmissionService: ITrackSubmissionService,
    override val userActionService: IUserActionService,
    override val contentFilterService: IContentFilterService,
    override val rateLimitService: IRateLimitService,
    override val permissionService: IPermissionService,
    override val mediaProbeService: IMediaProbeService,
    private val acquirePlaybackAudienceLeaseImpl: (String) -> PlaybackAudienceLease,
) : BasePluginScopedContext(pluginId, configFile, pluginDataDir, i18nStore), ServerSessionContext {

    override fun acquirePlaybackAudienceLease(): PlaybackAudienceLease =
        acquirePlaybackAudienceLeaseImpl(pluginId)
}
