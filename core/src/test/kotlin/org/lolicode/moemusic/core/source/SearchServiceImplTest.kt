package org.lolicode.moemusic.core.source

import kotlinx.coroutines.runBlocking
import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.event.OnSearchCompleted
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.core.config.MediaPolicyConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.testing.TestUser
import java.nio.file.Files
import kotlin.test.*

class SearchServiceImplTest {

    init {
        ModConfigManager.load(Files.createTempDirectory("moemusic-search-test"))
    }

    @BeforeTest
    fun resetConfig() {
        val config = MoeMusicConfig()
        ModConfigManager.save(config)
        ContentFilterRuntime.applyConfig(config)
    }

    @Test
    fun `uses reported source total for hasMore`() = runBlocking {
        val source = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"
            override val displayName: LocalizedText = LocalizedText.plain("Alpha")

            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> {
                val entries = (query.offset until query.offset + query.limit).map { index ->
                    SelectionEntry(selectionId = "track-$index", title = "Track $index", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = "alpha" }
                }
                return UserResult.Success(SearchResult(
                    entries = entries,
                    sourceId = id,
                    total = 45,
                ))
            }

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val outcome = SearchServiceImpl(listOf(source)).searchWithOutcome(
            SearchQuery(
                query = "test",
                sourceId = source.id,
                limit = 20,
                offset = 20,
            )
        )

        assertEquals(20, outcome.entries.size)
        assertEquals(45, outcome.total)
        assertTrue(outcome.hasMore)
        assertEquals(source.id, outcome.sourceId)
    }

    @Test
    fun `defaults to first searchable source`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val nonSearchable = object : MusicSource {
            override val id: String = "http"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }
        val searchable = object : MusicSource, SearchableMusicSource {
            override val id: String = "beta"
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> =
                UserResult.Success(
                    SearchResult(
                        entries = listOf(
                            SelectionEntry(selectionId = "beta-1", title = "Beta Result", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = "beta" }
                        ),
                        sourceId = id,
                        total = 1,
                    )
                )

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val service = SearchServiceImpl(listOf(nonSearchable, searchable))
        val outcome = service.searchWithOutcome(
            SearchQuery(
                query = "test",
                limit = 20,
                offset = 0,
            )
        )

        assertEquals("beta", service.defaultSearchSourceId())
        assertEquals("beta", outcome.sourceId)
        assertEquals(listOf("beta-1"), outcome.entries.map { it.selectionId })
        assertFalse(outcome.hasMore)
    }

