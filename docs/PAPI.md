# PAPI 参考

> **非公开契约。** PAPI 是 Z-Library 网页端使用的 `/papi/...` JSON 接口。它和 [EAPI](EAPI.md) 可共享账号会话，但路径、请求体及访问条件不通用。设置的书单浏览见 ADR-079/084，详情的评论与书单操作见 ADR-093–096。

## 传输与鉴权

- 只请求当前会话指定且已受信任的 HTTPS 主机。账号书单及评论 PAPI 仅发送 `remix_userid` 和 `remix_userkey` 两个 Cookie，不发送密码、其它 Cookie、EAPI 的 remix 头或 `source` 头。封面和头像的图片请求不带凭据。
- 不接受新会话、验证 Cookie 或重定向，不自动切换账号／域名。客户端使用 HTTP/1.1，避免 HTTP/2 跨主机连接复用导致的 421 重发，并阻止 OkHttp 按 `Retry-After` 重发。登录拒绝不改变 EAPI 会话；HTML 验证页、无效响应及限流按错误处理，不绕过验证。
- ADR-096：图书评论、自己的书单、本书归属与书单图书分页只在网络异常或 HTTP 502/503/504 时按 1/2/4 秒退避，首次以外最多自动重试三次。每次都检查原会话仍有效；切号、换域和取消时停止。HTML 验证页、鉴权拒绝、非暂时性错误、证书错误和书单写入不自动重发。
- PAPI 使用 `Accept: application/json`；除下述创建确认外，成功必须是 HTTP 2xx 且 `application/json` 中明确的 `success: 1`。不抓取网站目录或加载 WebView。

## 已接入端点

| 用途 | 方法与路径 | 请求体 |
|---|---|---|
| 自己的书单 | `GET /papi/booklist/current-user/0` | 无；末段 `0` 不可省略 |
| 自己的书单内图书 | `POST /papi/booklist/{listId}/get-books/{page}` | 空表单 |
| 本书的书单归属 | `GET /papi/booklist/current-user/{bookId}` | 无 |
| 加入已有书单 | `GET /papi/booklist/{listId}/add-book/{bookId}` | 无；该 GET 会写入 |
| 创建私有书单并加入本书 | `POST /papi/booklist/create` | `text/plain; charset=utf-8`，正文为 JSON |
| 读取图书评论 | `GET /papi/comments/book/{bookId}/{page}?limit=50` | 无 |

### 我的书单

- 「我的书单」读取无分页的 `list` 对象数组，例如 `{"success":1,"list":[{"id":42,"title":"阅读书单","books_count":2}]}`。`id` 为正整数；未知数量不显示为 0。卡片最多取四个不同图书的封面；缺少预览时只为可见书单查询单内第一页，最多两个并发。
- 进入单内页面前，重新从「我的书单」核对归属。单内响应示例：`{"success":1,"books":[{"book":{"id":123,"hash":"ab12","title":"示例图书"}}],"pagination":{"current":1,"next":2}}`。分页从 1 开始，使用 `pagination.next` 中更大的页号；`false`、`null`、`0` 表示末页。没有 `next` 时只在明确的 `total_pages` 范围内续页；空页或没有新图书时停止。图书引用来自 `books[].book` 的 `id` 和 `hash`，进入现有 EAPI 图书详情。

### 从详情加入书单

- 归属查询返回当前账号自有书单的完整 `list`，不假设有分页。示例：`{"success":1,"list":[{"id":42,"title":"待读","books_count":3,"readlistBookId":null},{"id":57,"title":"已读","readlistBookId":300}]}`。卡片复用「我的书单」的 `books_count`、可用封面及可见时的第一页补取逻辑，数量缺失时不显示 0。有效的正整数 `readlistBookId` 表示已经加入，`null` 或缺失表示未加入；异常值不用于写入。加入请求使用书单 `id` 和详情的数值 `bookId`，不用 EAPI hash 或归属条目 ID。只有明确的 `success:1` 才标记已加入；不接入移除。
- 新建示例：`{"title":"待读","description":"","private":true,"bookId":123}`。名称去除首尾空白且不为空，创建仅由「创建并加入」触发。按 zlibx 记录，创建可以返回无正文的 2xx、`success:1` 或带有效 `readlist.id` 的 JSON 确认；不会把非空 HTML 或普通 2xx 当作成功。面板随后重新读取归属：有返回 ID 时按 ID 核对，否则比较创建前的 ID 集合并核对名称。只有确认一份新书单已包含本书才提示成功。结果不明时可再检查归属，不自动重发或再发加书请求。

