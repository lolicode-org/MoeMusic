package org.lolicode.moemusic.core.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.lolicode.moemusic.api.LocalizedText
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

@Serializable
enum class MediaHostListMode {
    @SerialName("blacklist")
    BLACKLIST,

    @SerialName("whitelist")
    WHITELIST,
}

data class MediaUrlAccessPolicy(
    val enabled: Boolean,
    val hostListMode: MediaHostListMode,
    val hosts: List<String>,
    val blockPrivateIps: Boolean,
    val allowLocalFiles: Boolean,
)

sealed interface MediaUrlPolicyResult {
    data object Allow : MediaUrlPolicyResult

    data class Reject(
        val reason: LocalizedText,
    ) : MediaUrlPolicyResult
}

object MediaUrlPolicy {

    fun evaluate(url: String, policy: MediaUrlAccessPolicy): MediaUrlPolicyResult {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return reject("error.moemusic.media_policy.bad_url")
        val scheme = uri.scheme?.lowercase()
            ?: return reject("error.moemusic.media_policy.bad_url")

        return when (scheme) {
            "http", "https" -> {
                if (!policy.enabled) {
                    MediaUrlPolicyResult.Allow
                } else {
                    evaluateHttpUri(uri, policy)
                }
            }
            "file" -> if (policy.allowLocalFiles) {
                MediaUrlPolicyResult.Allow
            } else {
                reject("error.moemusic.media_policy.local_file_disabled")
            }
            else -> reject("error.moemusic.media_policy.scheme_not_allowed", scheme)
        }
    }

    private fun evaluateHttpUri(uri: URI, policy: MediaUrlAccessPolicy): MediaUrlPolicyResult {
        val host = uri.host?.lowercase()?.trimStart('.')
            ?: return reject("error.moemusic.media_policy.bad_url")

        if (!hostAllowed(host, policy)) {
            return reject("error.moemusic.media_policy.host_blocked", host)
        }

        if (policy.blockPrivateIps && isPrivateHost(host)) {
            return reject("error.moemusic.media_policy.private_ip", host)
        }

        return MediaUrlPolicyResult.Allow
    }

    private fun hostAllowed(host: String, policy: MediaUrlAccessPolicy): Boolean {
        if (policy.hosts.isEmpty()) {
            return policy.hostListMode == MediaHostListMode.BLACKLIST
        }
        val matched = policy.hosts.any { entry ->
            host == entry || host.endsWith(".$entry")
        }
        return when (policy.hostListMode) {
            MediaHostListMode.BLACKLIST -> !matched
            MediaHostListMode.WHITELIST -> matched
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
        } catch (_: UnknownHostException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun reject(key: String, vararg args: Any): MediaUrlPolicyResult.Reject =
        MediaUrlPolicyResult.Reject(LocalizedText.key(key, *args))
}
