package org.lolicode.moemusic.core.media

import kotlin.test.Test
import kotlin.test.assertIs

class MediaUrlPolicyTest {

    @Test
    fun `blacklist mode allows non private http hosts by default`() {
        val verdict = MediaUrlPolicy.evaluate(
            "https://example.com/track.mp3",
            MediaUrlAccessPolicy(
                enabled = true,
                hostListMode = MediaHostListMode.BLACKLIST,
                hosts = emptyList(),
                blockPrivateIps = true,
                allowLocalFiles = false,
            )
        )

        assertIs<MediaUrlPolicyResult.Allow>(verdict)
    }

    @Test
    fun `whitelist mode rejects hosts outside configured list`() {
        val verdict = MediaUrlPolicy.evaluate(
            "https://blocked.example.com/track.mp3",
            MediaUrlAccessPolicy(
                enabled = true,
                hostListMode = MediaHostListMode.WHITELIST,
                hosts = listOf("allowed.example.com"),
                blockPrivateIps = false,
                allowLocalFiles = false,
            )
        )

        assertIs<MediaUrlPolicyResult.Reject>(verdict)
    }

    @Test
    fun `local files are blocked by default`() {
        val verdict = MediaUrlPolicy.evaluate(
            "file:///tmp/test.mp3",
            MediaUrlAccessPolicy(
                enabled = true,
                hostListMode = MediaHostListMode.BLACKLIST,
                hosts = emptyList(),
                blockPrivateIps = true,
                allowLocalFiles = false,
            )
        )

        assertIs<MediaUrlPolicyResult.Reject>(verdict)
    }

    @Test
    fun `disabling host checks does not bypass local file opt in`() {
        val verdict = MediaUrlPolicy.evaluate(
            "file:///tmp/test.mp3",
            MediaUrlAccessPolicy(
                enabled = false,
                hostListMode = MediaHostListMode.BLACKLIST,
                hosts = emptyList(),
                blockPrivateIps = false,
                allowLocalFiles = false,
            )
        )

        assertIs<MediaUrlPolicyResult.Reject>(verdict)
    }
}
