package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.MoeMusicApi

internal fun resetPluginTestState() {
    PluginManager.reset()

    val pluginsField = MoeMusicApi::class.java.getDeclaredField("_plugins")
    pluginsField.isAccessible = true
    when (val plugins = pluginsField.get(MoeMusicApi)) {
        is MutableCollection<*> -> plugins.clear()
        is MutableMap<*, *> -> plugins.clear()
    }
}
