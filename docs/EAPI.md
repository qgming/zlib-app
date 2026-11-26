# EAPI 参考

> **非公开契约。** Z-Library 的移动端 EAPI 没有官方文档或稳定性承诺。字段、鉴权和域名都可能变化。本文件记录的是 `heartleo/zlib` 在 2026-09 至 2026-11 实测到的行为，以及 `baroxyton/zlibrary-eapi-documentation` 的端点说明，仅作为实现依据。

## 1. 传输约定

- Base：`https://<允许的域名>`，例如 `https://z-lib.gd`。
- 只使用 HTTPS；域名必须通过 allowlist（`AGENTS.md` §3.2）。
- `Accept: application/json`
- `User-Agent`：桌面 Chrome（与 CLI 一致）
- `source: android`：每个请求都带（ADR-076）。2026-11-24 复测：缺少该头时 `/eapi/book/search` 即使带有效会话也返回 HTTP 400 `{"success":0,"error":"Access denied"}`，登录、profile、热门等端点不受影响；上游 `heartleo/zlib` 加该头后实测搜索恢复（v0.0.16）。本项目同日匿名 curl 在 `zlib.bz` / `z101.by` 加头仍被拒，说明该头可能不是唯一条件（还可能与网络、会话或 TLS 指纹有关），以用户有效会话真机验证为准。
- POST：`Content-Type: application/x-www-form-urlencoded`
- 鉴权：Cookie `remix_userid` / `remix_userkey`，同时发同名请求头：
  ```
  remix-userid: <id>
  remix-userkey: <key>
  ```
- 列表 `limit` 默认 50；分页从 1 开始。
- 书单 PAPI 不套用上述 EAPI 规则；独立契约见 [PAPI.md](PAPI.md)。
- 响应统一 envelope：
  ```json
  { "success": 1, "error": "", "books": [], "pagination": { "current": 1, "total_pages": 10 } }
  ```
  失败：
  ```json
  { "success": 0, "error": "Daily limit reached" }
  ```
  **先解析 envelope，再看 HTTP 状态**；非 2xx 也可能带可用错误文本。

## 2. 端点

### 2.1 登录

```
POST /eapi/user/login
email=<email>&password=<password>
```

成功：

```json
{
  "success": 1,
  "user": {
    "id": 1234567,
    "remix_userkey": "....",
    "remix_user_key": "...."
  }
}
```

- 取 `remix_userkey`，为空时取 `remix_user_key`。
- `id` 可能是数字或字符串，统一转字符串。
- 失败：`{"success":0,"error":"Incorrect email or password"}` 或 `Authorization failed`。
- **已知问题**：部分账号密码登录被拒，但用浏览器 Cookie 可以正常调用其它端点。因此客户端必须保留 Cookie 导入；内嵌 WebView 登录已按 ADR-052 移除。
- 密码表单仅发送至请求发起时已授权的标准 HTTPS 来源，不跟随重定向。Cookie 导入接受 Netscape `#HttpOnly_` 行和浏览器导出，按来源解析，仅保留所需 remix 凭据（ADR-102）。
- 登录成功后，后续请求带 Cookie + 头；不要把密码写入任何存储。

### 2.2 搜索

```
POST /eapi/book/search
message=<query>
page=1
limit=50
e=1                 # 可选，精确匹配
yearFrom=2000       # 可选
yearTo=2024         # 可选
languages[]=english # 可重复，小写
extensions[]=epub   # 可重复，小写
order=popular       # 可选
```

响应：`books[]` + `pagination.current` + `pagination.total_pages`。

2026-11-23 后续匿名复测：`z101.by` 与 `z-library.ec` 对中文／英文关键词的仅 `message`、无筛选分页、单个排序、单个语言及多个筛选共 20 次请求，均返回 HTTP 400、`{"success":0,"error":"Access denied"}`；同日另测直连／代理结果一致，热门仍可返回书目。此记录不等同于用户手机有效会话的复核，也不证明所有账号或域名永久不可搜索。ADR-069 仅将此明确的搜索拒绝映射为“Z-library 拒绝搜索”，不改变参数、不改用网页或其它接口、不自动重试；中文 `拒绝访问` 采用相同映射。域名信息体检成功不代表搜索端点可用。

`books[]` 的关键字段（服务端可能给字符串/数字/null）：

