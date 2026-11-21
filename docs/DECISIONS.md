# 决策记录（ADR）

每条决策一个编号。改决策 = 新增一条 ADR 并标注取代关系，不要直接改历史。

---

## ADR-001：Kotlin 原生重写，不打包 Go CLI

**状态**：接受

**背景**：`heartleo/zlib` 是 Go CLI，没有公开 API；TUI 与终端能力无法直接复用到 Android。

**决策**：用 Kotlin 重写 EAPI 客户端与业务层；不采用 gomobile、不嵌入 Go 二进制、不调用 CLI 子进程。

**后果**：协议逻辑需要重新验证；但 UI、存储、后台下载、SAF 都是原生体验，包体小，可维护。

---

## ADR-002：只做 Z-Library 镜像，不做多书源

**状态**：接受

**背景**：多书源（Anna's Archive、Gutenberg、IA）协议、合法性和元数据模型完全不同。

**决策**：v1 只实现 `ZlibSource`；保留 `BookSource` 接口作为未来扩展点，不实现其它源。

**后果**：范围可控；未来接多书源需要新的数据模型和合规评估。

---

## ADR-003：范围 A+B+D，不做 C；Kindle 用系统分享替代

**状态**：接受

**背景**：CLI 的微信读书导入（扫码 + COS 上传）与 Kindle SMTP 都是独立子系统。

**决策**：
- A 核心 + B 元数据 + D 书单/历史删除。
- 不做微信读书导入 API、不做 Kindle。
- “打开书” = `ACTION_SEND` 分享给阅读器/微信读书。
- E 只保留 JSON 备份/恢复；更新检查放 v1.1。

**后果**：v1 不依赖微信 SDK/SMTP；分享面板的可用性取决于用户安装的 App。

---

## ADR-004：不做内置阅读器

**状态**：接受

**背景**：EPUB/PDF 阅读器是独立产品，涉及排版、翻页、目录、进度、字体。

**决策**：v1 只下载和分享；书架记录“已下载”，不记录阅读进度。

**后果**：用户需要外部阅读器；未来要做阅读器时另立模块和 ADR。

---

## ADR-005：书库按 CLI 语义（C1）

**状态**：接受

**背景**：CLI 的“书库”实际是已下载 + 收藏 + 历史 + 书单；本地文件只用于 `meta`。

**决策**：C1。不做任意本地文件导入、重复检测、系列、阅读进度。本地文件仅通过元数据编辑入口访问。

**后果**：数据模型简单；用户不能把任意本地书加入书架。

---

## ADR-006：SAF 作为唯一下载落盘方式

**状态**：接受

**背景**：MediaStore 直写 Downloads 对 `.part`、续传、改名不友好；应用私有目录用户找不到。

**决策**：用户通过 `ACTION_OPEN_DOCUMENT_TREE` 选一次目录（默认提示 `Download/ZLib`），持久化 URI 权限；`.part` 同目录，完成后 rename；不支持 rename 时 copy+delete。

**后果**：首次使用多一步；换目录需要重新授权；不申请 legacy 存储权限。

---

## ADR-007：minSdk 33 / targetSdk 37 / compileSdk 37.2

**状态**：接受

**背景**：Miuix glass/blur 依赖 API 31 的 RenderEffect；`miuix-blur` 的 manifest 实际要求 **minSdk 33**。用户接受较新设备范围与性能风险。

**决策**：minSdk 33，targetSdk 37，compileSdk 37.2，build-tools 37.0.0，JVM 21。

**后果**：放弃 Android 13 以下设备；省掉大量旧行为分支；玻璃效果可用。

---

## ADR-008：Miuix 0.9.5-beta03，全 Android 模块

**状态**：接受，带风险

**背景**：glass/blur 只在 0.9.5-beta 线；稳定 0.9.4 没有 glass。beta03 绑定 Compose 1.13.0-rc01 与 AGP 9.5.2。

**决策**：使用 `miuix-ui`、`preference`、`icons`、`glass-icons`、`material`、`squircle`、`blur`、`glass`、`nav`；排除 desktop-only 的 `miuix-window`；跳过 `navigation3-*`。Miuix 全部封装在 `:core:designsystem`。

