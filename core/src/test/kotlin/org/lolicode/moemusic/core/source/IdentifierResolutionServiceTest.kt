package org.lolicode.moemusic.core.source

import kotlinx.coroutines.runBlocking
import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.service.IdentifierResolutionOutcome
import org.lolicode.moemusic.api.IdentifierResolutionResult
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.event.OnIdentifierResolved
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.toArtistInfos
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.plugin.PluginManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class IdentifierResolutionServiceTest {

    @Test
    fun `tries configured default source first`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig(defaultSourceId = "beta"))
        val visited = mutableListOf<String>()
        val alpha = resolverSource("alpha") { _, _ ->
            visited += "alpha"
            IdentifierResolutionResult.Pass
        }
        val beta = resolverSource("beta") { _, _ ->
            visited += "beta"
            IdentifierResolutionResult.Resolved(
                TrackInfo(id = "b-1", title = "Beta Track", artists = listOf("Artist").toArtistInfos(), durationMs = 10) { sourceId = "beta" }
            )
        }

        val service = IdentifierResolutionService(listOf(alpha, beta))
        val outcome = service.resolve("identifier")

        assertIs<IdentifierResolutionOutcome.Resolved>(outcome)
        assertEquals(listOf("beta"), visited)
    }

    @Test
    fun `blocked result stops fallback`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val visited = mutableListOf<String>()
        val alpha = resolverSource("alpha") { _, _ ->
            visited += "alpha"
            IdentifierResolutionResult.Blocked(LocalizedText.plain("bad link"))
        }
        val beta = resolverSource("beta") { _, _ ->
            visited += "beta"
            IdentifierResolutionResult.Resolved(
                TrackInfo("b-1", "Beta Track", listOf("Artist").toArtistInfos(), 10) { sourceId = "beta" }
            )
        }

        val service = IdentifierResolutionService(listOf(alpha, beta))
        val outcome = service.resolve("identifier")

        assertIs<IdentifierResolutionOutcome.Blocked>(outcome)
        assertEquals(listOf("alpha"), visited)
    }

    @Test
    fun `pass falls through until resolved`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val alpha = resolverSource("alpha") { _, _ -> IdentifierResolutionResult.Pass }
        val beta = resolverSource("beta") { _, _ ->
            IdentifierResolutionResult.Resolved(
                TrackInfo("b-1", "Beta Track", listOf("Artist").toArtistInfos(), 10) { sourceId = "beta" }
            )
        }

        val service = IdentifierResolutionService(listOf(alpha, beta))
        val outcome = service.resolve("identifier")

        val resolved = assertIs<IdentifierResolutionOutcome.Resolved>(outcome)
        assertEquals("beta", resolved.sourceId)
        assertEquals("b-1", resolved.track.id)
    }

    @Test
    fun `choices outcome stamps source id`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val source = resolverSource("alpha") { _, _ ->
            IdentifierResolutionResult.Choices(
                listOf(
                    SelectionEntry(
                        selectionId = "album-1",
                        title = "Album",
                        artists = listOf("Artist").toArtistInfos(),
                        durationMs = -1,
                    )
                )
            )
        }

        val outcome = IdentifierResolutionService(listOf(source)).resolve("identifier")

        val choices = assertIs<IdentifierResolutionOutcome.Choices>(outcome)
        assertEquals("alpha", choices.sourceId)
        assertEquals("alpha", choices.entries.single().sourceId)
        assertEquals("album-1", choices.entries.single().selectionId)
    }

    @Test
    fun `all pass returns not found`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val alpha = resolverSource("alpha") { _, _ -> IdentifierResolutionResult.Pass }
        val beta = resolverSource("beta") { _, _ -> IdentifierResolutionResult.Pass }

        val service = IdentifierResolutionService(listOf(alpha, beta))
        val outcome = service.resolve("identifier")

        assertEquals(IdentifierResolutionOutcome.NotFound, outcome)
    }

    @Test
    fun `fallback resolver is tried only after all specific resolvers pass`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val visited = mutableListOf<String>()

        // Specific source (isFallbackResolver = false) that passes
        val specific = resolverSource("specific") { _, _ ->
            visited += "specific"
            IdentifierResolutionResult.Pass
        }
        // Fallback source (isFallbackResolver = true) that resolves
        val fallback = resolverSource("fallback", isFallback = true) { _, _ ->
            visited += "fallback"
            IdentifierResolutionResult.Resolved(
                TrackInfo("f-1", "Fallback Track", listOf("Artist").toArtistInfos(), 10) { sourceId = "fallback" }
            )
        }

        val service = IdentifierResolutionService(listOf(fallback, specific)) // fallback registered first
        val outcome = service.resolve("identifier")

        val resolved = assertIs<IdentifierResolutionOutcome.Resolved>(outcome)
        assertEquals("fallback", resolved.sourceId)
        // Despite fallback being registered first, specific must have been visited first
        assertEquals(listOf("specific", "fallback"), visited)
    }

    @Test
    fun `fallback resolver never runs if specific resolver claims identifier`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val visited = mutableListOf<String>()

        val specific = resolverSource("specific") { _, _ ->
            visited += "specific"
            IdentifierResolutionResult.Resolved(
                TrackInfo("s-1", "Specific Track", listOf("Artist").toArtistInfos(), 10) { sourceId = "specific" }
            )
        }
        val fallback = resolverSource("fallback", isFallback = true) { _, _ ->
            visited += "fallback"
            IdentifierResolutionResult.Resolved(
                TrackInfo("f-1", "Fallback Track", listOf("Artist").toArtistInfos(), 10) { sourceId = "fallback" }
            )
        }

        val service = IdentifierResolutionService(listOf(fallback, specific))
        val outcome = service.resolve("identifier")

        val resolved = assertIs<IdentifierResolutionOutcome.Resolved>(outcome)
        assertEquals("specific", resolved.sourceId)
        assertEquals(listOf("specific"), visited) // fallback never visited
    }

    @Test
    fun `submitter is passed to resolveIdentifier`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        var receivedSubmitter: MoeMusicUser? = null

        val source = resolverSource("src") { _, submitter ->
            receivedSubmitter = submitter
            IdentifierResolutionResult.Pass
        }

        val mockPlayer = object : MoeMusicUser() {
            override val displayName: String = "TestUser"
            override val id: java.util.UUID = java.util.UUID.randomUUID()
            override val locale: String = "en_us"
            override fun hasPermission(permission: String, defaultLevel: Int): Boolean = true
        }

        IdentifierResolutionService(listOf(source)).resolve("identifier", mockPlayer)
        assertEquals(mockPlayer, receivedSubmitter)
    }

    @Test
    fun `identifier resolved event is emitted with trimmed input and final outcome`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        PluginManager.eventBus.clear()
        var event: OnIdentifierResolved? = null
        PluginManager.eventBus.subscribe(OnIdentifierResolved::class.java) { event = it }

        val source = resolverSource("alpha") { _, _ ->
            IdentifierResolutionResult.Resolved(
                TrackInfo(
                    id = "alpha-1",
                    title = "Alpha",
                    artists = listOf("Artist").toArtistInfos(),
                    durationMs = 10,
                )
            )
        }

        val outcome = IdentifierResolutionService(listOf(source)).resolve("  identifier  ")

        val resolvedEvent = assertNotNull(event)
        assertEquals("identifier", resolvedEvent.identifier)
        assertEquals(outcome, resolvedEvent.outcome)
        val resolved = assertIs<IdentifierResolutionOutcome.Resolved>(resolvedEvent.outcome)
        assertEquals("alpha", resolved.sourceId)
        assertEquals("alpha", resolved.track.sourceId)
    }

    private fun resolverSource(
        id: String,
        isFallback: Boolean = false,
        resolver: suspend (String, MoeMusicUser?) -> IdentifierResolutionResult,
    ): IdentifierResolvableMusicSource = object : IdentifierResolvableMusicSource {
        override val id: String = id
        override val isFallbackResolver: Boolean = isFallback
        override suspend fun resolveIdentifier(identifier: String, submitter: MoeMusicUser?): IdentifierResolutionResult =
            resolver(identifier, submitter)
        override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResource = TODO()
    }
}
