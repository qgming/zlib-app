# 架构

## 1. 目标与非目标

**目标**：在 Android 上实现登录、域名体检、搜索、下载及 Z-Library“我的图书馆”（云端已下载、收藏、书单），并针对移动端做好 SAF、前台下载、分享和失败恢复。图书详情另提供只读评论与加入／创建自己的书单。云端内容以 Z-Library 服务端为准，术语见 `../GLOSSARY.md`。

**非目标**：应用备份/恢复、独立本地书库/书单及其导入导出、本地电子书元数据编辑及第三方查源、内置阅读器、Kindle、微信读书导入、多书源、MCP/TUI、静默自更新。范围调整见 ADR-030/ADR-031/ADR-120；ADR-120 仅允许本机 EPUB 只读展示书名、作者与内嵌封面，详情页继续展示 EAPI 图书信息，登录 Cookie 导入继续保留。详见 `DECISIONS.md`。

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
| `:app` | `ZlibApplication`、`MainActivity`、`BookDetailActivity`、`ShareImageActivity` 与分享图生成／导出、隔离进程的 `DomainVerificationActivity`、SharedView Kit 适配、根导航、Hilt 入口、全局错误宿主 |
| `:core:common` | `AppError`、`Result`、`DispatcherProvider`、文本/ISBN/文件名工具 |
| `:core:model` | 领域模型与 UI 共享模型，不依赖 Android 框架（除可空注解） |
| `:core:designsystem` | Miuix 深浅色主题、玻璃导航壳 `ZlibAppShell` / `ZlibPage`、统一弹层、图书网格、设置分组 |
| `:core:network` | OkHttp、`EapiClient`、共用 `PapiTransport` 的书单与评论客户端、Cookie/重定向策略、域名 allowlist/probe、独立无凭据 GitHub 发布查询 |
| `:core:database` | Room entities/DAO/DB、迁移、FTS（如需） |
| `:core:data` | Repository、下载队列、会话加密、设置 |
| `:feature:search` | 发现首页（热门与推荐）、独立搜索页、筛选与结果分页 |
| `:feature:detail` | 详情、图书评论、加入／新建书单面板、在线预览、下载按钮、分享、收藏 |
| `:feature:downloads` | 无封面的队列／已完成卡片、右侧暂停/继续/重试/X 移除记录、前台服务状态、顶栏云端下载记录入口 |
| `:feature:library` | 书架“本机 / 收藏”、我的书单、云端下载记录页；本机文件的打开/分享/删除、云端图书收藏 |
| `:feature:account` | 密码登录、Cookie 导入、登录域名选择、多账号卡片、手动切换/退出和配额 |
| `:feature:settings` | 域名、下载目录、主题、关于、真实发布日志与更新面板 |

## 4. 核心流程

### 4.1 启动

1. `ZlibApplication` 初始化 Hilt、Room、DataStore、Keystore；先加载本地目录设置。
2. 未配置目录 → SAF 目录引导，成功保存后才继续（0 网络请求）。
3. 每次启动优先验证上次使用的有效允许域名（锁定域优先）：若健康则直接复用并立即进入主界面／登录页；若用户已锁定该域且不可用，则停在失败恢复页不私自换域；否则并发探测其余允许镜像，首个健康镜像返回即立即选中并进入（ADR-115），profile 在进入后刷新。
4. 进入主界面后在后台执行一次性剩余镜像全量探测，收集各镜像实测延迟（`latencyMs`）以优化后续排序与自动选路权重（ADR-111/115），不切换当前已健康的活跃域，不做常驻轮询。

### 4.2 登录

```
密码登录: 输入 email/password → POST /eapi/user/login
          → success:1 + user.id/remix_userkey → 加密保存 → LOGGED_IN
Cookie:   粘贴 JSON / 导入 Netscape cookies.txt → 解析 → 校验
          → 加密保存
```

两种方式都归一化为 `Session(remixUserId, remixUserKey, domain, cookies)`。密码只在内存中存在一次；WebView 登录和 CookieManager 代码按 ADR-052 删除。首次登录与添加账号复用设计系统 `ZlibLoginForm`，登录／Cookie 切换按钮均为满宽；添加面板只保留简短的本机加密说明，账号卡片移除底部今日已用说明，圆环保留。登录页右侧更换图标打开 `ZlibDomainPicker`，点选指定域名后经 Repository 切换，不自动选择其它项。

ADR-051：`SessionVault` 保存多个 `SessionEntry(session, profile, expired)`、当前账号 ID 和默认关闭的自动切换开关，整个容器由同一个 Keystore AES-GCM 密钥加密后存 DataStore。开发期不读取/迁移旧 `session_blob`，首次保存删除旧键。`SessionManager` 的 Mutex 串行写入，在不可取消区完成持久化与内存发布；profile 按完整会话快照和用户 ID 回写，旧响应不能污染新会话。账号页加号打开添加面板（密码/Cookie），失败保留当前账号；同用户 ID 更新而不重复添加，长按刷新/退出指定账号。

手动切换/添加/退出当前账号通过 `DownloadRunner.withAccountChange` 与队列选择互斥，取消并等待旧写入者，活动任务改暂停后更换当前会话，用户可手动续传；下载引擎一次调用固定会话，JSON 文件地址/CDN 不混用账号。云端收藏/记录、详情和推荐随会话变更清理，搜索保留输入但清空旧结果。EAPI 只提供 `isPremium`，卡片标签仅据此区分标准/Premium，字段未知不猜。

