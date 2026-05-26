package org.lolicode.moemusic.core.platform

import java.nio.file.Path
import java.nio.file.Paths

object PlatformDirectories {

    fun homeDirectory(
        env: Map<String, String>,
        jvmUserHome: String,
        preferWindowsHome: Boolean = false,
    ): Path? {
        val candidates = buildList {
            if (preferWindowsHome) {
                add(env["USERPROFILE"].orEmpty())
                windowsHomeFromDrivePath(env)?.let(::add)
                add(env["HOME"].orEmpty())
            } else {
                add(env["HOME"].orEmpty())
                add(env["USERPROFILE"].orEmpty())
                windowsHomeFromDrivePath(env)?.let(::add)
            }
            add(jvmUserHome)
        }

        return candidates.firstOrNull { it.isNotBlank() }?.let(Paths::get)
    }

    private fun windowsHomeFromDrivePath(env: Map<String, String>): String? {
        val homeDrive = env["HOMEDRIVE"].orEmpty()
        val homePath = env["HOMEPATH"].orEmpty()
        return if (homeDrive.isNotBlank() && homePath.isNotBlank()) homeDrive + homePath else null
    }
}
