# MoeMusic Plugin API Doc

This doc is for plugin developers using the shared MoeMusic API. It describes the current public contracts in `:api` and the runtime behavior implemented by `:core`. The shared API has no Minecraft, Fabric, Forge, or NeoForge dependency. If your plugin also needs loader APIs, add those dependencies in your plugin project and use the Mod bootstrap path described below.

Use the KDoc comments in the source files as the source of truth for signatures. This document explains how the pieces fit together and what patterns are expected to stay stable. The Chinese version is available at [api_zh.md](../docs/api_zh.md).

## Dependency

MoeMusic core artifacts are published to both Codeberg Packages (recommended) and GitHub Packages.

### Option A: Codeberg Packages (Recommended)
You can download public packages without an access token:

```kotlin
repositories {
    maven {
        name = "MoeMusic"
        url = uri("https://codeberg.org/api/packages/lolicode/maven")
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
}

dependencies {
    implementation("org.lolicode.moemusic:api:x.y.z") // Replace with the latest version
}
```

### Option B: GitHub Packages
You must provide a GitHub Personal Access Token to download artifacts:

```kotlin
repositories {
    maven {
        name = "MoeMusic"
        url = uri("https://maven.pkg.github.com/lolicode-org/MoeMusic")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: "your-github-username"
            password = System.getenv("GITHUB_TOKEN") ?: "your-github-token"
        }
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
}

dependencies {
    implementation("org.lolicode.moemusic:api:x.y.z") // Replace with the latest version
}
```

## Compatibility

[MoeMusicApi.API_VERSION](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt) is the plugin API compatibility version. It is checked against [Plugin.supportedApiVersions](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt). It will be the same as Maven artifact version for release builds, but snapshot builds may increment more frequently for internal compatibility tracking.

Keep your supported range explicit.
This project's API version follows semantic versioning, and breaking changes will increment the major version. So it's recommended to declare the supported range as >= the current minor version and < the next major version, to allow patch updates without needing plugin changes.

```kotlin
override val supportedApiVersions: String = ">=2.0.0 <3.0.0"
```

We strive to maintain ABI compatibility within the same major version, so you do not need to recompile your plugins for every new release. However, to manage maintenance costs and avoid excessive complexity, please keep the following points in mind when writing plugins:

### Read-Only Event and Outcome Types

Events (e.g. `OnPlaybackStarted`), service outcomes (e.g. `SubmitOutcome`, `QueueRemoveOutcome`), client models (`ClientSearchPage`, etc.), and sealed results (`UserResult`, `FilterVerdict`) are produced by the core and intended to be read-only from the plugin's perspective. Plugins should not construct, destructure, or `.copy()` these types. 
The core may append fields in minor API versions, so if you construct them in your plugin, there might be unexpected breaking changes.

Types that plugins **do** construct (`TrackInfo`, `SearchResult`, `SelectionEntry`, `PlaybackResource`, `ArtistInfo`) use an interface + builder DSL pattern and are safe to construct with the factory syntax (e.g. `TrackInfo(id, title, artists, durationMs) { ... }`). Adding optional fields to these is additive and non-breaking.

## Create A Plugin

A plugin implements the [Plugin](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt) interface. The long-lived server runtime callback is where you register sources, translations, event handlers, and config listeners.

```kotlin
import kotlinx.serialization.Serializable
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.event.OnTrackSubmitted
import org.lolicode.moemusic.api.event.subscribe
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.pluginConfigSpec

@Serializable
data class ExampleConfig(
    val enabled: Boolean = true,
)

object ExamplePlugin : Plugin {
    override val id: String = "example:source"
    override val configId: String = "example_source"
    override val displayName: LocalizedText = LocalizedText.key("plugin.example.name")
    override val version: String = "1.0.0"
    override val supportedApiVersions: String = ">=2.0.0 <3.0.0"

    override val configSpec = pluginConfigSpec(::ExampleConfig) {
        boolean(
            key = "enabled",
            getter = { it.enabled },
            updater = { config, value -> config.copy(enabled = value) },
        )
    }

    private var config: ExampleConfig = ExampleConfig()

    override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
        config = ctx.loadConfig(configSpec)
        ctx.registerMusicSource(ExampleSource)

        ctx.eventBus.subscribe<OnTrackSubmitted> { event ->
            ctx.logger.debug("Accepted track {} from {}", event.track.id, event.track.sourceId)
        }

        ctx.onConfigChanged(configSpec) { updated ->
            config = updated
        }
    }
}
```

