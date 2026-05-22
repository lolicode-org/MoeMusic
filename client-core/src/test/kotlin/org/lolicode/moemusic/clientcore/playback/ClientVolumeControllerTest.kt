package org.lolicode.moemusic.clientcore.playback

import org.lolicode.moemusic.api.client.ClientVolumeOverride
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientVolumeControllerTest {

    @Test
    fun `fixed override clamps effective volume without mutating configured volume`() {
        var appliedGain = -1f
        val controller = ClientVolumeController { appliedGain = it }

        controller.setConfiguredVolumePercent(80)
        controller.setTransientOverride("ducking", ClientVolumeOverride.FixedPercent(35))

        assertEquals(80, controller.configuredVolumePercent)
        assertEquals(35, controller.effectiveVolumePercent)
        assertEquals(0.35f, appliedGain)
    }

    @Test
    fun `fixed override does not raise playback above configured volume`() {
        val controller = ClientVolumeController { }

        controller.setConfiguredVolumePercent(36)
        controller.setTransientOverride("ducking", ClientVolumeOverride.FixedPercent(86))

        assertEquals(36, controller.configuredVolumePercent)
        assertEquals(36, controller.effectiveVolumePercent)
    }

    @Test
    fun `relative override resolves against configured volume`() {
        val controller = ClientVolumeController { }

        controller.setConfiguredVolumePercent(75)
        controller.setTransientOverride("ducking", ClientVolumeOverride.PercentOfConfiguredVolume(40))

        assertEquals(75, controller.configuredVolumePercent)
        assertEquals(30, controller.effectiveVolumePercent)
    }

    @Test
    fun `lowest active override wins`() {
        val controller = ClientVolumeController { }

        controller.setConfiguredVolumePercent(90)
        controller.setTransientOverride("ducking", ClientVolumeOverride.FixedPercent(50))
        controller.setTransientOverride("focus", ClientVolumeOverride.PercentOfConfiguredVolume(40))

        assertEquals(36, controller.effectiveVolumePercent)
    }

    @Test
    fun `clearing one owner keeps other overrides active`() {
        val controller = ClientVolumeController { }

        controller.setConfiguredVolumePercent(60)
        controller.setTransientOverride("ducking", ClientVolumeOverride.FixedPercent(20))
        controller.setTransientOverride("focus", ClientVolumeOverride.FixedPercent(45))

        controller.clearTransientOverride("ducking")

        assertEquals(45, controller.effectiveVolumePercent)
    }

    @Test
    fun `changing configured volume recomputes relative override`() {
        val controller = ClientVolumeController { }

        controller.setConfiguredVolumePercent(50)
        controller.setTransientOverride("ducking", ClientVolumeOverride.PercentOfConfiguredVolume(40))
        assertEquals(20, controller.effectiveVolumePercent)

        controller.setConfiguredVolumePercent(80)
        assertEquals(32, controller.effectiveVolumePercent)
    }
}
