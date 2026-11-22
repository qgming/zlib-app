# 架构

## 1. 目标与非目标

**目标**：在 Android 上实现登录、域名体检、搜索、下载及 Z-Library“我的图书馆”（云端已下载、收藏、书单），并针对移动端做好 SAF、前台下载、分享和失败恢复。云端图书馆以 Z-Library 服务端为准，术语见 `../GLOSSARY.md`。

**非目标**：应用备份/恢复、独立本地书库/书单及其导入导出、本地电子书元数据编辑及第三方查源、内置阅读器、Kindle、微信读书导入、多书源、MCP/TUI、静默自更新。范围调整见 ADR-030/ADR-031；详情页继续展示 EAPI 图书信息，登录 Cookie 导入继续保留。详见 `DECISIONS.md`。

## 2. 分层

```
┌─────────────────────────────────────────────────────────┐
│ :app            导航壳 / Application / DI 入口          │
├─────────────────────────────────────────────────────────┤
│ :feature:*      Compose 页面 + ViewModel + UiState       │
├─────────────────────────────────────────────────────────┤
│ :core:data      Repository / 下载队列 / 会话 / 设置       │
├──────────────────────────┬──────────────────────────────┤
│ :core:network            │ :core:database                │
│ EapiClient / DomainProbe │ Room / DAO / Migration         │
├──────────────────────────┴──────────────────────────────┤
│ :core:model     Book / Account / DownloadItem / Domain    │
│ :core:common    AppError / Result / Dispatchers / 扩展    │
├─────────────────────────────────────────────────────────┤
│ :core:designsystem  Miuix 主题 + 组件（唯一直接依赖 Miuix）│
└─────────────────────────────────────────────────────────┘
```

依赖规则：feature 之间不互相依赖；core 不反向依赖 feature/app；禁止循环。`core:designsystem` 只依赖 model/common。

## 3. 模块图

| 模块 | 职责 |
|---|---|
| `:app` | `ZlibApplication`、`MainActivity`、`BookDetailActivity`、SharedView Kit 适配、根导航、Hilt 入口、全局错误宿主 |
| `:core:common` | `AppError`、`Result`、`DispatcherProvider`、文本/ISBN/文件名工具 |
| `:core:model` | 领域模型与 UI 共享模型，不依赖 Android 框架（除可空注解） |
| `:core:designsystem` | Miuix 深浅色主题、玻璃导航壳 `ZlibAppShell` / `ZlibPage`、统一弹层、图书网格、设置分组 |
| `:core:network` | OkHttp、`EapiClient`、Cookie/重定向策略、域名 allowlist/probe |
| `:core:database` | Room entities/DAO/DB、迁移、FTS（如需） |
| `:core:data` | Repository、下载队列、会话加密、设置 |
| `:feature:search` | 阅读首页（热门与推荐）、独立搜索页、筛选与结果分页 |
| `:feature:detail` | 详情、下载按钮、分享、收藏 |
| `:feature:downloads` | 队列列表、暂停/取消/重试、前台服务状态、本机文件分享与删除、云端下载记录入口 |
| `:feature:library` | 书架“收藏 / 书单”入口；云端收藏和独立下载记录页的分页与刷新 |
| `:feature:account` | 密码登录、WebView 登录、Cookie 导入、配额 |
| `:feature:settings` | 域名、下载目录、主题 |
| `:feature:booklists` | 云端书单提示页；列表与详情的 EAPI 先验证再接入 |

## 4. 核心流程

### 4.1 启动

1. `ZlibApplication` 初始化 Hilt、Room、DataStore、Keystore。
2. 读取加密会话；无会话 → 登录页；有会话 → 主界面。
3. 主界面后台（不阻塞首屏）触发一次域名体检；结果写入 `DomainRepository`。
4. 若当前域不健康，按优先级切换到健康域；切换不丢 Cookie。

### 4.2 登录

```
密码登录: 输入 email/password → POST /eapi/user/login
          → success:1 + user.id/remix_userkey → 加密保存 → LOGGED_IN
WebView:  打开登录页 → 用户完成登录 → CookieManager 读取目标域
          → 提取 remix_userid/remix_userkey → 加密保存
Cookie:   粘贴 JSON / 导入 Netscape cookies.txt → 解析 → 校验
          → 加密保存
```

