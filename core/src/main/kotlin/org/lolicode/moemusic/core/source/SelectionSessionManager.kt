package org.lolicode.moemusic.core.source

import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.core.config.ModConfigManager
import java.security.SecureRandom
import java.util.UUID

/**
 * An active, ephemeral selection choices session held in server memory.
 *
 * @property id Unique session token for correlation and command reference.
 * @property ownerUserId Owning player UUID, or null for server console.
 * @property sourceId Owning music source ID.
 * @property entries Full unpaginated list of resolved selection choices.
 * @property createdAt Epoch millisecond timestamp when the session was created.
 */
data class SelectionSession(
    val id: String,
    val ownerUserId: UUID?,
    val sourceId: String,
    val entries: List<SelectionEntry>,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A paginated slice of selection choices returned from an active session.
 */
data class SelectionPageSlice(
    val sessionId: String,
    val sourceId: String,
    val entries: List<SelectionEntry>,
    val offset: Int,
    val total: Int,
    val hasMore: Boolean,
)

/**
 * Manages ephemeral server-side selection choice sessions.
 *
 * Provides per-user isolation, TTL-based eviction, per-user session caps, and
 * safe thread-safe pagination of resolved container choices.
 */
object SelectionSessionManager {

    private const val MAX_SESSIONS_PER_USER = 5
    private const val SESSION_TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    private const val SESSION_TOKEN_LENGTH = 8

    private val lock = Any()
    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, SelectionSession>()

    /**
     * Creates and stores a new selection session.
     *
     * @param ownerUserId Owning user UUID, or null for server console.
     * @param sourceId Owning music source ID.
     * @param entries Resolved selection choices.
     * @return Stored [SelectionSession].
     */
    fun createSession(
        ownerUserId: UUID?,
        sourceId: String,
        entries: List<SelectionEntry>,
    ): SelectionSession {
        synchronized(lock) {
            pruneExpiredLocked()

            // Enforce per-user quota to prevent DoS from a single user
            if (ownerUserId != null) {
                val userSessionKeys = sessions.entries
                    .filter { it.value.ownerUserId == ownerUserId }
                    .map { it.key }
                if (userSessionKeys.size >= MAX_SESSIONS_PER_USER) {
                    val excessCount = userSessionKeys.size - MAX_SESSIONS_PER_USER + 1
                    userSessionKeys.take(excessCount).forEach { sessions.remove(it) }
                }
            }

            // Enforce global maximum active sessions
            val maxGlobal = ModConfigManager.config.media.maxActiveSelectionSessions
            while (sessions.size >= maxGlobal && sessions.isNotEmpty()) {
                val oldestKey = sessions.keys.first()
                sessions.remove(oldestKey)
            }

            val sessionId = generateSessionIdLocked()
            val session = SelectionSession(
                id = sessionId,
                ownerUserId = ownerUserId,
                sourceId = sourceId,
                entries = entries,
            )
            sessions[sessionId] = session
            return session
        }
    }

    /**
     * Retrieves an active session, verifying user ownership unless bypassed.
     */
    fun getSession(
        sessionId: String,
        requesterId: UUID?,
        bypassOwnership: Boolean = false,
    ): SelectionSession? {
        synchronized(lock) {
            val session = sessions[sessionId] ?: return null
            if (isExpiredLocked(session)) {
                sessions.remove(sessionId)
                return null
            }
            if (!bypassOwnership && session.ownerUserId != null && session.ownerUserId != requesterId) {
                return null
            }
            return session
        }
    }

    /**
     * Retrieves a paginated slice of selection choices from an active session.
     */
    fun getPage(
        sessionId: String,
        offset: Int,
        limit: Int,
        requesterId: UUID?,
        bypassOwnership: Boolean = false,
    ): SelectionPageSlice? {
        val session = getSession(sessionId, requesterId, bypassOwnership) ?: return null
        val total = session.entries.size
        val normalizedOffset = offset.coerceIn(0, total)
        val configuredMax = ModConfigManager.config.media.maxSelectionResultsPerPage
        val effectiveLimit = (if (limit > 0) limit else 20).coerceIn(1, configuredMax)
        val toIndex = (normalizedOffset + effectiveLimit).coerceAtMost(total)
        val slice = if (normalizedOffset < total) session.entries.subList(normalizedOffset, toIndex) else emptyList()
        val hasMore = toIndex < total

        return SelectionPageSlice(
            sessionId = session.id,
            sourceId = session.sourceId,
            entries = slice,
            offset = normalizedOffset,
            total = total,
            hasMore = hasMore,
        )
    }

    /**
     * Clears all selection sessions owned by [userId], e.g. on client disconnect.
     */
    fun clearUserSessions(userId: UUID) {
        synchronized(lock) {
            val keysToRemove = sessions.entries
                .filter { it.value.ownerUserId == userId }
                .map { it.key }
            keysToRemove.forEach { sessions.remove(it) }
        }
    }

    /**
     * Clears all active selection sessions across the runtime.
     */
    fun clear() {
        synchronized(lock) {
            sessions.clear()
        }
    }

    private fun generateSessionIdLocked(): String {
        while (true) {
            val sb = StringBuilder(SESSION_TOKEN_LENGTH)
            for (i in 0 until SESSION_TOKEN_LENGTH) {
                sb.append(SESSION_TOKEN_CHARS[random.nextInt(SESSION_TOKEN_CHARS.length)])
            }
            val id = sb.toString()
            if (!sessions.containsKey(id)) {
                return id
            }
        }
    }

    private fun isExpiredLocked(session: SelectionSession): Boolean {
        val ttlMs = ModConfigManager.config.media.selectionSessionTtlMinutes * 60_000L
        return System.currentTimeMillis() - session.createdAt > ttlMs
    }

    private fun pruneExpiredLocked() {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isExpiredLocked(entry.value)) {
                iterator.remove()
            }
        }
    }
}
