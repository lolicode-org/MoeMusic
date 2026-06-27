# MoeMusic

简体中文 | [English](./README.md)

> [!TIP]
> 本仓库存放的是 MoeMusic 的**核心库**，包含了核心业务逻辑、API 接口和共享数据模型。
> - 如果你需要获取可直接运行的 Minecraft Mod（Fabric / NeoForge / Forge）或其平台适配层源码，请访问 [MoeMusic Mod](https://github.com/lolicode-org/MoeMusic-Minecraft)。
> - 如果你需要完全脱离 Minecraft 的独立轻量终端客户端实现，请访问 [MoeMusic Terminal](https://github.com/KoishiMoe/MoeMusic-Terminal)。该项目可作为核心库可移植性与可扩展性的概念验证，同时在开发音源插件时，也可用于进行快速测试，避免启动 Minecraft 实例占用过多资源。

> [!NOTE]~~~~
> 本仓库的大部分代码在 AI 辅助下编写。虽然我人工对每个组件进行了审查和测试，但仍可能存在预料之外的问题。如在使用中遇到任何问题，欢迎提交 issue 或 pull request。

MoeMusic 是一个专为多人同步播放音乐而设计的 Kotlin 库，支持多平台（Windows、Linux、macOS）。

虽然它本身并不直接依赖 Minecraft 的任何组件，但它的核心逻辑是针对 Minecraft 联机环境进行设计和适配的，因此如果需要在其他项目中使用，可能需要额外的适配。

---

## 快速入门

### 引入依赖

MoeMusic 发布在 Codeberg Packages 和 GitHub Packages 上。 

有关如何在 Gradle 中声明仓库和引入依赖的具体配置，请参阅 API 开发文档的对应章节。

### 文档链接
- **开发接口指南**：[docs/api_zh.md](docs/api_zh.md) | [docs/api.md](docs/api.md)
- **架构设计与维护说明**：[docs/maintenance.md](docs/maintenance.md)
- **插件列表**：[GitHub Wiki 插件列表](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8)（你可以在此提交自己的插件）
- **自定义音源模板**：[MoeMusic-source-template](https://github.com/lolicode-org/MoeMusic-source-template)

---

## 基础配置与核心概念

MoeMusic 的运行配置划分如下：
- **核心配置**：写入 `config/moemusic/moemusic.toml`，由核心配置管理器 [ModConfigManager](core/src/main/kotlin/org/lolicode/moemusic/core/config/ModConfigManager.kt) 统一读写。
- **插件配置**：插件声明配置结构后，其独立的 TOML 配置文件将自动在 `config/moemusic/plugin-configs/` 目录下生成。
- **插件数据**：每个插件专属的持久化数据目录位于 `config/moemusic/plugin-data/`。

---

## 核心功能与代码实现

MoeMusic 采用模块化设计：
- `:api` 定义了公开的接口、生命周期上下文及事件模型。
- `:core` 实现了服务端播放队列、时间同步、内容过滤和插件加载器等核心业务。
- `:client-core` 包含了客户端播放状态锁、本地音量管理及安全防火墙机制。

以下是按模块实现划分的核心功能介绍：

### 1. 音频解码与播放
- **代码实现**：底层基于 `lavaplayer` 实现跨平台音频解码。
- **功能细节**：支持 MP3、OGG、WAV、FLAC、M3U/PLS 等多种音频流格式。客户端的音量控制通过 [IClientPlaybackService](api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt) 进行管理，区分持久化的基础配置音量与临时的运行时覆盖音量。

### 2. 时间同步与播放控制
- **代码实现**：核心播放控制器和队列调度机制。
- **功能细节**：支持播放队列管理、切歌投票、自动切歌以及服务端与客户端时钟同步，以对齐参与联机用户的播放进度。

### 3. 插件生命周期管理
- **代码实现**：[Plugin](api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt) 和 [PluginProvider](api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginProvider.kt)。
- **功能细节**：支持两种加载方式——通过 Java SPI 从 `config/moemusic/plugins/` 目录加载独立 JAR 插件，或通过 [MoeMusicApi.registerPlugin](api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt) 手动注册。分层上下文（如 [PluginScopedContext](api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt)）为各生命周期阶段提供了安全的 API 访问。

### 4. 自定义音源扩展
- **代码实现**：[MusicSource](api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt) 和 [SearchableMusicSource](api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt)。
- **功能细节**：开发者可以通过注册自定义音源来扩充音乐获取渠道。如果实现了 `SearchableMusicSource` 接口，该音源还会被编入客户端的搜索目录，支持分页查询。

### 5. 内容过滤与安全防火墙
- **代码实现**：[ContentFilterRules](api/src/main/kotlin/org/lolicode/moemusic/api/model/ContentFilter.kt) 与客户端媒体防火墙。
- **功能细节**：服务端与客户端协作拦截特定曲目的提交。此外，客户端配备了媒体防火墙，防止服务端推送危险外链（支持黑白名单自定义过滤）。

### 6. 权限控制与防滥用
- **代码实现**：[IPermissionService](api/src/main/kotlin/org/lolicode/moemusic/api/service/IPermissionService.kt) 与 [TrackSubmissionService](core/src/main/kotlin/org/lolicode/moemusic/core/playback/TrackSubmissionService.kt)。
- **功能细节**：在发起音源解析、搜索和控制播放前优先核对权限。系统内置了防刷限频（Rate Limiting）机制，同时 `TrackSubmissionService` 会在服务端校验歌曲时长限制及提交者权限，以防资源滥用。

### 7. 事件总线
- **代码实现**：[EventBus](api/src/main/kotlin/org/lolicode/moemusic/api/event/EventBus.kt)。
- **功能细节**：在内存中广播和订阅各项运行时事件（包含会话连接、搜索、队列变更及播放状态转换等），供注册插件进行监听。

### 8. 多语言与本地化支持
- **代码实现**：[I18nRegistry](api/src/main/kotlin/org/lolicode/moemusic/api/I18nRegistry.kt) 和 `assets/moemusic/lang/` 下的 JSON 本地化文本。
- **功能细节**：支持将客户端区域语言设置同步至服务端，以便服务端根据用户的语言偏好格式化文本消息。
- **语言覆盖功能**：支持读取外部语言文件进行翻译覆盖或扩展。只需将自定义的 JSON 语言文件放置在配置目录下的 `lang/<namespace>/*.json` 路径即可（核心与内置功能的 `<namespace>` 为 `moemusic`，对于插件则是其 ID 命名空间前缀）。

### 9. 单实例播放锁
- **代码实现**：[InstancePlaybackLock](client-core/src/main/kotlin/org/lolicode/moemusic/clientcore/playback/InstancePlaybackLock.kt)。
- **功能细节**：确保在同一台设备上，同一时间只有一个客户端实例能够占用音频输出通道，避免多个客户端的播放声音发生重叠。该锁利用平台中立的文件锁 (`tryLock()`) 在系统特定的状态目录下进行实现。

---

## 致谢

MoeMusic 依赖以下开源项目的支持：
- [lavaplayer](https://github.com/lavalink-devs/lavaplayer) - 音频解码与播放引擎
- [ktoml](https://github.com/orchestr7/ktoml) - TOML 配置文件读写支持
- [wire](https://github.com/square/wire) - Protobuf 数据序列化支持
- [codeberg](https://codeberg.org/) - 代码托管及 Maven 仓库分发
- [GitHub](https://github/) - 代码托管与 CI 流水线
- [Kotlin](https://kotlinlang.org/) - 核心开发语言

---

## 许可证

MoeMusic 采用 **GNU Affero General Public License v3.0 or later** (AGPL-3.0-or-later) 开源许可证发布。详情请参阅 [LICENSE](./LICENSE) 文件。
