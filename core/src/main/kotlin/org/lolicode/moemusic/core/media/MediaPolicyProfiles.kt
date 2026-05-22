package org.lolicode.moemusic.core.media

import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig

/**
 * Canonical media URL policy profiles used by built-in server and client media handling.
 */
object MediaPolicyProfiles {

    /**
     * Shared media firewall policy honored on a best-effort basis by both server and client.
     *
     * The server applies this before forwarding playback / cover URLs to clients, and the client
     * applies the same policy again before playback / cover fetching proceeds. Local `file://`
     * media is only allowed when explicitly enabled in shared config.
     */
    fun sharedMediaFirewall(config: MoeMusicConfig = ModConfigManager.config): MediaUrlAccessPolicy =
        MediaUrlAccessPolicy(
            enabled = config.media.firewall.enabled,
            hostListMode = config.media.firewall.hostListMode,
            hosts = config.media.firewall.hosts,
            blockPrivateIps = config.media.firewall.blockPrivateIps,
            allowLocalFiles = config.media.allowLocalFiles,
        )
}
