package org.lolicode.moemusic.api.model

/**
 * Optional source-supplied loudness metadata for a track.
 *
 * Sources may provide integrated LUFS, peak information, or both. Clients can still attenuate
 * using LUFS alone, while safe boosting requires both LUFS and a valid peak reading.
 */
public sealed interface LoudnessInfo {
    public val integratedLufs: Double? get() = null
    public val peak: PeakInfo? get() = null

    public fun toBuilder(): LoudnessInfoBuilder
}

/** Mutable builder for [LoudnessInfo]. */
public class LoudnessInfoBuilder internal constructor() {
    public var integratedLufs: Double? = null
    public var peak: PeakInfo? = null

    public fun build(): LoudnessInfo = LoudnessInfoImpl(
        integratedLufs = integratedLufs,
        peak = peak,
    )
}

/** Build a [LoudnessInfo] from an optional [configure] block. */
public fun LoudnessInfo(
    configure: LoudnessInfoBuilder.() -> Unit = {},
): LoudnessInfo = LoudnessInfoBuilder().apply(configure).build()

/** Returns a copy of this loudness metadata with [configure] applied to a seeded builder. */
public fun LoudnessInfo.copy(configure: LoudnessInfoBuilder.() -> Unit): LoudnessInfo =
    toBuilder().apply(configure).build()

internal data class LoudnessInfoImpl(
    override val integratedLufs: Double?,
    override val peak: PeakInfo?,
) : LoudnessInfo {
    override fun toBuilder(): LoudnessInfoBuilder = LoudnessInfoBuilder().also {
        it.integratedLufs = integratedLufs
        it.peak = peak
    }
}

/**
 * Peak reading associated with [LoudnessInfo].
 *
 * [amplitudeLinear] uses the common `0.0 .. 1.0` linear full-scale convention.
 */
public sealed interface PeakInfo {
    public val amplitudeLinear: Double
    public val kind: PeakKind get() = PeakKind.UNKNOWN

    public fun toBuilder(): PeakInfoBuilder
}

/** Mutable builder for [PeakInfo]. */
public class PeakInfoBuilder internal constructor(
    public var amplitudeLinear: Double,
) {
    public var kind: PeakKind = PeakKind.UNKNOWN

    public fun build(): PeakInfo = PeakInfoImpl(
        amplitudeLinear = amplitudeLinear,
        kind = kind,
    )
}

/** Build a [PeakInfo] from its required [amplitudeLinear] plus an optional [configure] block. */
public fun PeakInfo(
    amplitudeLinear: Double,
    configure: PeakInfoBuilder.() -> Unit = {},
): PeakInfo = PeakInfoBuilder(amplitudeLinear).apply(configure).build()

/** Returns a copy of this peak metadata with [configure] applied to a seeded builder. */
public fun PeakInfo.copy(configure: PeakInfoBuilder.() -> Unit): PeakInfo =
    toBuilder().apply(configure).build()

internal data class PeakInfoImpl(
    override val amplitudeLinear: Double,
    override val kind: PeakKind,
) : PeakInfo {
    override fun toBuilder(): PeakInfoBuilder = PeakInfoBuilder(amplitudeLinear).also {
        it.kind = kind
    }
}

/**
 * Declares what kind of peak reading a source is reporting.
 *
 * `UNKNOWN` should be used when the source does not specify whether the value is sample peak or
 * true peak. Clients must treat `UNKNOWN` conservatively.
 */
public enum class PeakKind {
    UNKNOWN,
    SAMPLE,
    TRUE,
}

private const val MIN_VALID_INTEGRATED_LUFS: Double = -70.0
private const val MAX_VALID_INTEGRATED_LUFS: Double = 0.0

/**
 * Validate and normalize this loudness metadata.
 *
 * Invalid subfields are dropped. Returns null when neither LUFS nor peak survives validation.
 */
public fun LoudnessInfo.normalizedOrNull(): LoudnessInfo? {
    val normalizedLufs = integratedLufs
        ?.takeIf(Double::isFinite)
        ?.takeIf { it in MIN_VALID_INTEGRATED_LUFS..MAX_VALID_INTEGRATED_LUFS }
    val normalizedPeak = peak?.normalizedOrNull()
    if (normalizedLufs == null && normalizedPeak == null) return null
    return LoudnessInfo {
        integratedLufs = normalizedLufs
        peak = normalizedPeak
    }
}

/** Validate and normalize this peak reading. Returns null when the amplitude is invalid. */
public fun PeakInfo.normalizedOrNull(): PeakInfo? {
    val normalizedAmplitude = amplitudeLinear
        .takeIf(Double::isFinite)
        ?.takeIf { it > 0.0 && it <= 1.0 }
        ?: return null
    return PeakInfo(normalizedAmplitude) {
        kind = this@normalizedOrNull.kind
    }
}

/**
 * Merge [update] into this loudness metadata fieldwise.
 *
 * Non-null subfields from [update] replace only that subfield. Missing or invalid update data
 * leaves the existing validated value unchanged.
 */
public fun LoudnessInfo?.mergedWith(update: LoudnessInfo?): LoudnessInfo? {
    val normalizedExisting = this?.normalizedOrNull()
    val normalizedUpdate = update?.normalizedOrNull()
    val mergedLufs = normalizedUpdate?.integratedLufs ?: normalizedExisting?.integratedLufs
    val mergedPeak = normalizedUpdate?.peak ?: normalizedExisting?.peak
    if (mergedLufs == null && mergedPeak == null) return null
    return LoudnessInfo {
        integratedLufs = mergedLufs
        peak = mergedPeak
    }
}
