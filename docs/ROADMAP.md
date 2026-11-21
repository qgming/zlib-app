# Roadmap

每个阶段以“可运行 + 可验证”为准，不按日期排期。v1 的 Definition of Done 见 `AGENTS.md` §9。

## v0.1 — 可构建的空壳

- [x] 方案、架构、EAPI、ADR 文档
- [x] Gradle 多模块骨架（`:app` + 6 个 core + 8 个 feature）
- [x] Miuix 主题与 `ZlibAppShell`；底部 4 tab 占位
- [x] 签名配置（本地 `key.properties`，CI secrets）
- [x] `:app:assembleDebug` 通过

**验收**：安装后能看到搜索/书架/下载/我的四个 tab，无崩溃；`test lint` 通过。

## v0.2 — 登录与域名

- [x] `EapiClient` 基础请求管线（form/JSON/envelope/错误分类）
- [x] 密码登录 + 加密会话（Keystore + DataStore）
- [x] WebView 登录 + Cookie 提取
- [x] 粘贴/Netscape Cookie 导入
- [x] 域名 allowlist + 并发体检 + 自动切换 + 自定义镜像
- [x] 账号页：昵称/邮箱/配额

**验收**：密码登录与 Cookie 导入需要真实账号手测；WebView 登录待真实登录页验证。域名体检/自动选择/会话加密已在真机（Xiaomi HyperOS，Android 16）启动路径验证，`test lint assembleDebug` 通过。

## v0.3 — 搜索与详情

- [ ] 搜索 + 语言/格式/年份/精确/排序/分页
- [ ] 详情页（封面、元数据、下载/分享/收藏）
- [ ] 推荐/热门
- [ ] 封面缓存（Coil）

**验收**：能搜到书、翻页、打开详情；空结果/网络错误/会话过期都有正确 UI。

## v0.4 — 下载与书架

- [ ] SAF 目录选择 + 持久化权限
- [ ] 前台 `dataSync` Service + 通知 + 暂停/取消/恢复
- [ ] Room 下载队列 + Range 续传 + `.part` 原子 rename
- [ ] 重试/退避/配额保护
- [ ] 书架：已下载列表、本地搜索/排序、删除/分享
- [ ] 分享 `ACTION_SEND` + `FileProvider`

**验收**：杀进程后队列恢复；断网/切网可续传；下载完成能在文件管理器找到；分享面板能打开阅读器。

## v0.5 — 收藏、历史、书单

- [ ] 收藏列表（只读）+ 本地标记
- [ ] 下载历史 + 删除（可选同时删本地）
- [ ] 书单列表/详情（只读书单；写入待实测）
- [ ] 下拉刷新与多设备一致性

**验收**：服务端状态与本地书架不互相污染；删除语义符合 ADR-018。

## v0.6 — 元数据

- [ ] EPUB 读写（OPF/封面）
- [ ] FB2 读写
- [ ] DOCX 读写
- [ ] Google Books / OpenLibrary / Douban 查源与合并
- [ ] 逐字段确认、重命名、嵌封面
- [ ] SAF 复制-修改-写回 + 失败回滚

**验收**：改完的元数据在外部阅读器中可见；原文件损坏时可恢复；大文件有上限和超时。

## v0.9 — 加固

- [ ] 域名状态机边界测试（513/517/循环/超时）
- [ ] 凭据泄漏审计（日志、崩溃、备份）
- [ ] Room migration 测试
- [ ] 低端机玻璃效果降级/关闭
- [ ] 无障碍与中英文文案
- [ ] JSON 备份/恢复

**验收**：安全红线全部有测试；lint 零 error；release APK 可覆盖安装。

## v1.0 — 发布

- [ ] GitHub Actions 构建/签名/Release
- [ ] README 截图、免责声明、下载说明
- [ ] 版本号/变更日志
- [ ] 朋友设备 smoke test（至少 2 台 Xiaomi/HyperOS）

## v1.1 — 后续

- MOBI / PDF / Goodreads
- 更新检查（跳转浏览器）
- 书单写入（实测后）
- 可选：更细的下载并发/限速

## 不做

内置阅读器、Kindle、微信读书导入 API、多书源、MCP、TUI、静默自更新。