```json
{
  "id": 115066162,
  "hash": "e9f13b",
  "title": "...",
  "author": "...",
  "year": "2024",
  "publisher": "...",
  "identifier": "9787229042066,7229042062",
  "isbn": "...",
  "isbn10": "...",
  "isbn13": "...",
  "categories": ["..."],
  "category": "...",
  "edition": "...",
  "pages": "320",
  "language": "english",
  "cover": "https://...",
  "extension": "epub",
  "filesize": 1234567,
  "filesizeString": "1.2 MB",
  "href": "/book/115066162/e9f13b",
  "dl": "/eapi/book/115066162/e9f13b/file",
  "description": "...",
  "date": "...",
  "interestScore": "4.5",
  "qualityScore": "4.0"
}
```

- 没有 `hash` 的结果不可下载，直接丢弃并记录。
- `categories` 可能是字符串、数组、对象数组；取 `name/title/path`，扁平化为逗号分隔。
- `identifier` 可能包含多个 ISBN、多个版本、或 Amazon ASIN；逐个校验，不要整串当 ISBN。

### 2.3 详情

```
GET /eapi/book/{id}/{hash}
```

返回单本书的完整字段，结构与搜索项相同。详情页按 `id:hash` 缓存，避免重复请求。详情的格式与文件大小胶囊显示当前引用已返回的文件属性，不读取 `/formats`，也不代表最终选入下载队列的文件（ADR-085）。

#### 在线预览（ADR-088/089/091）

- 在线预览仍使用上述图书详情端点，不经 PAPI。进入预览或点击刷新时，向当前会话的镜像重新请求，使用 `Cache-Control: no-cache, no-store`，不使用详情页先前收到的链接。
- 检查响应来源及 `id:hash`，明确 `readOnlineAvailable=false`、无 `readOnlineUrl` 或地址不是合法 HTTPS 时不打开。保留 `readOnlineUrl` 原始主机、路径和签名，不拼接或替换域名；`href` 仅用于复制不含签名的公开图书链接。
- 预览 WebView 不注入 `remix_userid` / `remix_userkey` 或任何账号 Cookie，也不限制阅读器页面及其资源 CDN 域名；进入前清空 Cookie，退出再次清空。阅读器自行决定其页面、脚本、图片与内容 CDN 的请求，签名链接不得写入 URL 导航参数、剪贴板、持久存储或日志。`support@z-lib.bz` 是站方固定支持地址，不能作为当前镜像的判断依据。
- 2026-11-25 在 `z101.by` 真机验证：详情返回 `readOnlineUrl`，阅读器顶层页面为 `reader.z101.by`，正文资源来自 `reader-content-alp-01.cdn-zlib.sk`。此前的 403／`Loading reader…` 来自客户端按资源主机名拦截，而不是缺少会话；移除页面与资源白名单、停止注入 Cookie 后可正常阅读。公开链接在未登录状态下也可打开。

#### 同一本书的可用格式（ADR-045）

```
GET /eapi/book/{id}/{hash}/formats
```

2026-11-22 在 `zh.z-lib.by` 已验证此端点：未登录和用户授权的有效账号会话均返回 HTTP 200、`application/json`、`{"success":1,"books":[]}`。本次收藏、热门及搜索样本未获得非空列表，不能据此判断其它图书或域名都没有其它格式，也不能将非空菜单标记为真机验收完成。程序要求有效会话后调用，使用相同 Cookie/remix 头和安全管线；读取此列表不调用 `/file`。

用户反馈单格式后，2026-11-22 再核对真机近期访问的两个图书引用：在 `zh.z-lib.by` 未登录请求仍返回成功的空数组；真机当前配置镜像 `zh.friday101.ru` 对其中一项的未登录只读请求也返回相同结果。单本详情只含当前 `extension`，未发现被客户端漏读的格式数组。上述补查不等于验证了当前镜像的登录态非空列表，也不能据此宣称网站无其它格式；客户端通过日志条目数与 MockWebServer 回归区分服务端空数组和本地过滤问题。

非空条目的字段依据 `nassim-arifette/zlibrary-api` 的 `parse_format()` 和 `roxycon-dev/Ciallo-Reader` 的 `getFormats()`，并用 MockWebServer 回归，**下例是契约样例，不是真实账号响应**：

```json
{
  "success": 1,
  "books": [
    { "id": 8, "hash": "exampleHash", "extension": "pdf", "filesize": 1048576, "filesizeString": "1 MB" }
  ]
}
```

