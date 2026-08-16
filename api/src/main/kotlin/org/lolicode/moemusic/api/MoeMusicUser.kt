package org.lolicode.moemusic.api

import java.util.UUID

/**
 * A lightweight, platform-agnostic representation of a connected MoeMusic user.
 *
 * The concrete platform implementation may wrap a Minecraft server player, but plugins only
 * interact with this abstract identity view. This keeps the `:api` module free of Minecraft
 * dependencies while still exposing the user display name, stable id, locale, and permission
 * checks needed by source and service APIs.
 */
public abstract class MoeMusicUser {

    /** Human-readable display name for this user. It is not guaranteed to be unique. */
    public abstract val displayName: String

    /** Stable identifier for this user in the current server identity domain. */
    public abstract val id: UUID

    /** The user's preferred locale, normalized to Minecraft-style lower-case form such as `en_us`. */
    public abstract val locale: String

    /**
     * Returns `true` if this user holds [permission].
     *
     * Resolution order:
     * 1. If a modded permission provider (e.g. LuckPerms) is available, delegate to it.
     * 2. Otherwise fall back to vanilla operator-level checks using [defaultLevel] (0–4 for vanilla OP levels, or 5 to disable for vanilla players).
     *
     * <b>Note:</b> Generally you should not need this. All common operations (e.g. command execution, queueing tracks) have built-in permission checks in the core module.
     *
     * @param permission   The permission node to check (e.g. `"moemusic.common.submit"`).
     * @param defaultLevel Vanilla operator level (0–4) or 5 (`LEVEL_DISABLED`, disabled for all vanilla players) used when no modded provider is present.
     *                     Defaults to 2 (standard operator).
     */
    public abstract fun hasPermission(permission: String, defaultLevel: Int = 2): Boolean

    override fun equals(other: Any?): Boolean = other is MoeMusicUser && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "MoeMusicUser(displayName=$displayName, id=$id)"
}