`AccountQuotaSwitcher` 只由明确的下载 Quota 错误调用，用户显式开启后，先读当前 profile 确认剩余 0，再按添加顺序读取候选；只选额度已知、用量已知且剩余大于 0 的有效账号。每任务账号最多检查一次，不在网络/认证/风控错误时轮换；候选失效仅标记该会话。无可用账号时当前任务额度失败、其它活动任务暂停，避免队列反复打额度错误。平台未公开该模式的风控保证，默认关闭并在开启时说明风险。

### 4.3 搜索

`SearchQuery`（关键词、语言、格式、年份、精确、排序）→ `BookRepository.search(page)` → `EapiClient.search` → `BookPage(books, page, totalPages)` → `SearchUiState`。

搜索状态：`Idle / Loading / Content / Empty / Error`；分页用 `Paging` 或手写 `page` 状态，二者只选一个（见 ADR）。

搜索栏固定在顶栏下方；右上角筛选按钮打开玻璃底部面板，应用后以新条件从第 1 页搜索。分页使用上一次提交的请求，不受未提交的关键词或筛选草稿影响。

ADR-069：搜索失败 envelope 的 `Access denied` / `拒绝访问` 单独映射为 `SearchRefused`，设计系统统一显示“Z-library 拒绝搜索”，覆盖首屏、作者搜索和追加分页；不触发会话过期或自动重试，其它端点不采用该文案。发现首页（名称见 ADR-073）通过与书架相同的顶栏标签切换“热门 / 推荐”，默认热门；两类列表的加载、空结果、错误与刷新状态独立，按会话隔离。app 分别持有两类网格滚动状态，双击底部发现仅滚动当前来源；切换来源复用已加载数据，下拉刷新只读取当前来源。

ADR-062：SavedStateHandle 只恢复关键词、已提交条件及基本筛选，不保存结果或凭据，恢复后重查第一页。最近搜索独立 DataStore 默认记录最多10项，单击搜索、长按删除，右侧纯图标清空需二次确认，不显示开关或底部引导文案。筛选读取匿名 `/eapi/info/languages` 与 `/eapi/info/extensions`，失败回退固定候选；语言直接使用 API 返回名称，不做本地特殊映射或括号翻译。语言、格式为右侧触发的多选玻璃菜单，空选择表示全部，排序单选复用主题选择组件，出版年份放最后；排序按 `EAPI.md` 的8个精确取值。搜索和云端列表靠近末尾自动分页，错误停止、手动重试，空页/无新引用/末页停止。

ADR-063：`SimilarBooksViewModel` 独立读取已核实的 `/similar`，结果为有限无分页列表；详情整页使用 `ZlibBookGrid`，头部/操作/简介/出版信息跨列，相似图书复用普通网格项，只有一个纵向滚动容器。app 回调负责点击打开详情，feature无交叉依赖。

详情中的作者／译者姓名和出版社链接通过 app 回调进入同一详情 Activity 内的 miuix-nav 搜索路由，使用独立 `SearchViewModel` 自动搜索单个姓名或完整出版社名称，默认筛选且只初始化一次；返回详情或从结果打开另一本详情后返回不重置搜索分页。feature 间无依赖，不新增端点；仍调用已有的普通 EAPI 搜索，见 ADR-043/087。

### 4.4 下载

```
用户点下载 → DownloadRepository.enqueue(book)
  → Room: download_queue(QUEUED)
  → DownloadService.start()
  → RESOLVING: GET /eapi/book/{id}/{hash}/file
    → 若 302：切到无凭据通道跟随文件跳转
    → 若 200 JSON：解析 file.downloadLink，再 GET CDN（Range 续传）
  → DOWNLOADING: 写入 SAF .part，Range 续传，进度写 Room + 通知
  → COMPLETED: 记录交付 → rename 或 copy → Room 确认完成 → 通知
  → FAILED/PAUSED/CANCELLED: 保留 .part，等待恢复/重试
```

队列串行，同一时刻只有一个 `DOWNLOADING`。进程重启从 Room 恢复未完成任务，用户暂停的任务不自动启动；按 ADR-103 在继续传输前核对私有下载交付记录和临时文件。图书和 APK 的 `dataSync` 服务在系统超时后及时停止；启动受限或超时保留可恢复状态。

ADR-060/103：临时文件按完整引用派生，与显示名解耦；确认同一资源且强验证器匹配才拼接，206 校验 Content-Range 和实际写入量，旧版无验证信息的片段从零重写。签名链接不保存；数据完整后记录待交付目标及 SHA-256，应用重启可在同一目录核对文件身份与内容后补记完成，无需重新下载。无法核对时不覆盖现有文件或误记完成。目录切换通过 DownloadRunner 与队列选择互斥，先停止写入再保存授权，旧目录文件不迁移或删除。已有精确引用且在当前目录可读时，详情提供打开/显式重新下载，后者保留已有文件并另取空闲文件名。

ADR-050：卡片右侧 X 移除任务记录，失败／取消任务的 X 位于重试右侧。Service 串行处理控制指令，`DownloadQueueControl` 将下一项选择与指定记录操作互斥；仅当目标正由当前写入任务处理时取消网络调用和协程、等待其完全退出，再删除记录与临时文件。操作其它记录不打断当前传输，已完成文件和 Book URI 关联保留；暂停／取消／删除不递归发送 Service 意图。服务生命周期和指令状态在 Main 管理，网络、文件和 Room IO 仍在 IO dispatcher 执行。