- 每条对应一个已有文件，必须用其独立 `id` / `hash` 调用下载端点；不拼接其它扩展名、不本地转换、不用普通搜索猜测其它格式。
- `extension` 转小写，只接受 1–20 位字母数字；引用校验与详情一致。`filesize` 可为数字或数字字符串，非负时保存为字节；`filesizeString` 仅用于显示，不按字节数字解析。
- 必须存在 `books` 数组。空数组是有效结果；字段缺失、错误类型或无效条目为解析错误，保留重试，不默默丢弃而声称已经展示所有选项。
- 按 `id:hash` 去重；不同引用的同格式文件保留。当前详情的文件格式可作为已确认选项补入，不能覆盖服务端条目的引用和大小。
- 下载按钮每次重新请求并打开玻璃格式菜单；不按详情当前扩展名筛选，不缓存空列表。即使只有当前格式也需点选后入队。失败保留当前格式和重试，成功直接列出可用项，不额外显示“未返回其他格式”小字（ADR-048）；菜单关闭和会话变化清除读取状态，重复点选只入队一次。调试日志仅记该图书引用及有效条目数，不含响应正文或鉴权信息。

### 2.4 下载

```
GET /eapi/book/{id}/{hash}/file
```

- 通常 302 到 CDN；跟随时使用独立无凭据通道，不携带也不恢复 `Cookie`、`remix-userid`、`remix-userkey`。JSON 签名链接同样不继承账号凭据。
- **部分镜像（2026-11 实测 z-lib.gl）返回 200 JSON 而不是 302**：
  ```json
  {"success":1,"file":{"downloadLink":"https://cdn.../book.epub?token=...","description":"","author":"","extension":"epub","allowDownload":true}}
  ```
  客户端必须先解析 `file.downloadLink`，再对 CDN 地址发 GET；`allowDownload=false` 或无链接视为失败。不要记录/持久化带签名的完整 URL。
- 文件名优先 `Content-Disposition: filename=...`，其次 URL 末段；清洗 `../`、控制字符、Windows 非法字符。
- 只有确认是同一资源且有强验证器时才发送 `Range: bytes=<written>-` 和 `If-Range`；206 还须验证匹配的验证器、范围及长度，200 从零写入。没有可信验证器的旧片段不能拼接（ADR-103）。
- 错误：4xx 不重试；网络/502/503/504 指数退避最多 3 次；`daily limit` 不重试。
- 下载 URL 带签名参数，不要写日志、不要持久化完整 URL（只存 `book_id/hash`，需要时重新解析）。

### 2.5 账号与配额

```
GET /eapi/user/profile
```

关键字段（兼容两套命名）：

```json
{
  "success": 1,
  "user": {
    "id": 1234567,
    "name": "...",
    "email": "...",
    "kindle_email": "...",
    "isPremium": 1,
    "confirmed": 1,
    "donations_active": 0,
    "donations_expire": "...",
    "personalDomains": ["..."],
    "downloads_today": 3,
    "downloads_limit": 10,
    "dailyDownloadsCount": 3,
    "dailyDownloadLimit": 10
  }
}
```

- `daily_amount = downloads_today ?? dailyDownloadsCount`
- `daily_allowed = downloads_limit ?? dailyDownloadLimit`
- `daily_remaining = max(0, allowed - amount)`
- `QuotaKnown = allowed != null`；未知时不要显示“0 本”。
- 账号等级仅确认 `isPremium`：false 显示“标准账号”，true 显示“Premium 会员”，字段未返回时不猜等级；`donations_active` 等字段未用于推断其它等级。
- 多账号（ADR-051）仍调用相同登录/profile 端点，每次显式使用该账号的 Cookie/remix 头，profile 按用户 ID 和会话隔离保存；密码不落盘。平台没有公开的多账号自动切换风控保证。自动切换默认关闭，仅额度错误后重新验证当前额度为 0 和候选额度大于 0 时采用，不因认证/网络/风控错误轮换。

### 2.6 收藏

```
GET /eapi/user/book/saved?page=1&limit=50
```

响应：`books[]` + `pagination`。取消/添加收藏的端点 CLI 未覆盖，已依据下述第三方说明接入（ADR-033）；2026-11-22 用户确认收藏功能真机验证成功。
收藏归属 Z-Library 账号，以服务端为准；本地如需缓存必须按账号隔离，不维护独立本地收藏标记（ADR-031）。

补充来源 `baroxyton/zlibrary-eapi-documentation` 列出了以下写操作，当前已实现请求与回归测试，并经用户真实账号确认可用：