Important plugin rules:

- `Plugin.id` is globally unique. Duplicate plugin IDs are fatal startup errors.
- `configId` must match `^[a-z0-9_-]+$`. The default sanitizes `id`, but namespaced IDs should usually override it for predictable filenames.
- `displayName` should normally be `LocalizedText.key(...)` backed by lang files.
- There is no hot reload. Jar changes require a JVM/game/server restart.
- Do not register sources or long-lived event handlers from `onServerSessionLoad`; that callback can run more than once in an integrated-server JVM.

## Bootstrap Paths

MoeMusic supports two plugin bootstrap paths. Developers can choose the appropriate mechanism depending on their technical requirements and target platforms:

### Comparison of Bootstrap Paths

| Dimension | Minecraft Mod Bootstrap | Standalone JAR Bootstrap |
| :--- | :--- | :--- |
| **Development & Build** | Requires Minecraft-specific mod development toolchains (e.g., Loom or Architectury). You may need to compile and adapt separate versions of the plugin for each target Minecraft version and mod loader, making the pipeline more complex. | Simpler development. It only depends on the `:api` module, requiring no Minecraft modding toolchain and freeing you from compatibility issues caused by loader or Minecraft updates. |
| **Cross-Platform Compatibility** | Hard-coded to specific Minecraft versions and mod loaders. Developers must closely monitor and adapt to breaking changes introduced by Minecraft updates. | **Cross-platform compatible**. Because it does not rely on Minecraft or any loader-specific APIs, the same compiled plugin JAR can theoretically be loaded and run on any major platform that integrates the MoeMusic core. |
| **APIs & Flexibility** | Full access to native Minecraft and mod loader (Fabric/NeoForge, etc.) APIs, offering maximum development freedom and flexibility. | Restricted to the public MoeMusic API. Cannot directly interact with loader-specific components or game engine subsystems. |
| **Dependency Resolution** | Leverages the mod loader's dependency management. Missing dependencies or runtime conflicts are automatically resolved or clearly reported in a user-friendly manner. | The built-in plugin loader is basic and lacks automatic dependency resolution or conflict management. External dependencies must be shaded/bundled into the fat JAR. |
| **Installation & Management** | Installed in the standard `mods/` directory. Users can manage, update, enable, or disable it using launcher-integrated mod managers. | Installed under the `config/moemusic/plugins/` directory. It cannot be managed by standard mod launchers, requiring users to manually download, install, and update. |
| **Publishing & Distribution** | Standard mod package format. Can be easily published to major mod distribution platforms like CurseForge or Modrinth. | Lacks mod-specific metadata descriptors (e.g., `fabric.mod.json`), which **may** result in stricter review or approval issues when uploading to mod distribution sites. |

**Minecraft mod bootstrap:**

```kotlin
import org.lolicode.moemusic.api.MoeMusicApi

fun onInitialize() {
    MoeMusicApi.registerPlugin(ExamplePlugin)
}
```

Call this from your Fabric, NeoForge, or other loader initializer before MoeMusic runtime initialization begins. This path is useful when your plugin also needs loader APIs.

**Standalone jar bootstrap:**

```kotlin
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.PluginProvider

class ExampleProvider : PluginProvider {
    override fun plugins(): Iterable<Plugin> = listOf(ExamplePlugin)
}
```

Package the jar with this service descriptor:

```text
META-INF/services/org.lolicode.moemusic.api.plugin.PluginProvider
```

The descriptor contains the provider class name:

