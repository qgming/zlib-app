# 架构

## 1. 目标与非目标

**目标**：在 Android 上复刻 `heartleo/zlib` CLI 的核心能力——登录、域名体检、搜索、下载、书架、收藏/历史、书单、元数据编辑——并针对移动端做好 SAF、前台下载、分享和失败恢复。

**非目标**：内置阅读器、Kindle、微信读书导入、多书源、MCP/TUI、静默自更新。详见 `DECISIONS.md`。

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
| `:app` | `ZlibApplication`、`MainActivity`、根导航、Hilt 入口、全局错误宿主 |
| `:core:common` | `AppError`、`Result`、`DispatcherProvider`、文本/ISBN/文件名工具 |
| `:core:model` | 领域模型与 UI 共享模型，不依赖 Android 框架（除可空注解） |
| `:core:designsystem` | Miuix 主题、颜色、排版、`ZlibScaffold`、`ZlibTopBar`、玻璃开关 |
| `:core:network` | OkHttp、`EapiClient`、Cookie/重定向策略、域名 allowlist/probe |
| `:core:database` | Room entities/DAO/DB、迁移、FTS（如需） |
| `:core:data` | Repository、下载队列、会话加密、设置、元数据仓储 |
| `:feature:search` | 搜索输入、筛选、分页、结果列表 |
| `:feature:detail` | 详情、下载按钮、分享、收藏 |
| `:feature:downloads` | 队列列表、暂停/取消/重试、前台服务状态 |
| `:feature:library` | 已下载、收藏、历史、书单入口、本地搜索/排序 |
| `:feature:account` | 密码登录、WebView 登录、Cookie 导入、配额 |
| `:feature:settings` | 域名、下载目录、主题、备份/恢复 |
| `:feature:metadata` | 文件选择、EPUB/FB2/DOCX 解析、查源、编辑、重命名、嵌封面 |
| `:feature:booklists` | 书单列表与详情 |

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

`SearchQuery`（关键词、语言、格式、年份、精确、排序）→ `SearchRepository.search(page)` → `EapiClient.search` → `SearchResult(books, page, totalPages)` → `SearchUiState`。

搜索状态：`Idle / Loading / Content / Empty / Error`；分页用 `Paging` 或手写 `page` 状态，二者只选一个（见 ADR）。

### 4.4 下载

```
用户点下载 → DownloadRepository.enqueue(book)
  → Room: download_queue(QUEUED)
  → DownloadService.start()
  → RESOLVING: GET /eapi/book/{id}/{hash}/file（不自动跟随？跟随但剥离凭据）
  → DOWNLOADING: 写入 SAF .part，Range 续传，进度写 Room + 通知
  → COMPLETED: rename → 入库 books → 通知 → 可选分享
  → FAILED/PAUSED/CANCELLED: 保留 .part，等待恢复/重试
```

队列串行，同一时刻只有一个 `DOWNLOADING`。进程被杀后，`DownloadService` 从 Room 恢复 `QUEUED/PAUSED` 项；`DOWNLOADING` 项重置为 `QUEUED` 并重新校验 `.part`。

### 4.5 分享

`BookDetail` → “打开/分享” → `FileProvider.getUriForFile` → `Intent.ACTION_SEND` + `type`（EPUB/MOBI/PDF…）→ 系统分享面板（微信读书、阅读器）。不申请 `REQUEST_INSTALL_PACKAGES`，不直接调微信 SDK。

### 4.6 元数据编辑

```
SAF 选择文件 → 复制到 cache（避免直接改 Provider 流）
  → 解析 EPUB/FB2/DOCX → MetadataDraft
  → 在线查源（Google Books / OpenLibrary / Douban）→ 合并/选择
  → 用户逐字段确认 → 写回 cache → 覆盖原 URI（"wt"）
  → 可选 rename（同目录，冲突时加后缀）
```

大文件（PDF）v1 不支持；EPUB/FB2/DOCX 均有大小上限与超时。

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
7. 重定向由 OkHttp 跟随，`Interceptor` 在离开 allowlist 时剥离凭据。

## 6. 数据模型（Room）

```
books(id TEXT PK, hash TEXT, title, subtitle, authors, publisher, year,
      language, extension, size, isbn10, isbn13, cover_url, description,
      categories, edition, pages, local_uri, downloaded_at, favorite,
      created_at, updated_at)

download_queue(id TEXT PK, book_id, hash, file_name, state, bytes_written,
               total_bytes, error, created_at, updated_at)

favorites(book_id TEXT PK, saved_at)
history(book_id TEXT PK, remote_date, downloaded_at)
booklists(id TEXT PK, name, description, created_at)
booklist_items(booklist_id, book_id, added_at, PRIMARY KEY(booklist_id, book_id))
domains(host TEXT PK, status, last_checked, detail)
```

- `books.local_uri` 为空 = 未下载；`favorite` 为冗余列，便于书架过滤，收藏表仍是真相。
- 下载队列与 `books` 分离；完成后更新 `books.local_uri/downloaded_at`。
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
├── SearchTab        → SearchScreen → BookDetail
├── LibraryTab       → LibraryScreen (Downloaded/Favorites/History/Booklists)
├── DownloadsTab     → DownloadsScreen
└── MeTab            → AccountScreen / SettingsScreen
Routes: login, domain-doctor, metadata-editor, booklist/{id}, detail/{id}:{hash}
```

`miuix-nav` 管理返回栈；底部 tab 保留各自栈（每 tab 一个 `NavHost` 或 `saveState/restoreState`）。深链只支持 `zlib://book/{id}:{hash}`（v1.1）。

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
- 备份：`android:allowBackup="false"`（避免凭据/书库被云备份）；应用内 JSON 备份只含书单/设置，不含会话。

## 11. 测试接缝

- `EapiClient` 接受 `OkHttpClient` + `baseUrlProvider`，测试用 MockWebServer。
- `DomainProbe` 接受 `HttpClient` 接口，测试注入 fake。
- Repository 依赖 DAO/API 接口，不依赖具体实现。
- 元数据解析器按格式实现 `MetadataFormat` 接口，测试用 fixture 文件。
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