**后果**：接受 RC Compose 与 beta API 变更；升级需单独验证；玻璃效果提供设置开关与低端机降级。

---

## ADR-009：组件化模块图与 miuix-nav

**状态**：接受

**模块**：`:app` + `:core:{common,model,designsystem,network,database,data}` + `:feature:{search,detail,downloads,library,account,settings,metadata,booklists}`。

**决策**：feature 之间不互相依赖；底部 4 tab（搜索/书架/下载/我的）；导航用 `miuix-nav`。

**后果**：编译并行、边界清晰；模块多，首次搭建成本高。

---

## ADR-010：Hilt + Room + KSP，1 天 spike，失败回退手写 DI

**状态**：接受（Hilt/KSP spike 已通过，Room 待 v0.4 接入）

**背景**：AGP 9.5.2 + Kotlin 2.4.21 + KSP 2.3.12 是前沿组合；Room+KSP 有本机先例，Hilt 未验证。

**决策**：先按 Hilt + Room + KSP 搭；第一天做最小 spike（Application + 一个 ViewModel + 一个 DAO）。失败则回退 `AppContainer` 构造注入，Room 保留。禁止两套 DI 混用。

**验证（2026-11-21）**：`:app` 与 `:core:data` 已启用 Hilt 2.60.1 Gradle 插件 + KSP 2.3.12，`@HiltAndroidApp`、`@AndroidEntryPoint`、跨模块 `@Inject`/`@Provides`、`@HiltViewModel` 均编译通过并在真机启动；`SecretCipher`、DataStore、Repository 由 Hilt 装配。Room 在 v0.4 下载队列落地时再加，不自造第二套 DI。

**后果**：Hilt 失败会多一天；回退后 ViewModel 工厂需要手写。当前无需回退。
---

## ADR-011：裸 OkHttp + 前台 Service 下载

**状态**：接受

**背景**：EAPI 需要动态域名、Cookie/remix 头、表单编码、错误 envelope、跨域重定向剥离凭据；Retrofit 的动态 baseUrl 反而绕。

**决策**：OkHttp + kotlinx.serialization + 自定义 `EapiClient`。下载用前台 `dataSync` Service + Room 队列，顺序 1，Range 续传；WorkManager 只做失败重试/清理。

**后果**：网络层代码量略多；但行为完全可控，测试用 MockWebServer。

---

## ADR-012：三登录通道，单账号

**状态**：接受

**决策**：邮箱密码（主）+ WebView 抓 Cookie（备）+ 粘贴/Netscape 导入（兜底）；单账号；退出清空所有 Cookie；密码不落盘。

**后果**：WebView 可能被 Cloudflare/DiamWall 挡；Cookie 导入是最后逃生门。

---

## ADR-013：CLI 同款域名策略

**状态**：接受

**决策**：内置 allowlist + 用户镜像；启动/登录失败/下载失败时探测；8 并发、10s 超时；自动切健康域；切换不丢 Cookie；不做后台常驻探测；设置可手动锁定。

**后果**：域名失效时恢复较快；探测本身可能被风控，需限频。

---

## ADR-014：元数据 v1 只做 EPUB/FB2/DOCX

**状态**：接受

**决策**：v1 支持 EPUB、FB2、DOCX 的读取/编辑/重命名/嵌封面；查源 Google Books、OpenLibrary、Douban。MOBI、PDF、Goodreads 放 v1.1。

**后果**：PDF/MOBI 用户 v1 不能改元数据；SAF 写回用“复制到 cache → 改 → 覆盖”策略。

---

## ADR-015：身份、许可证、分发

**状态**：接受

**决策**：
- 名称 `Zlib`，包名 `com.qgming.zlib`，仓库 `zlib-app`。
- MIT + NOTICE（`heartleo/zlib` MIT、Miuix Apache-2.0）。
- zh-CN 默认 + en 资源；零遥测。
- GitHub Releases 签名 APK；不上 Play；README 免责声明。
- 更新只做“检查 Release 并跳转浏览器”。

**后果**：需要自己维护签名与发布；Play 政策风险规避。

---

## ADR-016：错误映射与恢复

**状态**：接受