```
GET /eapi/user/book/{bookId}/save
GET /eapi/user/book/{bookId}/unsave
```

- `bookId` 仅接受 1–32 位数字；请求带 Cookie 与 remix 头，经现有 allowlist 和重定向安全管线。先解析 envelope，只有 `success:1` 才更新 UI；错误保留原状态，不把 HTTP 200 当作成功。
- 详情顶部右侧显示心形按钮；已收藏为红色实心，未收藏为空心，状态读取或操作期间禁用重复点击。读取失败提示重试，不猜测未收藏。
- 当前无已验证的详情收藏布尔字段，使用收藏列表逐页按数字 ID 查找（包含缺少 hash 的项）；找到即为已收藏，只有完整读完分页才确定未收藏。分页字段缺失/异常或任一页失败时为未知状态，不能误报未收藏。
- 写入成功通知书架收藏列表刷新；状态只保留在当前会话内存，不写 Room 的旧 `favorite` 列。会话变更清除状态，旧结果不回写。

列表还记录了可选查询参数 `order`；具体排序取值需另行核实。当前请求只使用 `page` / `limit`。

### 2.7 云端已下载 / 下载记录

```
GET /eapi/user/book/downloaded?page=1&limit=50
```

响应：`books[]` + `pagination`；日期优先取 `date_download`，兼容 `date` / `downloaded_at`。`date_download` 来源于 ZlibraryKO 的字段映射，后两者为现有兼容字段；实际账号响应仍需脱敏核对。
这是账号的云端下载记录，原界面将其称为“历史”，并非浏览历史。按 ADR-049/051 从下载管理右上角云图标进入。云端记录不表示当前设备存在文件，也不确认网站其他图书馆分组使用同一端点。本机文件以设置的 SAF 下载目录实际扫描为准，不调用 EAPI。

2026-11-22 用户确认下载管理内云端下载记录真机验证成功；此确认不等于原始响应字段、账号切换、所有分页边界或缺图原因都已单独核验。

封面兼容：ZlibraryKO 的实现记录了下载列表可能返回 `https://.../collections//<hash>.jpg`（缺目录）、相对占位图 `/img/cover-not-exists.png` 等无效值。当前映射仅保留有效的 HTTPS 绝对地址；已知缺目录/占位图视作缺失。可见卡片通过 §2.3 的详情端点补取，最多 3 个并发，同一会话内每本最多尝试一次；补取失败仍保留记录和占位封面。只替换封面，不覆盖下载日期，不猜目录。此兼容行为有模拟响应测试，尚未对用户账号确认具体缺图原因。图片加载不带 Cookie/remix 凭据。

第三方文档还列出可选查询参数 `order`；不能据此断定服务端实际支持所有排序。

删除一条历史：

```
GET /eapi/user/book/{numericId}/delete
```

- 只接受数字 ID；`id:hash` 先取 `:` 前部分。
- 删除成功返回 `{"success":1}`；**不影响本地文件**。删除本机文件在书架“本机”单独操作，不联动删除云端记录（ADR-049）。
- 已按 ADR-046 接入“云端下载记录 → 长按图书 → 删除记录 → 确认”。API 只接受 1–32 位数字 ID，由列表图书取 `id`，不接受完整 `id:hash` 或其它路径字符。
- 该 GET 会修改账号数据，客户端为其关闭应用和 OkHttp 自动重试、会话过期自动重发与自动重定向；异常或结果不明时保留记录，由用户刷新核实。只有 `success:1` 后移除条目并重读第一页，刷新失败后也不得沿用旧分页偏移继续追加。
- 删除只调用云端端点，不依赖 Room 文件 DAO，不改本机文件、任务或收藏。操作防重复、会话变化丢弃菜单与旧结果；请求路径、鉴权、错误、无自动重试与列表状态已用 MockWebServer 验证，真实账号删除待用户手动确认具体条目后验收。

### 2.8 推荐 / 热门

```
GET /eapi/user/book/recommended
GET /eapi/book/most-popular
```

响应：`{"success":1,"books":[...]}`，无分页。需要登录（推荐）或公开（热门）。

### 2.9 域名信息

```
GET /eapi/info/domains
```

免登录；成功返回 `{"success":1,"domains":[...]}`。用于域名体检：

- 200 + JSON + `success:1` + `domains` → `HEALTHY`
- HTML challenge / Cloudflare → `CHALLENGED`
- 513/517 或标题含 DiamWall → `DIAMWALL`
- 自重定向不结束 → `REDIRECT_LOOP`
- 其它非 2xx → `HTTP_ERROR`
- 连接失败/超时 → `NETWORK_ERROR`

