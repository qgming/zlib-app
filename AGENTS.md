# AGENTS.md

本文件是给 AI coding agent 与协作者的**硬约束**。开始任何改动前，先读：

1. `AGENTS.md`（本文件）
2. `docs/ARCHITECTURE.md`
3. `docs/EAPI.md`
4. `docs/DECISIONS.md`

如果代码与文档冲突，以 `docs/DECISIONS.md` 为准；要改决策，先加一条 ADR，再改代码。

---

## 1. 项目是什么

`Zlib` 是一个面向 Android、供自己和朋友使用的 **Z-Library 非官方客户端**。它用 Kotlin 重写 `heartleo/zlib` CLI 的 EAPI 行为，不是把 Go 程序打包进 APK，也不调用任何“官方 API”。

- 平台：Android，`minSdk 33`，`targetSdk 37`，`compileSdk 37.2`
- UI：Kotlin + Jetpack Compose + Miuix `0.9.5-beta03`
- 数据：Z-Library 移动端 EAPI（非公开契约，随时可能变）
- 分发：GitHub Releases 侧载 APK；不上 Google Play
- 许可证：MIT + `NOTICE` 归属声明
- 隐私：零遥测、零统计、零广告

**范围**：登录、域名体检/切换、搜索、详情、下载队列/续传、书架、收藏/历史、书单、元数据编辑（EPUB/FB2/DOCX）、系统分享。
**不做**：内置阅读器、Kindle、微信读书导入、多书源、MCP、TUI、静默自更新。

---

## 2. 不可协商的工具链

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Gradle | 9.9.2 |
| AGP | 9.5.2 |
| Kotlin / Compose compiler | 2.4.21 |
| Compose（Miuix 传递） | 1.13.0-rc01 |
| Miuix | 0.9.5-beta03 |
| compileSdk | 37.2 |
| build-tools | 37.0.0 |
| targetSdk | 37 |
| minSdk | 33 |
| jvmTarget | 21 |

规则：

- **不要**擅自升级/降级上述版本。Miuix beta 的 API 会变；升级必须单独提交，并更新 `gradle/libs.versions.toml` 与本文件。
- `gradle.properties` 初始必须包含：
  ```properties
  android.builtInKotlin=false
  android.newDsl=false
  org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8
  ```
  AGP 9 的 new DSL 与 `org.jetbrains.kotlin.android` 目前不兼容；`newDsl=false` 不是可选项。迁移到 AGP 内置 Kotlin 之前不要删除。
- `compileSdk` 必须写成 `compileSdk = 37` + `compileSdkMinor = 2`（经典 DSL）；只写 37 会停在 37.0，Miuix AAR 会拒绝。
- `miuix-blur` 的 manifest 要求 **minSdk 33**；这是本项目 minSdk 不是 31 的原因。
- 应用 `org.jetbrains.kotlin.android` 时**必须**关闭 AGP 内置 Kotlin；否则 KGP/KSP 会被拒绝。
- Java/Kotlin 目标均为 **21**（`miuix-nav` 含 JVM 21 字节码）。
- Miuix 依赖用**根坐标**（`top.yukonga.miuix.kmp:miuix-ui`），**不要**同时加 `miuix-ui-android`，否则 duplicate class。
- 不要混用 `org.jetbrains.compose.*` 与 `androidx.compose.*` 的不同版本；Compose 版本由 Miuix 的 POM 决定。
- `miuix-window` 是 Desktop JVM，**禁止**加入 Android 依赖。
- `miuix-navigation3-*` v1 不使用；导航统一用 `miuix-nav`。

### 已知构建陷阱

