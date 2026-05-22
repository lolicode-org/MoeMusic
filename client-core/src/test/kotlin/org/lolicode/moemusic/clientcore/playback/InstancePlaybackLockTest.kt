package org.lolicode.moemusic.clientcore.playback

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstancePlaybackLockTest {

    @Test
    fun `resolve lock path uses platform specific state directories`() {
        assertEquals(
            "C:/Users/Alice/AppData/Roaming/moemusic/instance-playback.lock",
            slashPath(
                InstancePlaybackLock.resolveLockPath(
                    osName = "Windows 11",
                    env = mapOf("APPDATA" to "C:\\Users\\Alice\\AppData\\Roaming"),
                    userHome = "C:\\Users\\Alice",
                )
            ),
        )
        assertEquals(
            "/home/alice/.local/state/moemusic/instance-playback.lock",
            slashPath(
                InstancePlaybackLock.resolveLockPath(
                    osName = "Linux",
                    env = emptyMap(),
                    userHome = "/home/alice",
                )
            ),
        )
        assertEquals(
            "/Users/alice/Library/Application Support/moemusic/instance-playback.lock",
            slashPath(
                InstancePlaybackLock.resolveLockPath(
                    osName = "Mac OS X",
                    env = emptyMap(),
                    userHome = "/Users/alice",
                )
            ),
        )
    }

    @Test
    fun `lock can be acquired released and probed`() {
        val tempDir = createTempDirectory("moemusic-instance-lock")
        val lockPath = tempDir.resolve("instance-playback.lock")

        InstancePlaybackLock.release()
        assertTrue(InstancePlaybackLock.probeAvailable(lockPath))
        assertFalse(InstancePlaybackLock.isHeld())
        assertTrue(InstancePlaybackLock.tryAcquire(lockPath))
        assertTrue(InstancePlaybackLock.isHeld())
        InstancePlaybackLock.release()
        assertFalse(InstancePlaybackLock.isHeld())
        assertTrue(InstancePlaybackLock.probeAvailable(lockPath))
        assertFalse(InstancePlaybackLock.isHeld())
    }

    @Test
    fun `lock acquisition fails while another channel owns the file lock`() {
        val tempDir = createTempDirectory("moemusic-instance-lock-external")
        val lockPath = tempDir.resolve("instance-playback.lock")

        InstancePlaybackLock.release()
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val externalLock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            checkNotNull(externalLock)
            assertFalse(InstancePlaybackLock.tryAcquire(lockPath))
            externalLock.release()
        }
        assertTrue(InstancePlaybackLock.tryAcquire(lockPath))
        InstancePlaybackLock.release()
    }

    private fun slashPath(path: Path): String = path.toString().replace('\\', '/')
}