#### 跳转到 allowlist 之外时的发现（ADR-102）

2026-11-23 实测：`z-lib.by` → `301 https://zh.z101.by/`，`zlib.re` → 当天先为 `302 https://zh.us101.ru/`、后为 `301 https://z-lib.by/`（跳转目标会变，多数目标都在内置 allowlist 之外），旧行为判 `INVALID`（“重定向到非允许域名”）。新行为：

1. 体检所有请求均**无 Cookie、无 remix 头**。
2. 对目标 host 通过标准 HTTPS 请求 `/eapi/info/domains`，最多跟 3 跳，重写端点路径，不沿用查询或凭据。
3. 对未知目标，成功 JSON 中包含已知家族只表示接口兼容，且结果归属实际应答的主机。未知 `discovered_hosts` 仅用于候选展示与复检；需用户明确添加，才允许它接收凭据。
4. 目标如果已受信任且可用，可作为自动切换候选。保存的旧锁定值仍可显示，但未批准时不会被设为可请求的当前来源。

2026-11-23 经用户系统代理（`127.0.0.1:7897`）复测，区分出三类真实状态：

| 类型 | 域名 | 观测 |
|---|---|---|
| 直连 EAPI 可用 | `zlib.bz`、`z101.by`、`z-lib.by`、`z-lib.gd`、`z-lib.gl`、`z-library.ec`、`z-library.qa`、`z-library.website`、`article.sk`、`articles.sk` | 200 + `success:1`，1.0–3.0s |
| DiamWall 人机验证 | `z-library.im`、`z-lib.sk`、`z-library.sk`、`1lib.sk` | 首次命中 `307` 自重定向 + `Server: DiamWall` + `Set-Cookie: __diamwall=…`；带 cookie 重放后为 `513` 或再次 `307`，最终是挑战页。浏览器因执行 JS 挑战而可用，EAPI 客户端不绕过 → `DIAMWALL` |
| 证书过期 | `z-lib.fm` | `schannel: SEC_E_CERT_EXPIRED`；浏览器也不可用 |
| 跳转器 | `zlib.re` → `z-lib.by/`；`z-lib.by` 根路径偶发 `503` | `z-lib.by` 已在内置允许列表；按 ADR-102，只有已授权且体检通过的目标可自动切换 |

体检对 `307 + Server: DiamWall`（或带 `__diamwall` cookie）直接判 `DIAMWALL`，不再误报重定向循环。`domains` 响应本身可以包含第三方代理（如 `proxy.zlibraryproxies.workers.dev`）；它只用于**校验**，不会被采纳为可用域名，也不会加入 allowlist。

### 2.10 书单与图书评论（见 PAPI 文档）

未确认可用的书单或图书评论 EAPI。设置中浏览书单、详情中加入／新建书单与只读评论使用 [PAPI.md](PAPI.md) 的限定接口；登录、图书搜索、图书详情与图书收藏继续走 EAPI。

关联／延展书单已按 ADR-099 取消，不读取书页 HTML、关联接口或匿名公开单内图书。书单的请求范围仅限当前账号自己的书单及详情加入／创建操作。

### 2.11 搜索语言与格式候选（已实测，筛选 UI 动态接入）

```
GET /eapi/info/languages
GET /eapi/info/extensions
```

2026-11-22 `zh.z-lib.by` 免登录请求均为 HTTP 200、`application/json`、`success:1`。需要固定 UA 和 `Accept: application/json`，没有额外参数；客户端已按 ADR-062 接入动态筛选候选。

语言响应中的 `languages` 是 **代码到显示名称的对象**，本次有 175 项，节选：

```json
{"success":1,"languages":{"english":"英语","russian":"俄语","french":"法语","chinese":"中文"}}
```

格式响应的 `extensions` 是字符串数组，本次完整响应：

```json
{"success":1,"extensions":["pdf","fb2","epub","lit","mobi","djvu","djv","txt","rtf","azw","azw3","cbz"]}
```

这两项用于搜索筛选候选：提交搜索时使用 `languages[]=chinese`、`extensions[]=epub` 等代码。`extensions` 表示全站候选，不能用作某一本书的可下载格式；后者必须使用 §2.3 的 `/formats`。