- 本机 `C:\Users\31645\.gradle\init.gradle` 会把 Maven Central 重定向到阿里云。Miuix beta03 是新发布，阿里云可能未同步；构建时用 `repo.maven.apache.org` 或 `exclusiveContent` 绕过，并在 `settings.gradle.kts` 里写明原因。
- `settings.gradle.kts` 显式加入了阿里云 `public` / `gradle-plugin`（国内加速），随后是 Google 与 Maven Central 回退；Miuix beta03 实际由回退仓库解析。
- `gradle-wrapper.properties` 目前用腾讯镜像下载 Gradle 9.9.2；对外发布前可切回 `services.gradle.org`，或保留并在 README 说明。
- 本机 SDK 默认只有 `platforms/android-37.0` 与 build-tools 35/36。先安装（新版 CLI 一次一个包）：
  ```powershell
  D:\Android\Sdk\cmdline-tools\latest\bin\android.exe sdk install "platforms;android-37.2"
  D:\Android\Sdk\cmdline-tools\latest\bin\android.exe sdk install "build-tools;37.0.0"
  ```
- 构建脚本用 `compileSdk = 37` + `compileSdkMinor = 2`；`android-37.0` 不能满足 Miuix 的 AAR 元数据要求。

---

## 3. 网络与凭据（安全红线）

### 3.1 只允许 EAPI，不抓 HTML

只用 `application/json` 的 `/eapi/...` 端点。不要新增网页抓取、DOM 解析或 Cloudflare 绕过逻辑。`docs/EAPI.md` 是唯一端点清单。

### 3.2 域名 allowlist

只允许 HTTPS，且 host 属于以下后缀（含子域）或用户显式添加的镜像：

```
1lib.sk, article.sk, articles.sk, z-lib.by, z-lib.fm, z-lib.gd, z-lib.gl,
z-lib.sk, z-library.ec, z-library.im, z-library.qa, z-library.sk,
z-library.website, zlib.bz
```

- 禁止 `http://`、端口、路径、查询串、userinfo、IP、单标签主机。
- 用户镜像只信任精确 host，不扩展到子域。
- 默认首次启动探测 8 个域名；超时 10s；**不做后台常驻探测**。

### 3.3 凭据

- 只持久化 `remix_userid` / `remix_userkey`（以及必要的 Cookie），用 Android Keystore 加密；**密码绝不落盘**。
- EAPI 请求同时带 Cookie 与 `remix-userid` / `remix-userkey` 头。
- **绝不**把凭据发给 allowlist 之外的 host。下载 302 到 CDN 时，必须剥离 `Cookie`、`remix-userid`、`remix-userkey`；跨 host 重定向同样剥离。
- 日志、崩溃信息、Analytics 中不得出现 Cookie、密码、remix key、完整下载 URL 的签名参数。禁止接入任何统计 SDK。
- WebView 登录只读取 `CookieManager` 中目标域的 `remix_userid/remix_userkey`；不要注入 JS 读取密码。

### 3.4 会话

- 单账号。退出登录必须清空内存 Cookie、加密存储、WebView Cookie。
- 400 `Invalid credentials` / `Please login` 视为会话过期：当前域自动重试一次，再提示重新登录。
- 登录端点对部分账号会返回 `Authorization failed`；必须保留 Cookie 导入通道。

---

## 4. EAPI 契约

完整字段见 `docs/EAPI.md`。这里只列不可破坏的行为：

- 所有 POST 用 `application/x-www-form-urlencoded`；`Accept: application/json`；固定桌面 Chrome UA。
- 列表接口的 `limit` 用 50；搜索分页从 1 开始。
- 响应统一是 `{ "success": 0|1, "error": "...", ... }`；**先解析 envelope，再判断 HTTP 状态**，否则会丢掉服务端错误文本。
- 下载：`GET /eapi/book/{id}/{hash}/file` → 302 到 CDN；必须跟随，但按 §3.3 剥离凭据；文件名优先 `Content-Disposition`，其次 URL 末段。
- 图书引用统一 `id:hash`；只有数字 ID 的历史删除用 `/eapi/user/book/{id}/delete`。
- ISBN 规范化：去 `-`/空格、转大写；ISBN-10 校验含 `X`；无效值置空；`identifier` 可能包含多个值或 ASIN。
- 全文本搜索 EAPI 不支持；不要假装支持。

---

## 5. 下载与存储

