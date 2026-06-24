package org.lolicode.moemusic.clientcore.audio

import org.lolicode.lavaplayer.tools.FriendlyException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientAudioFailureTest {

    @Test
    fun `http status cause messages classify retryability by status code`() {
        val retryable = ClientAudioFailure.fromFriendlyException(
            prefix = "Failed to load track: ",
            exception = FriendlyException(
                "That URL is not playable.",
                FriendlyException.Severity.COMMON,
                IllegalStateException("Status code 503"),
            ),
        )

        assertEquals(ClientAudioFailureReason.HTTP_STATUS, retryable.reason)
        assertEquals(ClientAudioFailureRecoverability.RETRYABLE, retryable.recoverability)
        assertEquals(503, retryable.httpStatusCode)

        val permanent = ClientAudioFailure.fromFriendlyException(
            prefix = "Failed to load track: ",
            exception = FriendlyException(
                "That URL is not playable.",
                FriendlyException.Severity.COMMON,
                IllegalStateException("Not success status code: 404"),
            ),
        )

        assertEquals(ClientAudioFailureReason.HTTP_STATUS, permanent.reason)
        assertEquals(ClientAudioFailureRecoverability.PERMANENT, permanent.recoverability)
        assertEquals(404, permanent.httpStatusCode)
    }

    @Test
    fun `friendly exception severity and causes classify retryability without message substring matching`() {
        val suspicious = ClientAudioFailure.fromFriendlyException(
            prefix = "",
            exception = FriendlyException(
                "Connecting to the URL failed.",
                FriendlyException.Severity.SUSPICIOUS,
                SocketTimeoutException("Read timed out"),
            ),
        )

        assertEquals(ClientAudioFailureReason.NETWORK, suspicious.reason)
        assertEquals(ClientAudioFailureRecoverability.RETRYABLE, suspicious.recoverability)

        val invalidUrl = ClientAudioFailure.fromFriendlyException(
            prefix = "",
            exception = FriendlyException(
                "Not a valid URL.",
                FriendlyException.Severity.COMMON,
                URISyntaxException("://bad", "bad syntax"),
            ),
        )

        assertEquals(ClientAudioFailureReason.INVALID_URL, invalidUrl.reason)
        assertEquals(ClientAudioFailureRecoverability.PERMANENT, invalidUrl.recoverability)

        val messageOnlyTemporary = ClientAudioFailure.fromFriendlyException(
            prefix = "",
            exception = FriendlyException(
                "temporary network timeout",
                FriendlyException.Severity.COMMON,
                null,
            ),
        )

        assertEquals(ClientAudioFailureReason.UNSUPPORTED_FORMAT, messageOnlyTemporary.reason)
        assertEquals(ClientAudioFailureRecoverability.PERMANENT, messageOnlyTemporary.recoverability)
        assertNull(messageOnlyTemporary.httpStatusCode)
    }
}