2026-11-23 免登录核对 `/eapi/book/search` 与 `heartleo/zlib` 的 `internal/zlib/constants.go` / `internal/cli/search_filter_form.go`：`order` 支持且区分大小写的取值为 `popular`（热门）、`bestmatch`（最佳匹配）、`date`（最近添加）、`titleA`（书名升序）、`title`（书名降序）、`year`（出版年份由新到旧）、`filesize`（文件由大到小）、`filesizeA`（文件由小到大）。不传时本次结果与 popular 一致；不存在独立方向参数。`date_created` / `date_updated` 已废弃，未知取值会静默退回默认，不以 HTTP 200 判定支持。

语言、格式从候选集选择并以 `languages[]` / `extensions[]` 重复参数提交。`e=1` 本次观察为提高精确命中的优先级，不保证过滤掉所有非精确结果。翻页每次重发所有条件，固定 limit=50；本次宽泛查询最多返回500项/10页，客户端以实际 pagination 和空/重复结果停止，不把该观测上限硬编码成永久平台保证。

### 2.12 相似图书（2026-11-23 免登录只读验证）

```
GET /eapi/book/{id}/{hash}/similar
```

`zh.z-lib.by`、`z-lib.by`、`z-lib.gd` 的公开样本响应为 `{"success":1,"books":[...]}`，条目包括 `id/hash/title/author/cover/isbn`，没有格式和文件大小。六个常规样本返回24项，一个新上架样本为空；条数不是固定契约。`books` 必须是数组，空数组有效，缺失或错误类型为解析失败。使用已有 BookMapper 映射安全引用，按 `id:hash` 去重并去掉自身；忽略 `coverData` HTML 属性片段与 `url` 网页短链接，不能从中替换 EAPI hash。

对公开样本 `24126585/217fd6` 比较无参数、`?page=2`、`?page=99`、`?page=2&limit=5&order=title` 的 books 完全相同，顶层无 pagination/hasMore，因此客户端只请求一次，不发送分页/排序参数。详情共用纵向网格展示完整结果；到末尾不重复请求或拼接，失败独立重试。非法 hash 的400响应仍先解析 success/error，不当作空列表。样本请求：`https://zh.z-lib.by/eapi/book/24126585/217fd6/similar`。

### 2.13 第三方说明中的其它补充端点（未实测，未接入）

以下来自用户补充的 `baroxyton/zlibrary-eapi-documentation`，只作为后续调查清单。来源提供方法与少量参数，没有完整响应契约；本表不新增产品计划，不代表当前域名仍支持这些接口。接入前必须验证响应、鉴权、错误、分页及权限，并添加脱敏 fixture。当前范围仍以 ADR 和 `ROADMAP.md` 为准。

| 用途 | 方法与路径 | 来源参数 / 当前范围 |
|---|---|---|
| 最近图书 | `GET /eapi/book/recently` | 仅记录，未安排 UI |
| 服务信息 | `GET /eapi/info` | 查询 `switchLanguage` |
| 套餐信息 | `GET /eapi/info/plans` | 查询 `switchLanguage`；当前范围外 |
| 捐赠信息 | `GET /eapi/user/donations` | 当前范围外 |
| 隐藏横幅 | `GET /eapi/user/hide-banner` | 当前范围外 |
| 密码找回 | `POST /eapi/user/password-recovery` | 表单 `email`；当前未接入 |
| 注册 | `POST /eapi/user/registration` | 表单 `email`, `password`, `name`；当前未接入 |
| 重发验证邮件 | `POST /eapi/user/email/confirmation/resend` | 未列表单参数；当前未接入 |
| Token 登录 | `POST /eapi/user/token-sign-in` | 表单 `name`, `id_token`；当前未接入 |
| 账号更新 | `POST /eapi/user/update` | 表单 `email`, `password`, `name`, `kindle_email`；当前未接入 |
| 发送图书 | `GET /eapi/book/{id}/{hash}/send-to-{type}` | `type` 未说明；Kindle 不在范围内 |

该来源也列出热门的可选查询参数 `switch-language`、详情的 `switchLanguage`，以及收藏/下载记录的 `order`。参数拼写按来源分别记录，不能当作同一参数统一替换；未验证前不改变现有请求。

## 3. 域名 allowlist

内置后缀（含子域）：

```
1lib.sk, article.sk, articles.sk, z-lib.by, z-lib.fm, z-lib.gd, z-lib.gl,
z-lib.sk, z-library.ec, z-library.gy, z-library.im, z-library.qa, z-library.sk,
z-library.website, z101.by, zlib.bz, zlib.re
```