- 只写用户通过 SAF 选择的目录树；持久化 URI 权限。`.part` 与最终文件同目录，完成后原子 rename；Provider 不支持 rename 时 copy+delete。
- 队列顺序执行，并发固定 **1**；暂停/取消/恢复必须跨进程存活（Room 持久化）。
- 前台 `Service`（`dataSync`）+ 通知；Android 13+ 处理 `POST_NOTIFICATIONS`，Android 14+ 声明前台服务类型。
- 续传：`Range: bytes=<written>-`；服务端返回 206 才继续，200 则重新开始；不支持 Range 时清空 `.part` 重下。
- 配额：从 `/eapi/user/profile` 读 `daily_amount` / `daily_allowed` / `daily_remaining`；配额错误不自动重试，显示重置提示。
- 重试：仅网络错误、502/503/504，指数退避（1s/2s/4s），最多 3 次；其它 4xx 不重试。
- 下载完成后写入 Room，并触发媒体扫描/分享 URI（`FileProvider`）。不要用 MediaStore 双写。

---

## 6. 架构规则

### 6.1 模块图

```
:app
:core:common        :core:model        :core:designsystem
:core:network       :core:database     :core:data
:feature:search     :feature:detail    :feature:downloads
:feature:library    :feature:account   :feature:settings
:feature:metadata   :feature:booklists
```

允许的依赖方向：

- `:app` → 所有 `:feature:*` + `:core:designsystem`
- `:feature:*` → `:core:data` / `:core:designsystem` / `:core:model` / `:core:common`
- `:core:data` → `:core:network` / `:core:database` / `:core:model`
- `:core:network` / `:core:database` → `:core:model` / `:core:common`
- `:core:designsystem` → `:core:model` / `:core:common`
- **禁止** feature 之间互相依赖；**禁止** core 反向依赖 feature/app；**禁止**循环依赖。

### 6.2 UI

- 所有 Miuix 依赖与主题封装在 `:core:designsystem`；feature 只通过 `ZlibTheme`、设计系统组件使用，不直接 import Miuix 的内部实现。
- MVVM + `StateFlow`；`UiState` 不可变；Composable 不做业务逻辑与网络请求。
- 每个 feature 一个 `ViewModel`（必要时拆分）；一次性事件用 `Channel`/`SharedFlow`，不要塞进 `UiState`。
- 导航用 `miuix-nav`；底部 3 tab：热门 / 书架 / 设置；搜索、下载管理从热门页顶栏进入，账号和域名体检从设置进入。
- 外观默认跟随系统，也可手动选浅色或深色；不根据壁纸取色。支持的设备始终开启玻璃效果，不提供设置开关；不支持材质或低内存时自动降级。

### 6.3 DI 与持久化

- 目标：Hilt + Room + KSP。第一天先做 **1 天 spike** 验证 AGP 9.5.2/Kotlin 2.4.21/KSP 2.3.12；失败则回退手写 `AppContainer`（构造注入），**不要混用**。
- Room 是书架、收藏、历史、书单、下载队列的唯一本地真相；DataStore 存设置与会话；Keystore 存凭据。
- 迁移必须写 `Migration` + 测试；禁止 `fallbackToDestructiveMigration` 进 release。
- 所有 IO 在 `Dispatchers.IO`；下载在 Service 的独立 scope；UI 只收集 Flow。

### 6.4 状态机

- 下载：`QUEUED → RESOLVING → DOWNLOADING → COMPLETED | FAILED | PAUSED | CANCELLED`。
- 域名：`UNKNOWN → PROBING → HEALTHY | CHALLENGED | DIAMWALL | REDIRECT_LOOP | HTTP_ERROR | NETWORK_ERROR | INVALID`。
- 登录：`LOGGED_OUT → AUTHENTICATING → LOGGED_IN | AUTH_FAILED`；会话过期回到 `LOGGED_OUT` 并保留提示。

---

## 7. 范围与分期

v1 交付：

