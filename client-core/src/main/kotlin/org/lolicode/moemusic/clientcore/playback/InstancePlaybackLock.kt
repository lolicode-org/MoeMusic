package org.lolicode.moemusic.clientcore.playback

import org.lolicode.moemusic.core.platform.PlatformDirectories
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Cross-instance playback lock used to ensure only one local Minecraft instance owns MoeMusic
 * audio output on the same device at a time.
 *
 * The lock file contents are diagnostic only; [FileChannel.tryLock] is the authoritative gate.
 */
object InstancePlaybackLock {

    private val logger = LoggerFactory.getLogger(InstancePlaybackLock::class.java)
    private val monitor = Any()

    @Volatile
    private var heldChannel: FileChannel? = null

    @Volatile
    private var heldLock: FileLock? = null

    fun isHeld(): Boolean = heldLock?.isValid == true

    fun tryAcquire(): Boolean = tryAcquire(resolveLockPath())

    fun release() {
        synchronized(monitor) {
            releaseLocked()
        }
    }

    fun probeAvailable(): Boolean = probeAvailable(resolveLockPath())

    internal fun tryAcquire(lockPath: Path): Boolean {
        synchronized(monitor) {
            if (heldLock?.isValid == true) return true

            releaseLocked()
            return runCatching {
                Files.createDirectories(lockPath.parent)
                val channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    false
                } else {
                    heldChannel = channel
                    heldLock = lock
                    writeDiagnosticMetadata(channel)
                    true
                }
            }.getOrElse { error ->
                logger.warn("Failed to acquire MoeMusic instance lock at {}: {}", lockPath, error.message)
                releaseLocked()
                false
            }
        }
    }

    internal fun probeAvailable(lockPath: Path): Boolean {
        if (isHeld()) return true

        return runCatching {
            Files.createDirectories(lockPath.parent)
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    false
                } else {
                    lock.release()
                    true
                }
            }
        }.getOrElse { error ->
            logger.debug("Failed to probe MoeMusic instance lock at {}: {}", lockPath, error.message)
            false
        }
    }

    internal fun resolveLockPath(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home").orEmpty(),
    ): Path = resolveStateDirectory(osName, env, userHome).resolve("moemusic").resolve("instance-playback.lock")

    internal fun resolveStateDirectory(
        osName: String,
        env: Map<String, String>,
        userHome: String,
    ): Path {
        val normalizedOs = osName.lowercase()
        return when {
            "win" in normalizedOs -> {
                val base = env["APPDATA"].orEmpty().ifBlank { env["LOCALAPPDATA"].orEmpty() }
                if (base.isNotBlank()) Paths.get(base) else fallbackHome(env, userHome, preferWindowsHome = true)
            }
            "mac" in normalizedOs || "darwin" in normalizedOs ->
                fallbackHome(env, userHome).resolve("Library").resolve("Application Support")
            else -> {
                val xdgStateHome = env["XDG_STATE_HOME"].orEmpty()
                if (xdgStateHome.isNotBlank()) Paths.get(xdgStateHome)
                else fallbackHome(env, userHome).resolve(".local").resolve("state")
            }
        }
    }

    private fun fallbackHome(
        env: Map<String, String>,
        userHome: String,
        preferWindowsHome: Boolean = false,
    ): Path {
        PlatformDirectories.homeDirectory(env, userHome, preferWindowsHome)?.let { return it }
        val tmpDir = System.getProperty("java.io.tmpdir").orEmpty()
        return if (tmpDir.isNotBlank()) Paths.get(tmpDir) else Paths.get(".")
    }

    private fun writeDiagnosticMetadata(channel: FileChannel) {
        val metadata = buildString {
            append("pid=")
            append(runCatching { ProcessHandle.current().pid() }.getOrNull() ?: "unknown")
            append('\n')
            append("acquired_at=")
            append(Instant.now())
            append('\n')
        }.toByteArray(StandardCharsets.UTF_8)

        channel.truncate(0L)
        channel.position(0L)
        channel.write(ByteBuffer.wrap(metadata))
        channel.force(true)
    }

    private fun releaseLocked() {
        runCatching { heldLock?.release() }
            .onFailure { logger.debug("Failed to release MoeMusic instance lock: {}", it.message) }
        heldLock = null

        runCatching { heldChannel?.close() }
            .onFailure { logger.debug("Failed to close MoeMusic instance lock channel: {}", it.message) }
        heldChannel = null
    }
}