**决策**：统一 `AppError`；会话过期当前域重试一次再重登；DiamWall/513/517 切域；配额不重试；网络/502/503/504 退避；其它 4xx 不重试。详见 `EAPI.md` §4。

**后果**：用户看到的错误是可行动的，不是原始 JSON。

---

## ADR-017：测试、CI 与签名

**状态**：接受

**决策**：
- 单测 + MockWebServer + Room migration + 关键 Compose UI。
- GitHub Actions：JDK 21、SDK 37.2、build-tools 37.0.0、`test lint assembleRelease`。
- 本地签名复用 `D:\dev\aiok\android\app\qgming2026.jks`（alias `qgming`），通过不提交的 `key.properties`；CI 用 Secrets 注入 base64 keystore。
- 不提交 keystore/密码；签名不随意更换。

**后果**：朋友可覆盖安装；keystore 丢失会导致无法更新，需要备份。

---

## ADR-018：服务端状态与本地文件语义

**状态**：接受

**决策**：
- 收藏/历史以服务端为准；启动/下拉刷新。
- 删除本地文件不删服务端历史。
- 删除历史默认只删服务端；弹窗可选“同时删除本地文件”。
- 取消收藏不影响本地文件。

**后果**：多设备行为可预测；本地删除不会误删云端记录。

---

## ADR-019：会话加密使用 Android Keystore + DataStore 自实现

**状态**：接受

**背景**：`androidx.security:security-crypto` 长期停留在 alpha 且已弃用；只需要保存一个会话 blob。

**决策**：`SecretCipher` 接口 + `KeystoreSecretCipher`（AES-256-GCM，密钥别名 `zlib.session.v1`，IV 前置）；密文 Base64 后写入 Preferences DataStore。密钥失效/解密失败视为未登录，不崩溃。

**后果**：无额外依赖；单元测试通过 `SecretCipher` 假实现覆盖 `SessionManager`，Keystore 本身留仪器测试。会话不参与备份（`allowBackup=false` + data extraction rules）。

---

## ADR-020：单元测试统一 JUnit4 + kotlin.test

**状态**：接受

**背景**：`docs/TESTING.md` 最初写 JUnit5；Android Gradle 默认 runner 与 JUnit5 需要额外插件，收益不足。

**决策**：JVM 单测用 JUnit4 `4.13.2` + `kotlin-test-junit`；网络用 MockWebServer；协程用 `kotlinx-coroutines-test`。Compose/仪器测试后续单独接入。

**后果**：`test` 任务零额外配置；JUnit5 专属特性不可用。

---

## ADR-023：下载先解析 JSON `file.downloadLink`

**状态**：接受（2026-11-22 实测）

**背景**：`/eapi/book/{id}/{hash}/file` 在 z-lib.gl 返回 `200 application/json`：`{"success":1,"file":{"downloadLink":"…","allowDownload":true,…}}`，而不是文档假设的 302。直接按文件流写入会把 477 字节 JSON 当成 EPUB 并标为完成。

**决策**：下载先请求 file 端点；若响应为 JSON，解析 `file.downloadLink`（兼容 `file` 字符串/对象、`downloadUrl`/`url`/`link`），再对链接发第二个 GET，携带 Range；仍走凭据剥离拦截器。`allowDownload=false`、无链接或响应不是 HTTPS 均失败。写入前做 EPUB/PDF/FB2 魔数校验，失败不标完成。

**后果**：适配两种镜像行为；JSON 错误页不再污染书架。签名链接只存在于内存，不落盘、不记日志。

---

## ADR-021：分享直接使用 SAF 文档 URI

**状态**：接受

**背景**：下载只写用户选择的 SAF 目录树（ADR-006）。`FileProvider` 只能暴露应用私有目录，把 SAF 文件复制到 cache 再分享会多一次 IO 和一份临时副本。

**决策**：`ACTION_SEND` 的 `EXTRA_STREAM` 直接使用 SAF `content://` URI，并加 `FLAG_GRANT_READ_URI_PERMISSION`；不引入 `FileProvider`。

**后果**：接收方可直接读取文档；无临时文件与清理逻辑。若后续需要分享应用内生成的非 SAF 文件（如改好的元数据缓存），再单独加 `FileProvider`。

---