```text
com.example.moemusic.ExampleProvider
```

Standalone jars are loaded from `config/moemusic/plugins/`. Provider classes must be public and have a public no-argument constructor. Standalone plugins are trusted local code, not sandboxed.

## Lifecycle And Contexts

The plugin lifecycle has runtime and session layers:

- `onServerRuntimeLoad(ctx)`: once per logical server runtime. Register sources, event handlers, translations, and config listeners here.
- `onServerSessionLoad(ctx)`: once per concrete server session. Use it for session-bound resources such as external playback audience leases.
- `onServerSessionUnload()`: release session-bound resources.
- `onServerRuntimeUnload()`: final logical-server runtime teardown.
- `onClientRuntimeLoad(ctx)`: once per client runtime. Use it for client playback/request integrations.
- `onClientRuntimeUnload()`: client shutdown.

Context layering is intentional:

- [PluginScopedContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L27): logger, i18n, plugin data directory, config load/save.
- [PluginRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L57): scoped context plus `eventBus` and `onConfigChanged`.
- [ServerRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L82): server services and `registerMusicSource`.
- [ServerSessionContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/ServerSessionContext.kt): per-session server services and `acquirePlaybackAudienceLease`.
- [ClientRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L130): client playback, request, and local content-filter services.

Raw server services such as `searchService`, `trackSubmissionService`, and `playbackController` do not apply shared permission or rate-limit checks. If your plugin is acting for a user, prefer `userActionService`, or explicitly check permissions and rate limits before doing work.

## Config And Localization

Plugin config is a single typed TOML file described by `Plugin.configSpec`. The config file lives under:

```text
config/moemusic/plugin-configs/<configId>.toml
```

Custom plugin state belongs under:

```text
config/moemusic/plugin-data/<configId>/
```

Config data classes must be serializable by Kotlin serialization. Validators return `null` when valid or `LocalizedText` when invalid; do not throw for ordinary bad input.

Lang files should be bundled under:

```text
assets/<namespace>/lang/en_us.json
assets/<namespace>/lang/zh_cn.json
```

For both standalone plugin jars and loader-mod plugins, `<namespace>` is always derived from `Plugin.id.substringBefore(':')`, not from the jar file name, provider class package, Fabric mod id, or NeoForge mod id. For example, a plugin with `Plugin.id == "soundcloud:music"` should bundle:

```text
assets/soundcloud/lang/en_us.json
assets/soundcloud/lang/zh_cn.json
```

Pass [LocalizedText](../api/src/main/kotlin/org/lolicode/moemusic/api/LocalizedText.kt) through your API results and exceptions, and let MoeMusic render it at the final user boundary. Use `LocalizedText.plain(...)` only for text that is already final, such as an upstream track title.

## Subscribe To Events

Plugins receive a shared observational event bus through runtime contexts:

```kotlin
ctx.eventBus.subscribe<OnTrackSubmitted> { event ->
    // Observe only. The submission has already been accepted.
}
```

Event delivery is synchronous and inline on the thread that calls `fire()`. Subscriber order is unspecified. Handlers cannot cancel or mutate the workflow. If a handler needs slow I/O, dispatch that work away from the event callback.

Connection events have two layers:

- [OnServerPlayerConnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L21) / [OnServerPlayerDisconnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L33): raw server connection events for every user, including users without a MoeMusic-capable client.
- [OnUserSessionStarted](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L44) / [OnUserParticipationChanged](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L56) / [OnUserSessionEnded](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L67): MoeMusic client session and active/standby participation state.

Client connection events are [OnClientConnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L197) and [OnClientDisconnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L206); use `onClientRuntimeUnload()` for final JVM/client shutdown cleanup.

## Create A Music Source

Every source implements the [MusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt) interface. Add [SearchableMusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L46) if it supports text search, and [IdentifierResolvableMusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L77) if it can interpret raw IDs or share links.

