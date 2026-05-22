package org.lolicode.moemusic.clientcore.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Bounded circular byte buffer for streaming PCM audio data.
 *
 * - **Producer** (LavaPlayer decode thread): calls [write].
 * - **Consumer** (OpenAL streaming thread): calls [read].
 * - [reset] is called on seek to discard stale audio.
 *
 * Byte ordering and sample format must be consistent with the [LavaPlayerTrackLoader] output
 * (stereo, 16-bit signed LE, 48000 Hz by default).
 *
 * @param capacity Total buffer capacity in bytes. Default: ~480 ms at 48kHz stereo 16-bit.
 */
class PcmRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    private val buffer = ByteArray(capacity)
    private var readPos = 0
    private var writePos = 0
    @Volatile private var available = 0

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    @Volatile var closed = false
        private set

    /**
     * Write up to `data.size` bytes. Returns bytes actually written.
     * Blocks if the buffer is full until space is available or [timeoutMs] elapses.
     */
    fun write(data: ByteArray, timeoutMs: Long = 500): Int {
        if (closed) return 0
        lock.lock()
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (available == capacity && !closed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return 0
                try {
                    notFull.await(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    // Restore interrupt flag so the decode loop exits on next iteration check
                    Thread.currentThread().interrupt()
                    return 0
                }
            }
            if (closed) return 0
            val canWrite = minOf(data.size, capacity - available)
            var written = 0
            while (written < canWrite) {
                val chunk = minOf(canWrite - written, capacity - writePos)
                System.arraycopy(data, written, buffer, writePos, chunk)
                writePos = (writePos + chunk) % capacity
                written += chunk
            }
            available += written
            if (written > 0) notEmpty.signalAll()
            return written
        } finally {
            lock.unlock()
        }
    }

    /**
     * Read up to `dest.size` bytes. Returns bytes actually read (0 on timeout or close).
     * Blocks if the buffer is empty until data is available or [timeoutMs] elapses.
     */
    fun read(dest: ByteArray, timeoutMs: Long = 100): Int {
        lock.lock()
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (available == 0 && !closed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return 0
                try {
                    notEmpty.await(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    // Restore interrupt flag so the caller can detect interruption
                    Thread.currentThread().interrupt()
                    return 0
                }
            }
            if (available == 0) return 0
            val canRead = minOf(dest.size, available)
            var bytesRead = 0
            while (bytesRead < canRead) {
                val chunk = minOf(canRead - bytesRead, capacity - readPos)
                System.arraycopy(buffer, readPos, dest, bytesRead, chunk)
                readPos = (readPos + chunk) % capacity
                bytesRead += chunk
            }
            available -= bytesRead
            if (bytesRead > 0) notFull.signalAll()
            return bytesRead
        } finally {
            lock.unlock()
        }
    }

    /** Discard all buffered data and re-open the buffer (e.g. on seek or after stop). */
    fun reset() {
        lock.lock()
        try {
            readPos = 0
            writePos = 0
            available = 0
            closed = false   // re-open after a close()/stop() cycle
            notFull.signalAll()
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Signal producer and consumer threads to stop waiting. */
    fun close() {
        closed = true
        lock.lock()
        try {
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    val availableBytes: Int get() = available

    companion object {
        /** 480 ms at stereo 48kHz 16-bit = 48000 × 2 ch × 2 bytes × 0.48 s ≈ 92160 bytes. */
        const val DEFAULT_CAPACITY = 92_160
    }
}
