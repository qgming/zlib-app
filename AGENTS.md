# AGENTS.md

本文件是给 AI coding agent 与协作者的**硬约束**。开始任何改动前，先读：

1. `AGENTS.md`（本文件）
2. `docs/ARCHITECTURE.md`
3. `docs/EAPI.md`
4. `docs/DECISIONS.md`

如果代码与文档冲突，以 `docs/DECISIONS.md` 为准；要改决策，先加一条 ADR，再改代码。

---

## 1. 项目是什么

`芝士读书`（项目名 `Zlib`）是一个面向 Android、供自己和朋友使用的 **Z-Library 非官方客户端**。它用 Kotlin 重写 `heartleo/zlib` CLI 的 EAPI 行为，不是把 Go 程序打包进 APK，也不调用任何“官方 API”。中文显示名以 ADR-053 为准；包名、签名及内部数据标识保持不变。

- 平台：Android，`minSdk 33`，`targetSdk 37`，`compileSdk 37.2`
- UI：Kotlin + Jetpack Compose + Miuix `0.9.5-beta03`
- 数据：Z-Library 移动端 EAPI（非公开契约，随时可能变）
- 分发：`https://github.com/qgming/zlib-app` 的 GitHub Releases 侧载 APK；不上 Google Play
- 许可证：MIT + `NOTICE` 归属声明
- 隐私：零遥测、零统计、零广告

**范围**：多账号登录/手动切换、域名体检/切换、搜索、详情、下载队列/续传、Z-Library 云端下载记录与收藏、云端下载记录删除、系统分享。书单页面与模块已按 ADR-051 移除。
**不做**：应用备份/恢复、独立本地书库/书单及其导入导出、本地电子书元数据编辑及第三方查源、内置阅读器、Kindle、微信读书导入、多书源、MCP、TUI、静默自更新。元数据范围见 ADR-030；云端图书馆与取消备份见 ADR-031；书架按 ADR-049/051 为“本机 / 收藏”，本机仅映射设置的下载目录实际文件，不维护独立本地书库；云端下载记录从下载管理右上角进入。登录 Cookie 导入继续保留；详情页仍展示 EAPI 图书信息。

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

图书服务只用 `application/json` 的 `/eapi/...` 端点。不要新增网页抓取、DOM 解析或 Cloudflare 绕过逻辑。`docs/EAPI.md` 是唯一图书端点清单。ADR-055/056 允许冷启动及用户手动通过独立无凭据客户端读取 GitHub Releases JSON，并按明确操作下载本项目 APK；不得复用 EAPI 会话／Cookie 或将 GitHub 加入图书域名 allowlist。

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
- 登录只保留邮箱密码和 Cookie 导入；WebView 登录及 CookieManager 代码已按 ADR-052 移除。

### 3.4 会话