ADR-045/048/092/121：详情“下载图书”分为约 30%／70% 的两个点击区，中间以竖线分隔。左侧版本图标逐次请求 `/eapi/book/{id}/{hash}/formats` 并显示原玻璃底部菜单，使用所选 `BookFormat` 的独立引用、格式与大小入队；不以当前详情的 hash／大小替代另一文件，也不以当前扩展名过滤。相同格式的多个引用全部保留，当前格式按引用去重并固定显示在第一项，空数组直接显示已确认的当前格式；请求失败保留当前格式及错误／重试，关闭或会话变化不自动下载。右侧直接下载当前详情文件，以已确认的 `id:hash`、格式、大小入队，不请求 `/formats`。两条路径共用重复点击保护、会话隔离及精确本机文件存在检查，已有文件仍提示打开／显式重新下载。

### 4.5 分享

本机文件由书架长按「分享文件」→ SAF 文件 URI + `FLAG_GRANT_READ_URI_PERMISSION` → `Intent.ACTION_SEND` + `type`（EPUB/MOBI/PDF…）→ 系统分享面板。文件分享不申请安装权限、不直接调用第三方 SDK；ADR-056 的显式应用更新另声明 REQUEST_INSTALL_PACKAGES。

本机图书文件沿用 ADR-021：直接授予 SAF 文档 URI，不经过 `FileProvider`。

ADR-101/114：图书详情与自有书单详情通过 app 回调创建有界的 `ShareImageContent` 显示快照，交非导出的 `ShareImageActivity`。`ShareImagePayload` 仅传递上限 24 KiB 的严格 JSON，不序列化完整 Book／会话；`ShareImageViewModel` 复用应用全局无凭据且启用内存／磁盘缓存的 `SingletonImageLoader` 读取封面（优先即时命中详情／书单缓存，并在内存缓存已解码位图供主题切换即时重绘），在 Default 用 `ShareImageRenderer`／`StaticLayout` 按所选浅色／深色配色绘制固定 1080px 宽、最高 3000px 的图片（移除图书封面外层大卡片），并于 IO 编码 PNG 到专用私有缓存。顶栏右侧提供浅色／深色切换按钮。缺图绘适配主题的本地经典书封，底部固定原图标和本地化软件名称。预览直接显示同一导出 Bitmap。书单仅展示前六本已加载图书，标明已知总数和部分预览，不为分享分页或改变公开状态。

「保存到本地」使用 `CreateDocument(image/png)`，由用户选择 SAF 文档位置；失败时尝试仅清理本次创建的目标，无法清理则提示。封面加载以整张图为单位限时，未取得的封面退回本地占位（ADR-104）。「分享」通过专用 `share-images` FileProvider、`EXTRA_STREAM`、`ClipData` 和临时只读授权发送单张 PNG。Provider 只暴露分享缓存目录，不暴露其它私有文件；缓存生成时清理过期图片。此图片导出与本机电子书、云端书单真相及下载队列分离，不加入存储或相册权限。

### 4.6 我的图书馆

- 书架顶部“本机 / 收藏”，首次默认本机，见 ADR-049/080。
- 「我的书单」从设置账号下方进入独立页面，底栏没有书单项，页面没有发现分组或书单搜索，见 ADR-084。
- 云端下载记录：从下载管理进入独立页面，`LibraryRepository` → 已确认的 `/eapi/user/book/downloaded` → 分页显示账号下载记录；从其他设备下载的记录也可出现，不据此声称本机有文件。旧名称“历史”不再使用。
- 收藏：`LibraryRepository` → `/eapi/user/book/saved` → 分页显示账号收藏；不创建独立本地收藏标记。
- 详情收藏：`BookFavoriteViewModel` → `FavoritesRepository` → 收藏列表按 ID 确认状态，save/unsave 写入成功后才切换心形状态并通知书架刷新；图标在详情顶栏右侧，已收藏为红心。状态未知时禁用按钮并提供重试，失败保留原状态；用户于 2026-11-22 确认收藏及云端下载记录真机验证成功，当前布局见 ADR-088。
- 我的书单：`BooklistViewModel` → `BooklistRepository` → `PapiBooklistClient`，只读当前账号拥有的书单。单内页先核对账号书单归属，再读取分页；点击图书走 app 的现有 EAPI 详情导航。会话或域名变化取消旧请求并丢弃旧列表/详情；没有本地替代或书单存储。
- 详情「加入书单」也使用 `BooklistRepository`，读取本书在当前账号各书单的归属并保留 `readlistBookId`。点击未加入书单执行 `add-book`，点击已加入书单执行 `remove-book`（ADR-113，若缺少 `readlistBookId` 则先刷新归属获取）；加入、移除成功或核对新书单确实包含本书后，使「我的书单」列表、预览及当前单内页失效重读，不让旧分页游标续读。面板按会话隔离，不持久化输入或归属。
- 书单卡片有四个封面位，优先使用响应内的预览图。缺少时对可见自有书单惰性读取图书第一页，最多两个并发，使用登录态 PAPI。封面失败或书单不足四本时保留空位，图片请求不带账号凭据。
- 刷新以云端结果更新视图。列表及补取的封面仅保存在当前 ViewModel 内存，随会话变更清空，取消旧请求并拒绝旧结果回写。
- 收藏确认在同一会话内复用服务端收藏 ID 分页，读写互不阻塞；每次查询限定页数和等待时间，成功结果最多复用 30 秒。列表刷新、收藏写入和会话变更使索引失效，未扫完不能判未收藏，不写独立本地标记（ADR-063）。
- 可见卡片封面缺失或地址无效时，使用详情 EAPI 补取，最多 3 并发、同一会话内每本一次；仅更新封面，失败保留占位图与记录。图片加载不带账号凭据，来源与限制见 `EAPI.md` §2.7。
- 云端下载记录删除：长按封面打开更多菜单，经确认框调用 `/eapi/user/book/{numericId}/delete`。成功才移除并从第一页刷新，避免旧分页偏移漏项；失败保留条目。该写入 GET 不自动重试或重定向，防重复确认并隔离会话，见 ADR-046。
- 本机文件：书架 `LocalFilesContent` → `LocalFilesViewModel` → `LocalFilesRepository` → `SafLocalFileStore` 扫描设置中的 SAF 树及子目录，过滤支持扩展名、排除 `.part`、Provider 未完成及待交付文件。`LocalFile` 以文档 URI 为身份，真实目录决定存在性，Room 精确 URI 匹配只补全元信息。打开、分享、删除前重验当前授权树下的精确文档；目录已变时拒绝。删除返回成功并确认该 URI 不再可见后，才清除 Room 对该 URI 的关联。
- 本机列表仅可见且 lifecycle STARTED 时收集，离开停止扫描；Provider 变动信号合并，Room 下载完成、交付记录变动、手动刷新即时触发核对，缺通知时以 3 至 15 秒的退避间隔兜底。按目录隔离旧扫描，并向 Provider 传递取消信号。权限错误清除陈旧文件并提示重新授权。

