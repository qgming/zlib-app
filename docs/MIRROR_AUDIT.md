# 镜像检查记录

检查日期为 2026-11-26。当前内置目录共 51 个主机，直接按「官方镜像」17 个与「社区镜像」34 个展示，见 ADR-107。社区首次使用需确认精确主机授权；匿名体检不授信，撤销授权后仍保留内置卡片。

## 来源与方法

- 用户指定的 `D:\dev\zlibx-ref`，提交 `a4c9f13cc7ec07b62c03e8b07a68d4d8044fc0e3`，`lib/providers/domain_provider.dart` 的全部 41 个默认主机。
- 官方域名公告：[Lib-Talk](https://lib-talk.io/viewtopic.php?p=478#p478)，用于补充 `z-library.gy`。
- 团队定制镜像公告：[Z-Library Mastodon](https://mastodon.social/@Z_Lib_official/114844833170738691)，用于补充社区镜像 `libb.la`，不据此扩大其子域权限。

本机开启 VPN 时，以本地代理为 VPN 路径、显式不使用代理为直连路径，检查确认两路出口不同。每个域名分别发送无账号、无 Cookie 的标准 HTTPS `GET /eapi/info/domains`，校验证书，不将重定向目标的响应归给原主机。只有 HTTP 200 的有效 EAPI JSON（`success:1`、`domains` 数组）记为 `eapi`；其中任一路成功即可进入新增目录。超时、DNS 或 TLS 失败只说明本次网络观测，不认定域名永久失效。

基础检查覆盖 56 个不同主机、112 次双线路请求（参考 41 个与原官方 16 个有一个重合）；外部补充再检查 5 个主机、10 次请求。原始无凭据分类结果保存在本地 `build/qa/mirror-audit-dual-route.json` 与 `build/qa/mirror-audit-external.json`，不把临时构建目录提交进源码。

## 参考项目全部 41 个主机

`eapi` 为本次接口检查成功，`timeout` 为超时，`dns` 为解析失败，`tls` 为 TLS 握手或证书失败，`diamwall` 为人机验证。以下顺序与参考项目一致。

| 主机 | VPN | 直连 | 纳入情况 |
|---|---|---|---|
| bookroom.lifestyle | eapi | eapi | 社区 |
| bibliotheca.world | eapi | timeout | 社区 |
| bibliotheca.study | eapi | eapi | 社区 |
| bibliotheca.lifestyle | eapi | timeout | 社区 |
| luto.top | eapi | eapi | 社区 |
| bibliotheca.life | eapi | timeout | 社区 |
| anthology.lifestyle | eapi | timeout | 社区 |
| elaborate.monster | eapi | eapi | 社区 |
| s0u1.top | eapi | eapi | 社区 |
| 26h2.tech | eapi | eapi | 社区 |
| orangerain.space | eapi | eapi | 社区 |
| zouwu.top | eapi | timeout | 社区 |
| xn--t8j0bxa8hymnb.jp | eapi | timeout | 社区 |
| 553211.xyz | eapi | eapi | 社区 |
| loves.works | eapi | eapi | 社区 |
| z-library.im | diamwall | diamwall | 保留在官方组，提供手动验证 |
| ireadhuang.sbs | eapi | timeout | 社区 |
| elaborate.quest | eapi | timeout | 社区 |
| elaborate.wtf | eapi | eapi | 社区 |
| 9libmirror.tech | eapi | eapi | 社区 |
| shunfei.shop | eapi | eapi | 社区 |
| elaboratethinking.online | eapi | timeout | 社区 |
| bookorg.tech | eapi | eapi | 社区 |
| booook.me | eapi | eapi | 社区 |
| 95427279.xyz | eapi | timeout | 社区 |
| zkoo.site | eapi | eapi | 社区 |
| adsblaze.com | eapi | timeout | 社区 |
| 817523.xyz | eapi | timeout | 社区 |
| toffeeboba.com | eapi | eapi | 社区 |
| interflow.ch | eapi | timeout | 社区 |
| free2read.cc | tls | dns | 两路均失败，不新增 |
| 8964520.xyz | timeout | timeout | 两路均失败，不新增 |
| umq.me | tls | dns | 两路均失败，不新增 |
| 110434.xyz | eapi | timeout | 社区 |
| frank9527.site | timeout | timeout | 两路均失败，不新增 |
| freebooks.lol | tls | dns | 两路均失败，不新增 |
| 226272.xyz | eapi | eapi | 社区 |
| jdbooks.xyz | tls | dns | 两路均失败，不新增 |
| arcfoxxxxxx.site | tls | dns | 两路均失败，不新增 |
| 86110101.xyz | eapi | eapi | 社区 |
| 909190.xyz | eapi | eapi | 社区 |

结果为新增社区 33 个，保留已在官方组的人机验证域 1 个，排除两路均失败的 7 个。

## 网络调研补充

| 主机 | VPN | 直连 | 处理 |
|---|---|---|---|
| z-library.gy | eapi | timeout | 加入官方组；有效空 `domains` 数组 |
| libb.la | eapi | eapi | 加入社区组；有效空 `domains` 数组 |
| zh.z101.by | eapi | timeout | 已属官方后缀，不额外重复内置 |
| zlib.tw | 301 → z-lib.by | timeout | 无原主机 EAPI 成功，不新增 |
| zliq.online | HTTP 200 非 EAPI | HTTP 200 非 EAPI | 不新增 |

## 使用边界

分类依据既有目录、公告和本次兼容性检查，不将社区镜像的自报结果当作归属认证。真实账号的登录、搜索、书单、评论、下载及镜像长期可用性未通过这批匿名请求验证。

人机验证通过独立网页让用户手动操作，只回传同域 `__diamwall`，返回后原主机 EAPI 成功才切换。跨域、表单或其它未支持的挑战可能无法完成；是否通过由真机与原站实际响应决定，网页打开或测试打包通过均不能代表挑战已解除。
