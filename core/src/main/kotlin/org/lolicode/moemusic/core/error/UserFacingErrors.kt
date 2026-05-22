package org.lolicode.moemusic.core.error

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.UserFacingException

/** Maps internal exceptions to localized, client-safe feedback. */
object UserFacingErrors {

    /** Whether [error] already represents an expected, user-visible failure. */
    fun isExpected(error: Throwable): Boolean =
        firstUserFacing(error) != null

    fun classify(error: Throwable): LocalizedText =
        firstUserFacing(error)
            ?.userMessage
            ?: LocalizedText.key("error.moemusic.internal")

    private fun firstUserFacing(error: Throwable): UserFacingException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<UserFacingException>()
            .firstOrNull()
}
