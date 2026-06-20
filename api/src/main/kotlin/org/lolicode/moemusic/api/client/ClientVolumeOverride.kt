package org.lolicode.moemusic.api.client

/**
 * Transient client-local volume override applied on top of the configured MoeMusic volume.
 *
 * These overrides are runtime-only and must not be persisted into MoeMusic's config file.
 *
 * Current semantics are attenuation-oriented: the effective output volume is the lowest resolved
 * percent across all active overrides and the configured/base volume. In particular, overrides do
 * not raise playback above the configured MoeMusic volume.
 */
public sealed interface ClientVolumeOverride {

    /**
     * Cap the effective MoeMusic volume at [percent] in the normalized `0..100` range.
     *
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     *
     * The actual output is still bounded by the configured/base MoeMusic volume, so this behaves
     * as `min(configuredVolumePercent, percent)`.
     */
    public data class FixedPercent(
        val percent: Int,
    ) : ClientVolumeOverride

    /**
     * Set the effective MoeMusic volume to [percent] of the current configured/base volume.
     *
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     *
     * For example, `PercentOfConfiguredVolume(40)` turns a configured volume of `75` into an
     * effective runtime volume of `30`.
     */
    public data class PercentOfConfiguredVolume(
        val percent: Int,
    ) : ClientVolumeOverride
}
