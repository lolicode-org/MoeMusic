package org.lolicode.moemusic.api.event

/**
 * Observational event bus.
 *
 * Events are **non-cancellable** and **non-mutating** — handlers are notified but cannot
 * alter the workflow that triggered the event.
 *
 * Delivery is **synchronous and inline**: handlers run on the same thread that called [fire],
 * and [fire] returns only after all matching handlers finish.
 * The specific calling thread for a given event depends on the publisher and is not itself a
 * stable per-event API guarantee.
 *
 * Relative order between subscribers is intentionally unspecified and must not be relied on.
 *
 * Handlers must not block; long-running work should be dispatched to a background thread by
 * the handler itself.
 * Uncaught exceptions in handlers are logged and swallowed so that other subscribers
 * still receive the event.
 *
 * The concrete implementation is `org.lolicode.moemusic.core.event.EventBusImpl` in `:core`.
 * Plugins receive the shared instance through runtime contexts such as
 * [org.lolicode.moemusic.api.plugin.ServerRuntimeContext.eventBus].
 */
public interface EventBus {

    /**
     * Subscribe [handler] to events of type [T].
     *
     * Subscriptions are permanent for the lifetime of the current plugin runtime.
     * There is no unsubscribe API in the initial design.
     * Relative order between subscribers is not part of the API contract.
     *
     * @param eventType Runtime class token for [T] (pass `MyEvent::class.java`).
     * @param handler   Called on every [fire] for matching events.
     */
    public fun <T : Any> subscribe(eventType: Class<T>, handler: (T) -> Unit)

    /**
     * Fire [event] to all registered subscribers of its runtime type.
     *
     * Matching handlers are invoked synchronously on the calling thread. This method returns
     * only after every matching handler has completed or thrown.
     *
     * Called by `:core` internals; plugins should not fire events directly unless
     * building a compatibility bridge.
     */
    public fun <T : Any> fire(event: T)
}

/** Convenience inline extension — avoids passing the `::class.java` token manually. */
public inline fun <reified T : Any> EventBus.subscribe(noinline handler: (T) -> Unit): Unit =
    subscribe(T::class.java, handler)
