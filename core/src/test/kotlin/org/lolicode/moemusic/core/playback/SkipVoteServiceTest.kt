package org.lolicode.moemusic.core.playback

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.toArtistInfos
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.testing.TestUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkipVoteServiceTest {

    @Test
    fun `vote fails when nothing is playing`() {
        val player = voter()
        val service = SkipVoteService(voteRequiredPercent = { 60 })

        val result = assertIs<SkipVoteService.RequestResult.Failure>(
            service.requestVote(
                requester = player,
                activeParticipants = listOf(player),
                currentTrack = null,
                trackSessionId = 1L,
            )
        )

        assertEquals(
            LocalizedText.key("error.moemusic.playback.nothing_to_skip"),
            result.message,
        )
    }

    @Test
    fun `vote requires the vote permission`() {
        val player = TestUser()
        val service = SkipVoteService(voteRequiredPercent = { 50 })

        val result = assertIs<SkipVoteService.RequestResult.Failure>(
            service.requestVote(
                requester = player,
                activeParticipants = listOf(player),
                currentTrack = track(),
                trackSessionId = 1L,
            )
        )

        assertEquals(PermissionNodes.VOTE.deniedMessage, result.message)
    }

    @Test
    fun `duplicate votes are rejected and a new track session resets vote state`() {
        val alice = voter("Alice")
        val bob = voter("Bob")
        val active = listOf(alice, bob)
        val service = SkipVoteService(voteRequiredPercent = { 51 })

        val first = assertIs<SkipVoteService.RequestResult.Registered>(
            service.requestVote(alice, active, track(), trackSessionId = 10L)
        )
        assertEquals(1, first.tally.voteCount)
        assertEquals(2, first.tally.requiredVotes)

        val duplicate = assertIs<SkipVoteService.RequestResult.AlreadyVoted>(
            service.requestVote(alice, active, track(), trackSessionId = 10L)
        )
        assertEquals(1, duplicate.tally.voteCount)

        val resetForNextTrack = assertIs<SkipVoteService.RequestResult.Registered>(
            service.requestVote(alice, active, track(), trackSessionId = 11L)
        )
        assertEquals(1, resetForNextTrack.tally.voteCount)

        val passed = assertIs<SkipVoteService.RequestResult.Passed>(
            service.requestVote(bob, active, track(), trackSessionId = 11L)
        )
        assertTrue(passed.tally.passed)
        assertEquals(2, passed.tally.voteCount)
    }

    @Test
    fun `participant leave can satisfy a lowered quorum`() {
        val alice = voter("Alice")
        val bob = voter("Bob")
        val carol = voter("Carol")
        val track = track()
        val service = SkipVoteService(voteRequiredPercent = { 100 })

        service.requestVote(alice, listOf(alice, bob, carol), track, trackSessionId = 20L)
        service.requestVote(bob, listOf(alice, bob, carol), track, trackSessionId = 20L)

        val tally = assertNotNull(
            service.onParticipantLeave(
                userId = carol.id,
                activeParticipants = listOf(alice, bob),
                currentTrack = track,
                trackSessionId = 20L,
            )
        )
        assertEquals(2, tally.requiredVotes)
        assertEquals(2, tally.voteCount)
        assertTrue(tally.passed)
    }

    private fun voter(name: String = "Voter"): TestUser =
        TestUser(
            displayName = name,
            permissions = setOf(PermissionNodes.VOTE.id),
        )

    private fun track(): TrackInfo =
        TrackInfo(id = "track-1", title = "Test Track", artists = listOf("Artist").toArtistInfos(), durationMs = 180_000) { sourceId = "http" }
}
