package org.lolicode.moemusic.core.config

import kotlin.math.roundToInt

/**
 * Shared client volume conversions.
 *
 * MoeMusic stores user-facing volume as an integer percentage in the `0..100` range and only
 * converts to OpenAL gain floats at the audio boundary.
 */
object ClientVolume {

    const val MIN_PERCENT: Int = 0
    const val MAX_PERCENT: Int = 100
    const val DEFAULT_PERCENT: Int = 50

    fun normalizePercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun percentToGain(percent: Int): Float = normalizePercent(percent) / 100.0f

    fun gainToPercent(gain: Float): Int =
        (gain.coerceIn(0.0f, 1.0f) * MAX_PERCENT).roundToInt()
}
