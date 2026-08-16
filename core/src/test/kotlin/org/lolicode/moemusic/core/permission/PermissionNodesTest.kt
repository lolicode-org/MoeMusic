package org.lolicode.moemusic.core.permission

import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.config.PermissionDefaultsConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionNodesTest {

    @Test
    fun `permission nodes levels obey bounds and support LEVEL_DISABLED`() {
        assertEquals(0, PermissionNodes.MIN_DEFAULT_LEVEL)
        assertEquals(4, PermissionNodes.MAX_VANILLA_LEVEL)
        assertEquals(5, PermissionNodes.MAX_DEFAULT_LEVEL)
        assertEquals(5, PermissionNodes.LEVEL_DISABLED)

        val customConfig = MoeMusicConfig(
            permissions = PermissionDefaultsConfig(
                submit = PermissionNodes.LEVEL_DISABLED,
                sourceHttpSubmit = 5,
                queueControl = 10,
                vote = -5,
            ),
        )

        assertEquals(PermissionNodes.LEVEL_DISABLED, PermissionNodes.SUBMIT.defaultLevel(customConfig))
        assertEquals(5, PermissionNodes.SOURCE_HTTP_SUBMIT.defaultLevel(customConfig))
        assertEquals(5, PermissionNodes.QUEUE_CONTROL.defaultLevel(customConfig))
        assertEquals(0, PermissionNodes.VOTE.defaultLevel(customConfig))
    }
}
