package org.lolicode.moemusic.core.source

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.lolicode.moemusic.api.model.ArtistInfo
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.SelectionEntryKind
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionSessionManagerTest {

    @BeforeEach
    fun setUp() {
        SelectionSessionManager.clear()
    }

    private fun sampleEntries(count: Int, sourceId: String = "test-source"): List<SelectionEntry> =
        (1..count).map { i ->
            SelectionEntry(
                selectionId = "item-$i",
                title = "Item Title $i",
                artists = listOf(ArtistInfo(id = "artist-$i", name = "Artist $i")),
                durationMs = 180_000L,
            ) {
                this.sourceId = sourceId
                this.kind = SelectionEntryKind.TRACK
            }
        }

    @Test
    fun `creates session and retrieves pages accurately`() {
        val owner = UUID.randomUUID()
        val entries = sampleEntries(25)
        val session = SelectionSessionManager.createSession(owner, "test-source", entries)

        assertNotNull(session.id)
        assertEquals(owner, session.ownerUserId)
        assertEquals(25, session.entries.size)

        // Page 1: offset=0, limit=10
        val page1 = SelectionSessionManager.getPage(session.id, offset = 0, limit = 10, requesterId = owner)
        assertNotNull(page1)
        assertEquals(10, page1.entries.size)
        assertEquals(0, page1.offset)
        assertEquals(25, page1.total)
        assertTrue(page1.hasMore)
        assertEquals("item-1", page1.entries.first().selectionId)
        assertEquals("item-10", page1.entries.last().selectionId)

        // Page 2: offset=10, limit=10
        val page2 = SelectionSessionManager.getPage(session.id, offset = 10, limit = 10, requesterId = owner)
        assertNotNull(page2)
        assertEquals(10, page2.entries.size)
        assertEquals(10, page2.offset)
        assertEquals(25, page2.total)
        assertTrue(page2.hasMore)
        assertEquals("item-11", page2.entries.first().selectionId)
        assertEquals("item-20", page2.entries.last().selectionId)

        // Page 3: offset=20, limit=10
        val page3 = SelectionSessionManager.getPage(session.id, offset = 20, limit = 10, requesterId = owner)
        assertNotNull(page3)
        assertEquals(5, page3.entries.size)
        assertEquals(20, page3.offset)
        assertEquals(25, page3.total)
        assertFalse(page3.hasMore)
        assertEquals("item-21", page3.entries.first().selectionId)
        assertEquals("item-25", page3.entries.last().selectionId)
    }

    @Test
    fun `enforces per-user ownership isolation`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val session = SelectionSessionManager.createSession(userA, "test-source", sampleEntries(5))

        // Owner can access
        assertNotNull(SelectionSessionManager.getSession(session.id, userA))
        assertNotNull(SelectionSessionManager.getPage(session.id, offset = 0, limit = 10, requesterId = userA))

        // Other user cannot access without bypass
        assertNull(SelectionSessionManager.getSession(session.id, userB, bypassOwnership = false))
        assertNull(SelectionSessionManager.getPage(session.id, offset = 0, limit = 10, requesterId = userB, bypassOwnership = false))

        // Other user can access with bypass
        assertNotNull(SelectionSessionManager.getSession(session.id, userB, bypassOwnership = true))
        assertNotNull(SelectionSessionManager.getPage(session.id, offset = 0, limit = 10, requesterId = userB, bypassOwnership = true))
    }

    @Test
    fun `allows console or unauthenticated owner to be accessed when requester is null or bypass is active`() {
        val session = SelectionSessionManager.createSession(null, "test-source", sampleEntries(5))

        // null requester accesses null owner session
        assertNotNull(SelectionSessionManager.getSession(session.id, null))
        assertNotNull(SelectionSessionManager.getPage(session.id, offset = 0, limit = 10, requesterId = null))

        // specific player with bypass can access
        val player = UUID.randomUUID()
        assertNotNull(SelectionSessionManager.getSession(session.id, player, bypassOwnership = true))
    }

    @Test
    fun `clears user sessions on disconnect`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()

        val sessionA1 = SelectionSessionManager.createSession(userA, "test-source", sampleEntries(5))
        val sessionA2 = SelectionSessionManager.createSession(userA, "test-source", sampleEntries(5))
        val sessionB1 = SelectionSessionManager.createSession(userB, "test-source", sampleEntries(5))

        SelectionSessionManager.clearUserSessions(userA)

        assertNull(SelectionSessionManager.getSession(sessionA1.id, userA))
        assertNull(SelectionSessionManager.getSession(sessionA2.id, userA))
        assertNotNull(SelectionSessionManager.getSession(sessionB1.id, userB))
    }

    @Test
    fun `handles offset beyond total entries gracefully`() {
        val owner = UUID.randomUUID()
        val session = SelectionSessionManager.createSession(owner, "test-source", sampleEntries(10))

        val page = SelectionSessionManager.getPage(session.id, offset = 50, limit = 10, requesterId = owner)
        assertNotNull(page)
        assertTrue(page.entries.isEmpty())
        assertEquals(10, page.offset) // clamped to total
        assertEquals(10, page.total)
        assertFalse(page.hasMore)
    }

    @Test
    fun `evicts oldest session when per-user capacity is reached`() {
        val user = UUID.randomUUID()
        val sessions = (1..6).map {
            SelectionSessionManager.createSession(user, "test-source", sampleEntries(1))
        }

        // Default max is 5 per user, so session 1 should have been evicted
        assertNull(SelectionSessionManager.getSession(sessions[0].id, user))
        assertNotNull(SelectionSessionManager.getSession(sessions[5].id, user))
    }
}
