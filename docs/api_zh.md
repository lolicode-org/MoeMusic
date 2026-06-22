# MoeMusic 插件 API 文档

本文档面向 MoeMusic 插件开发者，说明 `:api` 模块的公开接口以及 `:core` 模块当前实现的运行时行为。该公共接口不依赖 Minecraft 或 Fabric、NeoForge 等模组加载器；如果插件需要使用特定加载器提供的功能，请将相关依赖添加到插件自身的构建配置中，并按照本文档描述的模组注册方式进行注册。

关于方法签名和具体类型的最准确信息，请参考 KDoc。本文档主要介绍这些接口的组合方式和稳定约定。英文版 [api.md](../docs/api.md) 包含更完整的代码示例。

## 依赖

MoeMusic 核心组件发布在 Codeberg Packages（推荐）和 GitHub Packages 上。

### 选项 A：Codeberg Packages（推荐）
无需配置 Access Token 即可直接拉取公开依赖包：

```kotlin
repositories {
    maven {
        name = "MoeMusic"
        url = uri("https://codeberg.org/api/packages/lolicode/maven")
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
}

dependencies {
    implementation("org.lolicode.moemusic:api:x.y.z") // 请替换为最新版本
}
```

### 选项 B：GitHub Packages
需要配置 GitHub 认证 Token 才能下载依赖包：

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
    implementation("org.lolicode.moemusic:api:x.y.z") // 请替换为最新版本
}
```

## 兼容性

[MoeMusicApi.API_VERSION](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt) 是插件接口的内部版本，用于与 [Plugin.supportedApiVersions](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt) 进行匹配。正式发布版的接口版本号会和 Maven 产物版本号保持一致；快照版的接口版本号可能更频繁变动，不保证与 Maven 版本号完全同步。

建议在插件中显式声明所支持的 API 版本范围。
本项目接口版本遵循语义化版本规范，并且在主版本号不变的前提下保持向后兼容。因此，推荐的声明方式为：大于等于当前的最新次要版本号，并且小于下一个主版本号，例如：

```kotlin
override val supportedApiVersions: String = ">=2.0.0 <3.0.0"
```

我们尽力保持在同一主版本号下的ABI兼容，因此你不必在每个新版本发布时重新编译你的插件。但出于维护成本或避免引入过多复杂度考虑，在编写插件时请留意如下事项：

### 只读事件与结果类型

事件（如 `OnPlaybackStarted`）、服务结果（如 `SubmitOutcome`、`QueueRemoveOutcome`）、客户端模型（`ClientSearchPage` 等）以及密封结果（`UserResult`、`FilterVerdict`）均由核心产生，插件侧应**只读**使用。
请避免在插件中构造、解构或 `.copy()` 这些类型。我们可能会在次要版本的API更新中向其中增加字段，如果您在插件中构造它们，则可能面临意外的破坏性更改。

插件**可以**构造的类型（`TrackInfo`、`SearchResult`、`SelectionEntry`、`PlaybackResource`、`ArtistInfo`）采用接口 + Builder DSL 模式，可通过工厂语法安全构造（如 `TrackInfo(id, title, artists, durationMs) { ... }`）。为这些类型添加可选字段是纯增量的，不会造成破坏。

## 创建插件

插件的入口类需要实现 [Plugin](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt) 接口。其中，生命周期较长的服务端运行时回调方法 `onServerRuntimeLoad(ctx)` 是注册音源、本地化翻译、订阅事件以及监听配置变更的核心入口。

核心开发规则：

- `Plugin.id` 必须全局唯一；若存在重复的 ID，将在模组启动阶段抛出致命错误并终止运行。
- `configId` 必须符合正则表达式 `^[a-z0-9_-]+$`。尽管该值默认会根据 `id` 派生，但为了保证配置文件名和生成路径的稳定性，建议显式声明。
- `displayName` 建议使用 [LocalizedText.key(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/LocalizedText.kt)，并由对应的语言文件（Language Files）提供具体的本地化翻译。
- 不支持插件热重载；替换插件 Jar 包后，需要重启游戏客户端或服务端。
- 请勿在 `onServerSessionLoad` 回调中注册音源或长生命周期的事件监听器。因为在单人游戏或联机时，Minecraft 的集成服务端（Integrated Server）在同一个 JVM 进程中可能会多次启动和关闭 Server Session。

一个典型的插件结构通常包含：

1. 声明 `id`、`version` 以及 `supportedApiVersions`；
2. （可选）定义配置规范 `configSpec`；
3. （可选）在 `onServerRuntimeLoad` 中调用 `ctx.registerMusicSource(...)` 注册音源；
4. 通过 `ctx.eventBus.subscribe<T> { ... }` 订阅并观察所需事件；
5. 通过 `ctx.onConfigChanged(spec) { ... }` 实时监听并处理配置项变更。

## 注册与加载方式

MoeMusic 支持以下两种插件注册与加载方式。开发者可以根据具体的需求和目标平台，选择适合的加载机制：

### 加载方式对比

| 维度 | 作为模组加载                                                                              | 独立插件 Jar 包 |
| :--- |:------------------------------------------------------------------------------------| :--- |
| **开发与构建** | 需要使用模组开发工具链（如 Loom 或 Architectury）；可能需要对每个 Minecraft 版本以及不同的模组加载器进行单独适配，构建和开发流程较复杂。 | 开发更简单，无需使用模组开发工具链，也无需操心 Minecraft 版本和模组加载器更新带来的兼容问题。 |
| **跨平台能力** | 绑定于特定的 Minecraft 版本和模组加载器，且需要持续关注 Minecraft 版本更新带来的破坏性更改。                           | **跨平台兼容**。由于完全不依赖 Minecraft 及其模组接口，理论上同一份独立插件 JAR 可以在任何适配了 MoeMusic 核心的主流平台上直接加载运行。 |
| **自由度与接口** | 可以访问 Minecraft、模组加载器（Fabric/NeoForge 等）以及服务端提供的原生接口，拥有极高的开发自由度。                     | 仅能调用 MoeMusic 核心提供的 API 接口，无法直接访问特定模组加载器的底层功能。 |
| **依赖管理** | 模组加载器会自动处理依赖关系。当发生依赖缺失或冲突时，加载器会向用户提供清晰的错误提示，更容易被用户理解。                               | 核心的插件加载器较为基础，缺少依赖管理和版本冲突解决机制，如有外部依赖库，需在构建时将其打包进 JAR 中。 |
| **安装与更新** | 直接放入 `mods` 目录中。可以借助第三方启动器或游戏内置的模组管理功能进行版本更新、启用或禁用。                                 | 必须放置在 `config/moemusic/plugins/` 目录中。无法被启动器的模组管理器直接管理，需要用户手动安装与更新。 |
| **分发与审核** | 拥有完整的 Mod 属性，更容易发布到 CurseForge、Modrinth 等主流模组分发网站。                                  | 缺少模组专有的特征配置（如 `fabric.mod.json` 等），在上传到模组分发网站时**可能**更容易卡在审核阶段。 |

**1. 作为模组加载**

```kotlin
MoeMusicApi.registerPlugin(ExamplePlugin)
```

如果插件需要依赖 Fabric、NeoForge 等模组加载器的接口，可以直接在模组的初始化器中注册。请确保注册操作在 MoeMusic 运行时初始化之前完成。

**2. 独立插件 Jar 包**

```kotlin
class ExampleProvider : PluginProvider {
    override fun plugins(): Iterable<Plugin> = listOf(ExamplePlugin)
}
```

此时插件 Jar 包内需要包含 Java SPI 服务描述文件：

```text
META-INF/services/org.lolicode.moemusic.api.plugin.PluginProvider
```

该文件的内容为 Provider 实现类的全限定名，例如：

```text
com.example.moemusic.ExampleProvider
```

独立的插件 Jar 包需要放置在 `config/moemusic/plugins/` 目录下。对应的 [PluginProvider](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginProvider.kt) 类必须是 `public` 且提供无参构造函数。需要注意的是，独立插件将被视作受信任的本地代码执行，MoeMusic 不对其提供沙箱隔离。

## 生命周期与上下文模型

MoeMusic 的生命周期分为运行时（Runtime）和会话（Session）两层：

- `onServerRuntimeLoad(ctx)`：在每个逻辑服务端（Logical Server）运行时初始化时调用一次。用于注册音源、事件监听器、翻译文本以及配置监听器。
- `onServerSessionLoad(ctx)`：在每个具体的游戏会话（Server Session）建立时调用一次。只应当在此处放置与当前会话绑定的资源，例如外部播放受众租约（Audience Lease）。
- `onServerSessionUnload()`：在游戏会话结束时调用，用于释放与当前会话绑定的资源。
- `onServerRuntimeUnload()`：逻辑服务端运行时最终卸载时调用，用于执行全局清理。
- `onClientRuntimeLoad(ctx)`：在每个客户端（Client）运行时初始化时调用一次。用于客户端的播放、请求逻辑以及本地状态的集成。
- `onClientRuntimeUnload()`：在客户端关闭时调用，执行清理操作。

上下文的能力范围也严格遵循生命周期进行划分：

- [PluginScopedContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L27)：提供基本能力，如日志记录器（Logger）、国际化（I18n）、插件专属数据目录以及配置的读取与保存。
- [PluginRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L57)：在 `PluginScopedContext` 的基础上，增加了事件总线（EventBus）和配置变更监听。
- [ServerRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L82)：提供服务端特有的服务，以及注册自定义音源的方法 `registerMusicSource`。
- [ServerSessionContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/ServerSessionContext.kt)：提供与当前会话绑定的服务端能力，例如获取外部播放受众租约的方法 `acquirePlaybackAudienceLease`。
- [ClientRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L130)：提供客户端独有的服务，包括播放控制、请求发送以及本地内容过滤服务。

请注意，底层的原始服务（如 `searchService`、`trackSubmissionService`、`playbackController` 等）**不会**自动执行权限校验和请求频率限制。如果插件需要代表玩家执行操作，应优先使用 `userActionService`，或者在访问对应资源前显式执行权限和请求频率限制。

## 配置与本地化

插件的配置由 `Plugin.configSpec` 定义，并映射为单个强类型的 TOML 文件，存放路径为：

```text
config/moemusic/plugin-configs/<configId>.toml
```

插件自定义的持久化数据则应存放在：

```text
config/moemusic/plugin-data/<configId>/
```

配置的数据类（Data Class）必须支持 Kotlin Serialization 序列化。在进行参数校验时，验证器（Validator）如果返回 `null` 则表示校验通过，返回 `LocalizedText` 则表示具体的输入错误信息；**请勿**使用抛出异常的方式来表达常规的参数校验失败。

本地化语言文件建议打包在插件 Jar 中的以下路径：

```text
assets/<namespace>/lang/en_us.json
assets/<namespace>/lang/zh_cn.json
```

无论是独立 Jar 插件还是作为 Minecraft 模组加载的插件，`<namespace>` 都固定派生自 `Plugin.id.substringBefore(':')`，而不是 Jar 文件名、Provider 类包名、Fabric 模组 ID 或 NeoForge 模组 ID。例如 `Plugin.id == "moe:music"` 的插件应打包：

```text
assets/moe/lang/en_us.json
assets/moe/lang/zh_cn.json
```

在插件内部设计中，应尽可能使用并传递 [LocalizedText](../api/src/main/kotlin/org/lolicode/moemusic/api/LocalizedText.kt) 对象，以便 MoeMusic 核心在呈现给最终用户的界面边界处（如渲染客户端界面或发送聊天消息时）根据用户语言进行渲染。只有当文本内容已是确定的最终字符串（如音源返回的原始歌名、用户的直接输入、主机名或标识符等）时，才应使用 `LocalizedText.plain(...)`。

## 事件订阅与分发

事件总线（EventBus）通过运行时上下文（Runtime Context）向插件公开：

```kotlin
ctx.eventBus.subscribe<OnTrackSubmitted> { event ->
    // 仅作为观察者：此时曲目提交操作已完成，无法被取消。
}
```

事件的派发是**同步且内联（Inline）**的：订阅者的处理函数（Handler）将在调用 `fire()` 的同一个线程中执行，且 `fire()` 方法会等待所有匹配的订阅者执行完毕后才会返回。订阅者的执行顺序不属于公开的接口约定。另外，这些事件是不可取消的，也无法修改原有的执行流程。如果您在处理函数中需要进行耗时的 I/O 操作，请务必将其异步派遣至后台线程执行。

网络连接相关的事件分为以下两层：

- [OnServerPlayerConnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L21) / [OnServerPlayerDisconnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L33)：底层的游戏服务端连接事件。这些事件会针对所有加入服务器的玩家触发，包括那些未安装 MoeMusic 客户端的玩家。
- [OnUserSessionStarted](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L44) / [OnUserParticipationChanged](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L56) / [OnUserSessionEnded](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L67)：MoeMusic 客户端专属的会话事件。它们反映了客户端的连接状态，以及用户的参与模式（如 `ACTIVE` 活跃或 `STANDBY` 旁听状态）。

客户端本地的连接事件为 [OnClientConnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L197) 和 [OnClientDisconnected](../api/src/main/kotlin/org/lolicode/moemusic/api/event/Events.kt#L206)；而客户端进程关闭时的最终清理工作仍应在 `onClientRuntimeUnload()` 中处理。

## 自定义音源开发

所有音源都必须实现 [MusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt)。支持文本搜索的音源应额外实现 [SearchableMusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L46)；能够解析原始标识符或分享链接的音源应额外实现 [IdentifierResolvableMusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt#L77)。

核心接口方法的语义说明：

- `TrackInfo.id`：由音源自己定义和解释。它是音源内部稳定使用的曲目标识，不必等同于上游平台的 ID。一个音源若同时暴露多种上游资源类型，应使用带类型的标识，避免后续查询和播放走错接口。
- `TrackInfo.loudness`：可选的音源侧响度数据。将 LUFS 放在 `loudness.integratedLufs`，若音源还能提供可信的峰值测量，则可额外填写 `loudness.peak`。客户端会对缺失或无效的 LUFS 采用保守处理，因此能提供真实 LUFS 的音源应尽量提供。
- `search(...)`：返回用户可见的 [SearchResult](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L59)。无结果属于成功的空页面，不应抛出异常。
- 直接曲目行必须使用 [SelectionEntryKind.TRACK](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L30)，并将最终稳定的 `TrackInfo.id` 填入 `selectionId`。
- 专辑、歌单等中间容器行可使用 `CONTAINER` 或 `UNKNOWN`，随后通过 `resolveSelection(...)` 展开。
- `getTrackInfo(...)`：若曲目不存在，应返回 `UserResult.Success(null)`；若为预期内的拒绝操作，应返回 `UserResult.Error`；只有当本次查找必须异常中止时，才应当抛出异常。
- `resolve(...)`：返回 [PlaybackResolution](../api/src/main/kotlin/org/lolicode/moemusic/api/model/PlaybackResolution.kt) 的播放解析入口。同一首曲目在恢复播放、跳转进度或新玩家同步时可能会被多次解析，因此播放地址应视为可重新签发的资源。
- 若签发播放地址的同一次请求还能拿到当前曲目的额外元数据，可通过 `PlaybackResolution.trackPatch` 回传一个针对 TrackInfo 的补丁，例如响度信息或歌词。
- `resolveIdentifier(...)`：对于非本音源支持的输入，应返回 `Pass`；对于预期内的拒绝，应返回 `Blocked`；对于确定的直接曲目，应返回 `Resolved`；对于需要进一步展开选择的容器，应返回 `Choices`。
- 类似于通用 HTTP 解析器这类泛用解析器，应设置 `isFallbackResolver = true`，以便让针对特定平台的分享链接解析器优先尝试解析。

在执行任何高消耗或敏感操作之前，应先校验权限。音源专属权限的校验示例：

```kotlin
submitter?.hasPermission("your.node", defaultLevel = 2)
```

如果插件只需要复用 MoeMusic 内置的点歌权限校验、请求频率限制、搜索和提交流程，应调用运行时上下文中的 `userActionService`，不要直接调用底层的未检查服务。

## 可用性、过滤与提交

[TrackInfo.unavailableReason](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackInfo.kt) 与 [SelectionEntry.unavailableReason](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt) 仅用于表示音源层面的固有不可用状态，例如会员限制、地区限制、资源已下架、无法获取播放地址等。这类限制不能通过内容过滤的免过滤权限绕过。

因触发全局内容过滤器而导致不可用的情况，不应写入 `unavailableReason`。如果音源需要根据简介、标签等更丰富的元数据执行自己的过滤逻辑，应通过 `sourceFilterVerdict` 返回判定结果。曲目提交校验入口会使用与内置过滤器相同的免过滤规则执行该判定。

标准的曲目提交接口为 [ITrackSubmissionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt)：

- `submitBySourceAndId(sourceId, trackId, ...)`：提交稳定的音源曲目标识，并先通过 `getTrackInfo(...)` 获取权威元数据。
- `submitBySelection(sourceId, selectionId, ...)`：提交或进一步展开一个由音源返回的选择项。
- `submitResolved(track, ...)`：接收调用方提供的曲目元数据，并在可能时从所属音源重新获取权威元数据。跨越不可信边界时应使用这个方法。
- `submitResolvedFromSource(track, ...)`：接收刚由所属音源在同一服务端流程中返回的 `TrackInfo`。它会跳过额外的元数据刷新，但仍会写入提交者、检查时长、内容过滤、可用性和队列规则。

如果插件需要代表玩家执行操作，应优先使用 [IUserActionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IUserActionService.kt)。它会在调用底层原始服务前执行 MoeMusic 内置的权限校验与请求频率限制。

## 客户端运行时 API

客户端插件在 `onClientRuntimeLoad` 回调中会接收到 [ClientRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L130)。

[IClientPlaybackService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt) 暴露了本地播放状态快照、搜索音源目录、当前参与状态、可用性问题、配置音量、实际有效音量、每个服务器的播放启用状态以及临时音量覆盖等功能。音量值统一使用 `0..100` 的整数百分比表示。临时音量覆盖仅在当前运行时生效，且只能用于衰减或限制音量，无法将实际输出音量提升到配置的音量基准之上。

[IClientRequestService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientRequestService.kt) 提供了客户端向服务端发送的强类型请求接口，包括：搜索、播放队列快照、点歌、提交选中项、提交原始标识符、播放控制、从队列移除曲目以及内容过滤规则变更等。当遇到网络连接不兼容、未完成握手或请求超时等情况时，这些方法可能会抛出 `ClientRequestException`。

## 接口包参考

### `org.lolicode.moemusic.api`

- [MoeMusicApi](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicApi.kt)：显式插件注册入口与接口兼容版本常量。
- [LocalizedText](../api/src/main/kotlin/org/lolicode/moemusic/api/LocalizedText.kt)、[I18nRegistry](../api/src/main/kotlin/org/lolicode/moemusic/api/I18nRegistry.kt)：本地化文本与翻译注册系统。
- [UserResult](../api/src/main/kotlin/org/lolicode/moemusic/api/UserResult.kt)：预期内的用户可见返回结果模型。
- [UserFacingException](../api/src/main/kotlin/org/lolicode/moemusic/api/UserFacingException.kt) 及其子类：可直接展示给玩家的、导致流程中止的异常。
- [MusicSource](../api/src/main/kotlin/org/lolicode/moemusic/api/MusicSource.kt) 相关接口：用于实现音源、文本搜索、原始 ID/链接解析的核心接口。
- [MoeMusicUser](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicUser.kt)：跨平台的玩家身份封装，包含玩家语言设置及自定义权限检查方法。
- [DuplicateRegistrationException](../api/src/main/kotlin/org/lolicode/moemusic/api/DuplicateRegistrationException.kt)：当注册了重复的插件或音源 ID 时抛出的致命启动错误。

### `org.lolicode.moemusic.api.plugin`

- [Plugin](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/Plugin.kt)：插件生命周期入口。
- [PluginProvider](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginProvider.kt)：适用于独立 Jar 插件的 SPI 服务提供者接口。
- [PluginConfigSpec](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PluginConfigSpec.kt)：强类型插件配置规范定义，用于在前端渲染自适应设置界面。
- 各种上下文（Context）：包括 [PluginScopedContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L27)、[PluginRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L57)、[ServerRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L82)、[ServerSessionContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/ServerSessionContext.kt) 和 [ClientRuntimeContext](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/RuntimeContexts.kt#L130)。
- [PlaybackAudienceLease](../api/src/main/kotlin/org/lolicode/moemusic/api/plugin/PlaybackAudienceLease.kt)：面向非原生播放接收端声明活跃受众的会话级租约。

### `org.lolicode.moemusic.api.model`

- [TrackInfo](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackInfo.kt)、[ArtistInfo](../api/src/main/kotlin/org/lolicode/moemusic/api/model/ArtistInfo.kt)、[PlaybackResource](../api/src/main/kotlin/org/lolicode/moemusic/api/model/PlaybackResource.kt)、[TrackContext](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackContext.kt)、[PlaybackState](../api/src/main/kotlin/org/lolicode/moemusic/api/model/PlaybackState.kt)：曲目元数据、可播放资源以及核心播放状态模型。
- [SearchQuery](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L13)、[SearchResult](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L59)、[SelectionEntry](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L30)、[SelectionResolveResult](../api/src/main/kotlin/org/lolicode/moemusic/api/model/Search.kt#L70)：搜索流程与搜索项选择结果的数据模型。
- [TrackAddMode](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackAddTypes.kt#L6)、[TrackAddResult](../api/src/main/kotlin/org/lolicode/moemusic/api/model/TrackAddTypes.kt#L19)：点歌时的入队模式与最终入队结果模型。
- [ContentFilterRules](../api/src/main/kotlin/org/lolicode/moemusic/api/model/ContentFilter.kt#L69) 及其包含的各种规则模型：全局内容过滤规则的配置模型。

### `org.lolicode.moemusic.api.service`

- 底层原始服务：
  - [IPlaybackController](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IPlaybackController.kt)：纯控制层面的播放控制器。
  - [ISearchService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ISearchService.kt)：统一的搜索路由服务。
  - [IIdentifierResolutionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IIdentifierResolutionService.kt)：标识符/链接解析服务。
  - [ITrackSubmissionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt)：规范的曲目提交管道服务。
- [IUserActionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IUserActionService.kt)：前置了权限和频率限制校验的、代表玩家行为的用户动作服务。
- 策略与安全服务：
  - [IPermissionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IPermissionService.kt)：全局权限服务。
  - [IRateLimitService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IRateLimitService.kt)：请求频率限制服务。
  - [IContentFilterService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IContentFilterService.kt)：内容过滤判定服务。
  - [IMediaProbeService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IMediaProbeService.kt)：媒体流地址探测与防火墙校验服务。
- 相关结果模型：[IdentifierResolutionOutcome](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IIdentifierResolutionService.kt#L18)、[SubmitOutcome](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt#L19)、[SelectionSubmitOutcome](../api/src/main/kotlin/org/lolicode/moemusic/api/service/ITrackSubmissionService.kt#L32)、队列及播放控制指令执行结果、过滤判定结论（[FilterVerdict](../api/src/main/kotlin/org/lolicode/moemusic/api/service/FilterVerdict.kt)）等。

### `org.lolicode.moemusic.api.event`

- [EventBus](../api/src/main/kotlin/org/lolicode/moemusic/api/event/EventBus.kt) 及 `subscribe<T>` 订阅机制。
- 网络连接与 Session 级生命周期事件、搜索/解析/点歌提交事件、播放相关核心事件、客户端本地连接及播放事件、内容过滤变更事件等。

### `org.lolicode.moemusic.api.client`

- [IClientPlaybackService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientPlaybackService.kt)：客户端本地播放控制与状态服务。
- [IClientRequestService](../api/src/main/kotlin/org/lolicode/moemusic/api/client/IClientRequestService.kt)：客户端与服务端通讯的强类型请求和响应接口。
- 各种客户端专属模型：网络响应模型、搜索目录模型、过滤规则调整模型、以及临时音量覆盖参数等。

### `org.lolicode.moemusic.api.permission`

- [MoeMusicPermission](../api/src/main/kotlin/org/lolicode/moemusic/api/permission/MoeMusicPermission.kt)：[IPermissionService](../api/src/main/kotlin/org/lolicode/moemusic/api/service/IPermissionService.kt) 支持的 MoeMusic 内置标准公开权限组。
- 插件自定义的专属权限节点，仍直接通过 [MoeMusicUser.hasPermission(...)](../api/src/main/kotlin/org/lolicode/moemusic/api/MoeMusicUser.kt) 进行动态检索。

## 实用开发检查表

- 确保插件在 MoeMusic 运行时初始化之前完成注册。
- **只能**在 `onServerRuntimeLoad` 回调中执行自定义音源的注册操作。
- 任何面向玩家展示的文本，应当优先使用 `LocalizedText.key` 方式定义。
- 预期内的不通过（如点歌不合法）请返回 `UserResult.Error`，只有致命/非预期的底层失败时才应抛出 `UserFacingException`。
- 如果是代表玩家执行操作，除非有明确理由需要直接调用原始底层服务，否则一律优先通过 `userActionService` 发起。
- 在执行网络 I/O 或高耗能的敏感操作前，必须手动或自动执行权限与频控校验。
- 保持直接曲目的稳定标识符格式为 `(sourceId, trackId)`。
- 插件自身的配置文件与专属持久化数据，应存放在 MoeMusic 指定的配置和数据目录下，切勿将其直接写入或读取自 Jar 文件本身的所在目录。