三通道都归一化为 `Session(remixUserId, remixUserKey, domain, cookies)`。密码只在内存中存在一次。

### 4.3 搜索

`SearchQuery`（关键词、语言、格式、年份、精确、排序）→ `BookRepository.search(page)` → `EapiClient.search` → `BookPage(books, page, totalPages)` → `SearchUiState`。

搜索状态：`Idle / Loading / Content / Empty / Error`；分页用 `Paging` 或手写 `page` 状态，二者只选一个（见 ADR）。

搜索栏固定在顶栏下方；右上角筛选按钮打开玻璃底部面板，应用后以新条件从第 1 页搜索。分页使用上一次提交的请求，不受未提交的关键词或筛选草稿影响。

详情中的作者／译者姓名链接通过 app 回调进入同一详情 Activity 内的 miuix-nav 搜索路由，使用独立 `SearchViewModel` 状态自动搜索单个姓名，默认筛选且只初始化一次；返回详情或从结果打开另一本详情后返回不重置搜索分页。feature 间无依赖，不新增端点；仍调用已有的普通 EAPI 搜索，见 ADR-043。

### 4.4 下载

```
用户点下载 → DownloadRepository.enqueue(book)
  → Room: download_queue(QUEUED)
  → DownloadService.start()
  → RESOLVING: GET /eapi/book/{id}/{hash}/file
    → 若 302：跟随 CDN，剥离凭据
    → 若 200 JSON：解析 file.downloadLink，再 GET CDN（Range 续传）
  → DOWNLOADING: 写入 SAF .part，Range 续传，进度写 Room + 通知
  → COMPLETED: rename → 入库 books → 通知 → 可选分享
  → FAILED/PAUSED/CANCELLED: 保留 .part，等待恢复/重试
```

队列串行，同一时刻只有一个 `DOWNLOADING`。进程被杀后，`DownloadService` 从 Room 恢复 `QUEUED/PAUSED` 项；`DOWNLOADING` 项重置为 `QUEUED` 并重新校验 `.part`。

### 4.5 分享

`BookDetail` → “打开/分享” → 使用 SAF 文件 URI + `FLAG_GRANT_READ_URI_PERMISSION` → `Intent.ACTION_SEND` + `type`（EPUB/MOBI/PDF…）→ 系统分享面板（微信读书、阅读器）。不申请 `REQUEST_INSTALL_PACKAGES`，不直接调微信 SDK。

> 分享策略见 ADR-021：直接授予 SAF 文档 URI，不经过 `FileProvider`。

### 4.6 我的图书馆

- 书架顶部仅“收藏 / 书单”，默认收藏，分组保留滚动位置。
- 云端下载记录：从下载管理进入独立页面，`LibraryRepository` → 已确认的 `/eapi/user/book/downloaded` → 分页显示账号下载记录；从其他设备下载的记录也可出现，不据此声称本机有文件。旧名称“历史”不再使用。
- 收藏：`LibraryRepository` → `/eapi/user/book/saved` → 分页显示账号收藏；不创建独立本地收藏标记。
- 详情收藏：`BookFavoriteViewModel` → `FavoritesRepository` → 收藏列表按 ID 确认状态，save/unsave 写入成功后才切换“添加收藏 / 已收藏”并通知书架刷新；按钮位于下载右侧。状态未知时禁用按钮并提供重试，失败保留原状态；用户于 2026-11-22 确认收藏及云端下载记录真机验证成功，布局调整见 ADR-042。
- 书单：已接入提示页，明确“云端书单尚未接入”；先确认 EAPI 的读取/分页/详情/权限并补入 `EAPI.md`，再经 Repository 接入真实内容。已查到的第三方书单使用 PAPI，当前禁止接入；未验证时不猜端点、不抓网页、不做本地替代。
- 刷新以云端结果更新视图。列表及补取的封面仅保存在当前 ViewModel 内存，随会话变更清空，取消旧请求并拒绝旧结果回写。
- 可见卡片封面缺失或地址无效时，使用详情 EAPI 补取，最多 3 并发、同一会话内每本一次；仅更新封面，失败保留占位图与记录。图片加载不带账号凭据，来源与限制见 `EAPI.md` §2.7。
- 本机文件：下载管理的独立页面通过 `LocalFilesRepository` 读取 Room 文件记录，分享前检查可读性，删除成功后才清除文件记录。删除本机文件和删除云端下载记录分别操作，互不联动；云端删除仍待实现。