ADR-120：`CachedEpubMetadataReader` 在 IO 中逐本读取 EPUB，先显示扫描到的真实文件，再渐进补充 OPF 书名、作者与内嵌封面。`LocalFileMetadata` 仅是文件的显示信息，不生成云端引用、不写 Room。元信息优先，缺项回退到精确 URI 的已知资料／文件名；非 EPUB 沿用现有展示。缓存按树、精确 URI、文件名、大小、修改时间隔离，手动刷新重新读取，改变目录及可见文件集时丢弃旧结果。EPUB 只在应用私有缓存暂存一个有界副本，解析完成或取消即删除；经过尺寸检查和采样解码的封面以内容摘要命名，私有封面缓存定期清理。归档上限 128 MiB、10000 条目，单 XML 1 MiB、封面 8 MiB、累计解压读取 10 MiB；拒绝 DTD、外部实体及档案路径越界，不执行书内页面或读取网络。失败保留文件项和原有回退；不恢复编辑／查源或内置阅读器。

下载管理右上角云图标进入记录页，“已完成”是任务记录，移除不删除实际文件。点击已完成卡片按当前任务及 Book 的精确 `id:hash` 与 URI 关联，经 `LocalFilesRepository.readableDownloadedFile` 走与本机相同的 SAF／待交付核对，再复核关联和当前目录；共用 `launchLocalFile` 以 MIME、`ClipData` 和临时只读授权执行 `ACTION_VIEW`。缺文件显示“文件没有找到，可能已被删除”短 Toast，权限、目录变化和无可用阅读器分别提示；X 独立移除记录。本机没有独立路由，长按菜单两项相隔 12dp。

## 5. 域名管理

### 5.1 allowlist

内置官方后缀 + 内置 34 个社区镜像精确主机 + 用户明确添加的精确镜像，规则见 `AGENTS.md` §3.2、ADR-102 与 ADR-111。未知发现域只是无凭据探测候选，不能因兼容的探测结果或旧版锁定而获得凭据。

ADR-107/111：设置、登录与启动失败恢复直接展示 17 个官方、34 个社区镜像，来源与双线路复测见 `MIRROR_AUDIT.md`。内置社区镜像按精确主机直接纳入 `DomainPolicy` 允许列表（不扩展到子域），与官方镜像一样可直接选择、参与启动／自动恢复体检与自动切换，不再需要二次授权确认弹窗或撤销授权流程。用户手动输入的新主机仍遵循精确主机校验与独立删除管理。

### 5.2 探测

`DomainProbe` 对 `/eapi/info/domains`（免登录）发 GET：

- 8 个并发，10s 超时，最多 3 次重定向，记录每次体检的往返耗时（`latencyMs`）；
- 分类：`HEALTHY`（JSON `success:1` + domains）、`CHALLENGED`（HTML challenge）、`DIAMWALL`（513/517 或标题）、`REDIRECT_LOOP`、`HTTP_ERROR`、`NETWORK_ERROR`、`INVALID`；
- 结果区分跳转发起域与实际应答域；只让实际应答且已允许的域成为可自动切换候选；
- 不做后台常驻；启动、登录失败、下载失败、用户手动触发时探测。

启动、故障恢复与用户手动全部体检均覆盖内置目录（官方与社区镜像）及用户添加的镜像；健康域名卡片展示实测延迟，自动选路与体检列表排序优先按实测延迟升序排列健康镜像，非健康项保留原分组顺序（ADR-111）。目录内精确主机可接受 `success:1` 与空 `domains` 数组；未知重定向目标仍须返回已知家族，再由用户单独明确添加。

ADR-052：域名列表在初始化时即列出内置、用户添加及当前域名，未检测显示未知。单独／批量体检发布 `PROBING` 并阻止重复操作，单独体检只更新目标结果。域名管理最顶部为自动切换开关，顶栏右侧依次为全部体检和添加域名图标，添加使用玻璃底部面板；列表使用统一 24dp 卡片，右侧切换／体检纯图标有 48dp 触摸区，自定义域名长按可删除。开关复用 `lockedHost` 持久化：为空代表开启，关闭时锁定当前域名，之后手动选择同步更新锁定目标；全部体检与重启遵守锁定。添加失败保留输入与错误，操作成功用短提示反馈。

