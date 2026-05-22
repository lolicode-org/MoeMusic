# AGENTS.md

This file contains public guidance for agents working in the MoeMusic shared repository.

## Project Scope

- `:api` is the public plugin API, runtime events, plugin config schema, service contracts, permission model, API compatibility version, and shared models.
- `:core` owns loader-neutral server/runtime behavior: playback orchestration, queueing, search, source resolution, plugin lifecycle/config I/O, content filtering, media policy, rate limits, protocol definitions, and builtin sources.
- `:client-core` owns Minecraft-free client runtime/model behavior: playback availability, instance locking, volume state, request helpers, media firewall helpers, cover decoding, HUD models, and pure audio runtime helpers.
- Keep Minecraft, Fabric, Forge, NeoForge, and loader APIs out of this repository. Add narrow shared interfaces only when platform code genuinely needs to provide a capability to shared logic.

## Public API Changes

- Treat `org.lolicode.moemusic.api.**` as the supported public ABI.
- When changing public API shape or semantics, update KDocs, `docs/api.md`, `docs/api_zh.md`, and the ABI dump at `api/api/api.api`.
- Keep API package ownership clear:
  - `org.lolicode.moemusic.api` for shared primitives.
  - `org.lolicode.moemusic.api.plugin` for plugin lifecycle/config/context contracts.
  - `org.lolicode.moemusic.api.service` for exposed service contracts.
  - `org.lolicode.moemusic.api.client`, `.event`, `.model`, and `.permission` for their respective public surfaces.

## Architecture Rules

- Register music sources through `ServerRuntimeContext.registerMusicSource(...)` during `Plugin.onServerRuntimeLoad(...)`.
- Do not add long-lived registration APIs to `ServerSessionContext`; integrated-server sessions may restart inside one JVM.
- Keep `EventBus` observational. Events are synchronous, non-cancellable, and should not become workflow mutation hooks.
- Use `IUserActionService` for user-behalf actions that need built-in permissions and rate limits. Raw services intentionally skip those checks.
- Check permissions *before* network I/O, media probing, submissions, config mutation, or other resource-consuming work.
- Keep source identity stable as `(sourceId, trackId)`. Direct `SelectionEntryKind.TRACK` entries must use the final source-local track id as `selectionId`.
- Use `unavailableReason` only for inherent source-level unavailability. Use `sourceFilterVerdict` for content-filter findings so bypass rules remain possible.

## Build And Test

- The shared modules target Java 17.
- Useful verification tasks:
  - `./gradlew check`
  - `./gradlew checkPublicApi`
  - `./gradlew updatePublicApi` when an intentional API change needs a refreshed ABI dump.
- Prefer focused tests for narrow changes, and broaden coverage when changing shared behavior used by commands, packets, plugins, or client runtime.

## Documentation

- `docs/maintenance.md` is the public maintenance note for long-lived shared design decisions. Read it before making design decisions or starting work, and update it when making durable decisions that should be remembered.
- `docs/api.md` and `docs/api_zh.md` are the public plugin API guides.
- Keep docs concise and tied to current code. Avoid session notes, local machine setup, or stale branch-specific workarounds in public shared docs.
- Use in-code comments and KDocs for technical details, special cases, workarounds, and non-obvious implementation choices. Public docs should focus on design decisions, usage guidance, and long-term maintenance notes.
