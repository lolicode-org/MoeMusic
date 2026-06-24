package org.lolicode.moemusic.clientcore.audio

import org.lolicode.moemusic.api.model.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientAudioPlayerRuntimeTest {

    @Test
    fun `play wires loader output and gain through shared runtime`() {
        val ringBuffer = PcmRingBuffer()
        val loader = FakeTrackLoader()
        val output = FakeAudioOutput()
        val runtime = ClientAudioPlayerRuntime(ringBuffer, loader, output)

        runtime.setVolume(0.35f)
        runtime.setNormalizationGain(0.5f)
        runtime.play(PlaybackResource("https://example.com/a.mp3"), seekMs = 1_250)

        assertEquals(1, output.stopCalls)
        assertEquals(listOf(0.35f, 0.175f, 0.175f), output.gains)
        assertEquals(listOf(1_250L), output.startPositions)
        assertEquals("https://example.com/a.mp3", loader.loadedPlayback?.url)
        assertEquals(1_250L, loader.loadedSeekMs)
        assertTrue(runtime.isPlaying)
        runtime.reportPlaybackPosition(2_000L)
        assertEquals(2_000L, runtime.currentPositionMs())
    }

    @Test
    fun `normalization gain may boost but final output stays clamped`() {
        val output = FakeAudioOutput()
        val runtime = ClientAudioPlayerRuntime(PcmRingBuffer(), FakeTrackLoader(), output)

        runtime.setVolume(0.4f)
        runtime.setNormalizationGain(1.2f)

        assertEquals(0.48f, output.gains.last(), 0.000001f)

        runtime.setNormalizationGain(10.0f)

        assertEquals(1.0f, output.gains.last(), 0.000001f)
    }

    @Test
    fun `pause resume and stop update runtime state`() {
        val runtime = ClientAudioPlayerRuntime(PcmRingBuffer(), FakeTrackLoader(), FakeAudioOutput())

        runtime.play(PlaybackResource("https://example.com/a.mp3"))
        runtime.pause()
        assertFalse(runtime.isPlaying)
        runtime.resume()
        assertTrue(runtime.isPlaying)
        runtime.stop()
        assertFalse(runtime.isPlaying)
        assertEquals(0L, runtime.currentPositionMs())
    }

    @Test
    fun `load failure clears playback state and reports error`() {
        val loader = FakeTrackLoader()
        val output = FakeAudioOutput()
        val runtime = ClientAudioPlayerRuntime(PcmRingBuffer(), loader, output)
        val errors = mutableListOf<String>()

        runtime.play(PlaybackResource("https://example.com/a.mp3"), onError = errors::add)
        loader.failLast("temporary network error")

        assertEquals(listOf("temporary network error"), errors)
        assertFalse(runtime.isPlaying)
        assertEquals(0L, runtime.currentPositionMs())
        assertEquals(2, output.stopCalls)
    }

    @Test
    fun `reload restore replays saved playback from last reported position`() {
        val loader = FakeTrackLoader()
        val output = FakeAudioOutput()
        val runtime = ClientAudioPlayerRuntime(PcmRingBuffer(), loader, output)
        val playback = PlaybackResource("https://example.com/a.mp3")
        val errors = mutableListOf<String>()

        runtime.play(playback, onError = errors::add)
        runtime.reportPlaybackPosition(4_321L)
        runtime.saveStateForReload()
        runtime.stop()
        runtime.restoreAfterReload()
        loader.failLast("restore failed")

        assertEquals(2, loader.loads.size)
        assertEquals(4_321L, loader.loads.last().seekMs)
        assertEquals(listOf(0L, 4_321L), output.startPositions)
        assertEquals(listOf("restore failed"), errors)
        assertFalse(runtime.isPlaying)
    }

    private class FakeTrackLoader : ClientTrackLoader {
        data class Load(val playback: PlaybackResource, val seekMs: Long)

        val loads = mutableListOf<Load>()
        private val errorCallbacks = mutableListOf<(String) -> Unit>()
        var stopCalls: Int = 0

        val loadedPlayback: PlaybackResource?
            get() = loads.lastOrNull()?.playback

        val loadedSeekMs: Long?
            get() = loads.lastOrNull()?.seekMs

        override fun load(
            playback: PlaybackResource,
            ringBuffer: PcmRingBuffer,
            seekMs: Long,
            onError: (String) -> Unit,
        ) {
            loads += Load(playback, seekMs)
            errorCallbacks += onError
        }

        override fun stop(ringBuffer: PcmRingBuffer?) {
            stopCalls++
        }

        fun failLast(message: String) {
            errorCallbacks.last()(message)
        }
    }

    private class FakeAudioOutput : StreamingAudioOutput {
        val startPositions = mutableListOf<Long>()
        val gains = mutableListOf<Float>()
        var stopCalls: Int = 0
        var seekCalls: Int = 0

        override fun start(startPositionMs: Long) {
            startPositions += startPositionMs
        }

        override fun stop() {
            stopCalls++
        }

        override fun seek() {
            seekCalls++
        }

        override fun setGain(gain: Float) {
            gains += gain
        }
    }
}
