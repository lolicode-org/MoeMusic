package org.lolicode.moemusic.clientcore.media

import org.lolicode.moemusic.core.media.MediaPolicyProfiles
import org.lolicode.moemusic.core.media.MediaUrlPolicy
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult

/**
 * Built-in client-side firewall for server-provided media URLs.
 *
 * Playback and cover fetches should both pass through this helper so the client applies one
 * coherent policy surface to all server-supplied media.
 */
object ClientMediaFirewall {

    fun evaluate(url: String): MediaUrlPolicyResult =
        MediaUrlPolicy.evaluate(url, MediaPolicyProfiles.sharedMediaFirewall())
}
