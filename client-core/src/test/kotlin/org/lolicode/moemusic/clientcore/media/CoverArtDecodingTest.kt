package org.lolicode.moemusic.clientcore.media

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverArtDecodingTest {

    @Test
    fun `decode downscale stays at one when source is already small enough`() {
        assertEquals(
            1,
            computeCoverDecodeDownscaleFactor(
                sourceWidth = 1_024,
                sourceHeight = 768,
                maxTextureSize = 512,
                maxDecodeDownscaleFactor = 16,
            )
        )
    }

    @Test
    fun `decode downscale rounds up to keep oversized sources near target`() {
        assertEquals(
            3,
            computeCoverDecodeDownscaleFactor(
                sourceWidth = 2_049,
                sourceHeight = 2_049,
                maxTextureSize = 512,
                maxDecodeDownscaleFactor = 16,
            )
        )
    }

    @Test
    fun `decode downscale honors configured maximum`() {
        assertEquals(
            4,
            computeCoverDecodeDownscaleFactor(
                sourceWidth = 16_384,
                sourceHeight = 16_384,
                maxTextureSize = 512,
                maxDecodeDownscaleFactor = 4,
            )
        )
    }
}