- 用户可添加精确 host（HTTPS、无端口/路径/查询/凭据、非 IP、非单标签）。
- 不允许 `http://`。
- 密码登录不跟随重定向，EAPI 其他请求不跨来源继续；下载重定向到 CDN 时无凭据跟随。

### 直接分组的内置镜像（ADR-107）

目录直接展示 17 个官方与 34 个社区镜像。官方保留既有 16 个主机，增加团队公告的 `z-library.gy`。2026-11-26 对参考项目 `zlibx-ref` 提交 `a4c9f13cc7ec07b62c03e8b07a68d4d8044fc0e3` 的全部 41 个主机以及既有官方主机，分别通过本机 VPN 代理和不使用代理的直连路径做匿名检查；确认两条路径的出口不同。新增主机以至少一条路径 `/eapi/info/domains` 返回 HTTP 200、`success:1` 与 `domains` 数组为条件，不将 HTML 或跳转当作通过。

社区组按参考顺序直接纳入 33 个通过的精确主机，另加团队定制镜像公告中的 `libb.la`：

```
bookroom.lifestyle, bibliotheca.world, bibliotheca.study,
bibliotheca.lifestyle, luto.top, bibliotheca.life, anthology.lifestyle,
elaborate.monster, s0u1.top, 26h2.tech, orangerain.space, zouwu.top,
xn--t8j0bxa8hymnb.jp, 553211.xyz, loves.works, ireadhuang.sbs,
elaborate.quest, elaborate.wtf, 9libmirror.tech, shunfei.shop,
elaboratethinking.online, bookorg.tech, booook.me, 95427279.xyz,
zkoo.site, adsblaze.com, 817523.xyz, toffeeboba.com, interflow.ch,
110434.xyz, 226272.xyz, 86110101.xyz, 909190.xyz, libb.la
```

参考中的 `z-library.im` 已在官方组，双线路均返回 DiamWall。另七个主机两条线路均失败，未纳入新增目录。完整逐域结果、失败分类与公告来源见 [MIRROR_AUDIT.md](MIRROR_AUDIT.md)。`z-library.gy` 和 `libb.la` 返回有效但为空的 `domains` 数组；目录精确主机允许此响应，未知跳转目标仍须证明已知家族兼容，不能据空数组进入发现列表。

社区主机不扩充可信后缀。首次选择需确认精确主机授权，撤销后仍展示内置卡片；匿名体检成功不授信。手动全部体检覆盖完整目录，启动与故障恢复只覆盖官方、当前及已批准主机，局部结果合并保留。检查只证明当时接口兼容和连通性，不证明社区域名归属、长期可用性或真实账号业务。

### 人机验证页（ADR-108）

已授权镜像被标记 `DIAMWALL`／`CHALLENGED` 后可打开独立进程的同域 HTTPS 网页，让用户手动完成挑战。网页与图书预览的 Cookie 存储隔离，不注入账号凭据、不抓取 HTML 数据或自动求解。匿名传输只支持精确主机的 GET／HEAD，限制五次同源跳转、25 秒请求链和 8 MiB 单资源，513 挑战正文可继续渲染；跨域、表单、worker 与登录路径不支持。完成后只筛选 `__diamwall`，同时核对验证与接收时的系统网络，按精确主机及当前网络在主进程内存中保留最多 15 分钟；切网监听仅清理临时状态，只发送至原主机的 `/eapi/` 请求，匿名体检仍不带账号凭据。跨域跳转、PAPI、图片、阅读预览、CDN、更新服务均不接收它。

用户点击完成后，仓库在同一次加锁操作内重新确认授权、匿名体检原主机并选择，只有原主机 EAPI 成功才切换；跳转到其它主机的成功不算验证通过。网页加载完成不等于挑战解除，实际效果留待用户在真机与当前出口验证。

## 4. 错误与状态码

| 现象 | 分类 | 客户端动作 |
|---|---|---|
| 搜索失败 envelope 的 `Access denied` / `拒绝访问` | `SearchRefused` | 显示“Z-library 拒绝搜索”；保留手动重试，不自动重试或判为会话过期 |
| 400 + `Invalid credentials` / `Please login` | `SessionExpired` | 当前域重试一次；仍失败 → 重新登录 |
| 400 + `Incorrect email or password` | `AuthFailed` | 提示凭据错误；不要重试 |
| `Authorization failed`（登录） | `AuthFailed` | 引导 Cookie 导入 |
| `daily limit` / `Daily limit reached` | `Quota` | 显示剩余/重置提示；不重试 |
| 513 / 517 / DiamWall 标题 | `DomainBlocked` | 标记人机验证，可手动验证后重新体检，或切换域名／网络 |
| 自重定向 / 超过 10 跳 | `RedirectLoop` | 切域 |
| 502 / 503 / 504 | `Transient` | 指数退避，最多 3 次 |
| 其它 4xx | `Server` | 展示错误，不重试 |
| 连接失败/超时 | `Network` | 提示检查网络/VPN；可重试 |

