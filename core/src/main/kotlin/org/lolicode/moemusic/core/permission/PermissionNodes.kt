package org.lolicode.moemusic.core.permission

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.permission.MoeMusicPermission
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.config.PermissionDefaultsConfig

/**
 * Permission nodes used by both commands and packet-driven control paths.
 *
 * Each node also exposes the configured vanilla fallback level that should be used when no
 * advanced permission provider grants or denies the node explicitly.
 */
object PermissionNodes {

    data class Node(
        val id: String,
        private val defaultLevelProvider: PermissionDefaultsConfig.() -> Int,
        val deniedMessage: LocalizedText,
    ) {
        fun defaultLevel(config: MoeMusicConfig = ModConfigManager.config): Int =
            defaultLevelProvider(config.permissions).coerceIn(MIN_DEFAULT_LEVEL, MAX_DEFAULT_LEVEL)
    }

    const val MIN_DEFAULT_LEVEL: Int = 0
    const val MAX_DEFAULT_LEVEL: Int = 4

    val SUBMIT: Node = Node(
        id = "moemusic.common.submit",
        defaultLevelProvider = { submit },
        deniedMessage = LocalizedText.key("error.moemusic.permission.submit"),
    )

    internal val SOURCE_HTTP_SUBMIT: Node = Node(
        id = "moemusic.admin.source.http",
        defaultLevelProvider = { sourceHttpSubmit },
        deniedMessage = LocalizedText.key("error.moemusic.permission.http_play"),
    )

    val SUBMIT_SKIP_AUTOPLAY: Node = Node(
        id = "moemusic.common.submit.skip_autoplay",
        defaultLevelProvider = { submitSkipAutoplay },
        deniedMessage = LocalizedText.key("error.moemusic.permission.submit_skip_autoplay"),
    )

    val QUEUE_CONTROL: Node = Node(
        id = "moemusic.moderation.queue_control",
        defaultLevelProvider = { queueControl },
        deniedMessage = LocalizedText.key("error.moemusic.permission.queue_control"),
    )

    val VOTE: Node = Node(
        id = "moemusic.common.vote",
        defaultLevelProvider = { vote },
        deniedMessage = LocalizedText.key("error.moemusic.permission.vote"),
    )

    val PLAYBACK_CONTROL: Node = Node(
        id = "moemusic.moderation.playback_control",
        defaultLevelProvider = { playbackControl },
        deniedMessage = LocalizedText.key("error.moemusic.permission.playback_control"),
    )

    val QUEUE_VIEW: Node = Node(
        id = "moemusic.common.view_queue",
        defaultLevelProvider = { queueView },
        deniedMessage = LocalizedText.key("error.moemusic.permission.queue_view"),
    )

    val SEARCH: Node = Node(
        id = "moemusic.common.search",
        defaultLevelProvider = { search },
        deniedMessage = LocalizedText.key("error.moemusic.permission.search"),
    )

    val CONTENT_FILTER_MANAGE: Node = Node(
        id = "moemusic.moderation.filter_manage",
        defaultLevelProvider = { contentFilterManage },
        deniedMessage = LocalizedText.key("error.moemusic.permission.content_filter_manage"),
    )

    val CONFIG_RELOAD: Node = Node(
        id = "moemusic.admin.reload",
        defaultLevelProvider = { configReload },
        deniedMessage = LocalizedText.key("error.moemusic.permission.config_reload"),
    )

    val SYSTEM_INFO: Node = Node(
        id = "moemusic.admin.system.info",
        defaultLevelProvider = { systemInfo },
        deniedMessage = LocalizedText.key("error.moemusic.permission.system_info"),
    )

    val AUTOPLAY_REFRESH: Node = Node(
        id = "moemusic.moderation.autoplay_refresh",
        defaultLevelProvider = { autoplayRefresh },
        deniedMessage = LocalizedText.key("error.moemusic.permission.autoplay_refresh"),
    )

    /**
     * Allows the holder to bypass content-filter enforcement at the submission gate.
     *
     * Players with this permission can enqueue tracks that would otherwise be blocked by
     * server-configured content-filter rules.
     */
    val CONTENT_FILTER_BYPASS: Node = Node(
        id = "moemusic.privilege.bypass.filter",
        defaultLevelProvider = { contentFilterBypass },
        deniedMessage = LocalizedText.key("error.moemusic.permission.content_filter_bypass"),
    )

    val DURATION_POLICY_BYPASS: Node = Node(
        id = "moemusic.privilege.bypass.duration_policy",
        defaultLevelProvider = { durationPolicyBypass },
        deniedMessage = LocalizedText.key("error.moemusic.permission.duration_policy_bypass"),
    )

    val RATE_LIMIT_BYPASS: Node = Node(
        id = "moemusic.privilege.bypass.rate_limit",
        defaultLevelProvider = { rateLimitBypass },
        deniedMessage = LocalizedText.key("error.moemusic.permission.rate_limit_bypass"),
    )

    /**
     * Allows the holder to submit a track that is already in the queue or currently playing.
     *
     * Without this permission, duplicate submissions are rejected with [org.lolicode.moemusic.api.AlreadyQueuedException].
     */
    val SUBMIT_DUPLICATE: Node = Node(
        id = "moemusic.privilege.bypass.duplicate",
        defaultLevelProvider = { submitDuplicate },
        deniedMessage = LocalizedText.key("error.moemusic.permission.submit_duplicate"),
    )

    fun node(permission: MoeMusicPermission): Node = when (permission) {
        MoeMusicPermission.SUBMIT -> SUBMIT
        MoeMusicPermission.SUBMIT_SKIP_AUTOPLAY -> SUBMIT_SKIP_AUTOPLAY
        MoeMusicPermission.QUEUE_CONTROL -> QUEUE_CONTROL
        MoeMusicPermission.VOTE -> VOTE
        MoeMusicPermission.PLAYBACK_CONTROL -> PLAYBACK_CONTROL
        MoeMusicPermission.QUEUE_VIEW -> QUEUE_VIEW
        MoeMusicPermission.SEARCH -> SEARCH
        MoeMusicPermission.CONTENT_FILTER_MANAGE -> CONTENT_FILTER_MANAGE
        MoeMusicPermission.CONTENT_FILTER_BYPASS -> CONTENT_FILTER_BYPASS
        MoeMusicPermission.DURATION_POLICY_BYPASS -> DURATION_POLICY_BYPASS
        MoeMusicPermission.RATE_LIMIT_BYPASS -> RATE_LIMIT_BYPASS
        MoeMusicPermission.SUBMIT_DUPLICATE -> SUBMIT_DUPLICATE
    }
}
