package org.lolicode.moemusic.core.media

import org.lolicode.moemusic.core.config.MediaFirewallConfig
import org.lolicode.moemusic.core.config.MediaPolicyConfig
import org.lolicode.moemusic.core.config.MoeMusicConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaPolicyProfilesTest {

    @Test
    fun `server and client profiles both honor shared firewall config`() {
        val config = MoeMusicConfig(
            media = MediaPolicyConfig(
                allowLocalFiles = true,
                firewall = MediaFirewallConfig(
                    enabled = false,
                    hostListMode = MediaHostListMode.WHITELIST,
                    hosts = listOf("media.example.com"),
                    blockPrivateIps = false,
                ),
            ),
        )

        val policy = MediaPolicyProfiles.sharedMediaFirewall(config)

        assertEquals(false, policy.enabled)
        assertEquals(MediaHostListMode.WHITELIST, policy.hostListMode)
        assertEquals(listOf("media.example.com"), policy.hosts)
        assertEquals(false, policy.blockPrivateIps)
        assertEquals(true, policy.allowLocalFiles)
    }
}