页面入口迁移已按 ADR-032 实现；云端书单内容与下载记录删除仍待实现，进度见 `ROADMAP.md`。

## 5. 域名与网络

### 5.1 allowlist

内置后缀 + 用户镜像，规则见 `AGENTS.md` §3.2。`DomainPolicy` 是纯 Kotlin，可单测。

### 5.2 探测

`DomainProbe` 对 `/eapi/info/domains`（免登录）发 GET：

- 8 个并发，10s 超时，最多 3 次重定向；
- 分类：`HEALTHY`（JSON `success:1` + domains）、`CHALLENGED`（HTML challenge）、`DIAMWALL`（513/517 或标题）、`REDIRECT_LOOP`、`HTTP_ERROR`、`NETWORK_ERROR`、`INVALID`；
- 结果带 `finalUrl`；若重定向到 allowlist 内新域，记录为候选；
- 不做后台常驻；启动、登录失败、下载失败、用户手动触发时探测。

### 5.3 请求管线

`EapiClient` 每次请求：

1. 用当前 `domain` 拼 URL；
2. 加 `Accept: application/json`、UA、Cookie、`remix-userid/remix-userkey`；
3. POST 用 form body；
4. 先读 body，解析 envelope；HTTP 非 2xx 但 envelope 有 `success:0` 时保留服务端 error；
5. 识别 `Invalid credentials`/`Please login` → `SessionExpired`；
6. 识别 513/517/HTML DiamWall → `DomainBlocked`；
7. EAPI 重定向：网络拦截器在跟随前拒绝离开 allowlist（或非 HTTPS）的重定向，避免 307/308 把登录表单体重放到别的 host；下载请求不加此限制，按目标 host 剥离凭据。

## 6. 数据来源与本地持久化

Z-Library 服务端决定云端已下载、收藏和书单的内容；Room 保存设备侧下载任务、进度及文件记录。当前 Room v1 只有 `books` 和 `download_queue` 两张表：

```
books(id TEXT PK, hash TEXT, title, subtitle, authors, publisher, year,
      language, extension, size, isbn10, isbn13, cover_url, description,
      categories, edition, pages, local_uri, downloaded_at, favorite,
      created_at, updated_at)

download_queue(id TEXT PK, book_id, hash, file_name, state, bytes_written,
               total_bytes, error, created_at, updated_at)
```

> 列名概念上如上；v1 实现使用 Kotlin camelCase 列名，见 ADR-022。

- `books.local_uri` 仅记录本机文件关联，使用时还需确认文件可访问；为空不表示账号没有云端下载记录。
- 当前 schema 的 `favorite` 是早期保留列，不作为云端收藏的真相，不增加独立本地收藏或书单表。
- 下载队列与 `books` 分离；完成后更新 `books.local_uri/downloaded_at`。
- 如后续引入云端缓存，需明确账号归属、刷新与清理规则；缓存可由 EAPI 重建，不能成为独立云端书库。云端书单字段先验证协议再设计缓存。
- 迁移：每次 schema 变更一个 `Migration(n, n+1)` + `MigrationTestHelper`。

## 7. 状态与错误

```kotlin
sealed interface AppError {
  data object NoDomain : AppError
  data object NotLoggedIn : AppError
  data object SessionExpired : AppError
  data class AuthFailed(val message: String?) : AppError
  data class Quota(val remaining: Int, val resetHint: String?) : AppError
  data class DomainBlocked(val status: DomainStatus) : AppError
  data class Network(val cause: Throwable) : AppError
  data class Server(val code: Int, val message: String) : AppError
  data class Storage(val cause: Throwable) : AppError
  data class Parse(val message: String) : AppError
  data class Unsupported(val message: String) : AppError
  data class Unknown(val cause: Throwable) : AppError
}
```

`DomainStatus` 定义在 `:core:common`（纯枚举），`:core:model` 的 `DomainCheck` 复用它，避免 core 反向依赖。

