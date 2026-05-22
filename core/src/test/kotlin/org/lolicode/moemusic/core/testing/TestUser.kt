package org.lolicode.moemusic.core.testing

import org.lolicode.moemusic.api.MoeMusicUser
import java.util.UUID

internal class TestUser(
    override val displayName: String = "Test Player",
    override val id: UUID = UUID.randomUUID(),
    override val locale: String = "en_us",
    private val permissions: Set<String> = emptySet(),
    private val opLevel: Int = -1,
) : MoeMusicUser() {

    override fun hasPermission(permission: String, defaultLevel: Int): Boolean =
        permission in permissions || opLevel >= defaultLevel
}