- 多账号保存加密会话，只激活一个当前账号，密码不落盘。退出指定账号清理其内存/加密会话，不清除其它账号；切换前暂停并等待当前写入停止。旧版单会话不兼容、不迁移，见 ADR-051/052。
- 额度耗尽自动切换默认关闭；显式开启后，仅明确额度错误且 profile 确认已耗尽时检查其它账号，只选择有效且剩余额度已知大于 0 的会话，每任务每账号至多一次。认证、网络及风控错误不轮换，无可用账号时暂停剩余队列；不承诺平台不会限制多账号。
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
- 导航用 `miuix-nav`；底部 3 tab：阅读 / 书架 / 设置；阅读页仍显示热门与推荐，双击底部阅读入口回到顶部。书架顶部“本机 / 收藏”，首次默认本机，已移除书单。搜索、下载管理从阅读页顶栏进入，账号和域名体检从设置进入。下载管理“下载任务 / 已完成”卡片右侧用至少 48dp 触摸区域的纯图标操作，云端下载记录从右上角云图标进入；本机文件打开／分享／真实删除在书架本机，长按菜单动作间距 12dp，见 ADR-049/051。
- 主 tab 切换使用 150ms 整页横向滑动，标题／顶栏分组与内容共同移动，底部导航固定，各页滚动状态独立，见 ADR-041。设置按“账号 → 外观 → 下载目录 → 域名管理 → 关于”排列（ADR-057），所有选项无左侧图标及预留空间，卡片内部无行间分割线。设置分组和下载任务卡片使用 24dp 圆角；下载卡片无封面，右侧水平排列暂停／继续／重试及 X，X 仅移除任务记录，活动任务先停止写入，已完成文件保留，见 ADR-050。图书详情例外使用非导出的 `BookDetailActivity` 接入 ITGSA SharedView Kit `1.0.0.5`；SDK 仅 app 依赖，默认尝试 ROM 能力、不提供设置项，不支持时普通 Activity 转场，见 ADR-040/041 与 `docs/ANIMATIONS.md`。
- 图书网格仅“2:3 封面 + 单行书名”，超长末尾省略，无整项卡片。设置支持自动/2/3/4/5 列；默认“自动”，小于 360dp 为 2 列、360–599dp 为 3 列、600–839dp 为 4 列、840dp 及以上为 5 列。缺图或加载失败使用书名和作者的经典排版书封，见 ADR-034/036。
- 外观默认跟随系统，也可手动选浅色或深色；不根据壁纸取色。支持的设备始终开启玻璃效果，不提供设置开关；不支持材质或低内存时自动降级。
- 登录与添加账号共用表单，Cookie 导入与登录按钮同宽；账号卡片仅用圆环展示额度，不显示底部“今日已用”文案。域名页最顶部为自动切换开关，右上角依次为全部体检和添加域名；添加及登录页更换域名使用玻璃底部面板，域名各自为 24dp 卡片，右侧切换／单独体检图标至少 48dp。关闭自动切换后锁定当前选择，手动切换更新锁定目标；单独体检不换域名。外观密度选项文案为“网络格数”，见 ADR-052。
- 设置移除组标题，各组与关于／账号／域名／下载管理卡片统一 24dp 圆角和 12dp 间距；设置／关于入口保留 Miuix 原生 56dp 最小行高，随内容自然伸展。下载目录与域名管理拆为独立卡片，入口和页面标题统一“域名管理”，见 ADR-057。设置末尾“关于”无副标题；关于页为居中透明图标、中文名称“芝士读书”和实际构建版本，下方提供检查更新、真实 GitHub Release 日志、协议与隐私、开放源代码许可，末尾“关于作者”交系统打开 `https://github.com/qgming`，无反馈及项目与开发者页面。每次冷启动检查一次 `qgming/zlib-app` 最新正式 Release，仅新版自动弹同一玻璃底部面板：上方日志、下方取消／更新。点击更新才通过独立无凭据客户端下载匹配 APK，显示进度，校验摘要／包名／签名／递增版本后保存到用户 SAF 下载目录，再由用户点击安装交系统确认；可按需引导未知来源授权，不静默安装、不后台轮询，见 ADR-053/055/056。
- 图标以用户提供的 `app/artwork/zhishi.svg` 为准，Android 矢量保留渐变与镂空；桌面为奶油色背景自适应图标，关于页仅显示透明图形、不加底板或裁切，见 ADR-054。

### 6.3 DI 与持久化

- 使用 Hilt + Room + KSP；工具链 spike 已通过并在当前代码落地，不再保留手写 `AppContainer` 回退计划，不混用第二套 DI。
- Z-Library 服务端是“我的图书馆”（云端已下载、收藏、书单）的数据真相；Room 保存下载队列、续传进度及已知文件元信息。本机文件列表以 SAF 当前下载目录及子目录扫描为准，按 URI 关联 Room 元信息；可见期间监听变化并每 3 秒核对，离开停止，外部删除后移除，拒绝旧目录选择操作。云端数据如需缓存，必须按账号隔离并可重新拉取，不建立独立本地书单或收藏标记；DataStore 存设置与会话；Keystore 存凭据。
- 迁移必须写 `Migration` + 测试；禁止 `fallbackToDestructiveMigration` 进 release。
- 所有 IO 在 `Dispatchers.IO`；下载在 Service 的独立 scope；UI 只收集 Flow。

### 6.4 状态机

- 下载：`QUEUED → RESOLVING → DOWNLOADING → COMPLETED | FAILED | PAUSED | CANCELLED`。
- 域名：`UNKNOWN → PROBING → HEALTHY | CHALLENGED | DIAMWALL | REDIRECT_LOOP | HTTP_ERROR | NETWORK_ERROR | INVALID`。
- 登录：`LOGGED_OUT → AUTHENTICATING → LOGGED_IN | AUTH_FAILED`；会话过期回到 `LOGGED_OUT` 并保留提示。

---

## 7. 范围与分期

v1 交付：

- A 核心：搜索/筛选/分页、详情、下载队列/续传、云端图书馆、收藏/下载记录、推荐/热门、配额、域名体检、本机文件分享/删除
- D 云端下载记录删除、多账号管理；书单已按 ADR-051 移除，不以本地书单代替

详情页下载按钮右侧支持云端收藏/取消收藏，显示“添加收藏 / 已收藏”，状态与写入结果以服务端为准，见 ADR-033/042。右上角“书籍信息”玻璃底部面板只展示出版社、出版年份、语言、版次、页数、ISBN 和分类；文件格式／大小只放在下载格式选项中，见 ADR-048。简介本地处理 EAPI 字段中的分段、换行和常见富文本。用户于 2026-11-22 确认收藏功能及下载管理内云端下载记录真机验证成功；账号切换、多设备和异常路径仍需独立验收。