ADR-108/109/110：可信主机被标记为 `DIAMWALL`／`CHALLENGED` 时，选择操作发出导航事件，由 app 打开非导出 `:domain_verification` 进程的验证 Activity。其 WebView 数据目录与图书预览隔离，不再通过 `DomainVerificationTransport` 拦截 WebView 资源，而由隔离进程内的 Chromium WebView 原生加载并由用户手动完成挑战，界面复用 Compose + Miuix 的 `ZlibPreviewPage` 风格（禁用玻璃背景采样、顶栏返回／域名标题／刷新／完成按钮、顶部进度条）。严格禁止注入、读取或回传 `remix_*` 账号凭据，不自动求解；进入与退出均清空隔离 WebView Cookie 与存储。用户点击完成后，仅提取白名单防壁垒 Cookie（`__diamwall`、`cf_clearance`、`__cf_bm`、`ddos_guard`、`_dd_`、`__ddg1_`、`__ddg2_`）、当前 WebView `User-Agent` 和验证时的系统网络标识交回主进程。`DomainVerificationCookies` 根据主机允许状态、网络一致性和 15 分钟有效期决定是否提供，同域 `/eapi/`、`/papi/` 与初始匿名体检显式读取；网络未知、改变或过期后丢弃，默认网络回调仅清理验证状态，不安装全局 CookieJar。根 ViewModel 调用仓库的原子复检／选择，在同一个锁内确认原主机 EAPI 成功后才选择，不将跳转目标的成功当作原主机验证完成；绝不向封面、预览、CDN 或更新客户端共享验证 Cookie。

### 5.3 请求管线

`EapiClient` 每次请求：

1. 用当前 `domain` 拼 URL，发出前校验受信任的标准 HTTPS 来源；
2. 加 `Accept: application/json`、UA（同域同网络存在有效防壁垒验证状态时复用验证 WebView UA）、Cookie（必要时附带同域白名单防壁垒 Cookie）、`remix-userid/remix-userkey`；
3. POST 用 form body；
4. 有界读取且可取消，解析 envelope；HTTP 非 2xx 但 envelope 有 `success:0` 时保留服务端 error；搜索等列表缺少有效书目数组时报错；
5. 识别 `Invalid credentials`/`Please login` → `SessionExpired`；
6. 识别 513/517/HTML DiamWall → `DomainBlocked`；
7. 密码登录和修改云端记录等单次写入不跟随或重发；EAPI 其他请求不跨来源重定向。需要识别新来源时走独立匿名体检；下载文件跳转走独立无凭据通道（ADR-102）。

书单和评论客户端共用 `PapiTransport`，只实现 [PAPI.md](PAPI.md) 记录的端点。请求固定在会话的当前受信任 HTTPS 主机，发送当前会话的 `remix` Cookie，并在当前网络同精确主机存在有效防壁垒验证状态时复用白名单防壁垒 Cookie 与对应 WebView `User-Agent`（ADR-109/110）；不跟随重定向、不接受新会话 Cookie、不刷新登录、不切域。响应按 ADR-109 与 `EapiClient` 一致，先有界读取并尝试按 JSON 对象解析，仅在非有效 JSON 时再按 HTTP 状态码与 HTML 内容判定 `CHALLENGED` / `DIAMWALL` 或格式错误。ADR-096 在仓库层为限定的读取处理 1/2/4 秒退避，重试前后检查会话；写入不重试、不进入 HTTP 缓存。失败在当前功能内处理，不改变 EAPI 登录态。

ADR-099 已取消关联／延展书单、书页 HTML 解析和匿名公开书单读取。网络层仅提供当前使用的 JSON 接口，不保留 HTML 数据提取或公开书单备用通道。

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
- 云端缓存须明确账号归属、刷新与清理规则，能从服务端重建，不能成为独立云端书库。书单仅在当前会话内存保留，不保存到 Room 或导航恢复状态。
- 迁移：每次 schema 变更一个 `Migration(n, n+1)` + `MigrationTestHelper`。

## 7. 状态与错误

```kotlin
sealed interface AppError {
  data object NoDomain : AppError
  data object NotLoggedIn : AppError
  data object SessionExpired : AppError
  data object SearchRefused : AppError
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
│                        → DownloadHistoryScreen (云端下载记录) → BookDetailActivity
├── LibraryTab       → LibraryScreen (本机 / 收藏)
│                        → LocalFilesContent (目录实际文件，外部打开/分享/删除)
│                        → 收藏 BookDetailActivity
└── SettingsTab      → SettingsScreen → AccountScreen / DomainSettingsScreen
│                                    → MyBooklistsScreen → BooklistBooksScreen → BookDetailActivity
│                                    → AboutScreen → AboutDocumentScreen / ReleaseHistoryScreen
Routes: login, search, downloads, download-history, my-booklists, booklist, account, domain-doctor, about, about-document, release-history
详情: 非导出的 BookDetailActivity，Intent 图书引用 id/hash
      → 图书评论列表 → 评论详情
      → 搜索 / 在线预览
```

