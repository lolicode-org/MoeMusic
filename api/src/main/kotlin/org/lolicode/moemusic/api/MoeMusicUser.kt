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
     *
     *                     Some permission checker implementations impose limitations on permission node formatting (e.g. fabric-permission-api-v1, in which nodes are parsed as `Identifier`s under the hood).
     *                     In this case, uppercase letters will be automatically mapped to lowercase. Nodes containing other invalid characters (e.g. spaces) may fail to parse and fallback to default operator checks.
     *                     **ALWAYS** use characters in `[a-z0-9.]` to ensure compatibility & consistence behavior across all platforms. This will be a requirement in api v3.
     *
     *                     In addition, the permission check bridge may cache the converted node key *(not the result)* if the checker does not support plain permission nodes (e.g. fabric-permission-api-v1),
     *                     since this cache is not bounded, if you request a large number of distinct permission checks, they will live in the cache forever, leading to a memory leak.
     * @param defaultLevel Vanilla operator level (0–4) or 5 (`LEVEL_DISABLED`, disabled for all vanilla players) used when no modded provider is present.
     *                     Defaults to 2 (standard operator).
     */
    // TODO: Enforce permission node syntax in v3
    public abstract fun hasPermission(permission: String, defaultLevel: Int = 2): Boolean

    override fun equals(other: Any?): Boolean = other is MoeMusicUser && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "MoeMusicUser(displayName=$displayName, id=$id)"
}