UI 只消费 `AppError` 的用户可读映射；恢复动作由 feature 决定（切域、重登、等待配额、重试、换目录）。

## 8. 导航

```
MainScaffold
├── PopularTab       → PopularScreen → BookDetailActivity
│                    → SearchScreen → BookDetailActivity
│                    → DownloadsScreen (本机任务、进度)
│                        → LocalFilesScreen (文件分享/删除) → BookDetailActivity
│                        → DownloadHistoryScreen (云端下载记录) → BookDetailActivity
├── LibraryTab       → LibraryScreen (收藏 / 书单) → BookDetailActivity / BooklistsScreen (尚未接入提示)
└── SettingsTab      → SettingsScreen → AccountScreen / DomainSettingsScreen
Routes: login, search, downloads, local-files, download-history, account, domain-doctor
详情: 非导出的 BookDetailActivity，Intent 图书引用 id/hash
```

`miuix-nav` 管理返回栈，主 tab 为阅读/书架/设置，首页“阅读”使用 Glass 系列的 `NotesOutline` 图标，书架使用 `BookOutline`，见 ADR-038；内容继续为热门和推荐。搜索和下载管理从阅读页顶栏进入，返回后回到原来的位置。双击底部“阅读”入口回到网格顶部并展开顶栏；使用系统双击间隔，切换其它 tab 重置识别。网格状态由 app 持有并传给首页，不引入 feature 依赖。深链只支持 `zlib://book/{id}:{hash}`（v1.1）。

导航图对应 ADR-032 的当前实现；书单列表与详情待验证协议后增加。所有 feature 由 app 通过回调组合，不互相依赖。

主 tab 切换采用 150ms 整页横向滑动，距离为当前页面完整宽度，不叠加淡入淡出；标题、顶栏操作、书架分组及内容共同移动。底栏置于动画容器外保持固定，采样正在移动的页面，Miuix 指示器继续动画；每页底部按实测底栏高度预留安全区。`AnimatedContent` 以 tab 为内容键，每页提供独立 `ZlibPageScroll` 并通过可保存状态保留滚动位置，防止退出页面的 dispose 清除新页面的滚动驱动；重复选择同页不重播，快速切换可重定向。页面动画期间阻挡移动页交互，不阻挡底栏，见 ADR-041。

ADR-040 将详情从 miuix-nav 路由改为独立 `BookDetailActivity`，以接入 ITGSA SharedView Kit 的 ActivityOptions。SDK 固定 AAR 仅由 app 引用；设计系统通过 `ZlibBookOpenRequest` 提供封面快照和实时坐标，feature 只提交请求。按 ADR-041 默认尝试 ROM 能力、不提供设置项或应用内模拟转场；设备支持时使用 Bitmap 源打开，返回前更新 WINDOW/SCREEN 声明对应的封面落点，不支持／快照失效时普通打开。详情先展示基础信息并读取完整详情，加载完成后才提供下载。进程重建从 Intent 图书引用恢复，转场 Bitmap 和来源查询器不落盘、不含凭据。具体来源、边界、许可和验收见 `ANIMATIONS.md`。

ADR-042：详情主页面依次显示封面／书名／作者／评分、并排的下载与收藏按钮、简介；操作区无外层卡片，不显示默认首次下载提示，实际下载结果继续反馈。顶部右侧的信息按钮打开 `ZlibGlassBottomSheet`，原出版／文件字段移入可滚动、可复制且带安全区的面板。`BookDescription` 只在本地格式化 EAPI 的 `description`，规范段落、换行、列表和强调，移除图片、链接行为与脚本等内容后交给 Compose 文本渲染；不使用 WebView 或发起外部请求，不更改网络字段与存储。简介支持文本选择和统一行距。

ADR-043/044：`ContributorNames` 本地解析作者原文中的姓名范围，各姓名通过 `LinkAnnotation.Clickable` 独立点击搜索；国籍、角色与分隔符原样保留普通样式，姓名内空格／中点／连字符不拆开。详情书名使用 SemiBold（600），蓝色姓名链接使用 Medium（500），字号不变。`BookDetailLauncher` 按来源 Activity 分别防重复点击和保存返回会话，使作者搜索结果可继续打开详情并逐层返回。

