package org.lolicode.moemusic.core.event

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EventBusTest {

    @Test
    fun `subscriber receives fired event`() {
        val bus = EventBusImpl()
        var received: String? = null
        bus.subscribe(String::class.java) { received = it }
        bus.fire("hello")
        assertEquals("hello", received)
    }

    @Test
    fun `multiple subscribers all receive event`() {
        val bus = EventBusImpl()
        var count = 0
        repeat(3) { bus.subscribe(String::class.java) { count++ } }
        bus.fire("x")
        assertEquals(3, count)
    }

    @Test
    fun `fire invokes handlers inline on the calling thread and waits for completion`() {
        val bus = EventBusImpl()
        val callerThread = Thread.currentThread()
        val handlerEntered = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        var handlerThread: Thread? = null
        var completed = false

        bus.subscribe(String::class.java) {
            handlerThread = Thread.currentThread()
            handlerEntered.countDown()
            assertTrue(releaseHandler.await(1, TimeUnit.SECONDS), "test helper should release handler")
            completed = true
        }

        val releaser = thread(start = true, isDaemon = true) {
            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS), "handler should start before fire returns")
            releaseHandler.countDown()
        }

        bus.fire("hello")
        releaser.join(1_000L)

        assertSame(callerThread, handlerThread, "handlers should run on the thread that called fire()")
        assertTrue(completed, "fire() should return only after handlers have completed")
    }

    @Test
    fun `exception in subscriber does not prevent other subscribers`() {
        val bus = EventBusImpl()
        var secondCalled = false
        bus.subscribe(String::class.java) { throw RuntimeException("boom") }
        bus.subscribe(String::class.java) { secondCalled = true }
        bus.fire("test")
        assertEquals(true, secondCalled)
    }

    @Test
    fun `clear removes existing subscriptions`() {
        val bus = EventBusImpl()
        var count = 0
        bus.subscribe(String::class.java) { count++ }
        bus.clear()

        bus.fire("test")

        assertEquals(0, count)
    }
}