详情中作者／译者姓名独立为蓝色可点击文本，国籍、角色与分隔符普通显示；点击通过 app 回调进入独立搜索状态并自动搜索单个姓名，见 ADR-043。用户于 2026-11-22 确认测试正常。详情书名字重 600，姓名链接字重 500，字号不变，见 ADR-044。

详情“下载图书”先通过 EAPI `/formats` 打开玻璃格式菜单，用户点选后以所选文件独立 `id:hash` 入队；空数组/错误保留已确认的当前格式，禁止自动下载或本地转换，见 ADR-045/048。成功直接列出选项，不显示“未返回其他格式”小字；请求失败仍保留错误和重试。云端下载记录长按更多菜单提供“删除记录”，二次确认后仅删除云端记录，成功再刷新分页；该写入 GET 禁止自动重试/重定向，不影响本机文件、任务和收藏，见 ADR-046。用户确认删除菜单安全区与短提示测试正常；真实多格式非空结果、账号切换及异常路径仍需专项验收。

玻璃底部面板由设计系统统一预留导航栏／横向刘海安全区和 16dp 底部间距，feature 不重复添加（ADR-047）；云端下载记录删除成功与“已是最新版本”统一使用设计系统 Toast 短提示，不插入顶部提示卡片，见 ADR-064。提示随页面退出和会话变化清除。

GitHub 更新检查、真实发布日志与显式 APK 下载／安装按 ADR-055/056 提前纳入当前版本；不做后台轮询或静默安装。云端书单已移除，重新纳入须另立 ADR。
应用备份/恢复及本地书库/书单导入导出已按 ADR-031 取消。
本地元数据编辑及其格式/查源扩展已取消，不自动延期；重新纳入范围需另立 ADR（见 ADR-030）。
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
core/data/                 Repository、下载队列、会话、设置
feature/search/            搜索 + 筛选 + 分页
feature/detail/            详情 + 下载/分享入口
feature/downloads/         队列／已完成 UI + 前台服务控制 + 云端下载记录图标入口
feature/library/           书架：本机文件打开/分享/删除 + 收藏；独立云端下载记录页
feature/account/           登录（密码/Cookie）+ 多账号卡片/切换/退出 + 配额
feature/settings/          域名、目录、主题、关于、真实发布日志与更新面板
```

---

## 11. Agent 工作流

1. 读 §1–§7 与相关文档，确认改动属于 v1 范围。
2. 先写/改 ADR（若涉及决策），再改代码。
3. 小步提交；每个 commit 只做一件事。
4. 本地跑 `test lint assembleDebug`；发布另按 `docs/RELEASING.md` 构建并校验全部 ABI APK。启用 CI 后必须保持通过。
5. 不猜 API：字段以 `docs/EAPI.md` 和真实响应为准；失败时先加日志脱敏，再定位。
6. 不绕过安全红线；不在 feature 里直接发请求；不把 Miuix 泄漏到 core:data。
7. 文档与代码同 commit 更新。

---

## 12. 已知风险

- Miuix `0.9.5-beta03` 是实验版本，API 可能在下个 beta 变；升级前先看 release notes。
- Compose `1.13.0-rc01` 是 RC；不要同时引入要求稳定 Compose 的第三方库。
- Hilt/KSP 已通过当前工具链构建；后续升级仍须独立验证，不混用第二套 DI。
- 阿里云镜像可能缺 beta03；构建失败先查仓库解析，不要改版本碰运气。
- Z-Library 域名/风控会变；任何“永久可用”的假设都是错的。


正式发布按 ADR-058 使用四种 ABI 与 universal APK，版本与签名规则见 `docs/RELEASING.md`。

当前正式版本为 1.1.0（versionCode=3），已于 2026-11-23 发布为 v1.1.0；手动检查更新已是最新版仅使用 Toast，自动检查保持静默，见 ADR-064。

2026-11-23 后续行为以 ADR-060–064 为准：首次未配置目录先完成 SAF 目录选择，再进入登录；已有会话的 profile 后台刷新不阻塞主界面。下载 `.part` 按完整引用隔离、校验续传区间，目录切换暂停并等待所有旧写入停止。搜索支持动态语言/格式、已核实的八种排序、条件恢复和默认记录的本机最近搜索；长按删除单条，纯图标清空需确认，不显示记录开关或底部引导。筛选语言直接按API显示，语言/格式右侧多选、排序右侧单选，出版年份最后。搜索/收藏/云端下载记录接近底部自动分页，出错后手动重试。详情底部相似图书与头部共用纵向网格，端点无分页时只展示一次完整有限列表。收藏仅短时会话内复用；本机仍每3秒核对，测量不能记录文件名或URI。