```kotlin
import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.IdentifierResolutionResult
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.SearchableMusicSource
import org.lolicode.moemusic.api.SourceNetworkException
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.model.ArtistInfo
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo

object ExampleSource : SearchableMusicSource, IdentifierResolvableMusicSource {
    override val id: String = "example"
    override val displayName: LocalizedText = LocalizedText.key("source.example")

    override suspend fun search(
        query: SearchQuery,
        submitter: MoeMusicUser?,
    ): UserResult<SearchResult> {
        val entries = listOf(
            SelectionEntry(
                selectionId = "track-123",
                title = "Example Song",
                artists = listOf(ArtistInfo(id = "artist-1", name = "Example Artist")),
                durationMs = 180_000,
                kind = org.lolicode.moemusic.api.model.SelectionEntryKind.TRACK,
            ),
        )

        return UserResult.Success(
            SearchResult(
                entries = entries,
                sourceId = id,
                total = entries.size,
                hasMore = false,
            ),
        )
    }

    override suspend fun getTrackInfo(
        trackId: String,
        submitter: MoeMusicUser?,
    ): UserResult<TrackInfo?> {
        if (trackId != "track-123") return UserResult.Success(null)
        return UserResult.Success(
            TrackInfo(
                id = trackId,
                title = "Example Song",
                artists = listOf(ArtistInfo(id = "artist-1", name = "Example Artist")),
                durationMs = 180_000,
            ) {
                // Optional fields are set in the builder block. `TrackInfo` is an interface built
                // through this DSL (not a data class), so new optional fields can be added without
                // breaking older plugins.
                sourceId = ExampleSource.id
            },
        )
    }

    override suspend fun resolve(
        track: TrackInfo,
        submitter: MoeMusicUser?,
    ): PlaybackResolution {
        val url = runCatching { signPlaybackUrl(track.id) }
            .getOrElse { throw SourceNetworkException(it) }
        return PlaybackResolution(PlaybackResource(url = url)) {
            trackPatch = ResolvedTrackPatch {
                loudness = LoudnessInfo {
                    integratedLufs = -14.2
                    peak = PeakInfo(0.83) { kind = PeakKind.UNKNOWN }
                }
            }
        }
    }

    override suspend fun resolveIdentifier(
        identifier: String,
        submitter: MoeMusicUser?,
    ): IdentifierResolutionResult {
        if (!identifier.startsWith("example:")) return IdentifierResolutionResult.Pass
        val trackId = identifier.removePrefix("example:")
        return when (val result = getTrackInfo(trackId, submitter)) {
            is UserResult.Success -> result.value
                ?.let(IdentifierResolutionResult::Resolved)
                ?: IdentifierResolutionResult.Blocked(LocalizedText.key("error.example.not_found"))
            is UserResult.Error -> IdentifierResolutionResult.Blocked(result.message)
        }
    }

    private fun signPlaybackUrl(trackId: String): String =
        "https://media.example.invalid/$trackId"
}
```

Source method contracts:

- `TrackInfo.id` is an opaque key owned by the source. It does not have to be a bare platform ID; use typed keys when one source exposes multiple upstream resource types.
- `TrackInfo.loudness` is optional source-supplied loudness metadata. Put integrated LUFS in `loudness.integratedLufs`, and optionally attach `loudness.peak` when the source also has a trustworthy peak reading.
- `search(...)` returns user-visible `SelectionEntry` rows. Empty result is a successful empty page, not an exception.
- Direct track rows must use `SelectionEntryKind.TRACK` and put the final stable `TrackInfo.id` key in `selectionId`.
- Container rows can use `CONTAINER` or `UNKNOWN` and later resolve through `resolveSelection(...)`.
- `getTrackInfo(...)` returns `Success(null)` for not found, `UserResult.Error` for expected user-facing rejection, and throws only when the lookup must abort.
- `resolve(...)` returns a [PlaybackResolution](../api/src/main/kotlin/org/lolicode/moemusic/api/model/PlaybackResolution.kt). It may be called again for the same track on resume, seek, or late-join sync, so treat playback URLs as renewable.
- Attach resolve-time metadata such as loudness or synchronized lyrics through `PlaybackResolution.trackPatch`. That patch is intentionally limited to a small subset of writable track fields.
- `resolveIdentifier(...)` returns `Pass` when the input does not belong to your source, `Blocked` for expected refusal, `Resolved` for a final track, or `Choices` for a further selection step.
- Generic resolvers such as plain HTTP should set `isFallbackResolver = true` so source-specific share-link handlers run first.

