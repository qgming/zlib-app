# Zlib

一个面向 Android 的 Z-Library 客户端：登录、搜索、下载、书架、收藏/历史、元数据编辑。**非官方项目**，仅供自己和朋友使用，以开源方式维护。

> 本项目不是 Z-Library 官方客户端，不复刻其品牌或 Logo。Z-Library 属于影子图书馆，下载受版权保护的内容在部分司法辖区有法律风险；请自行判断并承担使用风险。项目不上架 Google Play，只通过 GitHub Releases 侧载。

## 状态

**v0.4 进行中（下载已可用）。** v0.2 已落地 EAPI 管线、域名体检/切换、三种登录、加密会话与配额；v0.3 接入搜索/详情/热门推荐/Coil 封面；v0.4 已接入 SAF 下载目录、Room 队列、前台下载 Service、暂停/取消/恢复、下载页、书架列表与系统分享。顶部/底部导航已切换为 Miuix 最新玻璃风格。

下载适配了镜像的两种行为：302 到 CDN，以及 200 JSON `file.downloadLink`；写入前校验 EPUB/PDF/FB2 魔数，Range 206 续传已在真机验证为字节一致。`/eapi/user/profile` 已修正为 GET；镜像缺该接口时会体检并切换一次域名。

## 功能范围（v1）

- 账号：邮箱密码登录、内嵌 WebView 登录抓 Cookie、粘贴/导入 Netscape Cookie；单账号
- 网络：内置镜像 allowlist、并发域名体检、失败自动切换、自定义镜像、系统 VPN/代理
- 搜索：关键词、语言、格式、年份、精确匹配、排序、分页
- 浏览：推荐、热门、收藏、下载历史、书单
- 下载：SAF 目录、顺序队列、HTTP Range 续传、暂停/取消/恢复、前台服务通知、配额提示
- 书架：已下载、收藏、历史、书单合并展示，搜索/排序，封面缓存
- 元数据：EPUB / FB2 / DOCX 的读取、在线查源（Google Books / OpenLibrary / Douban）、编辑、重命名、嵌封面
- 分享：通过系统 `ACTION_SEND` 把书分享到阅读器 App（含微信读书），**不做内置阅读器**
- 设置：域名、下载目录、主题（Miuix / Monet / 玻璃效果开关）、备份恢复
- 隐私：零遥测、零统计

### 明确不做

- Kindle 推送、微信读书导入 API（只走系统分享）
- 内置 EPUB/PDF 阅读器
- 多书源（Anna's Archive、Gutenberg 等）
- MCP server、TUI、终端封面
- 静默自更新（只做“检查 Release 并跳转浏览器”）
- MOBI / PDF / Goodreads（v1.1 再评估）

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
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 模块图、数据流、状态机
- [docs/EAPI.md](docs/EAPI.md) — EAPI 端点、错误、域名与凭据规则
- [docs/DECISIONS.md](docs/DECISIONS.md) — ADR 决策记录
- [docs/ROADMAP.md](docs/ROADMAP.md) — 版本计划
- [docs/TESTING.md](docs/TESTING.md) — 测试与 CI
- [CONTRIBUTING.md](CONTRIBUTING.md) — 贡献流程

## English summary

Zlib is an unofficial Android client for Z-Library, built with Kotlin, Jetpack Compose, and Miuix. It supports login, mirror health checks, search, downloads over SAF, a local library, favorites/history, and EPUB/FB2/DOCX metadata editing. There is no built-in reader; books are shared to reader apps via `ACTION_SEND`. The project is MIT-licensed, has no telemetry, and is distributed as sideloaded APKs through GitHub Releases. It is not affiliated with Z-Library.

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party attribution.

