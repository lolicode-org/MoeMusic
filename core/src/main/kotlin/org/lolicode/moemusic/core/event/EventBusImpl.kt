package org.lolicode.moemusic.core.event

import org.lolicode.moemusic.api.event.EventBus
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

class EventBusImpl : EventBus {
    private val logger = LoggerFactory.getLogger(EventBusImpl::class.java)
    private val subscribers = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<(Any) -> Unit>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribe(eventType: Class<T>, handler: (T) -> Unit) {
        subscribers.computeIfAbsent(eventType) { CopyOnWriteArrayList() }
            .add(handler as (Any) -> Unit)
    }

    override fun <T : Any> fire(event: T) {
        subscribers[event::class.java]?.forEach { handler ->
            try { handler(event) } catch (e: Exception) {
                logger.error("EventBus: uncaught exception in handler for {}: {}", event::class.simpleName, e.message, e)
            }
        }
    }

    fun clear() {
        subscribers.clear()
    }
}
