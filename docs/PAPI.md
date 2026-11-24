# PAPI 参考

> **非公开契约。** PAPI 是 Z-Library 网页端使用的 `/papi/...` JSON 接口。它和 [EAPI](EAPI.md) 可共享账号会话，但路径、请求体及访问条件不通用。按 ADR-079/084，芝士读书仅为「设置 → 我的书单」接入以下两类只读接口。

## 传输与鉴权

- 只请求当前会话指定且已受信任的 HTTPS 主机。PAPI 请求仅发送 `remix_userid` 和 `remix_userkey` 两个 Cookie，不发送密码、其它 Cookie、EAPI 的 remix 头或 `source` 头。封面图片请求不带凭据。
- 不接受新会话、不跟随重定向、不自动重发请求或切换账号／域名。客户端使用 HTTP/1.1，避免 HTTP/2 跨主机连接复用导致的 421 重发，也关闭 OkHttp 对 503 即刻重试的处理。PAPI 的登录拒绝只影响书单，不使 EAPI 会话失效。HTML 验证页、无效响应及限流按错误处理，不绕过验证。
- PAPI 使用 `Accept: application/json`，只接受 `application/json` 中的明确成功（HTTP 2xx 且 `success: 1`）。不抓取网站目录或加载 WebView。

## 已接入端点

| 用途 | 方法与路径 | 账号 Cookie / 请求体 |
|---|---|---|
| 自己的书单 | `GET /papi/booklist/current-user/0` | 有；无请求体。末段 `0` 不可省略 |
| 自己的书单内图书 | `POST /papi/booklist/{listId}/get-books/{page}` | 有；空表单 |

- 「我的书单」读取无分页的 `list` 对象数组，例如 `{"success":1,"list":[{"id":42,"title":"阅读书单","books_count":2}]}`。`id` 为正整数；未知数量不显示为 0。卡片最多取四个不同图书的封面；缺少预览时只为可见书单查询单内第一页，最多两个并发。
- 进入单内页面前，重新从「我的书单」核对归属。单内响应示例：`{"success":1,"books":[{"book":{"id":123,"hash":"ab12","title":"示例图书"}}],"pagination":{"current":1,"next":2}}`。分页从 1 开始，使用 `pagination.next` 中更大的页号；`false`、`null`、`0` 表示末页。没有 `next` 时只在明确的 `total_pages` 范围内续页；空页或没有新图书时停止。图书引用来自 `books[].book` 的 `id` 和 `hash`，进入现有 EAPI 图书详情。

## 边界与验证

- 2026-11-24，`zlib.bz` 上使用 EAPI 登录会话读取自己的书单成功；不代表其它镜像、所有账号或新入口的应用界面已验收。
- 不接入公开目录、书单搜索、推荐、收藏、创建、编辑、删除、单内加书或评论。字段与页码兼容性以脱敏示例和 MockWebServer 回归为准，不将账号或凭据放进样例。

## 参考

- [ZlibraryKO 的 PAPI 书单读取](https://github.com/ZlibraryKO/zlibrary.koplugin/blob/b7eacbab6cd7e64259857424749f0e2f26ff7bc5/zlibrary/api_account.lua)：复用 EAPI 登录凭据的独立参考。
- [nassim-arifette/zlibrary-api](https://github.com/nassim-arifette/zlibrary-api/blob/cedae14c1ca344b1b35ef8d779adb7d84743b19d/zlib_connector/eapi.py) 与 [blue-bird1/scriptcat 的接口笔记](https://github.com/blue-bird1/scriptcat/blob/3c03da9cab7f2ed30f9e870ca667242f2341497d/docs/sites/z-library/booklists.md)：书单列表与单内分页的交叉参考。
