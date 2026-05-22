package org.lolicode.moemusic.core.session

import org.lolicode.moemusic.api.event.OnServerPlayerConnected
import org.lolicode.moemusic.api.event.OnServerPlayerDisconnected
import org.lolicode.moemusic.api.event.subscribe
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.testing.TestUser
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConnectionEventsDispatcherTest {

    @BeforeTest
    fun resetBeforeTest() {
        PluginManager.reset()
    }

    @AfterTest
    fun resetAfterTest() {
        PluginManager.reset()
    }

    @Test
    fun `raw server connection events fire for every player`() {
        val player = TestUser(locale = "zh_cn")
        val events = mutableListOf<String>()

        CoreEvents.bus.subscribe<OnServerPlayerConnected> {
            events += "join:${it.user.id}:${it.user.locale}"
        }
        CoreEvents.bus.subscribe<OnServerPlayerDisconnected> {
            events += "leave:${it.user.id}:${it.user.locale}"
        }

        ServerConnectionEventsDispatcher.connected(player)
        ServerConnectionEventsDispatcher.disconnected(player)

        assertEquals(
            listOf(
                "join:${player.id}:${player.locale}",
                "leave:${player.id}:${player.locale}",
            ),
            events,
        )
    }
}