`miuix-nav` 管理返回栈，主 tab 为发现/书架/设置（英文 Discover / Bookshelf / Settings，ADR-073/084），首页使用 Glass 系列的 `NotesOutline` 图标，书架使用 `BookOutline`，见 ADR-038；热门和推荐按 ADR-069 通过顶栏标签切换。搜索和下载管理从发现页顶栏进入，返回后回到原来的位置。双击底部“发现”入口回到当前来源网格顶部并展开顶栏；使用系统双击间隔，切换其它 tab 重置识别。两类网格状态由 app 独立持有并传给首页，不引入 feature 依赖。当前未注册外部 URI 深链；下载通知通过应用内部显式 Intent 进入下载管理。

自有单内路由只保存书单 ID，恢复后重新核对当前账号是否仍拥有此书单。书单列表和单内路由共用会话内状态，返回列表沿用滚动位置。所有 feature 由 app 通过回调组合，不互相依赖。

主 tab 切换采用 150ms 整页横向滑动，距离为当前页面完整宽度，不叠加淡入淡出；标题、顶栏操作、当前页分组及内容共同移动。底栏置于动画容器外保持固定，采样正在移动的页面，Miuix 指示器继续动画；每页底部按实测底栏高度预留安全区。`AnimatedContent` 以 tab 为内容键，每页提供独立 `ZlibPageScroll` 并通过可保存状态保留滚动位置，防止退出页面的 dispose 清除新页面的滚动驱动；重复选择同页不重播，快速切换可重定向。页面动画期间阻挡移动页交互，不阻挡底栏，见 ADR-041。

ADR-040 将详情从 miuix-nav 路由改为独立 `BookDetailActivity`，以接入 ITGSA SharedView Kit 的 ActivityOptions。SDK 固定 AAR 仅由 app 引用；设计系统通过 `ZlibBookOpenRequest` 提供封面快照和实时坐标，feature 只提交请求。按 ADR-041 默认尝试 ROM 能力、不提供设置项或应用内模拟转场；设备支持时使用 Bitmap 源打开，返回前更新 WINDOW/SCREEN 声明对应的封面落点，不支持／快照失效时普通打开。详情先展示基础信息并读取完整详情，加载完成后才提供下载。进程重建从 Intent 图书引用恢复，转场 Bitmap 和来源查询器不落盘、不含凭据。具体来源、边界、许可和验收见 `ANIMATIONS.md`。

ADR-085/087/088/092/096/100/101/121–123：详情依次显示封面、书名、作者、信息胶囊、下载／预览操作、简介、评论预览与查看更多、同宽同样式的「加入书单」、出版信息和相似图书；顶栏为收藏、分享。胶囊按双评分、出版年份、当前文件格式、当前文件大小排列，兴趣评分为黄色、下载质量为绿色，以斜杠分隔且缺单项显示“—”；其余胶囊保留原图标与尺寸，自动换行，缺项不占位，出版年份为空或数值零时隐藏；仅展示当前详情已有字段，不发起格式查询。简介按 Compose 实际排版在超过五行时提供上下箭头展开／收起，保存同一详情的展开状态，按钮上下等距。`BookDescription` 只在本地格式化 EAPI 的 `description`，保留换行、列表、强调和复制，移除图片、链接行为与脚本；不使用 WebView。出版信息展示出版社、语言、版次、页数、ISBN 和完整分类，不重复胶囊字段；只有出版社为蓝色可点击搜索，其余字段为普通只读文本，不复制。下载入口按 ADR-121 分区，按 ADR-122 与预览按钮等宽；两个按钮使用相同最小宽度和权重，空间不足时换成两个等宽整行按钮。格式菜单、操作反馈、安全边界与存储沿用现有流程。

ADR-093–096：`BookDetailActivity` 内的图书详情预览、评论列表与评论详情共用 `BookCommentsViewModel`，导航只传图书及评论 ID。预览最多三张主评论卡片；列表没有书名，只展示主评论的三行摘要；评论详情显示全文及按 `parent_id` 排列的回复，为嵌套回复显示实际回复对象。在这三页之间往返保留同一本书的已加载数据；转入其他路由、切书、切号或更换镜像时取消旧读取。头像使用独立无凭据图片加载器。`BooklistPickerViewModel` 在图书详情驱动玻璃选择面板与创建表单，复用「我的书单」的四封面卡片与可见时取图逻辑；写入绑定打开时的会话，确认后通过设计系统 Toast 反馈。

ADR-088/089/091：详情根据响应判断是否显示预览，不把签名地址传入导航。进入预览和手动刷新均由 `BookPreviewViewModel` → `BookRepository.resolveOnlinePreview` 读取当前镜像的 EAPI 详情，不使用缓存。预览页单独用 WebView 渲染服务器返回的原始 HTTPS 阅读链接，不注入会话 Cookie，不限制阅读器页面及资源 CDN 域名，进入前与退出后都清空 Cookie；顶栏提供返回与 Miuix 更多菜单，可刷新、复制公开图书链接、显式交浏览器打开当前阅读链接。预览页不使用玻璃背景采样，避免 `layerBackdrop` 捕获 WebView 导致瓦片内存超限和闪烁。

ADR-043/044/087：`ContributorNames` 本地解析作者原文中的姓名范围，各姓名通过 `LinkAnnotation.Clickable` 独立点击搜索；国籍、角色与分隔符原样保留普通样式，姓名内空格／中点／连字符不拆开。详情书名使用 SemiBold（600），蓝色姓名链接使用 Medium（500），作者行统一比正文大 1sp。`BookDetailLauncher` 按来源 Activity 分别防重复点击和保存返回会话，使详情内发起的搜索结果可继续打开详情并逐层返回。

