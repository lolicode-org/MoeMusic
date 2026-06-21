# MoeMusic Shared Architecture And Maintenance Notes

This document collects architecture and maintainer notes for the shared MoeMusic codebase.

## Scope

The shared project is the version-independent kernel for MoeMusic. It must not depend on Minecraft, Fabric, NeoForge, or loader APIs.

- [api](../api) defines the public plugin API, runtime events, plugin config schema, service contracts, permission model, API compatibility version, and shared data models.
- [core](../core) owns server-side business logic: playback orchestration, queueing, search, source resolution, submission checks, plugin lifecycle/config I/O, content filtering, media URL policy, request throttling, protocol definitions, and built-in sources.
- [client-core](../client-core) owns Minecraft-free client runtime/model logic: playback availability, instance playback locking, client volume state, direct request services, media firewall helpers, HUD layout models, cover decoding, and pure audio decode/runtime helpers.

Keep Minecraft-facing adaptation in the platform repository. If shared code needs a capability from Minecraft, define a narrow loader-neutral interface in the shared project and implement it in platform code.

This split exists so Minecraft version ports can replace loader glue without rewriting the plugin API, queue/search/submission behavior, or client playback model. Keep shared logic testable without a game runtime and keep loader APIs outside shared modules.

## Java And Compatibility