## 5. ISBN 规范化

1. 去掉 `-` 和空格，转大写。
2. ISBN-10：10 位，前 9 位数字，末位数字或 `X`；加权和 `% 11 == 0`。
3. ISBN-13：13 位数字；权重 1/3 交替；`% 10 == 0`。
4. 无效值置空；不要显示“伪 ISBN”。
5. 从 `identifier`/`isbn` 按 `, ; | \n` 拆分，逐个校验；ASIN（`B` 开头 10 位）不是 ISBN。
6. 由有效 ISBN-13（`978` 前缀）推导 ISBN-10；由有效 ISBN-10 推导 ISBN-13；两者必须属于同一版本。

## 6. 不支持 / 未验证

- 全文本搜索：EAPI 无确认端点，返回 `Unsupported`。
- 收藏写入：save/unsave 已按 ADR-033 接入，用户于 2026-11-22 确认真机验证成功；异常路径仍由模拟响应回归覆盖，账号切换/多设备仍需专项验收。
- 未确认书单或图书评论的 EAPI；书单、只读评论的限定 PAPI 范围、验证边界见 [PAPI.md](PAPI.md)。
- Kindle 推送、微信读书导入：不做；只用系统分享。
- HTML 目录、书页及书单页面抓取和验证绕过：不做。ADR-099 已取消 HTML 数据读取例外。
- 本地电子书元数据编辑及第三方查源：计划已按 ADR-030 取消；本文件中的书名、作者、ISBN、封面等字段仍用于 EAPI 图书信息展示。
- 应用备份/恢复及本地书库/书单导入导出：已按 ADR-031 取消；登录所需 Cookie 导入保留。

## 7. 参考

- `heartleo/zlib`：`docs/eapi.md`、`internal/zlib/eapi*.go`、`domain_policy.go`、`download.go`
- [baroxyton/zlibrary-eapi-documentation README.md](https://github.com/baroxyton/zlibrary-eapi-documentation/blob/e2184c463ed6ea8e3772a8ebe0c588b421fe366a/README.md)：第三方非官方说明；2026-11-22 核对。用户提供的 raw `/README` 返回 404，实际文件名为 [`README.md`](https://raw.githubusercontent.com/baroxyton/zlibrary-eapi-documentation/main/README.md)。其旧域名与“必须个人域下载”的说明不能替代当前域名策略和已实测下载流程。
- [ZlibraryKO api_shared.lua](https://github.com/ZlibraryKO/zlibrary.koplugin/blob/7d15f06166999ec559d5fb9901b3a4d7f3235906/zlibrary/api_shared.lua)：云端下载记录的日期与无效封面兼容依据。
- [nassim-arifette/zlibrary-api books.py](https://github.com/nassim-arifette/zlibrary-api/blob/cedae14c1ca344b1b35ef8d779adb7d84743b19d/zlib_connector/books.py)：格式选项独立引用、扩展名与显示大小的映射依据。
- [Ciallo-Reader ZLibraryEapiClient.kt](https://github.com/roxycon-dev/Ciallo-Reader/blob/50f374cde1cfa41cb16c072b7455e9b402863d0f/app/src/main/java/com/example/source/zlibrary/ZLibraryEapiClient.kt)：格式数组的 `id/hash/extension/filesize/filesizeString` 映射依据；只参考字段，不采用其其它鉴权、网页或下载凭据行为。
- [rookslog/zlibrary-mcp ADR-005](https://github.com/rookslog/zlibrary-mcp/blob/70eebdae00a0499819c06c6eb1dcb132883084a5/docs/adr/ADR-005-EAPI-Migration.md)：该项目迁移 EAPI 时记录缺少书单端点；不作为所有服务端均不支持的证明。
- 实测日期：2026-09-04、2026-10-25（域名与登录行为）
- 本项目实测：2026-11-22（`zh.z-lib.by` 登录、资料及图书收藏对照）；记录不含账号标识或凭据。
