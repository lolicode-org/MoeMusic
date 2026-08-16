package org.lolicode.moemusic.core.session

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.event.OnUserSessionStarted
import org.lolicode.moemusic.api.event.OnUserSessionEnded
import org.lolicode.moemusic.api.event.OnUserParticipationChanged
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.source.SelectionSessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks connected MoeMusic-capable client sessions independently from their active playback
 * participation state.
 *
 * A session can be:
 * - [Participation.ACTIVE]: receives playback broadcasts and counts toward vote/auto-pause logic
 * - [Participation.STANDBY]: still connected, but opted out of playback broadcasts
 *
 * Locale hints persist across ACTIVE -> STANDBY, and standby-only sessions are allowed from the
 * initial handshake, so direct request/response packets can stay localized until disconnect.
 *
 * Event semantics:
 * - first compatible session registration -> `OnUserSessionStarted`
 * - ACTIVE/STANDBY transition without disconnect -> `OnUserParticipationChanged`
 * - disconnect -> `OnUserSessionEnded`
 */
object UserSessionRegistry {

    enum class Participation {
        ACTIVE,
        STANDBY,
    }

    data class Session(
        val user: MoeMusicUser,
        val locale: String,
        val participation: Participation,
        val protocolVersion: Int = MoeMusicProtocol.VERSION,
    ) {
        val supportsFraming: Boolean
            get() = protocolVersion >= 3
    }

    private val sessions: ConcurrentHashMap<UUID, Session> = ConcurrentHashMap()
    private val localeHints: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()

    fun upsert(
        user: MoeMusicUser,
        locale: String = user.locale,
        participation: Participation,
        protocolVersion: Int = MoeMusicProtocol.VERSION,
    ): Session {
        rememberLocale(user.id, locale)
        val updated = Session(
            user = user,
            locale = locale,
            participation = participation,
            protocolVersion = protocolVersion,
        )
        val previous = sessions.put(user.id, updated)
        if (previous == null) {
            CoreEvents.bus.fire(OnUserSessionStarted(user, participation.toApiState()))
        } else if (previous.participation != participation) {
            CoreEvents.bus.fire(
                OnUserParticipationChanged(
                    user = updated.user,
                    previousState = previous.participation.toApiState(),
                    newState = participation.toApiState(),
                )
            )
        }
        return updated
    }

    fun activate(
        user: MoeMusicUser,
        locale: String = user.locale,
        protocolVersion: Int = MoeMusicProtocol.VERSION,
    ): Session =
        upsert(
            user = user,
            locale = locale,
            participation = Participation.ACTIVE,
            protocolVersion = protocolVersion,
        )

    fun registerStandby(
        user: MoeMusicUser,
        locale: String = user.locale,
        protocolVersion: Int = MoeMusicProtocol.VERSION,
    ): Session =
        upsert(
            user = user,
            locale = locale,
            participation = Participation.STANDBY,
            protocolVersion = protocolVersion,
        )

    fun supportsFraming(userId: UUID): Boolean =
        sessions[userId]?.supportsFraming ?: false

    fun protocolVersion(userId: UUID): Int =
        sessions[userId]?.protocolVersion ?: 0

    fun setParticipation(userId: UUID, participation: Participation): Session? {
        val previous = sessions[userId] ?: return null
        if (previous.participation == participation) return previous

        val updated = previous.copy(participation = participation)
        sessions[userId] = updated
        CoreEvents.bus.fire(
            OnUserParticipationChanged(
                user = updated.user,
                previousState = previous.participation.toApiState(),
                newState = participation.toApiState(),
            )
        )
        return updated
    }

    fun standby(userId: UUID): Session? = setParticipation(userId, Participation.STANDBY)

    fun disconnect(userId: UUID): Session? {
        val removed = sessions.remove(userId)
        SelectionSessionManager.clearUserSessions(userId)
        if (removed == null) {
            localeHints.remove(userId)
            return null
        }
        CoreEvents.bus.fire(OnUserSessionEnded(removed.user, removed.participation.toApiState()))
        localeHints.remove(userId)
        return removed
    }

    fun rememberLocale(userId: UUID, locale: String) {
        localeHints[userId] = locale
        sessions.computeIfPresent(userId) { _, session -> session.copy(locale = locale) }
    }

    fun getActive(userId: UUID): MoeMusicUser? =
        sessions[userId]
            ?.takeIf { it.participation == Participation.ACTIVE }
            ?.user

    fun session(userId: UUID): Session? = sessions[userId]

    fun localeFor(userId: UUID): String? = sessions[userId]?.locale ?: localeHints[userId]

    fun activeSessions(): List<Session> =
        sessions.values
            .filter { it.participation == Participation.ACTIVE }

    fun activeUsers(): List<MoeMusicUser> =
        sessions.values
            .asSequence()
            .filter { it.participation == Participation.ACTIVE }
            .map(Session::user)
            .toList()

    fun activeCount(): Int =
        sessions.values.count { it.participation == Participation.ACTIVE }

    fun clear() {
        sessions.keys.toList().forEach(::disconnect)
        SelectionSessionManager.clear()
        localeHints.clear()
    }

    private fun Participation.toApiState(): UserParticipationState =
        when (this) {
            Participation.ACTIVE -> UserParticipationState.ACTIVE
            Participation.STANDBY -> UserParticipationState.STANDBY
        }
}
