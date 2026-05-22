package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.MoeMusicApi

internal fun resetPluginTestState() {
    PluginManager.reset()

    val pluginsField = MoeMusicApi::class.java.getDeclaredField("_plugins")
    pluginsField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val plugins = pluginsField.get(MoeMusicApi) as MutableMap<String, *>
    plugins.clear()
}