## ADR-022：Room 列名使用 Kotlin camelCase

**状态**：接受

**背景**：`docs/ARCHITECTURE.md` 的 schema 用 snake_case 示例；Room 默认按属性名生成列名。

**决策**：v1 schema 直接用 camelCase 列名（`bytesWritten`、`localUri` 等），表名仍为 `books` / `download_queue`；查询与迁移以 `core/database/schemas/1.json` 为准。

**后果**：少一层 `@ColumnInfo` 噪声；文档中的 snake_case 仅作概念示意。

---

## ADR-024：热门作为首页，搜索使用独立路由

**状态**：接受；替代 ADR-009 中首个 tab 为搜索的决定。

**决策**：主导航为热门 / 书架 / 下载 / 我的。热门页展示 EAPI 热门与推荐，搜索从热门页的顶部按钮进入独立路由，不占底部 tab。热门、搜索结果和书架共用大封面网格。

**布局**：沿用 Miuix 示例的滚动布局；顶栏渐进模糊、图标按钮和底栏共享页面采样层，内容使用实测栏高作为滚动内边距。关闭玻璃效果时保留同样的页面背景和导航入口。

---

## ADR-025：三栏主导航、独立下载管理和分组书架

**状态**：接受；替代 ADR-024 的底部导航安排，保留热门首页与独立搜索。

**决策**：底部仅保留热门 / 书架 / 设置。首页顶栏由左到右提供搜索、下载管理两个操作。下载管理只展示任务、进度与记录；下载目录、账号、域名及外观集中到分组设置。

**书架**：已下载来自 Room；收藏和下载历史从已记载的 EAPI 分页读取，按分组保留滚动位置，支持刷新。收藏和历史本次只读，不把远端记录当成已下载文件，也不改变本地删除的语义。

---

## ADR-026：固定深浅色方案，默认跟随系统

**决策**：停止使用壁纸动态取色。浅色使用 `#F5F5F5` 页面、`#FFFFFF` 卡片和黑色主文字；深色使用 `#000000` 页面、`#202022` 卡片和白色主文字。设置通过 Miuix 弹出菜单选择跟随系统、浅色或深色；旧版只有取色开关时，升级后默认跟随系统。

---

## ADR-027：玻璃外观跟随 Miuix 示例的静态主题

**背景**：顶栏只放大按钮直径、不调整继承的玻璃形状，会使轮廓偏方。额外绘制实色按钮底和覆盖导航选中颜色也会改变示例的玻璃观感。

**决策**：保留 ADR-026 的页面、卡片和主文字颜色，其余以 Miuix 内置浅色/深色方案为基础。玻璃使用同版本示例的深浅材质样式及中等边缘高光。顶栏图标使用库的 44 dp 原生尺寸与同半径圆形，不绘制额外底色；列表滚入顶栏时按 Miuix 默认行为浮起玻璃材质。底栏保留原生高度与指示器，只收窄三项的布局宽度。

---

## ADR-028：玻璃是默认界面，不再提供开关

**状态**：接受；取代 ADR-008 和 ADR-024 中允许手动关闭玻璃的决定。

**决策**：支持玻璃的设备始终使用玻璃导航，不读取旧版 `glass_enabled`，也不提供设置开关。保留设备不支持材质或低内存时的自动降级，确保导航入口和安全区不变。

---

## ADR-029：玻璃弹层与书架顶栏共用设计系统

**状态**：接受；细化 ADR-025 的书架分组与 ADR-026 的外观菜单。

**决策**：书架分组使用 `GlassSegmentedTabRow`，置于 `GlassTopAppBar` 内，由列表滚动驱动材质，内容内边距包含分组栏的实测高度。搜索筛选使用 `GlassBottomSheet`，应用内的确认框复用 `GlassDialog`，外观模式使用原生展开／收起动画的 `GlassDropdownPreference`。弹层与导航使用一致的中等级 Bloom Stroke（`MiddleLight` / `MiddleDark`），不另增设置项。

**实现边界**：导航仍采样滚动内容；弹层单独采样含顶栏和底栏的完整页面，通过外层 `Scaffold` 展示，避免采到自身和遮罩。设备不支持玻璃时保留实色控件和原有交互。