- [api](../api), [core](../core), and [client-core](../client-core) target Java 17.
- Loader-facing modules compile the Minecraft adapter sources at the Java level required by their Minecraft branch.
- [MoeMusicApi.API_VERSION](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt#L25) is a generated compatibility version, not the same as the Maven artifact version. Snapshot artifact versions should not change the plugin compatibility signal by themselves.
- `MoeMusicProtocol.VERSION` is generated in [core](../core) (via [build.gradle.kts](../core/build.gradle.kts#L34-L47)) and is the client/server wire compatibility signal. Do not use the plugin API version as the protocol compatibility check.

The public ABI surface is currently [api](../api). Kotlin ABI validation is enabled there; update the committed dump at [api.api](../api/api/api.api) when intentionally changing the public API.

When changing public API shape or semantics, update KDocs and the public guides at [api.md](api.md) and [api_zh.md](api_zh.md).

### Public API Evolution Rules

These conventions keep the plugin API binary-compatible across minor versions. A value type is only
an ABI hazard for whoever *constructs/copies* it: plugins construct source-output models; the host
constructs events and service outcomes.

1. **Source-output value models are interfaces, not `data class`es.** Models that plugins build
   (`TrackInfo`, `SelectionEntry`, `SearchResult`, `PlaybackResource`, `ArtistInfo`) are a sealed
   interface + `*Builder` + frozen factory/`copy { }` DSL, backed by an `internal` impl that stays
   off the ABI dump. Add a new optional field as a default getter on the interface plus a `var` on
   the builder — both additive. Never add a field to a fixed constructor and never expose these as
   `data class`es.
2. **Interfaces evolve additively.** With `jvmDefault` enabled, add a **default** method, a new
   sub-interface, or a new optional via the builder. Never add an abstract method or a new parameter
   to an existing method.
3. **Sealed result wrappers grow by adding a subtype, never a field** (`UserResult`, `FilterVerdict`,
   `IdentifierResolutionResult`, `SelectionResolveResult`).
4. **Growing, plugin-read enumerations are open token types** (`@JvmInline value class` with
   companion constants + `entries`/`of`), e.g. `TrackAddResult`, `QueueRemoveResult`,
   `UserParticipationState`, `ContentFilterMutationTarget`, `SelectionEntryKind`,
   `ClientAvailabilityIssue`, `PlaybackStartCause`. Consumers must always `else` over them.
   Truly closed sets stay `enum`/`sealed` and are documented as exhaustive; enums that are
   `@Serializable` (e.g. the `ContentFilterText*` rules) stay enums to preserve persisted data and
   carry a "non-exhaustive across versions" note instead.
5. **Events and service-outcome `data class`es are read-only**: plugins must not construct,
   destructure, or `copy` them; appending fields is then binary-safe for read-only consumers.
6. Any intentional public-API change regenerates [api.api](../api/api/api.api); a binary-breaking
   change bumps the **major** `moemusic-api-compat`.

## Protocol Evolution Rules
### Enums

The accessor default is always the enum value 0. If a value that do not exist in the current build 
(mostly sent by a newer build), the enum will be mapped to the default value. If the default value 
is *not* an *_UNSPECIFIED/*_UNKNOWN sentinel but a semantic value instead, this will introduce unexpected 
behaviors. To avoid this, follow these rules:

1. *New Wire Enums*: **Always** set enum value 0 as an *_UNSPECIFIED/*_UNKNOWN sentinel and add
   checks around it, unless it's **guaranteed** to never change in the future.
2. *Existing Wire Enums*: **Avoid** adding new enum values to them. If new target is needed, 
   treat it as a protocol-breaking change and gate that on the sender side, by protocol/capability version.

## Package Ownership

The shared implementation is organized by feature ownership:

- [core.protocol](../core/src/main/kotlin/org/lolicode/moemusic/core/protocol): packet ids, generated protobuf package, protocol version, and view mappers.
- [core.network](../core/src/main/kotlin/org/lolicode/moemusic/core/network): server packet request/response orchestration behind a small platform session bridge.
- [core.transport](../core/src/main/kotlin/org/lolicode/moemusic/core/transport): loader-neutral send port.
- [core.user](../core/src/main/kotlin/org/lolicode/moemusic/core/user): checked user actions.
- [core.source](../core/src/main/kotlin/org/lolicode/moemusic/core/source): source search and identifier resolution.
- [core.media](../core/src/main/kotlin/org/lolicode/moemusic/core/media) and [core.media.probe](../core/src/main/kotlin/org/lolicode/moemusic/core/media/probe): media URL policy and server probing.
- [core.ratelimit](../core/src/main/kotlin/org/lolicode/moemusic/core/ratelimit): request throttling.
- [core.contentfilter](../core/src/main/kotlin/org/lolicode/moemusic/core/contentfilter): filter runtime and rule editing.
- [core.playback](../core/src/main/kotlin/org/lolicode/moemusic/core/playback): queueing, playback control, lyrics, vote-skip, autoplay, and audience leases.

Prefer adding code to the existing feature package instead of creating cross-cutting utility packages. Cross-cutting helpers should be introduced only when several feature packages already share a concrete, stable need.

## Plugin Model

Plugins have two bootstrap paths. Standalone plugins are self-contained jars under `config/moemusic/plugins/` and expose [PluginProvider](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginProvider.kt#L14) through Java's service descriptor. Minecraft loader mods that need Fabric/NeoForge/Minecraft bootstrap can call [MoeMusicApi.registerPlugin(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt#L47) during their initializer.

Duplicate [Plugin.id](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L47) and [MusicSource.id](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L124) registrations are fatal. Built-in plugins, API-registered plugins, and standalone jar plugins are merged during [PluginManager.initialize(...)](../core/src/main/kotlin/org/lolicode/moemusic/core/plugin/PluginManager.kt#L136), and source registration must go through the runtime registration path so collisions fail fast with ownership details.

Standalone plugin jars are trusted local code, not sandboxed. Their classloader is child-first for plugin classes but parent-first for the Java/Kotlin runtime, SLF4J, and MoeMusic API types. This avoids duplicate API classes while still letting a standalone jar carry its own implementation dependencies. There is no hot reload guarantee; jar changes require a JVM/game/server restart.

Plugin lifecycle has runtime and session layers:

- [onServerRuntimeLoad](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L100) / [onServerRuntimeUnload](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L115): once per logical server runtime in the JVM.
- [onServerSessionLoad](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L103) / [onServerSessionUnload](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L112): once per concrete Minecraft server session.
- [onClientRuntimeLoad](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L109) / [onClientRuntimeUnload](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L118): once per client runtime.
- [OnClientConnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L197) / [OnClientDisconnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L206): current client connection scope; disconnect fires before the final local session/playback snapshot is cleared.

There is no hot reload guarantee. Config reloads should notify config listeners rather than rerunning plugin lifecycle callbacks, because lifecycle callbacks may register long-lived sources and event handlers.

Public API package organization is intentional:

- [org.lolicode.moemusic.api](../api/src/main/kotlin/org/lolicode/moemusic/api): shared primitives.
- [org.lolicode.moemusic.api.plugin](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin): plugin lifecycle, config, and context contracts.
- [org.lolicode.moemusic.api.service](../api/src/main/kotlin/org/lolicode/moemusic/api/service): exposed service contracts.

## Runtime Contexts

Context layering is part of the public API design:

- [PluginScopedContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L27): logger, i18n, plugin data directory, and config I/O.
- [PluginRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L57): scoped context plus `eventBus` and `onConfigChanged`.
- [ServerRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L82): server runtime services plus checked `userActionService`.
- [ClientRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L130): client playback and request services.
- [ServerSessionContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/ServerSessionContext.kt): per-session-safe server capabilities.

[ServerSessionContext.acquirePlaybackAudienceLease()](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/ServerSessionContext.kt#L59) is the bridge hook for non-native playback consumers. Plugins should hold a lease while they have an active external listener; the first lease resumes/starts playback as needed, and releasing the final lease allows auto-pause.

Do not move long-lived registration APIs onto [ServerSessionContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/ServerSessionContext.kt) as a convenience. Integrated singleplayer can create several concrete server sessions in one JVM, so session callbacks are intentionally shaped for resources that can be safely acquired and released repeatedly.

## Event Bus

[EventBus](../api/src/main/kotlin/org/lolicode/moemusic/api/event/EventBus.kt) is the shared runtime plugin bus. It persists across integrated-server world restarts; reset it only on final runtime teardown or tests.

Delivery is synchronous and inline on the thread that calls `fire()`. The call returns only after all matching handlers complete. Subscriber order is unspecified.

User session lifecycle events have two layers:

- Raw server connection events cover every server user.
- MoeMusic participation events cover compatible client sessions after handshake/participation is known.

Keep this split. Compatibility plugins sometimes need raw join/leave cleanup for users without the MoeMusic client, while playback and audience logic needs the more precise participation state.

## Config Model

Plugin config is defined through [Plugin.configSpec](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt#L66). Loading and saving goes through [PluginScopedContext.loadConfig(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L46) and [saveConfig(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L49). Plugin TOML files live under `config/moemusic/plugin-configs/`, while custom plugin data directories live under `config/moemusic/plugin-data/`.
When a plugin exposes a config spec, MoeMusic creates the missing default TOML file during plugin manager initialization and leaves existing files untouched.

Plugin config filenames are derived from `configId`, which must match:

```text
^[a-z0-9_-]+$
```

Server-side live reload has a narrow safe boundary: `default_source_id`, `default_language`, `vote_required_percent`, `permissions`, `content_filter`, `media`, and `autoplay` can be reapplied without restarting the logical server runtime. Client-local `client.*` settings are not part of that server reload path.

Shared `moemusic.toml` writes should go through [ModConfigManager.update(...)](../core/src/main/kotlin/org/lolicode/moemusic/core/config/ModConfigManager.kt#L97) or subsection helpers. Avoid rebuilding the whole root from stale UI snapshots because it can overwrite newer changes made elsewhere in the same JVM.

Plugin configs are deliberately separate from `moemusic.toml`. This keeps plugin-owned state portable, avoids core config write conflicts, and lets generated config screens save one plugin without rewriting unrelated server policy.

Enum config entries have two UI intents:

- `enumSelector(...)` for ordinary fixed choice sets.
- `enumDropdown(...)` for searchable/filterable option lists. Use dropdowns carefully because filtering can make unmatched options temporarily invisible.

## Permissions And Abuse Boundaries

Check permissions before any action that consumes resources, has side effects, or can be abused. Examples include search/source API calls, media probes, submissions, playback controls, and config mutations.

Permission resolution order is:

1. singleplayer owner or real server console
2. optional advanced permission provider
3. vanilla fallback level

Do not treat every non-user command source as trusted. Only the real server console should bypass checks.

Public permission groups are semantic, not command-shaped:

- `moemusic.common.submit`
- `moemusic.common.submit.skip_autoplay`
- `moemusic.moderation.queue_control`
- `moemusic.common.vote`
- `moemusic.moderation.playback_control`
- `moemusic.common.view_queue`
- `moemusic.common.search`

Admin and built-in source-private permissions should follow the same semantic style. Direct HTTP/HTTPS URL submission is intentionally more restricted because it can expose server/client IPs to untrusted hosts.

Submission-side abuse controls live at authoritative gates. [TrackSubmissionService](../core/src/main/kotlin/org/lolicode/moemusic/core/playback/TrackSubmissionService.kt) enforces track duration policy, and checked packet/command/plugin entry points apply request rate limiting before external I/O.

## Content Filtering And Media Policy

Content filtering and media firewall behavior belong in shared config/core logic, not thin loader adapters.

The content filter uses a two-permission model:

- `moemusic.privilege.bypass.filter`: bypasses user-driven content-filter gates such as search-query checks and track submission checks. Internal/null-submitter work is never bypassed.
- `moemusic.moderation.filter_manage`: controls rule detail visibility. Managers may see specific rejection details; ordinary users should receive generic managed-filter messages.

[FilterVerdict](../api/src/main/kotlin/org/lolicode/moemusic/api/service/FilterVerdict.kt) is `Allow` or `Reject(reason)`. Do not add a sensitivity flag; masking is done at the wire/user boundary based on permission.

Sources that perform their own text checks should return [FilterVerdict](../api/src/main/kotlin/org/lolicode/moemusic/api/service/FilterVerdict.kt) through [TrackInfo.sourceFilterVerdict](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackInfo.kt#L52) or [SelectionEntry.sourceFilterVerdict](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L62). [TrackInfo.unavailableReason](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackInfo.kt#L51) and [SelectionEntry.unavailableReason](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L61) are for inherent, unbypassable source-level failures such as VIP or region lock, not policy filters.

Autoplay has no submitter, so no permission bypass applies. It must filter fetched tracks through the content filter runtime.

## Search, Resolution, And Submission

Search is single-source per request, not aggregated across all sources.

[MusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L115) exposes `displayName`; text search support is expressed by implementing [SearchableMusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L46). Direct HTTP playback stays available without pretending to be a searchable source.

Search pagination uses `limit`, `offset`, `total`, and explicit `hasMore` semantics.

`TrackInfo.id` is an opaque source-owned key. It may be a direct URL, a typed platform key such as `song:123`, or any other stable value the source can later pass to `getTrackInfo(...)` and `resolve(...)`.

`SelectionEntry.kind == SelectionEntryKind.TRACK` is a stable identity contract. Such rows must use the final stable `TrackInfo.id` as `selectionId`, and known direct-track rows should submit through `(sourceId, trackId)` rather than `resolveSelection(...)`.

Identifier resolution is two-pass:

1. specific resolvers where `isFallbackResolver == false`
2. fallback resolvers where `isFallbackResolver == true`

This prevents share links from being probed as raw media files before source-specific resolvers have a chance to claim them.

[TrackSubmissionService](../core/src/main/kotlin/org/lolicode/moemusic/core/playback/TrackSubmissionService.kt) is the canonical submission path. `submitBySourceAndId(...)` and `submitResolved(...)` refresh metadata from authoritative [getTrackInfo()](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L177) when possible; `submitResolvedFromSource(...)` is reserved for tracks just returned by their owning source in the same server-side flow and skips that extra lookup. Every path still stamps submitter, checks duration, filters, inherent availability, and queue policy before enqueueing.

The wire submission format sends `(source_id, track_id, mode)`. The server must not trust client-provided track metadata.

Queue removal should target stable track identity, not visible display order: use `(source_id, track_id)` for packets and commands.

## Client Shared Runtime

Client volume is stored as an integer percent (`0..100`) in config/UI and converted to floating gain only at the audio boundary.

[IClientPlaybackService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt) distinguishes configured/base volume from effective runtime volume:

- [setConfiguredVolumePercent(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt#L37) persists the base value.
- [setTransientVolumeOverride(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt#L50) and [clearTransientVolumeOverride(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt#L55) are runtime-only layering hooks for client plugins and must not leak into persisted config.

Track loudness normalization is a separate client-local gain layer. Sources may populate `TrackInfo.integratedLufs` directly from `getTrackInfo(...)` or return it later through `resolve(...).trackPatch`; the client uses that metadata for attenuation-only normalization against `client.loudness_normalization.target_lufs`. The configured MoeMusic volume remains the ceiling: normalization must never boost output above the user-owned volume / override result.

The client volume-state layer is internally thread-safe. Plugins may call the public client volume APIs from non-Minecraft threads without scheduling onto the Minecraft client thread.

Screen-facing client runtime models belong in [client-core](../client-core), not nested inside platform UI handlers.

Client runtime code may expose public API through `api/client`, but the implementation should remain independent from Minecraft classes. Platform code should adapt Minecraft networking, audio output, and UI rendering at the edge.

### Single-Instance Playback Lock

To prevent multiple clients running on the same local device from playing music simultaneously and creating overlapping/noisy audio output, MoeMusic uses [InstancePlaybackLock](../client-core/src/main/kotlin/org/lolicode/moemusic/clientcore/playback/InstancePlaybackLock.kt).

- **Lock Protocol**: The lock is backed by a filesystem file lock (`FileChannel.tryLock()`), meaning it is co-operative and enforced at the OS level. The lock file is saved in the OS-specific state directory:
  - **Windows**: `%APPDATA%/moemusic/instance-playback.lock` (or `%LOCALAPPDATA%` fallback)
  - **macOS**: `~/Library/Application Support/moemusic/instance-playback.lock`
  - **Linux/Other**: `$XDG_STATE_HOME/moemusic/instance-playback.lock` (or `~/.local/state/moemusic/instance-playback.lock` fallback)
- **Lifecycle**: The lock is not acquired greedily on client launch. It is acquired only when a track begins playing or playback state becomes relevant (e.g. `PlaybackSnapshotPush` with a current track, or a resume-like `StateUpdate`). If the lock cannot be acquired, the client switches its playback participation to `STANDBY` while the accepted session-level clock sync continues. The lock is released on playback `STOPPED`, disconnect, or when the user explicitly opts out.

## Networking Contracts

Networking uses one physical channel with a typed packet registry keyed by packet ids. Avoid stringly-typed ad-hoc dispatch.

The client must send [PacketIds.CLIENT_HANDSHAKE](../core/src/main/kotlin/org/lolicode/moemusic/core/protocol/PacketIds.kt#L17) on join even when local playback is disabled or the instance playback lock is unavailable. This lets the server learn the client's locale and reply to GUI/search/queue requests.

[PacketIds.CLIENT_HANDSHAKE](../core/src/main/kotlin/org/lolicode/moemusic/core/protocol/PacketIds.kt#L17) carries the initial client state. Mid-connection participation changes use [PacketIds.CLIENT_STATE_CHANGE](../core/src/main/kotlin/org/lolicode/moemusic/core/protocol/PacketIds.kt#L20).

Server-side single-recipient sends should fail closed. Only allowlisted direct-response/handshake packets may target standby or pre-registered sessions; playback/state packets require active user-registry membership.

Server handshake includes the source catalog (`id`, display name, searchable flag, default source) used by client search UI.

## Localization

Shared `assets/moemusic/lang/*.json` bundles must be loaded independently of discovered plugins. Core rendering is used even when zero plugins are installed, so localization cannot depend on built-in plugin discovery.

User-facing, expected workflow failures should use [UserFacingException](../api/src/main/kotlin/org/lolicode/moemusic/api/UserFacingException.kt) or [UserResult](../api/src/main/kotlin/org/lolicode/moemusic/api/UserResult.kt) and should not be logged as unexpected warnings/errors at packet, command, or client response boundaries.

Unknown exceptions are still implementation failures. Classify them at user boundaries, keep the user-facing message short, and log enough detail for operators.

## Build And Publishing

The shared build publishes [api](../api), [core](../core), and [client-core](../client-core).

Platform builds should support both dependency modes:

- local sibling composite build through `includeBuild("../shared")` for development
- published shared artifacts for CI/release builds when the sibling build is absent or disabled

Shared artifact versions are catalog-backed. The shared project uses separate versions for API artifacts, protocol compatibility, core, and client-core. Keep generated build-info values tied to those catalog versions.

Keep repository declarations consistent across subprojects so shading and published-artifact fallback resolve the same dependencies.
