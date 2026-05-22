package org.lolicode.moemusic.core.playback

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.core.permission.PermissionNodes
import java.util.UUID

class SkipVoteService(
    private val voteRequiredPercent: () -> Int,
    private val canVote: (MoeMusicUser) -> Boolean = { user ->
        user.hasPermission(PermissionNodes.VOTE.id, PermissionNodes.VOTE.defaultLevel())
    },
) {

    data class VoteTally(
        val title: String,
        val voteCount: Int,
        val requiredVotes: Int,
        val passed: Boolean,
        val added: Boolean,
    )

    sealed interface RequestResult {
        data class Failure(val message: LocalizedText) : RequestResult
        data class Registered(val tally: VoteTally) : RequestResult
        data class AlreadyVoted(val tally: VoteTally) : RequestResult
        data class Passed(val tally: VoteTally) : RequestResult
    }

    private data class VoteState(
        val trackSessionId: Long,
        val voterIds: MutableSet<UUID> = linkedSetOf(),
    )

    private var currentState: VoteState? = null

    fun requestVote(
        requester: MoeMusicUser,
        activeParticipants: Collection<MoeMusicUser>,
        currentTrack: TrackInfo?,
        trackSessionId: Long,
    ): RequestResult {
        val track = currentTrack ?: run {
            synchronized(this) {
                currentState = null
            }
            return RequestResult.Failure(LocalizedText.key("error.moemusic.playback.nothing_to_skip"))
        }

        if (!canVote(requester)) {
            return RequestResult.Failure(PermissionNodes.VOTE.deniedMessage)
        }

        val eligibleVoters = eligibleVoters(activeParticipants)
        val eligibleIds = eligibleVoters.mapTo(linkedSetOf()) { it.id }
        if (requester.id !in eligibleIds) {
            return RequestResult.Failure(PermissionNodes.VOTE.deniedMessage)
        }

        val tally = synchronized(this) {
            val state = stateFor(trackSessionId)
            state.voterIds.retainAll(eligibleIds)

            val requiredVotes = requiredVotes(eligibleIds.size)
            val added = state.voterIds.add(requester.id)
            val voteCount = state.voterIds.size
            val passed = voteCount >= requiredVotes
            if (passed) {
                currentState = null
            }
            VoteTally(
                title = displayTitle(track),
                voteCount = voteCount,
                requiredVotes = requiredVotes,
                passed = passed,
                added = added,
            )
        }

        return when {
            tally.passed -> RequestResult.Passed(tally)
            tally.added -> RequestResult.Registered(tally)
            else -> RequestResult.AlreadyVoted(tally)
        }
    }

    fun onParticipantLeave(
        userId: UUID,
        activeParticipants: Collection<MoeMusicUser>,
        currentTrack: TrackInfo?,
        trackSessionId: Long,
    ): VoteTally? {
        currentTrack ?: run {
            synchronized(this) {
                currentState = null
            }
            return null
        }

        val eligibleVoters = eligibleVoters(activeParticipants)
        val eligibleIds = eligibleVoters.mapTo(linkedSetOf()) { it.id }

        return synchronized(this) {
            val state = currentState ?: return@synchronized null
            if (state.trackSessionId != trackSessionId) {
                currentState = null
                return@synchronized null
            }

            state.voterIds.remove(userId)
            state.voterIds.retainAll(eligibleIds)
            if (eligibleIds.isEmpty()) {
                currentState = null
                return@synchronized null
            }

            val requiredVotes = requiredVotes(eligibleIds.size)
            val voteCount = state.voterIds.size
            val passed = voteCount >= requiredVotes
            if (passed) {
                currentState = null
            }
            VoteTally(
                title = displayTitle(currentTrack),
                voteCount = voteCount,
                requiredVotes = requiredVotes,
                passed = passed,
                added = false,
            )
        }
    }

    fun reset() {
        synchronized(this) {
            currentState = null
        }
    }

    private fun eligibleVoters(activeParticipants: Collection<MoeMusicUser>): List<MoeMusicUser> =
        activeParticipants.filter(canVote)

    private fun stateFor(trackSessionId: Long): VoteState {
        val existing = currentState
        if (existing != null && existing.trackSessionId == trackSessionId) {
            return existing
        }
        return VoteState(trackSessionId).also { currentState = it }
    }

    private fun requiredVotes(eligibleVoters: Int): Int {
        if (eligibleVoters <= 0) return 0
        val percent = voteRequiredPercent().coerceIn(1, 100)
        return ((eligibleVoters * percent) + 99) / 100
    }

    private fun displayTitle(track: TrackInfo): String =
        track.title.ifBlank { track.id.ifBlank { "current track" } }
}
