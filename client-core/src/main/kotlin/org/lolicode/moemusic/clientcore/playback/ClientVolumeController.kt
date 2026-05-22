package org.lolicode.moemusic.clientcore.playback

import org.lolicode.moemusic.api.client.ClientVolumeOverride
import org.lolicode.moemusic.core.config.ClientVolume
import kotlin.math.roundToInt

/**
 * Shared client-side controller for MoeMusic volume state.
 *
 * The configured volume is the user-owned value that should be persisted to config. Transient
 * overrides are runtime-only adjustments such as ducking that lower the effective sink gain
 * without mutating the configured volume. Resolution is attenuation-oriented: the effective output
 * is the minimum of the configured volume and all resolved override percents.
 */
class ClientVolumeController(
    private val applyGain: (Float) -> Unit,
) {
    private val lock = Any()

    private val transientOverrides: LinkedHashMap<String, ClientVolumeOverride> = linkedMapOf()
    private var configuredVolumePercentState: Int = ClientVolume.DEFAULT_PERCENT

    val configuredVolumePercent: Int
        get() = synchronized(lock) { configuredVolumePercentState }

    val effectiveVolumePercent: Int
        get() = synchronized(lock) { computeEffectiveVolumePercentLocked() }

    fun setConfiguredVolumePercent(percent: Int) {
        synchronized(lock) {
            configuredVolumePercentState = ClientVolume.normalizePercent(percent)
            applyEffectiveGainLocked()
        }
    }

    fun setTransientOverride(ownerId: String, override: ClientVolumeOverride) {
        synchronized(lock) {
            transientOverrides[ownerId] = override
            applyEffectiveGainLocked()
        }
    }

    fun clearTransientOverride(ownerId: String) {
        synchronized(lock) {
            if (transientOverrides.remove(ownerId) == null) return
            applyEffectiveGainLocked()
        }
    }

    fun clearAllTransientOverrides() {
        synchronized(lock) {
            if (transientOverrides.isEmpty()) return
            transientOverrides.clear()
            applyEffectiveGainLocked()
        }
    }

    private fun applyEffectiveGainLocked() {
        applyGain(ClientVolume.percentToGain(computeEffectiveVolumePercentLocked()))
    }

    private fun computeEffectiveVolumePercentLocked(): Int {
        val configured = configuredVolumePercentState
        return transientOverrides.values
            .fold(configured) { currentMin, override ->
                minOf(currentMin, resolveOverridePercent(configured, override))
            }
    }

    private fun resolveOverridePercent(
        configuredPercent: Int,
        override: ClientVolumeOverride,
    ): Int = when (override) {
        is ClientVolumeOverride.FixedPercent ->
            ClientVolume.normalizePercent(override.percent)

        is ClientVolumeOverride.PercentOfConfiguredVolume ->
            ClientVolume.normalizePercent((configuredPercent * (override.percent / 100.0)).roundToInt())
    }
}
