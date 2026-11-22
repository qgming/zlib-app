# Zlib

一个面向 Android 的 Z-Library 客户端：登录、搜索、下载，以及账号的“我的图书馆”（云端已下载、收藏、书单）。**非官方项目**，仅供自己和朋友使用，以开源方式维护。

> 本项目不是 Z-Library 官方客户端，不复刻其品牌或 Logo。Z-Library 属于影子图书馆，下载受版权保护的内容在部分司法辖区有法律风险；请自行判断并承担使用风险。项目不上架 Google Play，只通过 GitHub Releases 侧载。

## 状态

**v0.4 进行中（下载已可用）。** v0.2 已落地 EAPI 管线、域名体检/切换、三种登录、加密会话与配额；v0.3 接入搜索/详情/热门推荐/Coil 封面；v0.4 已接入 SAF 下载目录、Room 队列、前台下载 Service、暂停/取消/恢复、下载页、书架列表与系统分享。顶部/底部导航已切换为 Miuix 最新玻璃风格。

下载适配了镜像的两种行为：302 到 CDN，以及 200 JSON `file.downloadLink`；写入前校验 EPUB/PDF/FB2 魔数，Range 206 续传已在真机验证为字节一致。`/eapi/user/profile` 已修正为 GET；镜像缺该接口时会体检并切换一次域名。

范围调整（ADR-031/032）：已取消应用备份和独立本地书库计划，“我的图书馆”以 Z-Library 云端为准。书架顶部仅“收藏 / 书单”，默认收藏；收藏的读取/分页/刷新已接入，书单目前明确提示“云端书单尚未接入”。原“历史”实际是云端下载记录，现从下载管理进入；本机文件分享/删除也已迁入下载管理。已对缺失/已知无效封面增加详情 EAPI 补取，并隔离不同会话的列表与封面状态。2026-11-22 用户确认收藏功能与云端下载记录真机验证成功；封面补取边界、账号切换和多设备一致性仍待专项复验。

## 功能范围（v1）

- 账号：邮箱密码登录、内嵌 WebView 登录抓 Cookie、粘贴/导入 Netscape Cookie；单账号
- 网络：内置镜像 allowlist、并发域名体检、失败自动切换、自定义镜像、系统 VPN/代理
- 浏览：阅读首页包含热门和为你推荐；底部使用 Glass 系列的 Notes 图标，与书架区分。切页时标题和内容整页左右滑动，底栏固定；双击“阅读”回到顶部。搜索结果、云端收藏和下载记录共用封面网格
- 详情转场：接入 ITGSA SharedView Kit 1.0.0.5，默认使用设备 ROM 声明的能力，不增加设置项；支持时从封面一镜到底展开为独立详情页，返回更新封面落点，不支持或快照失败时普通打开。设备验收与 SDK 来源见 [动画说明](docs/ANIMATIONS.md)
- 网格：统一 2:3 封面与下方单行书名，超长末尾省略，无整项卡片；在「设置 → 外观 → 图书网格列数」选择自动、2、3、4、5 列。默认“自动”，常见手机三列，窄分屏两列，平板按窗口宽度使用四列、五列；手动选择保留并立即应用
- 缺图书封：缺失或加载失败时，按实际书名和作者排成素色底、细框、居中衬线文字的经典书封，加载成功后显示真实封面
- 搜索：从阅读页右上角进入，使用 Miuix 搜索栏；搜索页右上角打开筛选面板，包含语言、格式、年份、精确匹配和排序
- 下载：从阅读页右上角打开下载管理，查看进度、暂停、取消、续传与本机任务记录；通过“本机文件”分享/删除文件，通过“云端下载记录”查看账号在各设备上的下载记录
- 书架：顶部仅“收藏 / 书单”，默认显示 Z-Library 云端收藏；云端书单尚未接入，需先验证 EAPI，不维护独立本地书单。云端下载记录不表示当前设备已有文件
- 详情：下载与收藏并排，收藏位于右侧，显示“添加收藏 / 已收藏”；操作区无外层卡片，出版和文件信息从右上角信息按钮打开玻璃底部面板。简介支持段落、换行、常见富文本与选择复制
- 作者：详情中作者、译者的姓名各自为蓝色链接，点击自动搜索单个姓名；国籍、角色说明和分隔符保持普通文字。书名字重 600，姓名字重 500
- 收藏：点击详情的“添加收藏 / 已收藏”加入或取消云端收藏，成功后刷新书架收藏列表。用户于 2026-11-22 确认收藏逻辑真机验证成功
- 本机文件：点击封面或书名进入详情，长按打开分享/删除操作面板，删除需要确认
- 分享：通过系统 `ACTION_SEND` 把书分享到阅读器 App（含微信读书），**不做内置阅读器**
- 设置：底部独立入口，按账号、下载与网络、外观分组管理；账号显示名称与邮箱，左侧图标统一常规粗细，以 26dp 为基准随标题字号缩放，左右各留 15dp；设置与下载管理顶部卡片均无内部行间分割线，玻璃效果在支持的设备上始终开启
- 隐私：零遥测、零统计

