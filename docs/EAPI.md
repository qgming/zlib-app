# EAPI 参考

> **非公开契约。** Z-Library 的移动端 EAPI 没有官方文档或稳定性承诺。字段、鉴权和域名都可能变化。本文件记录的是 `heartleo/zlib` 在 2026-09 至 2026-11 实测到的行为，以及 `baroxyton/zlibrary-eapi-documentation` 的端点说明，仅作为实现依据。

## 1. 传输约定

- Base：`https://<允许的域名>`，例如 `https://z-lib.gd`。
- 只使用 HTTPS；域名必须通过 allowlist（`AGENTS.md` §3.2）。
- `Accept: application/json`
- `User-Agent`：桌面 Chrome（与 CLI 一致）
- POST：`Content-Type: application/x-www-form-urlencoded`
- 鉴权：Cookie `remix_userid` / `remix_userkey`，同时发同名请求头：
  ```
  remix-userid: <id>
  remix-userkey: <key>
  ```
- 列表 `limit` 默认 50；分页从 1 开始。
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
- **已知问题**：部分账号密码登录被拒，但用浏览器 Cookie 可以正常调用其它端点。因此客户端必须提供 Cookie 导入/WebView 通道。
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

返回单本书的完整字段，结构与搜索项相同。详情页按 `id:hash` 缓存，避免重复请求。

### 2.4 下载

```
GET /eapi/book/{id}/{hash}/file
```

- 通常 302 到 CDN；必须跟随，但**离开 allowlist 的每一跳都要剥离** `Cookie`、`remix-userid`、`remix-userkey`。
- 文件名优先 `Content-Disposition: filename=...`，其次 URL 末段；清洗 `../`、控制字符、Windows 非法字符。
- 支持 `Range: bytes=<written>-`；206 继续，200 重新开始。
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

### 2.6 收藏

```
GET /eapi/user/book/saved?page=1&limit=50
```

响应：`books[]` + `pagination`。取消/添加收藏的端点 CLI 未覆盖；v1 只读收藏，写操作需实测后再加。

### 2.7 下载历史

```
GET /eapi/user/book/downloaded?page=1&limit=50
```

响应：`books[]` + `pagination`；日期取 `date`，为空取 `downloaded_at`。

删除一条历史：

```
GET /eapi/user/book/{numericId}/delete
```

- 只接受数字 ID；`id:hash` 先取 `:` 前部分。
- 删除成功返回 `{"success":1}`；**不影响本地文件**。客户端默认只删服务端记录，弹窗可选“同时删本地”。

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

## 3. 域名 allowlist

内置后缀（含子域）：

```
1lib.sk, article.sk, articles.sk, z-lib.by, z-lib.fm, z-lib.gd, z-lib.gl,
z-lib.sk, z-library.ec, z-library.im, z-library.qa, z-library.sk,
z-library.website, zlib.bz
```

- 用户可添加精确 host（HTTPS、无端口/路径/查询/凭据、非 IP、非单标签）。
- 不允许 `http://`。
- 登录重定向到 allowlist 外 → 拒绝；下载重定向到 CDN → 跟随但剥离凭据。

## 4. 错误与状态码

| 现象 | 分类 | 客户端动作 |
|---|---|---|
| 400 + `Invalid credentials` / `Please login` | `SessionExpired` | 当前域重试一次；仍失败 → 重新登录 |
| 400 + `Incorrect email or password` | `AuthFailed` | 提示凭据错误；不要重试 |
| `Authorization failed`（登录） | `AuthFailed` | 引导 WebView/Cookie 导入 |
| `daily limit` / `Daily limit reached` | `Quota` | 显示剩余/重置提示；不重试 |
| 513 / 517 / DiamWall 标题 | `DomainBlocked` | 切域；提示 VPN/代理 |
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
- 收藏写入、书单创建/编辑：CLI 未覆盖，v1 只读；要加先实测并补文档。
- Kindle 推送、微信读书导入：不做；只用系统分享。
- HTML 页面抓取、Cloudflare 绕过：不做。

## 7. 参考

- `heartleo/zlib`：`docs/eapi.md`、`internal/zlib/eapi*.go`、`domain_policy.go`、`download.go`
- `baroxyton/zlibrary-eapi-documentation`
- 实测日期：2026-09-04、2026-10-25（域名与登录行为）