顶栏和浮动底栏采样页面内容；滚动容器使用实测栏高和系统安全区做内容内边距，不裁掉首尾卡片。玻璃样式按深浅色使用同版本 Miuix 示例默认材质，不跟随壁纸；支持的设备始终启用。固定配色和玻璃样式见 ADR-026 至 ADR-028。等待加载使用与书架下拉刷新同款的 Miuix 环形游点；可计量的下载继续显示字节进度。

书架分组是玻璃顶栏的一部分，分组下方的内容可滚入顶栏的渐进模糊区域。玻璃筛选、确认框和外观菜单由设计系统统一封装，采样完整页面但不采样弹层及遮罩，见 ADR-029。

图书网格共用 `ZlibBookTile`，仅显示宽高 2:3 的封面和下方单行书名，超长末尾省略，无包裹整项的卡片背景。封面等比裁切填满，点击进入详情。`ZlibCover` 在缺图、加载中和失败时绘制本地经典书封：深色布面配色、细金框、居中衬线书名与作者；尺寸随封面缩放，作者缺失显示“作者不详”。真实图片成功后替换，本地排版不发网络请求、不写图书文件。详情及下载列表复用同一实现。本机文件长按打开分享/删除面板，删除继续二次确认。

网格密度由设置的 `BookGridColumns` 统一控制：自动/2/3/4/5 列，保存到 DataStore，经 `ZlibTheme` 的 CompositionLocal 提供给所有 `ZlibBookGrid`，feature 不读取存储或互相依赖。默认“自动”，使用 `BoxWithConstraints` 的当前布局宽度，小于 360dp 为 2 列、360–599dp 为 3 列、600–839dp 为 4 列、840dp 及以上为 5 列；手动选择覆盖宽度规则，旋转/分屏只影响自动模式，见 ADR-036。设置左侧图标使用 `Glass.Normal` 常规粗细，以 26dp 为基准随标题字号缩放，视觉上略大于单行标题，不增加底板；图标左右各留 15dp。设置与下载管理顶部同类卡片均无内部行间分割线。账号入口主标题显示名称，副标题显示邮箱，见 ADR-038/039。

## 9. DI 与并发

- Hilt：`@HiltAndroidApp`、`@AndroidEntryPoint`、`@HiltViewModel`；`NetworkModule`、`DatabaseModule`、`RepositoryModule`、`DispatcherModule`。
- 若 spike 失败：`AppContainer` 在 `Application` 中构造，ViewModel 用 `ViewModelProvider.Factory`；接口不变，只换装配。
- 所有 Repository 方法 `suspend` 或返回 `Flow`；禁止在 main 线程做 IO。
- 下载 Service 用 `SupervisorJob + Dispatchers.IO`；队列状态写 Room 用单写者 `Mutex`。

## 10. 安全

- 凭据加密：Keystore AES-GCM，密文存 DataStore；不存密码。
- 日志：统一 `SafeLog`，对 Cookie/remix/URL query 做脱敏；release 关闭 verbose。
- 网络安全：只 HTTPS；不允许用户配置 `http://` 镜像；重定向剥离凭据；可选证书校验保持系统默认（不做 pinning，避免镜像更换即挂）。
- WebView：只允许 allowlist 域；禁用文件访问；不注入密码；登录完成即销毁。
- 系统备份与数据迁移：保留 `android:allowBackup="false"` 和 data extraction rules，防止会话及设备记录外泄；不提供应用备份/恢复或本地书库导入导出。登录后通过 EAPI 重新读取云端图书馆。

## 11. 测试接缝

- `EapiClient` 接受 `OkHttpClient` + `baseUrlProvider`，测试用 MockWebServer。
- `DomainProbe` 接受 `HttpClient` 接口，测试注入 fake。
- Repository 依赖 DAO/API 接口，不依赖具体实现。
- 下载引擎把“打开 SAF 流”抽象为 `DownloadSink`，测试用临时文件。

## 12. 目录

```
app/src/main/kotlin/com/qgming/zlib/
  ZlibApplication.kt
  MainActivity.kt
  navigation/
core/*/src/main/kotlin/com/qgming/zlib/core/...
feature/*/src/main/kotlin/com/qgming/zlib/feature/...
```