顶栏和浮动底栏采样页面内容；滚动容器使用实测栏高和系统安全区做内容内边距，不裁掉首尾卡片。玻璃样式按深浅色使用同版本 Miuix 示例默认材质，不跟随壁纸；支持的设备始终启用。固定配色和玻璃样式见 ADR-026 至 ADR-028。等待加载使用与书架下拉刷新同款的 Miuix 环形游点；可计量的下载继续显示字节进度。

发现和书架的分组都是玻璃顶栏的一部分，分组下方的内容可滚入顶栏的渐进模糊区域。玻璃筛选、确认框和外观菜单由设计系统统一封装，采样完整页面但不采样弹层及遮罩，见 ADR-029。

ADR-047：`ZlibGlassBottomSheet` 统一为所有面板内容增加横向系统栏／刘海、底部导航栏安全区与额外 16dp 间距，保留 Miuix 的 IME 避让；详情信息、格式菜单和搜索筛选不再各自重复计算。`ZlibPage` 不再提供 Snackbar 插槽，`ZlibSnackbarHost` 已删除（ADR-067）。ADR-064/067 将云端记录删除成功、手动检查已是最新版、本机文件删除/分享失败、账号与域名操作结果统一为 `ZlibToastHost`（系统 Toast 短样式），消费一次性事件；宿主按 ViewModel 会话代次或当前目录重建，页面退出或账号变化取消当前 Toast，不插入网格顶部，也不为已是最新版打开底部面板。

发现页与书架的图书网格共用 `ZlibBookTile` 与 `ZlibLocalFileTile` 的排版，仅显示宽高 2:3 的封面和下方单行书名，超长末尾省略，无包裹整项的卡片背景。封面等比裁切填满，云端图书点击进入详情，本机文件点击外部打开。`ZlibCover` 在缺图、加载中和失败时绘制本地经典书封：深色布面配色、细金框、居中衬线书名与作者；尺寸随封面缩放，作者缺失显示“作者不详”。真实封面成功后替换；本机 EPUB 另按 ADR-120 读取内嵌显示信息，其它格式不自动解析，不查源或写图书文件。详情复用同一实现；搜索结果页按 ADR-112/119/121–123 使用单列图文列表，左侧 2:3 封面（保留经典缺图封面与 SharedView Kit 转场来源），右侧自上而下依次展示书名、出版社普通只读文字、作者／译者及双评分、出版年份、内容语言、格式、文件大小共最多五项胶囊（左对齐自动换行、缺项不占位）。双评分为首个普通胶囊，背景、尺寸与其它搜索胶囊一致，不再悬浮或预留卡片外空间；列表直接使用普通卡片布局。搜索与详情的出版年份为空或数值零时隐藏，原始字段保留。内容语言使用 `BookLanguages` 识别已知语言名称、ISO 代码与已核实别名，按应用有效中／英文显示，未知值原样保留，不改写原字段或筛选提交值。出版社直接取已有搜索结果，缺失不占位；仅搜索胶囊收紧内边距与图文间距以优先在一行容纳常见短值，详情尺寸不变。下载任务卡片按 ADR-050 不显示封面。本机文件长按打开分享/删除面板，删除继续二次确认。

网格密度由设置的 `BookGridColumns` 统一控制：自动/2/3/4/5 列，保存到 DataStore，经 `ZlibTheme` 的 CompositionLocal 提供给所有 `ZlibBookGrid`，feature 不读取存储或互相依赖。默认“自动”，使用 `BoxWithConstraints` 的当前布局宽度，小于 360dp 为 2 列、360–599dp 为 3 列、600–839dp 为 4 列、840dp 及以上为 5 列；手动选择覆盖宽度规则，旋转/分屏只影响自动模式，见 ADR-036。设置按 ADR-050/056/057 依次为账号、外观、下载目录、域名管理、关于，下载与域名为独立卡片，ADR-056 移除分组标题，各组选项无左侧图标及图标预留空间，文字横向 20dp 内边距；内部无行间分割线，组间 12dp。设置分组和下载任务卡片圆角 24dp，其它卡片默认 16dp。账号入口主标题显示名称，副标题显示邮箱。

ADR-053/055：中文显示名“芝士读书”由 `core:common` 共享字符串资源提供，桌面、启动、登录和通知一致；英语用 Zlib，不改包名和签名。设置末尾的“关于”不显示副标题，经 app 导航进入 `feature:settings`，app 将自己的图标 Painter 和 `BuildConfig` 版本信息传入，feature 不依赖 app。关于页复用设计系统的居中标识、24dp 无图标分组和原生入口；ADR-057 保留 Miuix 原生 56dp 最小行高，不设最大高度，随内容自然伸展，保留实时发布日志、协议与隐私和许可原生文档，最后的“关于作者”交系统打开 `https://github.com/qgming`；不再有反馈模板或项目与开发者页面。`AboutDocumentsViewModel` 仅在进入许可页后于 IO 读取 APK 内文本，错误可重试；构建任务将根 LICENSE 与依赖许可全文同步为 assets，防止维护副本漂移。按 ADR-072，根 NOTICE 只在仓库维护，不再加载为独立许可条目。

