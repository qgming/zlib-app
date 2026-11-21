# 测试

## 1. 测试金字塔

| 层 | 范围 | 工具 |
|---|---|---|
| 单元 | ISBN、域名策略、错误映射、EAPI JSON 映射、文件名清洗 | JUnit5 + kotlin.test |
| 集成 | 登录/搜索/下载重定向/错误 envelope | MockWebServer + OkHttp |
| 数据库 | Entity/DAO、Migration | Room + `MigrationTestHelper` |
| UI | 登录、搜索、下载、书架、元数据 | Compose UI Test |
| 手测 | 真机网络/SAF/通知/分享/WebView | Xiaomi/HyperOS 真机 |

## 2. 必测行为

### 2.1 安全

- 跨 host 重定向后，`Cookie` / `remix-userid` / `remix-userkey` 被剥离。
- 非 allowlist 域名被拒绝；`http://`、端口、路径、IP、单标签主机被拒绝。
- 日志中不出现密码、Cookie、remix key、完整签名下载 URL。
- 退出登录清空内存/加密存储/WebView Cookie。
- `allowBackup=false`；备份 JSON 不含会话。

### 2.2 域名

- 200 JSON `success:1` + domains → `HEALTHY`。
- HTML challenge → `CHALLENGED`。
- 513/517 或 DiamWall 标题 → `DIAMWALL`。
- 自重定向/超过 10 跳 → `REDIRECT_LOOP`。
- 超时/连接失败 → `NETWORK_ERROR`。
- 切换域名后 Cookie 仍可用。

### 2.3 下载

- 206 续传、200 重下、Content-Disposition 文件名清洗。
- `.part` 完成前不出现最终文件；rename 失败走 copy+delete。
- 逐进程恢复：`DOWNLOADING` 重置为 `QUEUED`，校验 `.part`。
- 配额错误不重试；502/503/504 退避；4xx 不重试。
- 前台服务通知可暂停/取消；Android 13+ 通知权限。

### 2.4 元数据

- EPUB/FB2/DOCX fixture 读取 → 写回 → 再读，字段一致。
- 封面嵌入/替换；重命名冲突加后缀。
- 无效文件/超大文件/Provider 写失败有回滚。

## 3. 命令

```powershell
# 全部单测 + lint
.\gradlew.bat test lint

# 单模块
.\gradlew.bat :core:network:testDebugUnitTest
.\gradlew.bat :core:database:testDebugUnitTest
.\gradlew.bat :feature:search:testDebugUnitTest

# 调试构建
.\gradlew.bat :app:assembleDebug

# 仪器测试（需模拟器/真机）
.\gradlew.bat :app:connectedDebugAndroidTest
```

## 4. MockWebServer 约定

- 每个测试用独立 `MockWebServer`；`baseUrlProvider` 指向它。
- fixture 放 `src/test/resources/eapi/`，命名 `login_success.json`、`search_page1.json`、`download_302.json` 等。
- 下载测试用本地临时文件作 `DownloadSink`，不要写真实 SAF。
- 重定向测试显式设置 `Location`，并断言第二跳请求头已剥离凭据。

## 5. CI

GitHub Actions：

1. `actions/setup-java` JDK 21。
2. 安装 SDK 37.2 + build-tools 37.0.0（`android.exe sdk install` 或 `android-actions/setup-android`）。
3. 缓存 Gradle；`./gradlew test lint`。
4. `./gradlew :app:assembleRelease`，从 Secrets 还原 keystore。
5. 上传 APK artifact；tag 时创建 Release。
6. 失败时上传 `**/build/reports/`。

Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。日志不得打印这些值。

## 6. 手测清单（发布前）

- [ ] 三种登录方式；错误密码；Cookie 过期。
- [ ] 国内网络/VPN 切换；域名被墙；DiamWall。
- [ ] 搜索空结果/分页/筛选。
- [ ] 下载 1 本/多本排队；暂停/继续；杀进程；断网重连。
- [ ] SAF 换目录；文件管理器可见；分享到微信读书/阅读器。
- [ ] 收藏/历史删除；多设备刷新。
- [ ] EPUB/FB2/DOCX 元数据编辑；封面；重命名。
- [ ] 深色/浅色/Monet；玻璃开/关；低端机降级。
- [ ] 通知权限拒绝；前台服务被系统杀。
