package org.lolicode.moemusic.core.user

import kotlinx.coroutines.runBlocking
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.PermissionDeniedException
import org.lolicode.moemusic.api.RateLimitedException
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.permission.MoeMusicPermission
import org.lolicode.moemusic.api.service.*
import org.lolicode.moemusic.core.config.MediaPolicyConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.config.RequestRateLimitConfig
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.*

class UserActionServiceImplTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("moemusic-user-action-test")
        ModConfigManager.load(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `search checks permission before rate limit and delegate`() = runBlocking {
        configureRateLimit(searchRequests = 1, submitRequests = 10)
        val user = FakeUser()
        var denySearch = true
        var searchCalls = 0

        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                requireHandler = { permission, _ ->
                    if (permission == MoeMusicPermission.SEARCH && denySearch) {
                        throw PermissionDeniedException(LocalizedText.plain("denied"))
                    }
                }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = object : ISearchService {
                override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): SearchResult {
                    searchCalls++
                    return SearchResult(entries = emptyList(), sourceId = "alpha", total = 0)
                }
            },
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = RecordingPlaybackController(),
        )

        assertFailsWith<PermissionDeniedException> {
            service.search(SearchQuery(query = "test"), user)
        }
        denySearch = false

        service.search(SearchQuery(query = "test"), user)

        assertEquals(1, searchCalls, "denied searches must not consume the rate limit or reach the raw delegate")
    }

    @Test
    fun `search rate limit blocks before raw delegate`() = runBlocking {
        configureRateLimit(searchRequests = 1, submitRequests = 10)
        val user = FakeUser()
        var searchCalls = 0

        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = object : ISearchService {
                override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): SearchResult {
                    searchCalls++
                    return SearchResult(entries = emptyList(), sourceId = "alpha", total = 0)
                }
            },
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = RecordingPlaybackController(),
        )

        service.search(SearchQuery(query = "test"), user)
        assertFailsWith<RateLimitedException> {
            service.search(SearchQuery(query = "test"), user)
        }

        assertEquals(1, searchCalls, "rate-limited searches must be rejected before the raw search service runs")
    }

    @Test
    fun `submit by source and id requires submit and queue control before delegating play now`() = runBlocking {
        configureRateLimit(searchRequests = 10, submitRequests = 10)
        val user = FakeUser()
        val callOrder = mutableListOf<String>()

        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                requireHandler = { permission, _ ->
                    callOrder += "require:$permission"
                }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = object : ITrackSubmissionService {
                override suspend fun submitBySourceAndId(
                    sourceId: String,
                    trackId: String,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome {
                    callOrder += "delegate"
                    return SubmitOutcome(SampleTrack, TrackAddResult.QUEUED)
                }

                override suspend fun submitBySelection(
                    sourceId: String,
                    selectionId: String,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SelectionSubmitOutcome = error("unused")

                override suspend fun submitResolved(
                    track: TrackInfo,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome = error("unused")

                override suspend fun submitResolvedFromSource(
                    track: TrackInfo,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome = error("unused")
            },
            playbackController = RecordingPlaybackController(),
        )

        service.submitBySourceAndId("alpha", "track-1", user, TrackAddMode.PLAY_NOW)

        assertEquals(
            listOf(
                "require:${MoeMusicPermission.SUBMIT}",
                "require:${MoeMusicPermission.QUEUE_CONTROL}",
                "delegate",
            ),
            callOrder,
        )
    }

    @Test
    fun `submit by source and id requires separate skip autoplay permission`() = runBlocking {
        configureRateLimit(searchRequests = 10, submitRequests = 10)
        val user = FakeUser()
        val callOrder = mutableListOf<String>()

        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                requireHandler = { permission, _ ->
                    callOrder += "require:$permission"
                }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = object : ITrackSubmissionService {
                override suspend fun submitBySourceAndId(
                    sourceId: String,
                    trackId: String,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome {
                    callOrder += "delegate"
                    return SubmitOutcome(SampleTrack, TrackAddResult.QUEUED)
                }

                override suspend fun submitBySelection(
                    sourceId: String,
                    selectionId: String,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SelectionSubmitOutcome = error("unused")

                override suspend fun submitResolved(
                    track: TrackInfo,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome = error("unused")

                override suspend fun submitResolvedFromSource(
                    track: TrackInfo,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome = error("unused")
            },
            playbackController = RecordingPlaybackController(),
        )

        service.submitBySourceAndId("alpha", "track-1", user, TrackAddMode.SKIP_AUTOPLAY)

        assertEquals(
            listOf(
                "require:${MoeMusicPermission.SUBMIT}",
                "require:${MoeMusicPermission.SUBMIT_SKIP_AUTOPLAY}",
                "delegate",
            ),
            callOrder,
        )
    }

    @Test
    fun `control playback with null requester bypasses permission but still performs action`() {
        val playbackController = RecordingPlaybackController()
        val permissionService = FakePermissionService()
        val service = UserActionServiceImpl(
            permissionService = permissionService,
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = playbackController,
        )

        val outcome = service.controlPlayback(PlaybackAction.PAUSE, requester = null)

        assertEquals(1, playbackController.pauseCalls)
        assertTrue(permissionService.requiredPermissions.isEmpty(), "system/null actions should not depend on user permission checks")
        assertEquals(PlaybackActionOutcome(), outcome)
    }

    @Test
    fun `skip uses queue control permission for immediate skip`() {
        val playbackController = RecordingPlaybackController()
        val user = FakeUser()
        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                hasHandler = { permission, _ -> permission == MoeMusicPermission.QUEUE_CONTROL }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = playbackController,
        )

        val outcome = service.controlPlayback(PlaybackAction.SKIP, requester = user)

        assertEquals(1, playbackController.skipCalls)
        assertEquals(PlaybackActionOutcome(), outcome)
    }

    @Test
    fun `queue remove uses queue control permission as ownership bypass`() {
        val playbackController = RecordingPlaybackController().apply {
            nextRemoveQueuedTrackResult = QueueRemoveResult.REMOVED
        }
        val user = FakeUser()
        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                hasHandler = { permission, _ -> permission == MoeMusicPermission.QUEUE_CONTROL }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = playbackController,
        )

        service.removeQueuedTrack("alpha", "track-1", user)

        assertEquals(true, playbackController.lastRemoveBypassOwnership)
        assertEquals(user, playbackController.lastRemoveRequester)
    }

    @Test
    fun `clearQueue allows self clearing without queue control permission`() {
        val playbackController = RecordingPlaybackController().apply {
            nextClearOutcome = QueueClearOutcome(removedCount = 3)
        }
        val user = FakeUser(id = UUID.randomUUID())
        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                hasHandler = { _, _ -> false }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = playbackController,
        )

        val outcome = service.clearQueue(targetUserId = user.id, requester = user)

        assertEquals(3, outcome.removedCount)
        assertNull(outcome.failure)
        assertEquals(user.id, playbackController.lastClearTargetUserId)
        assertEquals(user, playbackController.lastClearRequester)
        assertFalse(playbackController.lastClearBypassOwnership)
    }

    @Test
    fun `clearQueue rejects clearing other user or all when lacking queue control permission`() {
        val playbackController = RecordingPlaybackController()
        val user = FakeUser(id = UUID.randomUUID())
        val otherUser = UUID.randomUUID()
        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                hasHandler = { _, _ -> false }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = playbackController,
        )

        // Targeting other user
        val userOutcome = service.clearQueue(targetUserId = otherUser, requester = user)
        assertEquals(0, userOutcome.removedCount)
        assertEquals("error.moemusic.permission.queue_control", (userOutcome.failure as? LocalizedText.Key)?.key)

        // Targeting all
        val allOutcome = service.clearQueue(targetUserId = null, requester = user)
        assertEquals(0, allOutcome.removedCount)
        assertEquals("error.moemusic.permission.queue_control", (allOutcome.failure as? LocalizedText.Key)?.key)
    }

    @Test
    fun `clearQueue permits clearing all and other user when having queue control permission`() {
        val playbackController = RecordingPlaybackController().apply {
            nextClearOutcome = QueueClearOutcome(removedCount = 5)
        }
        val user = FakeUser(id = UUID.randomUUID())
        val otherUser = UUID.randomUUID()
        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                hasHandler = { permission, _ -> permission == MoeMusicPermission.QUEUE_CONTROL }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = playbackController,
        )

        val outcome = service.clearQueue(targetUserId = otherUser, requester = user)
        assertEquals(5, outcome.removedCount)
        assertNull(outcome.failure)
        assertEquals(otherUser, playbackController.lastClearTargetUserId)
        assertTrue(playbackController.lastClearBypassOwnership)
    }

    @Test
    fun `clearQueue rejects blank targetUserName when targetUserId is null`() {
        val playbackController = RecordingPlaybackController()
        val user = FakeUser(id = UUID.randomUUID())
        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                hasHandler = { permission, _ -> permission == MoeMusicPermission.QUEUE_CONTROL }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = NoopIdentifierResolutionService,
            trackSubmissionService = NoopTrackSubmissionService,
            playbackController = playbackController,
        )

        val outcome = service.clearQueue(targetUserId = null, targetUserName = "   ", requester = user)
        assertEquals(0, outcome.removedCount)
        assertEquals("error.moemusic.queue.clear_invalid_target", (outcome.failure as? LocalizedText.Key)?.key)
        assertNull(playbackController.lastClearTargetUserId)
    }

    @Test
    fun `identifier submit uses trusted source-resolved submission path`() = runBlocking {
        configureRateLimit(searchRequests = 10, submitRequests = 10)
        val user = FakeUser()
        val callOrder = mutableListOf<String>()

        val service = UserActionServiceImpl(
            permissionService = FakePermissionService(
                requireHandler = { permission, _ ->
                    callOrder += "require:$permission"
                }
            ),
            requestRateLimiter = RequestRateLimiter(nowMillis = { 0L }),
            searchService = NoopSearchService,
            identifierResolutionService = object : IIdentifierResolutionService {
                override suspend fun resolve(identifier: String, submitter: MoeMusicUser?): IdentifierResolutionOutcome {
                    callOrder += "resolve:$identifier"
                    return IdentifierResolutionOutcome.Resolved(SampleTrack, SampleTrack.sourceId!!)
                }
            },
            trackSubmissionService = object : ITrackSubmissionService {
                override suspend fun submitBySourceAndId(
                    sourceId: String,
                    trackId: String,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome = error("unused")

                override suspend fun submitBySelection(
                    sourceId: String,
                    selectionId: String,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SelectionSubmitOutcome = error("unused")

                override suspend fun submitResolved(
                    track: TrackInfo,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome = error("identifier submit should not use caller-supplied metadata path")

                override suspend fun submitResolvedFromSource(
                    track: TrackInfo,
                    submitter: MoeMusicUser?,
                    mode: TrackAddMode,
                ): SubmitOutcome {
                    callOrder += "trusted-submit:${track.id}"
                    return SubmitOutcome(track, TrackAddResult.QUEUED)
                }
            },
            playbackController = RecordingPlaybackController(),
        )

        val outcome = service.submitIdentifier("identifier", user, TrackAddMode.NORMAL)

        val submitted = assertIs<IdentifierSubmitOutcome.Submitted>(outcome)
        assertEquals(SampleTrack, submitted.track)
        assertEquals(
            listOf(
                "require:${MoeMusicPermission.SUBMIT}",
                "resolve:identifier",
                "trusted-submit:${SampleTrack.id}",
            ),
            callOrder,
        )
    }

    private fun configureRateLimit(searchRequests: Int, submitRequests: Int) {
        ModConfigManager.save(
            MoeMusicConfig(
                media = MediaPolicyConfig(
                    rateLimit = RequestRateLimitConfig(
                        enabled = true,
                        windowSeconds = 10,
                        searchRequests = searchRequests,
                        submitRequests = submitRequests,
                    )
                )
            )
        )
    }

    private class FakePermissionService(
        private val requireHandler: (MoeMusicPermission, MoeMusicUser?) -> Unit = { _, _ -> },
        private val hasHandler: (MoeMusicPermission, MoeMusicUser) -> Boolean = { _, _ -> false },
    ) : IPermissionService {
        val requiredPermissions: MutableList<Pair<MoeMusicPermission, MoeMusicUser?>> = mutableListOf()

        override fun has(permission: MoeMusicPermission, user: MoeMusicUser): Boolean =
            hasHandler(permission, user)

        override fun require(permission: MoeMusicPermission, user: MoeMusicUser?) {
            requiredPermissions += permission to user
            requireHandler(permission, user)
        }
    }

    private class FakeUser(
        override val id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123"),
        override val displayName: String = "TestUser",
    ) : MoeMusicUser() {
        override val locale: String = "en_us"
        override fun hasPermission(permission: String, defaultLevel: Int): Boolean = false
    }

    private class RecordingPlaybackController : IPlaybackController {
        var pauseCalls: Int = 0
        var skipCalls: Int = 0
        var lastRemoveRequester: MoeMusicUser? = null
        var lastRemoveBypassOwnership: Boolean = false
        var nextRemoveQueuedTrackResult: QueueRemoveResult = QueueRemoveResult.NOT_FOUND

        override val currentContext: TrackContext? = null
        override fun userQueueSnapshot(): List<TrackInfo> = emptyList()
        override fun play(track: TrackInfo, playback: PlaybackResource) = Unit
        override fun pause() { pauseCalls++ }
        override fun resume() = Unit
        override fun seek(positionMs: Long) = Unit
        override fun skip() { skipCalls++ }
        override fun stop() = Unit
        override fun enqueueAndPlay(track: TrackInfo) = Unit

        override fun removeQueuedTrack(
            sourceId: String,
            trackId: String,
            requester: MoeMusicUser?,
            bypassOwnership: Boolean,
        ): QueueRemoveResult {
            lastRemoveRequester = requester
            lastRemoveBypassOwnership = bypassOwnership
            return nextRemoveQueuedTrackResult
        }

        var lastClearTargetUserId: UUID? = null
        var lastClearTargetUserName: String? = null
        var lastClearRequester: MoeMusicUser? = null
        var lastClearBypassOwnership: Boolean = false
        var nextClearOutcome: QueueClearOutcome = QueueClearOutcome(removedCount = 0)

        override fun clearQueue(
            targetUserId: UUID?,
            targetUserName: String?,
            requester: MoeMusicUser?,
            bypassOwnership: Boolean,
        ): QueueClearOutcome {
            lastClearTargetUserId = targetUserId
            lastClearTargetUserName = targetUserName
            lastClearRequester = requester
            lastClearBypassOwnership = bypassOwnership
            return nextClearOutcome
        }
    }

    private companion object {
        val SampleTrack: TrackInfo = TrackInfo(id = "track-1", title = "Track 1", artists = listOf(ArtistInfo(id = "artist-1", name = "Artist 1")), durationMs = 60_000L) { sourceId = "alpha" }

        val NoopSearchService: ISearchService = object : ISearchService {
            override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): SearchResult =
                error("unused")
        }

        val NoopIdentifierResolutionService: IIdentifierResolutionService = object : IIdentifierResolutionService {
            override suspend fun resolve(identifier: String, submitter: MoeMusicUser?): IdentifierResolutionOutcome =
                IdentifierResolutionOutcome.NotFound
        }

        val NoopTrackSubmissionService: ITrackSubmissionService = object : ITrackSubmissionService {
            override suspend fun submitBySourceAndId(
                sourceId: String,
                trackId: String,
                submitter: MoeMusicUser?,
                mode: TrackAddMode,
            ): SubmitOutcome = error("unused")

            override suspend fun submitBySelection(
                sourceId: String,
                selectionId: String,
                submitter: MoeMusicUser?,
                mode: TrackAddMode,
            ): SelectionSubmitOutcome = error("unused")

            override suspend fun submitResolved(
                track: TrackInfo,
                submitter: MoeMusicUser?,
                mode: TrackAddMode,
            ): SubmitOutcome = error("unused")

            override suspend fun submitResolvedFromSource(
                track: TrackInfo,
                submitter: MoeMusicUser?,
                mode: TrackAddMode,
            ): SubmitOutcome = error("unused")
        }
    }
}