ADR-055/056/104/105：`AboutViewModel` 在根页面共享，冷启动检查一次／用户手动检查 → `AppUpdateRepository` → `GitHubReleaseClient` 读取固定公开仓库的 `/repos/qgming/zlib-app/releases/latest`；日志页独立读取 `/releases?per_page=20` 的正式发布标题、tag 与真实正文，无预写假日志。更新客户端独立构建，无会话、Cookie、EAPI 拦截器或自动重试；最新 tag 按 SemVer 比较。手动操作的连接失败及明确限流使用「网络环境异常，请关闭或开启 VPN 后重试。」短 Toast，检查更新不弹失败面板；其他响应错误保留相应状态。日志保留中性失败／重试与 Releases 入口；缓存标明来源且不可触发 APK 下载。启动检查只在新版时自动弹面板，失败静默；手动检查加入正在进行的启动检查时，仍只反馈一次。

更新客户端按 Android 当前默认网络标识保存限流截止时间，latest 和 history 共用。切网后丢弃空闲旧连接并允许用户手动查询新网络，切回受限网络继续等待；未知或在请求过程中变化的网络遵从保守等待。`ACCESS_NETWORK_STATE` 仅用于请求时读取网络标识，不读取公网 IP、不判定 VPN、不注册常驻监听、不随网络变化自动查询。

点击更新由 `UpdateDownloadManager` 启动独立 `UpdateDownloadService(dataSync)`，`ReleaseApkDownloader` 用匿名客户端下载匹配 ABI／通用 APK，逐跳只允许 GitHub 与发布资产 CDN HTTPS，限制跳数、长度及摘要。取消等待真实写入线程结束后再清理临时文件。`UpdateApkStore` 检查包名、相同签名、tag 与递增 versionCode，保存到用户授权的下载目录，保留私有验证副本用于安装。完成自动弹出同一面板的安装状态；用户点击后经 FileProvider 只授予私有 updates 单文件读取权限，必要时引导未知来源授权，再打开系统安装器。图书队列不参与更新下载，不自动安装、不定时检查。

ADR-054：图标源保存于 `app/artwork/zhishi.svg`，app 使用对应的 Android 渐变矢量与 even-odd 透明镂空。桌面自适应资源组合奶油色背景和安全区内缩前景；关于页直接传入透明前景 Painter，不添加背景或圆角裁切，不引入运行时 SVG 解析。

ADR-070：更新日志与许可页共用设计系统 `ZlibExpandableText`，整行标题切换、正文向下展开且可复制，24dp 圆角和12dp间距。日志可比较的版本优先、按版本降序，其余标签按发布时间降序置后，按 tag 去重，仅最新一项默认展开；许可按依赖族展示离线来源与完整许可，默认全部收起，SharedView SDK单独说明发布方条款。各项展开状态由页面保存，滚动或旋转不覆盖手动选择；开源文本及来源记录见 `feature/settings/src/main/legal/README.md`。

ADR-072/073/075/076/077/078/118/123：当前正式版为 1.5.2/code10，已发布为 GitHub Latest；构建、发布资产和验证事实见 `RELEASING.md`。所有EAPI请求统一携带`source: android`，用于通过搜索端点的客户端来源校验；最近搜索以自动换行胶囊展示。设置外观组依次为主题模式、软件语言、网络格数；`ApplicationLanguages`以Android13+原生`LocaleManager.applicationLocales`为语言唯一持久来源，空列表跟随系统，zh-CN/en为显式选择，manifest声明localeConfig。系统配置重建Activity，Compose使用stringResource读取当前资源，非Compose的ViewModel事件和Repository错误通过core:common的AppText应用资源桥接；JVM默认表从同一XML构建生成。中文首页统一“发现”，英文Discover，内部路由与双击回顶不变。许可页直接显示项目及各依赖，移除介绍与单独Project attribution条目，根NOTICE仍在仓库维护。

## 9. DI 与并发

- Hilt：`@HiltAndroidApp`、`@AndroidEntryPoint`、`@HiltViewModel`；`NetworkModule`、`DatabaseModule`、`RepositoryModule`、`DispatcherModule`。
- Hilt/KSP 工具链验证已通过，当前统一使用构造注入与 Hilt ViewModel，不混用第二套 DI。
- 所有 Repository 方法 `suspend` 或返回 `Flow`；禁止在 main 线程做 IO。
- 下载 Service 用 `SupervisorJob + Dispatchers.IO`；队列状态写 Room 用单写者 `Mutex`。

## 10. 安全

- 凭据加密：Keystore AES-GCM，密文存 DataStore；不存密码。
- 日志：统一 `SafeLog`，对 Cookie/remix/URL query 做脱敏；release 关闭 verbose。
- 网络安全：只 HTTPS；不允许用户配置 `http://` 镜像；重定向剥离凭据；可选证书校验保持系统默认（不做 pinning，避免镜像更换即挂）。
- 密码登录和 Cookie 导入均只请求 EAPI；不嵌入登录网页或初始化 WebView Cookie 存储。
- 系统备份与数据迁移：保留 `android:allowBackup="false"` 和 data extraction rules，防止会话及设备记录外泄；不提供应用备份/恢复或本地书库导入导出。登录后从服务端重新读取云端图书馆。

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


## 正式分发

ADR-071/074：从1.3.0起通过 `:app:prepareReleaseAssets` 只生成含四种ABI的 release universal 正式通用 APK 与SHA-256校验文件，替代ADR-058的五包构建要求，日常检查与打包不附带调试APK；原包名与签名不变，versionCode继续递增，GitHub Release标签与versionName对应。已发布1.2.0匹配不到独立ABI包时会选择唯一通用包，不需改旧客户端；真实日志及更新资产使用同一发布源，详见 `RELEASING.md`。