- A 核心：搜索/筛选/分页、详情、下载队列/续传、书架、收藏/历史、推荐/热门、配额、域名体检
- B 元数据：EPUB / FB2 / DOCX + Google Books / OpenLibrary / Douban；重命名、嵌封面
- D 书单 / 历史删除
- E 只保留 JSON 备份/恢复；更新检查放 v1.1

v1.1：MOBI / PDF / Goodreads、更新检查、更多导入导出。
v2+：不做内置阅读器；如要做，另立 ADR 和独立模块。

---

## 8. 签名与仓库卫生

- 本地签名复用 `D:\dev\aiok\android\app\qgming2026.jks`，alias `qgming`。在项目根目录用**不提交**的 `key.properties` 指向它。
- **禁止**提交：`key.properties`、`local.properties`、`*.jks`、`*.keystore`、任何密码/Token、`build/`、`.gradle/`。
- CI 用 GitHub Secrets 注入 base64 keystore 与密码；日志中不得打印 secret。
- 更换签名会导致朋友无法覆盖安装；不要随意换 key。
- 提交前跑 `git diff --cached` 检查是否混入 secrets；`.gitignore` 不是保险。

---

## 9. 构建、测试、完成定义

```powershell
# 调试构建
.\gradlew.bat :app:assembleDebug

# 单测 + lint
.\gradlew.bat test lint

# 指定模块
.\gradlew.bat :core:network:testDebugUnitTest
```

完成定义（Definition of Done）：

1. 代码可编译，`assembleDebug` 通过。
2. 新增/修改逻辑有单测；网络映射用 MockWebServer；Room 变更带 migration 测试。
3. `lint` 无新增 error；无 secrets、无调试日志泄漏。
4. 更新 `docs/DECISIONS.md`（若改变决策）与相关文档。
5. 不破坏 §3 的安全红线与 §6 的依赖方向。

---

## 10. 代码地图

```
app/                       Application、MainActivity、导航壳、DI 入口
core/common/               结果类型、错误模型、dispatcher、扩展
core/model/                Book、Account、DownloadItem、Domain、UiState 共享模型
core/designsystem/         Miuix 主题、颜色、组件封装（唯一允许直接依赖 Miuix 的地方）
core/network/              OkHttp、EapiClient、Cookie/重定向策略、域名探测
core/database/             Room entities/DAO/DB、迁移
core/data/                 Repository、下载队列、会话、设置、元数据
feature/search/            搜索 + 筛选 + 分页
feature/detail/            详情 + 下载/分享入口
feature/downloads/         队列 UI + 前台服务控制
feature/library/           书架 + 收藏 + 历史
feature/account/           登录（密码/WebView/Cookie）+ 配额
feature/settings/          域名、目录、主题、备份
feature/metadata/          EPUB/FB2/DOCX 读取、查源、编辑、重命名
feature/booklists/         书单
```

---

## 11. Agent 工作流

1. 读 §1–§7 与相关文档，确认改动属于 v1 范围。
2. 先写/改 ADR（若涉及决策），再改代码。
3. 小步提交；每个 commit 只做一件事。
4. 本地跑 `test lint assembleDebug`；CI 必须绿。
5. 不猜 API：字段以 `docs/EAPI.md` 和真实响应为准；失败时先加日志脱敏，再定位。
6. 不绕过安全红线；不在 feature 里直接发请求；不把 Miuix 泄漏到 core:data。
7. 文档与代码同 commit 更新。

---

## 12. 已知风险

- Miuix `0.9.5-beta03` 是实验版本，API 可能在下个 beta 变；升级前先看 release notes。
- Compose `1.13.0-rc01` 是 RC；不要同时引入要求稳定 Compose 的第三方库。
- Hilt 在 AGP 9.5.2 + Kotlin 2.4.21 下未在本机验证；spike 失败立即回退，不硬扛。
- 阿里云镜像可能缺 beta03；构建失败先查仓库解析，不要改版本碰运气。
- Z-Library 域名/风控会变；任何“永久可用”的假设都是错的。