### 图书评论

- 页码从 0 开始，响应 `{"success":1,"commentsDisabled":false,"comments":[{"id":10,"parent_id":null,"text":"示例评论","date":"2026-11-01 12:00:00","user":{"name":"读者","dateRelative":"3 天前"}}]}`。`commentsDisabled` 为真时不要求 `comments`，与正常空数组区分。
- 主评论按服务端顺序排列，按评论 ID 去重。`parent_id` 用于区分主评论与回复、查找直接回复对象并统计已加载的直接回复数；同级沿用服务端顺序，嵌套回复在父回复后显示。追加成功后重新统计，不把未加载的回复算入总数。没有总页字段，短页、无新条目或达到 20 页即停止。详情预览最多展示三条主评论，首批不足时按需继续找主评论；恢复评论详情路由时按需找到对应主评论。其余情况由用户选择「加载更多」继续。
- 头像按 `user.avatar`、`user.cover` 顺序选择；以单个 `/` 起始的路径相对当前镜像解析，绝对地址仅支持当前镜像、内置域名家族或 Z-Library 的 `cdn-zlib.sk` 图片主机上的标准端口 HTTPS，不接受 userinfo、查询串或片段。图片按服务端返回的 URL 匿名加载，失败显示本地占位；不查询用户资料，不跟随头像跳转。
- 点赞数取 `reactionsInfo.reactions` 的 `[{"item_id":10,"count":2}]` 或按评论 ID 索引的 `{"10":{"count":2}}` / `{"10":2}`。仅接受非负整数或数字字符串，未提供有效数值时按 0 显示。不处理点赞状态或写入。不将评论正文作为 HTML 解析或生成可点击链接。

## 边界与验证

- 2026-11-24，`zlib.bz` 上使用 EAPI 登录会话读取自己的书单成功；不代表其它镜像或本次新增的评论、加入和创建已用真实账号验收。
- 新增端点的依据是 zlibx 记录的 2026-11-06 至 11-09 抓包及其解析测试，不复制其跟随验证跳转的做法。字段与页码以示例及 MockWebServer 回归覆盖，不将真实账号或凭据放进样例。需要由用户手动选择测试书单验证真实写入。
- 关联／延展书单已按 ADR-099 取消，书页 HTML、关联接口和匿名公开单内读取不在当前契约内；历史调查及失败记录仅保存在 `DECISIONS.md` 和 `TESTING.md`。
- 不接入公开目录、书单搜索、无关图书的书单推荐、收藏、编辑、删除、移除图书，或评论的发布、回复、点赞和删除。

## 参考

- [zlibx 的图书评论](https://github.com/minglaiyo/zlibx/blob/a4c9f13cc7ec07b62c03e8b07a68d4d8044fc0e3/vendor/olib_api_plugin/lib/src/zlibrary_search.dart#L489)及[书单创建、加入、归属](https://github.com/minglaiyo/zlibx/blob/a4c9f13cc7ec07b62c03e8b07a68d4d8044fc0e3/vendor/olib_api_plugin/lib/src/zlibrary_booklists.dart#L471)：新端点的请求与响应参考。
- [ZlibraryKO 的 PAPI 书单读取](https://github.com/ZlibraryKO/zlibrary.koplugin/blob/b7eacbab6cd7e64259857424749f0e2f26ff7bc5/zlibrary/api_account.lua)：复用 EAPI 登录凭据的独立参考。
- [nassim-arifette/zlibrary-api](https://github.com/nassim-arifette/zlibrary-api/blob/cedae14c1ca344b1b35ef8d779adb7d84743b19d/zlib_connector/eapi.py) 与 [blue-bird1/scriptcat 的接口笔记](https://github.com/blue-bird1/scriptcat/blob/3c03da9cab7f2ed30f9e870ca667242f2341497d/docs/sites/z-library/booklists.md)：书单列表与单内分页的交叉参考。
