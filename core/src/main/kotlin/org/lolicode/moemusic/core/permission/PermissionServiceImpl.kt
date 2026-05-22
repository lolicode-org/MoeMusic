package org.lolicode.moemusic.core.permission

import org.lolicode.moemusic.api.service.IPermissionService
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.permission.MoeMusicPermission
import org.lolicode.moemusic.api.PermissionDeniedException

/** Public server-side implementation of [IPermissionService]. */
class PermissionServiceImpl : IPermissionService {

    override fun has(permission: MoeMusicPermission, user: MoeMusicUser): Boolean {
        val node = PermissionNodes.node(permission)
        return user.hasPermission(node.id, node.defaultLevel())
    }

    override fun require(permission: MoeMusicPermission, user: MoeMusicUser?) {
        val checkedUser = user ?: return
        val node = PermissionNodes.node(permission)
        if (!checkedUser.hasPermission(node.id, node.defaultLevel())) {
            throw PermissionDeniedException(node.deniedMessage)
        }
    }
}