Check permissions before expensive or sensitive work. For custom source-private permissions, use `submitter?.hasPermission("your.node", defaultLevel = 2)` before network I/O. If you want MoeMusic's built-in checked submit/search path, call `userActionService` from a runtime context instead of raw services.

## Track Availability, Filtering, And Submission

Use [TrackInfo.unavailableReason](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackInfo.kt) or [SelectionEntry.unavailableReason](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt) only for inherent, unbypassable source-level restrictions such as VIP-only content, region lock, deleted media, or no stream URL.

Do not put shared content-filter hits in `unavailableReason`. If a source checks richer metadata such as descriptions or tags, return a `FilterVerdict` through `sourceFilterVerdict`; the submission gate will enforce it with the same bypass rules as built-in filters.

The canonical submission path is [ITrackSubmissionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt):

- `submitBySourceAndId(sourceId, trackId, ...)`: submits a stable source track key after an authoritative `getTrackInfo(...)` lookup.
- `submitBySelection(sourceId, selectionId, ...)`: submits or expands a source-owned selection row.
- `submitResolved(track, ...)`: accepts caller-supplied metadata and refreshes it from the owning source when possible. Use this at untrusted boundaries.
- `submitResolvedFromSource(track, ...)`: accepts a `TrackInfo` that was just returned by its owning source in the same server-side flow. It skips the extra metadata refresh but still stamps the submitter, checks duration, filters, availability, and queue policy.

For user-facing actions, prefer [IUserActionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IUserActionService.kt). It applies built-in permissions and shared request budgets before reaching the raw services.

## Client Runtime APIs

Client plugins receive [ClientRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L130) in `onClientRuntimeLoad`.

[IClientPlaybackService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt) exposes local playback state, search source catalog, participation state, configured/effective volume, per-server playback enablement, and transient volume overrides. Volume is stored as an integer percent. Transient overrides are runtime-only and attenuation-oriented; they never raise playback above the configured volume.

[IClientRequestService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientRequestService.kt) exposes typed client-to-server requests for search, queue snapshots, submission, selection, identifier submission, playback control, queue removal, and content-filter mutations. These methods can fail with `ClientRequestException` when there is no compatible connection, the handshake is missing, or the request times out.

## API Package Reference

### `org.lolicode.moemusic.api`

- [MoeMusicApi](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt): explicit plugin registration and API compatibility version.
- [Plugin](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt), [PluginProvider](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginProvider.kt), and [MusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt)-related contracts are referenced from here or subpackages.
- [LocalizedText](../api/src/main/kotlin/org/lolicode/moemusic/api/LocalizedText.kt), [I18nRegistry](../api/src/main/kotlin/org/lolicode/moemusic/api/I18nRegistry.kt): locale-independent user-facing text and translation registry.
- [UserResult](../api/src/main/kotlin/org/lolicode/moemusic/api/UserResult.kt): expected user-facing success/error result.
- [UserFacingException](../api/src/main/kotlin/org/lolicode/moemusic/api/UserFacingException.kt) and subclasses: exceptional user-visible aborts.
- [MoeMusicUser](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicUser.kt): platform-agnostic user identity, locale, and custom permission checks.
- [DuplicateRegistrationException](../api/src/main/kotlin/org/lolicode/moemusic/api/DuplicateRegistrationException.kt): fatal duplicate plugin/source ID error.

### `org.lolicode.moemusic.api.plugin`