### 明确不做

- 应用备份/恢复、独立本地书库/书单及其导入导出（ADR-031）；登录 Cookie 导入继续保留
- Kindle 推送、微信读书导入 API（只走系统分享）
- 内置 EPUB/PDF 阅读器
- 多书源（Anna's Archive、Gutenberg 等）
- MCP server、TUI、终端封面
- 静默自更新（只做“检查 Release 并跳转浏览器”）
- 本地电子书元数据编辑、第三方查源、嵌封面与编辑器重命名（计划已取消，见 ADR-030）；详情页继续展示 EAPI 返回的图书信息

## 技术栈

- Kotlin 2.4.21 / Jetpack Compose（Compose 1.13.0-rc01）
- Miuix 0.9.5-beta03（`miuix-ui`、`preference`、`icons`、`glass-icons`、`material`、`squircle`、`blur`、`glass`、`nav`；不含 desktop-only 的 `miuix-window`）
- AGP 9.5.2 / Gradle 9.9.2 / JDK 21
- Hilt + Room + KSP（Hilt 有 1 天 spike，失败则回退手写 `AppContainer`）
- OkHttp + kotlinx.serialization；前台 `dataSync` Service + Room 下载队列

## 构建

前置：

- Android 13+ 设备（`minSdk 33`；`miuix-blur` 要求 33）
- JDK 21
- Android SDK Platform **37.2**、Build-Tools **37.0.0**
- 把 SDK 路径写进 `local.properties`（`sdk.dir=D:/Android/Sdk`）

```powershell
# 安装缺失的 SDK 组件（新版 CLI 一次一个包）
D:\Android\Sdk\cmdline-tools\latest\bin\android.exe sdk install "platforms;android-37.2"
D:\Android\Sdk\cmdline-tools\latest\bin\android.exe sdk install "build-tools;37.0.0"

# 调试构建
.\gradlew.bat :app:assembleDebug

# 单测 + lint
.\gradlew.bat test lint
```

### 签名

本地签名复用 `D:\dev\aiok\android\app\qgming2026.jks`（alias `qgming`）。在项目根目录创建 **不提交** 的 `key.properties`：

```properties
storeFile=D:/dev/aiok/android/app/qgming2026.jks
storePassword=...
keyAlias=qgming
keyPassword=...
```

CI 使用 GitHub Secrets 注入 base64 的 keystore 和密码；仓库里永远不要出现 `*.jks`、`key.properties`、密码。签名一旦更换，朋友无法覆盖安装。

## 文档

- [AGENTS.md](AGENTS.md) — 给 AI/协作者的硬约束
- [GLOSSARY.md](GLOSSARY.md) — 云端图书馆、已下载、本机文件等术语
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 模块图、数据流、状态机
- [docs/EAPI.md](docs/EAPI.md) — EAPI 端点、错误、域名与凭据规则
- [docs/DECISIONS.md](docs/DECISIONS.md) — ADR 决策记录
- [docs/ANIMATIONS.md](docs/ANIMATIONS.md) — 主导航切页及 SharedView Kit 接入与验收
- [docs/ROADMAP.md](docs/ROADMAP.md) — 版本计划
- [docs/TESTING.md](docs/TESTING.md) — 测试与 CI
- [CONTRIBUTING.md](CONTRIBUTING.md) — 贡献流程

## English summary

Zlib is an unofficial Android client for Z-Library, built with Kotlin, Jetpack Compose, and Miuix. Its scope is login, mirror health checks, search, downloads over SAF, and the account's cloud library. The bookshelf has Favorites and Booklists tabs; Favorites is connected, while Booklists explicitly shows that access is not yet available. Download management provides separate local file actions and cloud download history. Invalid or missing covers can be recovered through book details for visible cards. App backup/restore and an independent local library are out of scope (ADR-031/032). Local e-book metadata editing has also been removed from the roadmap (ADR-030); book details continue to display information returned by the EAPI. There is no built-in reader; books are shared to reader apps via `ACTION_SEND`. The project is MIT-licensed, has no telemetry, and is distributed as sideloaded APKs through GitHub Releases. It is not affiliated with Z-Library.

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party attribution.

