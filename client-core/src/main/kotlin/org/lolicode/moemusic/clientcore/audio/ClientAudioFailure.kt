package org.lolicode.moemusic.clientcore.audio

import org.lolicode.lavaplayer.tools.FriendlyException
import org.lolicode.lavaplayer.tools.io.HttpClientTools
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException

data class ClientAudioFailure(
    val message: String,
    val reason: ClientAudioFailureReason,
    val recoverability: ClientAudioFailureRecoverability,
    val httpStatusCode: Int? = null,
    val cause: Throwable? = null,
) {
    companion object {
        fun noMatches(url: String): ClientAudioFailure =
            ClientAudioFailure(
                message = "No audio source found at: $url",
                reason = ClientAudioFailureReason.NO_MATCHES,
                recoverability = ClientAudioFailureRecoverability.PERMANENT,
            )

        fun trackStuck(thresholdMs: Long): ClientAudioFailure =
            ClientAudioFailure(
                message = "Playback stalled for ${thresholdMs}ms.",
                reason = ClientAudioFailureReason.STUCK,
                recoverability = ClientAudioFailureRecoverability.RETRYABLE,
            )

        fun network(message: String, cause: Throwable? = null): ClientAudioFailure =
            ClientAudioFailure(
                message = message,
                reason = ClientAudioFailureReason.NETWORK,
                recoverability = ClientAudioFailureRecoverability.RETRYABLE,
                cause = cause,
            )

        fun decoder(message: String, cause: Throwable? = null): ClientAudioFailure =
            ClientAudioFailure(
                message = message,
                reason = ClientAudioFailureReason.DECODER,
                recoverability = ClientAudioFailureRecoverability.RETRYABLE,
                cause = cause,
            )

        fun unsupported(message: String, cause: Throwable? = null): ClientAudioFailure =
            ClientAudioFailure(
                message = message,
                reason = ClientAudioFailureReason.UNSUPPORTED_FORMAT,
                recoverability = ClientAudioFailureRecoverability.PERMANENT,
                cause = cause,
            )

        fun httpStatus(message: String, statusCode: Int, cause: Throwable? = null): ClientAudioFailure =
            ClientAudioFailure(
                message = message,
                reason = ClientAudioFailureReason.HTTP_STATUS,
                recoverability = httpStatusRecoverability(statusCode),
                httpStatusCode = statusCode,
                cause = cause,
            )

        fun fromFriendlyException(prefix: String, exception: FriendlyException): ClientAudioFailure {
            val statusCode = findHttpStatusCode(exception)
            if (statusCode != null) {
                return ClientAudioFailure(
                    message = prefix + exception.message.orEmpty(),
                    reason = ClientAudioFailureReason.HTTP_STATUS,
                    recoverability = httpStatusRecoverability(statusCode),
                    httpStatusCode = statusCode,
                    cause = exception,
                )
            }

            val reason = when {
                exception.findCause<URISyntaxException>() != null -> ClientAudioFailureReason.INVALID_URL
                exception.findNetworkCause() != null || exception.severity == FriendlyException.Severity.SUSPICIOUS ->
                    ClientAudioFailureReason.NETWORK
                exception.severity == FriendlyException.Severity.COMMON -> ClientAudioFailureReason.UNSUPPORTED_FORMAT
                exception.severity == FriendlyException.Severity.FAULT -> ClientAudioFailureReason.DECODER
                else -> ClientAudioFailureReason.UNKNOWN
            }
            return ClientAudioFailure(
                message = prefix + exception.message.orEmpty(),
                reason = reason,
                recoverability = recoverabilityFor(exception.severity, reason),
                cause = exception,
            )
        }

        private fun recoverabilityFor(
            severity: FriendlyException.Severity,
            reason: ClientAudioFailureReason,
        ): ClientAudioFailureRecoverability =
            when {
                reason == ClientAudioFailureReason.INVALID_URL -> ClientAudioFailureRecoverability.PERMANENT
                reason == ClientAudioFailureReason.UNSUPPORTED_FORMAT -> ClientAudioFailureRecoverability.PERMANENT
                reason == ClientAudioFailureReason.NETWORK -> ClientAudioFailureRecoverability.RETRYABLE
                severity == FriendlyException.Severity.COMMON -> ClientAudioFailureRecoverability.PERMANENT
                else -> ClientAudioFailureRecoverability.RETRYABLE
            }

        private fun httpStatusRecoverability(statusCode: Int): ClientAudioFailureRecoverability =
            when {
                statusCode in setOf(401, 403, 404, 410) -> ClientAudioFailureRecoverability.PERMANENT
                statusCode == 408 || statusCode == 409 || statusCode == 425 || statusCode == 429 -> ClientAudioFailureRecoverability.RETRYABLE
                statusCode in 500..599 -> ClientAudioFailureRecoverability.RETRYABLE
                else -> ClientAudioFailureRecoverability.PERMANENT
            }

        private fun findHttpStatusCode(throwable: Throwable): Int? {
            var current: Throwable? = throwable
            while (current != null) {
                val message = current.message.orEmpty()
                // LavaPlayer exposes HTTP status failures only through a small set of exact cause messages.
                STATUS_CODE_PATTERNS.firstNotNullOfOrNull { pattern ->
                    pattern.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull()
                }?.let { return it }
                current = current.cause
            }
            return null
        }

        private inline fun <reified T : Throwable> Throwable.findCause(): T? {
            var current: Throwable? = this
            while (current != null) {
                if (current is T) return current
                current = current.cause
            }
            return null
        }

        private fun Throwable.findNetworkCause(): Throwable? {
            var current: Throwable? = this
            while (current != null) {
                if (HttpClientTools.isRetriableNetworkException(current) ||
                    current is SocketTimeoutException ||
                    current is ConnectException ||
                    current is NoRouteToHostException ||
                    current is UnknownHostException ||
                    current is SocketException ||
                    current is InterruptedIOException
                ) {
                    return current
                }
                current = current.cause
            }
            return null
        }

        private val STATUS_CODE_PATTERNS = listOf(
            Regex("""Status code (\d{3})"""),
            Regex("""Not success status code: (\d{3})"""),
        )
    }
}

enum class ClientAudioFailureReason {
    NO_MATCHES,
    INVALID_URL,
    UNSUPPORTED_FORMAT,
    HTTP_STATUS,
    NETWORK,
    DECODER,
    STUCK,
    UNKNOWN,
}

enum class ClientAudioFailureRecoverability {
    RETRYABLE,
    PERMANENT,
}