    @Test
    fun `configured default searchable source is preferred`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig(defaultSourceId = "beta"))
        val alpha = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> =
                UserResult.Success(
                    SearchResult(
                        entries = listOf(SelectionEntry("alpha-1", "Alpha", listOf("Artist").toArtistInfos(), 60_000) { sourceId = "alpha" }),
                        sourceId = id,
                        total = 1,
                    )
                )
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }
        val beta = object : MusicSource, SearchableMusicSource {
            override val id: String = "beta"
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> =
                UserResult.Success(
                    SearchResult(
                        entries = listOf(SelectionEntry("beta-1", "Beta", listOf("Artist").toArtistInfos(), 60_000) { sourceId = "beta" }),
                        sourceId = id,
                        total = 1,
                    )
                )
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val service = SearchServiceImpl(listOf(alpha, beta))
        val outcome = service.searchWithOutcome(SearchQuery(query = "test"))

        assertEquals("beta", service.defaultSearchSourceId())
        assertEquals(listOf("beta-1"), outcome.entries.map { it.selectionId })
    }

    @Test
    fun `non searchable configured default falls back to first searchable source`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig(defaultSourceId = "http"))
        val nonSearchable = object : MusicSource {
            override val id: String = "http"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }
        val searchable = object : MusicSource, SearchableMusicSource {
            override val id: String = "beta"
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> =
                UserResult.Success(
                    SearchResult(
                        entries = listOf(SelectionEntry("beta-1", "Beta", listOf("Artist").toArtistInfos(), 60_000) { sourceId = "beta" }),
                        sourceId = id,
                        total = 1,
                    )
                )
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val service = SearchServiceImpl(listOf(nonSearchable, searchable))

        assertEquals("beta", service.defaultSearchSourceId())
    }

    @Test
    fun `typed source exceptions become localized search failures`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val source = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> {
                throw SourceTimeoutException()
            }
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val outcome = SearchServiceImpl(listOf(source)).searchWithOutcome(
            SearchQuery(query = "test", sourceId = source.id)
        )

        assertTrue(outcome.entries.isEmpty())
        assertEquals(LocalizedText.key("error.moemusic.source.timeout"), outcome.failure)
    }

    @Test
    fun `unexpected source exceptions become generic search failures`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val source = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> {
                throw IllegalStateException("boom")
            }
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val outcome = SearchServiceImpl(listOf(source)).searchWithOutcome(
            SearchQuery(query = "test", sourceId = source.id)
        )

        assertTrue(outcome.entries.isEmpty())
        assertEquals(LocalizedText.key("error.moemusic.internal"), outcome.failure)
    }

    @Test
    fun `search limit is clamped by server config`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig(media = MediaPolicyConfig(maxSearchResultsPerPage = 7)))
        var seenLimit = -1
        val source = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> {
                seenLimit = query.limit
                return UserResult.Success(
                    SearchResult(
                        entries = (0 until query.limit).map { index ->
                            SelectionEntry("track-$index", "Track $index", listOf("Artist").toArtistInfos(), 60_000) { sourceId = "alpha" }
                        },
                        sourceId = id,
                        total = query.limit,
                    )
                )
            }

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val outcome = SearchServiceImpl(listOf(source)).searchWithOutcome(
            SearchQuery(query = "test", sourceId = source.id, limit = 99),
        )

        assertEquals(7, seenLimit)
        assertEquals(7, outcome.entries.size)
    }

    @Test
    fun `query content filter rejects before searching source`() = runBlocking {
        val config = MoeMusicConfig(
            contentFilter = ContentFilterRules(
                enabled = true,
                textRules = listOf(
                    ContentFilterTextRule(
                        pattern = "spoiler",
                        mode = ContentFilterTextRuleMode.SUBSTRING,
                        scope = ContentFilterTextRuleScope.ALL,
                        ignoreCase = true,
                    )
                ),
            ),
        )
        ModConfigManager.save(config)
        ContentFilterRuntime.applyConfig(config)

        var sourceCalled = false
        val source = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"

            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> {
                sourceCalled = true
                return UserResult.Success(SearchResult(entries = emptyList(), sourceId = id, total = 0))
            }

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val error = assertFailsWith<FilterBlockException> {
            SearchServiceImpl(listOf(source)).searchWithOutcome(
                SearchQuery(query = "spoiler opening", sourceId = source.id),
                TestUser(),
            )
        }

        assertFalse(sourceCalled)
        val reason = assertIs<LocalizedText.Key>(error.fullReason)
        assertEquals("error.moemusic.content_filter.text_blocked", reason.key)
    }

    @Test
    fun `search completed event uses effective query and result payload`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig(defaultSourceId = "alpha", media = MediaPolicyConfig(maxSearchResultsPerPage = 7)))
        PluginManager.eventBus.clear()
        var event: OnSearchCompleted? = null
        PluginManager.eventBus.subscribe(OnSearchCompleted::class.java) { event = it }

        val source = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"

            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> =
                UserResult.Success(
                    SearchResult(
                        entries = listOf(
                            SelectionEntry("track-1", "Track 1", listOf("Artist").toArtistInfos(), 60_000) { sourceId = "alpha" }
                        ),
                        sourceId = id,
                        total = 1,
                    )
                )

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val outcome = SearchServiceImpl(listOf(source)).searchWithOutcome(
            SearchQuery(query = "test", limit = 99, offset = -5)
        )

        val completed = assertNotNull(event)
        assertEquals(outcome.entries, completed.entries)
        assertEquals(1, completed.total)
        assertFalse(completed.hasMore)
        assertEquals("alpha", completed.sourceId)
        assertEquals("alpha", completed.query.sourceId)
        assertEquals(7, completed.query.limit)
        assertEquals(0, completed.query.offset)
        assertEquals(null, completed.failure)
    }

    @Test
    fun `empty page keeps reported total instead of inflating to offset`() = runBlocking {
        ModConfigManager.save(MoeMusicConfig())
        val source = object : MusicSource, SearchableMusicSource {
            override val id: String = "alpha"

            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> =
                UserResult.Success(
                    SearchResult(
                        entries = emptyList(),
                        sourceId = id,
                        total = 45,
                    )
                )

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution = TODO()
        }

        val outcome = SearchServiceImpl(listOf(source)).searchWithOutcome(
            SearchQuery(query = "test", sourceId = source.id, limit = 10, offset = 50)
        )

        assertTrue(outcome.entries.isEmpty())
        assertEquals(45, outcome.total)
        assertFalse(outcome.hasMore)
    }
}
