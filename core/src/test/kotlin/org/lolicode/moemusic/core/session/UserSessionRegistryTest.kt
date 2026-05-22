package org.lolicode.moemusic.core.session

import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.event.OnUserSessionStarted
import org.lolicode.moemusic.api.event.OnUserSessionEnded
import org.lolicode.moemusic.api.event.OnUserParticipationChanged
import org.lolicode.moemusic.api.event.subscribe
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.testing.TestUser
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserSessionRegistryTest {

    @BeforeTest
    fun resetBeforeTest() {
        PluginManager.reset()
        UserSessionRegistry.clear()
    }

    @AfterTest
    fun resetAfterTest() {
        PluginManager.reset()
        UserSessionRegistry.clear()
    }

    @Test
    fun `standby keeps locale hints until real disconnect`() {
        val user = TestUser(locale = "en_us")

        val standby = UserSessionRegistry.registerStandby(user, locale = "zh_cn")

        assertEquals(UserSessionRegistry.Participation.STANDBY, standby.participation)
        assertNull(UserSessionRegistry.getActive(user.id))
        assertEquals("zh_cn", UserSessionRegistry.localeFor(user.id))

        UserSessionRegistry.rememberLocale(user.id, "ja_jp")
        assertEquals("ja_jp", UserSessionRegistry.localeFor(user.id))

        UserSessionRegistry.disconnect(user.id)
        assertNull(UserSessionRegistry.localeFor(user.id))
    }

    @Test
    fun `join leave and participation events reflect connection lifecycle precisely`() {
        val userId = UUID.randomUUID()
        val joins = mutableListOf<Pair<UUID, UserParticipationState>>()
        val changes = mutableListOf<Triple<UUID, UserParticipationState, UserParticipationState>>()
        val leaves = mutableListOf<Pair<UUID, UserParticipationState>>()
        CoreEvents.bus.subscribe<OnUserSessionStarted> { joins += it.user.id to it.state }
        CoreEvents.bus.subscribe<OnUserParticipationChanged> {
            changes += Triple(it.user.id, it.previousState, it.newState)
        }
        CoreEvents.bus.subscribe<OnUserSessionEnded> { leaves += it.user.id to it.state }

        UserSessionRegistry.registerStandby(TestUser(id = userId))
        UserSessionRegistry.registerStandby(TestUser(id = userId, locale = "zh_cn"), locale = "zh_cn")
        UserSessionRegistry.standby(userId)
        UserSessionRegistry.activate(TestUser(id = userId))
        UserSessionRegistry.standby(userId)
        UserSessionRegistry.disconnect(userId)
        UserSessionRegistry.activate(TestUser(id = userId))
        UserSessionRegistry.disconnect(userId)

        assertEquals(
            listOf(
                userId to UserParticipationState.STANDBY,
                userId to UserParticipationState.ACTIVE,
            ),
            joins,
        )
        assertEquals(
            listOf(
                Triple(userId, UserParticipationState.STANDBY, UserParticipationState.ACTIVE),
                Triple(userId, UserParticipationState.ACTIVE, UserParticipationState.STANDBY),
            ),
            changes,
        )
        assertEquals(
            listOf(
                userId to UserParticipationState.STANDBY,
                userId to UserParticipationState.ACTIVE,
            ),
            leaves,
        )
    }
}
