package org.lolicode.moemusic.core.event

import org.lolicode.moemusic.core.plugin.PluginManager

/**
 * Global accessor for the shared event bus.
 *
 * The bus is owned by [PluginManager] (initialised during [PluginManager.initialize]). Plugins
 * receive it via [org.lolicode.moemusic.api.plugin.ServerRuntimeContext.eventBus] /
 * [org.lolicode.moemusic.api.plugin.ClientRuntimeContext.eventBus], but internal `:core` code can use
 * this object for convenience.
 *
 * Usage:
 * ```kotlin
 * CoreEvents.bus.fire(OnUserSessionStarted(player, UserParticipationState.ACTIVE))
 * ```
 */
object CoreEvents {
    /** The shared [EventBusImpl] instance. */
    val bus: EventBusImpl get() = PluginManager.eventBus
}
