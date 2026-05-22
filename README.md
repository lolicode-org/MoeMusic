# MoeMusic

[简体中文](./README_zh.md) | English

> [!TIP]
> This repository contains the **core library** of MoeMusic, including core business logic, API interfaces, and models.
> For the ready-to-run Minecraft Mod (Fabric / NeoForge) or its launcher-specific code, please visit [MoeMusic Mod](https://github.com/lolicode-org/MoeMusic-Minecraft).

> [!NOTE]
> Most of the codebase in this repository is generated and refined with AI assistance. While all components are human reviewed, tested, and validated, if you encounter any issues during use, please feel free to open an issue or submit a pull request.

MoeMusic is a Kotlin library designed for synchronized multi-user music playback across multiple platforms (Windows, Linux, macOS). While not directly tied to any Minecraft components, it is optimized for Minecraft mod development and integrates with Minecraft modding environments, so some concepts may require additional adaptation to fit into non-Minecraft projects.

---

## Getting Started

### Dependency Configuration

MoeMusic core components are published to Codeberg Packages and GitHub Packages. 

Detailed installation and build setup instructions (including repository declarations for Gradle) are located in the API Documentation:
- **English**: [docs/api.md](docs/api.md#dependency-and-compatibility)
- **Chinese**: [docs/api_zh.md](docs/api_zh.md#依赖与兼容性)

### Documentation Links
- **Public API Guide**: [docs/api.md](docs/api.md) | [docs/api_zh.md](docs/api_zh.md)
- **Architecture & Maintenance**: [docs/maintenance.md](docs/maintenance.md)
- **Example Audio Source Project**: [MoeMusic-source-template](https://github.com/lolicode-org/MoeMusic-source-template)

---

## Configuration & Concept

MoeMusic organizes configurations at runtime:
- **Core Settings**: Written to `config/moemusic/moemusic.toml` and managed by [ModConfigManager](core/src/main/kotlin/org/lolicode/moemusic/core/config/ModConfigManager.kt).
- **Plugin Settings**: Individual TOML configs are automatically created under `config/moemusic/plugin-configs/`.
- **Plugin Data**: Persistent directories for custom plugins are created under `config/moemusic/plugin-data/`.

---

## Core Features & Code Implementation

MoeMusic is split into modular components:
- `:api` holds public definitions, interfaces, and event schemas.
- `:core` implements server-side business logic, session synchronization, and playback scheduling.
- `:client-core` handles local client playback, locking, and firewall validation.

Here is a breakdown of core features mapped to their code implementations:

### 1. Audio Decoding & Playback
- **Implementation**: Powered by `lavaplayer`.
- **Details**: Decodes diverse formats (MP3, OGG, WAV, FLAC, M3U/PLS, etc.) in a cross-platform manner. The client-side audio volume and playback state are coordinated by [IClientPlaybackService](api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt), separating base configuration volume from transient runtime overrides.

### 2. Time & Playback Synchronization
- **Implementation**: Core queue and controller routines.
- **Details**: Handles server-side queue ordering, playback progression, vote-skipping, and autoplay. Clients receive synchronization signals to align audio playback progress across multiple players.

### 3. Plugin Lifecycle & Architecture
- **Implementation**: [Plugin](api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt) and [PluginProvider](api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginProvider.kt).
- **Details**: Supports both standalone JAR loading via Java SPI under `config/moemusic/plugins/` and platform mod loader registration via [MoeMusicApi.registerPlugin](api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt). Scoped contexts ([PluginScopedContext](api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt)) govern lifecycle boundaries.

### 4. Custom Audio Sources
- **Implementation**: [MusicSource](api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt) and [SearchableMusicSource](api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt).
- **Details**: Allows developers to register custom music providers. If search is supported, implementing `SearchableMusicSource` integrates the source into the server catalog, supporting paginated queries.

### 5. Content Filtering & Media Firewall
- **Implementation**: [ContentFilterRules](api/src/main/kotlin/org/lolicode/moemusic/api/model/ContentFilter.kt) and client-side firewall.
- **Details**: Enforces content rules on both client and server to filter out sensitive content. The client-side firewall validates server-delivered audio links against white/blacklists to prevent security hazards (e.g. unsolicited IP discovery).

### 6. Permissions & Abuse Prevention
- **Implementation**: [IPermissionService](api/src/main/kotlin/org/lolicode/moemusic/api/service/IPermissionService.kt) and [TrackSubmissionService](core/src/main/kotlin/org/lolicode/moemusic/core/playback/TrackSubmissionService.kt).
- **Details**: Protects server resources by checking permissions before issuing music source searches, media probing, or queue controls. Rate limiting protects external endpoint queries, and `TrackSubmissionService` enforces maximum track durations and submitter validations.

### 7. Event Bus
- **Implementation**: [EventBus](api/src/main/kotlin/org/lolicode/moemusic/api/event/EventBus.kt).
- **Details**: Distributes synchronous events (connection changes, search, playback updates, and filter changes) to registered plugins.

### 8. Localization & Multi-language Support
- **Implementation**: [I18nRegistry](api/src/main/kotlin/org/lolicode/moemusic/api/I18nRegistry.kt) and JSON files under `assets/moemusic/lang/`.
- **Details**: Synchronizes client regional settings with the server. Text formatting is resolved server-side to target the user's localized preference before sending packet payloads.
- **Language Overrides**: Users can override or supply translation keys dynamically by placing JSON translation files in `<configDir>/lang/<namespace>/*.json` (where `<namespace>` is `moemusic` for core/built-ins, or the prefix of a plugin's ID).

### 9. Single-Instance Playback Lock
- **Implementation**: [InstancePlaybackLock](client-core/src/main/kotlin/org/lolicode/moemusic/clientcore/playback/InstancePlaybackLock.kt).
- **Details**: Ensures only one client instance on the same device owns the active audio output device at a time, preventing overlapping playback from multiple clients. The lock uses a platform-neutral file lock (`tryLock()`) on a system-specific state directory.

---

## Acknowledgements

MoeMusic stands on the shoulders of these open-source projects:
- [lavaplayer](https://github.com/lavalink-devs/lavaplayer) - Core audio decoding and playback
- [ktoml](https://github.com/orchestr7/ktoml) - TOML configuration file support
- [wire](https://github.com/square/wire) - Protobuf packet serialization
- [codeberg](https://codeberg.org/) - Code hosting and artifact distribution
- [GitHub](https://github/) - Repository mirror and CI automation
- [Kotlin](https://kotlinlang.org/) - The primary programming language

---

## License

MoeMusic is licensed under the **GNU Affero General Public License v3.0 or later** (AGPL-3.0-or-later). See the [LICENSE](./LICENSE) file for details.