- [Plugin](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt): lifecycle entry point.
- [PluginProvider](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginProvider.kt): Java SPI provider for standalone jars.
- [PluginConfigSpec](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginConfigSpec.kt) and entry types: typed plugin config schema and generated UI metadata.
- [PluginScopedContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L27), [PluginRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L57), [ServerRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L82), [ServerSessionContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/ServerSessionContext.kt), [ClientRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L130): lifecycle-scoped capabilities.
- [PlaybackAudienceLease](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PlaybackAudienceLease.kt): session-scoped bridge for non-native playback consumers.

### `org.lolicode.moemusic.api.model`

- [TrackInfo](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackInfo.kt), [ArtistInfo](../api/src/main/kotlin/org/lolicode/moemusic/api/model/ArtistInfo.kt), [PlaybackResource](../api/src/main/kotlin/org/lolicode/moemusic/api/model/PlaybackResource.kt), [TrackContext](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackContext.kt), [PlaybackState](../api/src/main/kotlin/org/lolicode/moemusic/api/model/PlaybackState.kt): track and playback state models.
- [SearchQuery](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L13), [SearchResult](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L59), [SelectionEntry](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L30), [SelectionResolveResult](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L70): search and selection flow models.
- [TrackAddMode](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackAddTypes.kt#L6), [TrackAddResult](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackAddTypes.kt#L19): queue placement intent and result.
- [ContentFilterRules](../api/src/main/kotlin/org/lolicode/moemusic/api/model/ContentFilter.kt#L69) and rule models: shared content-filter data.

### `org.lolicode.moemusic.api.service`

- Raw server services: [IPlaybackController](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IPlaybackController.kt), [ISearchService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ISearchService.kt), [IIdentifierResolutionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IIdentifierResolutionService.kt), [ITrackSubmissionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt).
- Checked user-behalf service: [IUserActionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IUserActionService.kt).
- Shared policy services: [IPermissionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IPermissionService.kt), [IRateLimitService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IRateLimitService.kt), [IContentFilterService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IContentFilterService.kt), [IMediaProbeService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IMediaProbeService.kt).
- Outcome models: [IdentifierResolutionOutcome](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IIdentifierResolutionService.kt#L18), [SubmitOutcome](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt#L19), [SelectionSubmitOutcome](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt#L32), queue/playback action results, and [FilterVerdict](../api/src/main/kotlin/org/lolicode/moemusic/api/service/FilterVerdict.kt).

### `org.lolicode.moemusic.api.event`

- [EventBus](../api/src/main/kotlin/org/lolicode/moemusic/api/event/EventBus.kt) and `subscribe<T>`.
- Server connection/session events, search/resolution/submission events, server playback events, client playback/connection events, and content-filter mutation events.

### `org.lolicode.moemusic.api.client`

- [IClientPlaybackService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt): local playback state and controls.
- [IClientRequestService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientRequestService.kt): typed request/response API to the connected server.
- Client response models, search catalog models, content-filter mutation models, and [ClientVolumeOverride](../api/src/main/kotlin/org/lolicode/moemusic/api/client/ClientVolumeOverride.kt).

### `org.lolicode.moemusic.api.permission`

- [MoeMusicPermission](../api/src/main/kotlin/org/lolicode/moemusic/api/permission/MoeMusicPermission.kt): built-in public MoeMusic permission groups understood by `IPermissionService`.
- Custom plugin-owned nodes should use `MoeMusicUser.hasPermission(...)` directly.

## Practical Checklist

- Register your plugin once, before MoeMusic runtime initialization.
- Register music sources only during `onServerRuntimeLoad`.
- Use `LocalizedText.key` for messages the user may see.
- Use `UserResult.Error` for expected "no" answers and `UserFacingException` for aborted operations.
- Use `userActionService` for user-behalf actions unless you intentionally need raw services.
- Check permissions and rate limits before network I/O or other resource-consuming work.
- Keep direct track identity stable as `(sourceId, trackId)`.
- Keep plugin config/data under the MoeMusic plugin config/data directories, not inside the executable jar.
