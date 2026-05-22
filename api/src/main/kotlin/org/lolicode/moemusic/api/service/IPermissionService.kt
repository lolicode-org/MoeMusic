package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.PermissionDeniedException
import org.lolicode.moemusic.api.permission.MoeMusicPermission

/**
 * Shared built-in MoeMusic permission checks exposed to plugins on the server side.
 *
 * This service resolves MoeMusic's own permission catalog against the current runtime defaults and
 * the active permission provider. The catalog intentionally contains only MoeMusic's shared public
 * permission groups; source-private built-in nodes are not exposed here. For custom plugin-owned
 * permission nodes, use [MoeMusicUser.hasPermission] directly.
 */
public interface IPermissionService {

    /** Returns `true` when [user] holds the built-in [permission]. */
    public fun has(permission: MoeMusicPermission, user: MoeMusicUser): Boolean

    /**
     * Enforce [permission] for [user].
     *
     * Null users are treated as trusted server-internal/system work and always pass.
     *
     * @throws PermissionDeniedException when [user] does not hold [permission].
     */
    public fun require(permission: MoeMusicPermission, user: MoeMusicUser?)
}
