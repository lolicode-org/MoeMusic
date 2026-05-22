package org.lolicode.moemusic.core.error

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.RateLimitedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserFacingErrorsTest {

    @Test
    fun `expected user-facing exceptions are detected through wrappers`() {
        val wrapped = IllegalStateException("wrapper", RateLimitedException())

        assertTrue(UserFacingErrors.isExpected(wrapped))
        assertEquals("error.moemusic.rate_limit", (UserFacingErrors.classify(wrapped) as LocalizedText.Key).key)
    }

    @Test
    fun `unexpected exceptions are classified as internal`() {
        val error = IllegalStateException("boom")

        assertFalse(UserFacingErrors.isExpected(error))
        assertEquals("error.moemusic.internal", (UserFacingErrors.classify(error) as LocalizedText.Key).key)
    }
}
