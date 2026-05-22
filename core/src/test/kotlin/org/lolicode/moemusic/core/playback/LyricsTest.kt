package org.lolicode.moemusic.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsTest {

    @Test
    fun `parseLyrics supports standard timestamps`() {
        val parsed = parseLyrics("[00:12.34]Hello\n[01:02.345]World")

        assertNotNull(parsed)
        assertEquals(listOf(12_340L, 62_345L), parsed.lines.map { it.startMs })
        assertEquals(listOf("Hello", "World"), parsed.lines.map { it.text })
    }

    @Test
    fun `parseLyrics ignores metadata tags and applies offset`() {
        val parsed = parseLyrics("[ti:Song]\n[offset:250]\n[00:10.00]Line")

        assertNotNull(parsed)
        assertEquals(250L, parsed.offsetMs)
        assertEquals("Line", parsed.lineAt(10_250L)?.text)
    }

    @Test
    fun `parseLyrics supports multiple timestamps per line`() {
        val parsed = parseLyrics("[00:01.00][00:02.50]Echo")

        assertNotNull(parsed)
        assertEquals(listOf(1_000L, 2_500L), parsed.lines.map { it.startMs })
        assertEquals(listOf("Echo", "Echo"), parsed.lines.map { it.text })
    }

    @Test
    fun `parsed lyrics line lookup stays stable across boundaries`() {
        val parsed = parseLyrics("[00:01.00]A\n[00:03.00]B\n[00:05.00]C")

        assertNotNull(parsed)
        assertNull(parsed.lineAt(999L))
        assertEquals("A", parsed.lineAt(1_000L)?.text)
        assertEquals("A", parsed.lineAt(2_999L)?.text)
        assertEquals("B", parsed.lineAt(3_000L)?.text)
        assertEquals("C", parsed.nextLineAfter(3_000L)?.text)
        assertNull(parsed.nextLineAfter(5_000L))
    }
}
