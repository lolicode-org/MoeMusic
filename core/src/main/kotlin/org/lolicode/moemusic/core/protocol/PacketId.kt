package org.lolicode.moemusic.core.protocol

/**
 * Typed identifier for a MoeMusic network channel.
 *
 * Namespaced to avoid collisions with other mods.
 * The string form `"namespace:path"` is used as the channel key in bad packets.
 */
data class PacketId(val namespace: String, val path: String) {
    /** Returns the canonical `"namespace:path"` channel key string. */
    fun toChannelKey(): String = "$namespace:$path"

    override fun toString(): String = toChannelKey()
}
