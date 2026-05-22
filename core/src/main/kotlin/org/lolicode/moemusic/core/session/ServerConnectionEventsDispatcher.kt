package org.lolicode.moemusic.core.session

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.event.OnServerPlayerConnected
import org.lolicode.moemusic.api.event.OnServerPlayerDisconnected
import org.lolicode.moemusic.core.event.CoreEvents

/**
 * Fires raw Minecraft server connection lifecycle events for all users, independent of whether
 * they ever complete a MoeMusic-specific handshake.
 */
object ServerConnectionEventsDispatcher {

    fun connected(user: MoeMusicUser) {
        CoreEvents.bus.fire(OnServerPlayerConnected(user))
    }

    fun disconnected(user: MoeMusicUser) {
        CoreEvents.bus.fire(OnServerPlayerDisconnected(user))
    }
}
