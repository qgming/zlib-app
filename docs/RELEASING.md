# 正式发布

源码与发布渠道：`https://github.com/qgming/zlib-app`。最低 Android 13，包名 `com.qgming.zlib`，沿用原签名。

## 版本

`app/build.gradle.kts` 的 `appVersionName` 使用 SemVer，`appVersionCode` 每次公开发布递增。
Git 标签为 `v<appVersionName>`，例如 `v1.0.0`。同一版本的所有架构与通用包使用相同 versionCode，便于互相覆盖安装和后续更新。

本次构建版本为 `1.5.1`（`versionCode=9`）。用户于 2026-11-27 明确要求设置该版本、编写更新日志、提交当前全部源码改动并正式发布至 GitHub；按现有流程先校验本地包和草稿资产，再公开并设为 Latest，无需另行确认发布。更新正文为 `docs/releases/v1.5.1.md`，不替换此前已发布的同名版本资产。

当前正式版为 `1.5.0`（`versionCode=8`），于 2026-11-27 00:21:42（北京时间，UTC 为 2026-11-26 16:21:42）发布为 `v1.5.0` 并设为 Latest，标签指向构建源码提交 `6d6df48`；仓库内历史版本 `v1.0.0`–`v1.4.0` 的更新正文均已重写为 Markdown 并同步更新至线上对应 Release。上一正式版 `v1.4.0`（`versionCode=7`）于 2026-11-25 16:51:49（北京时间，UTC 为 2026-11-25 08:51:49）发布，标签指向 `32fd44ba0be5d72c72f63d23a05668925d7546c9`。更早版本：`v1.3.1`（`versionCode=6`）指向 `e173ac2f9ea90dec8278ad031cbfe2d8090cf6fa`，`v1.3.0`（`versionCode=5`）指向 `d236ad8036b500f292799bedc4e4bb864a66451e`，`v1.2.0`（`versionCode=4`）指向 `5784a9862152041539a65b3506852e0dabcc2222`，`v1.1.0`（`versionCode=3`）指向 `6c1ccb2103990b4d8fe8fed4dc8eeed4102b96ac`。

1.3.1 修复了移动端搜索被拒绝的问题（ADR-076），并将最近搜索改为胶囊布局（ADR-077）；正文见 `docs/releases/v1.3.1.txt`，发布授权与验证见 ADR-078。

1.4.0 沿用原包名与签名，按正式通用版任务构建。用户于 2026-11-25 确认完整日志 `docs/releases/v1.4.0.txt`，并要求“全部git提交。正式版也发布到release”；全部改动已提交并推送 main，`v1.4.0` 标签及正式 Release 已发布，草稿阶段与公开下载后均核对三个附件。本轮未安装新版本。

按ADR-071/072/074，1.3.0起只打包并交付正式通用APK；本机构建与公开发布独立记录。1.3.0日志在 `docs/releases/v1.3.0.txt`，开头含移动端搜索服务异常声明；用户已确认并授权全部提交与发布（ADR-075），线上正文与文件逐字一致。

## 构建

使用 AGENTS.md 规定的工具链，并在本机通过未提交的 `key.properties` 配置原签名。

```powershell
.\gradlew.bat test lint :app:prepareReleaseAssets
```

`app/build/release/` 只生成以下单一可安装包与校验文件：

- `zhishi-<version>-universal.apk`
- `SHA256SUMS.txt`

通用包包含 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 四种架构。ABI拆分已关闭，不再使用 `-PsplitApks=true`；任务只收集 `app-release.apk` 并同步输出目录，避免残留旧架构包。日常构建不附带 `:app:assembleDebug`，不生成调试APK；只有用户另行明确要求时才单独构建调试包。已发布1.0.0–1.2.0的五包记录保留，不替换线上旧资产。
正式包启用 R8 代码优化及资源裁剪，依赖的 consumer rules 自动合并；SharedView Kit 的 ROM 接口及占位实现必须保留。`app/build/outputs/mapping/release/` 保留 mapping 供发布版本问题定位，不上传为 APK 资产。

## 校验

1. `test`、`lint` 通过；保留已知 warning 的记录，不将未实测设备标为已验收。
2. 对每个 APK 执行 SDK `apksigner verify --verbose --print-certs`，确认各包及已安装版本的签名一致。
3. 用 `aapt dump badging` 检查包名、versionName、versionCode、minSdk、targetSdk，正式包不得含 `debuggable`。
4. 检查通用APK的 ZIP 内 `lib/<abi>/` 包含四种架构，且各架构原生库完整。
5. 用 `zipalign -c -P 16 -v 4 <apk>` 检查 APK 对齐；核对 `SHA256SUMS.txt`。
6. 从旧独立架构版覆盖安装新通用版，验证数据保留；不能仅凭构建成功宣称所有ABI或真实线上更新链路已验收。

## 上传

提交前检查 `git diff --cached`，禁止提交本机配置、密钥、凭据、下载文件、构建目录及 APK。
将代码推送到 main，创建对应标签，再通过 GitHub Releases 上传一个通用 APK 和校验文件。
先上传为 draft 并核对完整性，确认后发布为正式版本；自 ADR-118 起，更新正文统一保存在 `docs/releases/<tag>.md`，使用清晰的 Markdown 格式（标题、无序列表、加粗与行内代码等），并与线上保持一致；应用内「检查更新」与「更新日志」已原生支持 Markdown 渲染并兼容历史纯文本。正文按「新增功能／体验优化／问题修复」分组，只写用户能感知的行为变化；不写安装步骤、架构选择、签名与校验说明，这些内容由 README 与下载页承担。历史版本 `v1.0.0`–`v1.4.0` 在仓库内已同步重写为对应的 `.md` 文件，如需同步更新线上历史 Release 正文需待用户确认。

## 应用内更新

客户端仅查询公开正式 Release，排除 draft 和 prerelease。按设备支持顺序选择文件名中含精确 ABI 的 APK，找不到时回退唯一通用包。
该行为已存在于正式1.2.0，因此其arm64-v8a等独立安装包能直接更新到只提供 `zhishi-1.3.0-universal.apk` 的Release，不需要过渡版本。Release中只能有一个没有ABI后缀的APK，避免通用候选不唯一；非APK的校验文件和更新日志不参与候选。
APK 的包名、签名须匹配已安装应用，versionCode 必须更大，versionName 须匹配 Release 标签。GitHub 返回资产摘要时同时校验 SHA-256。
下载在用户点击更新后才开始，完成后保存在已授权 SAF 下载目录；用户再次点击安装，由系统确认。不会静默安装。

ADR-104：遵从 GitHub 匿名限流时间；限流或查询失败时可展示明确标记的已缓存发布信息并打开固定 Releases 页面。缓存不证明已是最新版，也不提供应用内 APK 下载；只有成功完成在线检查并确认新版可用，才提供显式更新操作。

ADR-105：手动更新检查与日志的连接失败／明确限流统一使用短 Toast「网络环境异常，请关闭或开启 VPN 后重试。」；检查更新不弹失败面板，启动失败静默。服务端等待时间在同一默认网络上继续有效，Android 确认切网后可手动重查，切回原网络仍受原等待约束。网络变化不自动查询，GitHub 匿名配额仍由其服务端按出口 IP 计算。

发布后核对应用的真实日志、最新版本状态和旧版更新到新版的下载／安装链路；发生网络限流时必须如实记录，不能用模拟数据代替线上结果。

## v1.0.0 构建记录

- 181 项单测通过，lint 无 error，仅既有 `ObsoleteSdkInt` warning；R8 与资源裁剪构建通过。
- 五种 APK 的原签名、包名／版本、最低系统、非调试属性、ABI、16KB ZIP 对齐及 SHA-256 均已核对。
- APK 大小：arm64-v8a 4,532,150 字节；armeabi-v7a 4,527,638 字节；x86_64 4,529,184 字节；x86 4,528,182 字节；universal 4,628,014 字节。
- x86_64 Android 17 / 16KB 模拟器：覆盖安装、真实账号登录、阅读列表、设置及关于页通过。发布时物理设备已断开，arm64 优化正式包和其它架构设备不据此标记通过。

## v1.1.0 构建记录

- 242项单测通过，失败、错误、跳过均为0；当前模块lint无issue，调试构建及正式R8/资源裁剪构建均通过。
- 五个APK已校验原签名、包名com.qgming.zlib、versionName1.1.0/versionCode3、最低Android13、targetSdk37、非debuggable、原生ABI、16KB ZIP对齐与SHA-256。
- 大小：arm64-v8a 4,597,686字节；armeabi-v7a 4,593,174字节；x86_64 4,594,720字节；x86 4,593,718字节；universal 4,693,550字节。
- x86_64 Android17 / 16KB模拟器已覆盖安装正式包，相似图书导航与返回位置、关于版本、已是最新版Toast通过；其它ABI及复杂SAF故障不标为真机通过，详见TESTING.md。
- 纯文本更新正文为 `docs/releases/v1.1.0.txt`，附数字序号。正式资产位于 `app/build/release/`，包含五个APK与SHA256SUMS.txt。
- 2026-11-23 08:12:25 UTC正式发布 `https://github.com/qgming/zlib-app/releases/tag/v1.1.0`，非草稿、非预发布并标记为Latest。七个附件包括五种APK、SHA256SUMS.txt及v1.1.0.txt；先验证草稿中的名称、字节数、SHA-256和正文，再发布。发布后重新下载全部附件，SHA-256均与本地一致，正文与TXT一致。
- 无凭据latest API复核遇到HTTP403 rate limit；使用已有GitHub认证复核latest为v1.1.0。未据此声称无凭据应用检查或旧版到新版的安装链路已重新验收，详见TESTING.md。

## v1.2.0 构建记录

- 251项单测通过，失败、错误、跳过均为0；lint无新增问题，调试构建与正式R8/资源裁剪构建均通过。
- 五个APK已校验原签名（证书SHA-256 d964fb1414f44fc8eb0f4a3c4835a5c8fb70e4527e3f039bc70b0e1ccaca032e）、包名com.qgming.zlib、versionName1.2.0/versionCode4、最低Android13、targetSdk37、非debuggable、原生ABI（universal含四种架构）、16KB ZIP对齐与SHA-256。
- 大小：arm64-v8a 4,564,918字节；armeabi-v7a 4,560,406字节；x86_64 4,561,952字节；x86 4,560,950字节；universal 4,660,782字节。
- 本次发布时本机无已连接设备，未进行覆盖安装与真机回归，不标记任何ABI为真机通过；旧版到新版的安装链路仍需后续带设备验收。
- 更新正文为 `docs/releases/v1.2.0.txt`；同时把已发布的 v1.0.0、v1.1.0 正文改为同格式纯文本（去掉安装说明），线上已同步替换。
- 2026-11-23 发布 `https://github.com/qgming/zlib-app/releases/tag/v1.2.0`，非草稿、非预发布并标记为Latest。七个附件包括五种APK、SHA256SUMS.txt及v1.2.0.txt；草稿阶段核对名称、字节数与正文，发布后重新下载全部APK逐一比对SHA-256均一致。

## v1.3.0 构建与发布记录

- 2026-11-24执行 `test lint :app:assembleDebug :app:prepareReleaseAssets --max-workers=1` 成功；274项单测通过，失败、错误、跳过均为0，13个当前模块lint无issue。
- 输出仅含 `zhishi-1.3.0-universal.apk`（4,905,150字节）与 `SHA256SUMS.txt`，旧架构产物已由Sync输出同步移除。SHA-256为 `ac102b57e42b31cc211e805ba9f76a1fef9a0689a0ecdfceed0aae51001b23db`。
- 原签名证书SHA-256 d964fb1414f44fc8eb0f4a3c4835a5c8fb70e4527e3f039bc70b0e1ccaca032e；包名com.qgming.zlib、versionName1.3.0/versionCode5、minSdk33、targetSdk37、非debuggable。四种原生ABI、16KB ZIP对齐、校验和及六份离线许可资产全文均已核对。
- 正式通用包已在emulator-5554覆盖安装成功，系统版本复核为1.3.0/code5，未清除应用数据。用户在此次改名之前确认中英文测试正常；最新“发现 / Discover”已核对APK资源。其它ABI真机和线上旧版到新版的应用内安装链路仍需发布后验收。
- 日志确认前只进行本地构建与安装；用户随后要求今后仅打包正式通用版，执行 `:app:prepareReleaseAssets --max-workers=1` 成功（17秒），未附带调试APK构建，正式包与上述SHA-256一致。
- 用户确认日志后授权全部提交及发布。构建源码以 `d236ad8036b500f292799bedc4e4bb864a66451e` 提交并推送main，`v1.3.0`标签指向该提交。2026-11-23 16:58:18 UTC正式发布 `https://github.com/qgming/zlib-app/releases/tag/v1.3.0`，非草稿、非预发布并设为Latest。
- 三个附件为唯一 `zhishi-1.3.0-universal.apk`、`SHA256SUMS.txt`、`v1.3.0.txt`。草稿阶段核对正文、名称、字节数及GitHub SHA-256摘要；发布后重新下载全部附件逐字节比对本地一致，更新正文与确认后的TXT逐字一致。未上传调试包或独立架构包。
- 通过已有GitHub认证读取latest确认v1.3.0；匿名latest API返回HTTP403限流，不标记为成功。公开通用APK匿名下载为HTTP200，4,905,150字节及SHA-256核对通过。未重新进行应用内旧版到新版下载/安装，不能把元数据和公开下载成功等同于该链路验收。

## v1.3.1 构建与发布记录

- 2026-11-24 执行 `test lint :app:prepareReleaseAssets` 成功；275 项单测通过，失败、错误、跳过均为 0；15 个 lint 结果文件无 issue。R8 与资源裁剪的正式构建通过。
- 输出仅含 `zhishi-1.3.1-universal.apk`（4,921,534 字节）与 `SHA256SUMS.txt`。APK 的 SHA-256 为 `e07486901a1b561c3f487cf840c968162b165aeb176610eee06d45fad5ed2e90`。
- 原签名证书 SHA-256 d964fb1414f44fc8eb0f4a3c4835a5c8fb70e4527e3f039bc70b0e1ccaca032e；包名 com.qgming.zlib、versionName 1.3.1/versionCode 6、minSdk 33、targetSdk 37、非 debuggable；四种原生 ABI、16KB ZIP 对齐与 SHA256SUMS.txt 均已核对。
- 正式通用包已在 emulator-5554 覆盖安装，系统报告 1.3.1/code6，未清除应用数据。用户确认搜索恢复正常，并在胶囊版搜索历史安装后确认界面无问题；其它 ABI 真机与应用内 1.3.0 到 1.3.1 的更新链路未专项验收。
- 用户确认后授权全部提交与发布。构建源码以 `e173ac2f9ea90dec8278ad031cbfe2d8090cf6fa` 提交并推送 main，`v1.3.1` 标签指向该提交。2026-11-24 04:54:16 UTC（北京时间 12:54:16）正式发布 `https://github.com/qgming/zlib-app/releases/tag/v1.3.1`，非草稿、非预发布并设为 Latest。
- 三个附件为唯一 `zhishi-1.3.1-universal.apk`、`SHA256SUMS.txt`、`v1.3.1.txt`。草稿阶段核对正文、名称、字节数及 GitHub SHA-256 摘要；发布后重新下载全部附件，逐字节与本地一致，正文与 TXT 一致（GitHub 正文比 TXT 多一个行尾换行，与此前版本相同）。
- 无凭据 latest API 本次返回 HTTP 200，读取为 `v1.3.1`；公开通用 APK 匿名下载 HTTP 200，4,921,534 字节及 SHA-256 一致。未据此声称其它 ABI、所有异常路径或应用内旧版更新链路已验收。

## v1.4.0 构建与发布记录

- 2026-11-25 执行 `test lint :app:prepareReleaseAssets --offline --max-workers=1` 成功（3 分 16 秒）。当前模块共 63 个测试套件、357 项单测，失败、错误、跳过均为 0；13 个当前模块 lint 无 issue。正式包启用 R8 与资源裁剪，未构建调试包或独立架构包。
- 输出仅含 `zhishi-1.4.0-universal.apk`（5,202,010 字节）和 `SHA256SUMS.txt`。APK 的 SHA-256 为 `f7ea11321f73719639e6c0038938f4a85486be0e6f1f2373c0fb63c738f2a252`，与校验文件一致。
- 原签名证书 SHA-256 为 `d964fb1414f44fc8eb0f4a3c4835a5c8fb70e4527e3f039bc70b0e1ccaca032e`；包名 `com.qgming.zlib`、versionName `1.4.0`、versionCode `7`、minSdk 33、targetSdk 37、非 debuggable。四种 ABI 及各自完整原生库、16KB ZIP 对齐、六份离线许可资产与仓库原文字节一致均已核对。
- 完整纯文本日志为 `docs/releases/v1.4.0.txt`，以公开 1.3.1 为基线，汇总最终保留的我的书单、评论、加入／创建书单、在线预览、图片分享和详情调整；不包含已取消的实验功能。用户确认并授权全部提交及正式发布；发布前复核 APK 和待提交内容，无凭据、本机配置或构建产物混入源码提交。
- 本轮未使用模拟器，未安装或操作真机；不将打包及静态检查等同于分享保存、第三方接收或真实账号交互验收。完整验证边界见 `TESTING.md` 第 19 节。
- 分享实现提交为 `5b51890ab181064943f8d125fde0b71b43b431a2`，版本及已确认日志提交为 `32fd44ba0be5d72c72f63d23a05668925d7546c9`；此前两份本地功能提交一并推送 main，`v1.4.0` 标签指向后者。2026-11-25 08:51:49 UTC 正式发布 `https://github.com/qgming/zlib-app/releases/tag/v1.4.0`，非草稿、非预发布并设为 Latest。
- 三个附件为唯一 `zhishi-1.4.0-universal.apk`、`SHA256SUMS.txt`、`v1.4.0.txt`。草稿阶段核对名称、字节数及 GitHub SHA-256 摘要；正式发布后匿名重新下载三个附件均为 HTTP 200，逐字节与本地一致，线上正文与确认后的 TXT 完全一致。
- 已有 GitHub 登录状态读取 latest 确认为 `v1.4.0`；无凭据 latest API 返回 HTTP 403 且明确为 rate limit exceeded（剩余额度 0），未将其记为成功。未进行应用内旧版到新版下载／安装或新增真机操作，发布资产校验不能代替这些交互验收。

## 更新提示与镜像本地候选（2026-11-26）

ADR-105/106 的本地构建仅生成 `app/build/release/zhishi-1.4.0-universal.apk`（5,292,530 字节）和 `SHA256SUMS.txt`，仍为 `1.4.0`／`versionCode=7`。APK 的 SHA-256 为 `f9b4d6db6e74907308b369ea7b1e9d1284a4d8b2ffc0f401471631ca21221997`，原签名、四种 ABI 与 16 KB ZIP 对齐均通过。此候选未发布，不替换同名线上资产。完整验证见 `TESTING.md` 第 21 节。

## 双分组镜像与人工验证本地构建（2026-11-26）

- `test lint :app:prepareReleaseAssets --offline --max-workers=1 --console=plain` 通过。当前 13 个模块共 68 个测试套件、427 项单测，失败、错误、跳过均为 0；lint 无错误，隔离挑战 WebView 的必要 JavaScript 启用保留一条 `SetJavaScriptEnabled` 安全提醒。
- 唯一正式通用包为 `zhishi-1.4.0-universal.apk`，5,350,954 字节，SHA-256 `b42ccd8c3e6cc47f9e0ffb8a2e9e9a6cad628379e6faa3afa8cddda45390e044`，与 `SHA256SUMS.txt` 一致。包名 `com.qgming.zlib`、`1.4.0`／`versionCode=7`、minSdk 33、targetSdk 37；原签名证书 SHA-256 `d964fb1414f44fc8eb0f4a3c4835a5c8fb70e4527e3f039bc70b0e1ccaca032e`。四 ABI 原生库、16 KB ZIP 对齐和六份许可资产均已核验。
- 本地包未发布、未递增版本号、未安装真机；不得以此同版本本地构建替换已发布资产。挑战实际可用性、VPN 上的客户端更新操作和账号功能保留给用户真机验收，详见 `TESTING.md` 第 22 节。

## v1.5.0 构建与发布记录

- 2026-11-27 顺序执行 `test lint` 与 `:app:prepareReleaseAssets` 通过。当前 13 个模块共 69 个测试套件、460 项单测，失败、错误、跳过均为 0；lint 无新增 error。正式包启用 R8 与资源裁剪，未构建调试包或独立架构包。
- 输出仅含 `zhishi-1.5.0-universal.apk`（5,634,806 字节）与 `SHA256SUMS.txt`（93 字节）。APK 的 SHA-256 为 `3ad28039fafe55219f3c56a7e98e9e2b8f01ae17176254ec00de6aab77ff5562`，与校验文件一致。
- 原签名证书 SHA-256 为 `d964fb1414f44fc8eb0f4a3c4835a5c8fb70e4527e3f039bc70b0e1ccaca032e`（`CN=qgming, C=CN`）；包名 `com.qgming.zlib`、versionName `1.5.0`、versionCode `8`、minSdk 33、targetSdk 37、非 debuggable；四种 ABI 原生库完整、16 KB ZIP 对齐通过。
- 正式通用包已按用户要求覆盖安装至真机 `4993375f`（`M332BF`），ADB 返回 `Success`，系统复核 `1.5.0` / `versionCode=8`，保留原有数据。
- 完整 Markdown 更新日志保存为 `docs/releases/v1.5.0.md`，同时 `v1.0.0`–`v1.4.0` 全部改写为 `.md` 并同步更新线上历史 Release 正文。构建源码提交 `6d6df48` 及标签 `v1.5.0` 已推送 `origin/main`。
- 2026-11-26 16:21:42 UTC（北京时间 2026-11-27 00:21:42）正式发布 `https://github.com/qgming/zlib-app/releases/tag/v1.5.0`，非草稿、非预发布并设为 Latest。三个附件为唯一 `zhishi-1.5.0-universal.apk`、`SHA256SUMS.txt`、`v1.5.0.md`；草稿阶段核对 GitHub 摘要，发布后匿名重新下载三份附件逐字节 SHA-256 均与本地完全一致；无凭据 `releases/latest` API 返回 HTTP 200 且 `tag_name` 为 `v1.5.0`。

## 下载与搜索修复本地候选（2026-11-27）

- 修复 `/file` 的有效 JSON 下载链接因错误／缺失 MIME 被当成无效文件的问题，补充固定原因码诊断；按 ADR-119 在搜索结果中增加出版社并收紧胶囊留白。
- `test lint :app:prepareReleaseAssets --offline --max-workers=1 --console=plain` 通过；70 个测试套件共 470 项单测，失败、错误、跳过均为 0，13 份 lint 报告无 error，保留 6 条既有 warning。
- 唯一正式通用包为 `app/build/release/zhishi-1.5.0-universal.apk`，5,651,190 字节，SHA-256 `6a562c450e8b7ff17c70244431df9447b5ec84208710cec081d5c1a6851586e6`，与 `SHA256SUMS.txt` 一致；原签名、包名、`1.5.0`／`versionCode=8`、非调试、四种 ABI、16 KB ZIP 对齐与许可资产均已核对。
- 此包按用户要求交付手动测试，未安装设备、未递增版本、未发布，不替换线上同名资产。用户实际失败响应与真实下载链路仍需安装后复验，详见 `TESTING.md` 第 24 节。

## 文件打开与本机 EPUB 识别本地候选（2026-11-27）

- 按 ADR-120 增加已完成卡片外部打开和缺文件 Toast、本机 EPUB 只读书名／作者／内嵌栅格封面渐进展示，并保留前一候选的下载响应与搜索排版修复。
- `test lint :app:prepareReleaseAssets --offline --max-workers=1 --console=plain` 通过（6 分 56 秒）；74 个套件共 518 项单测全部通过，13 份 lint 报告无 error，保留相同的 6 条既有 warning。
- 本地唯一正式通用 APK 为 `app/build/release/zhishi-1.5.0-universal.apk`（5,684,494 字节），SHA-256 `fa84fd6b29934c333d1f628fc172cd60ce90fe723008f9b21301107c4ab63642`，已与 `SHA256SUMS.txt` 核对。原签名、包名、`1.5.0`／`versionCode=8`、非调试、四 ABI 原生库、16 KB ZIP 对齐及六份许可资产逐字节核验通过。
- 用户最新明确要求只打包、真机手动测试，本轮未安装或操作设备。未更改版本、未提交或公开发布，同版本候选不替换线上资产；实际 EPUB 图片解码、不同 SAF Provider 和第三方阅读应用行为待用户验收，详见 `TESTING.md` 第 25 节。

后续按用户追加安装要求，该候选已成功覆盖安装至 `4993375f`（`M332BF`），系统复核 `1.5.0`／`versionCode=8`、更新时间 2026-11-27 13:31:13；保留应用数据，未自动操作应用验证业务功能。

## v1.5.1 构建与发布记录

- 用户于 2026-11-27 明确要求版本 `1.5.1`、编写更新日志、提交当前全部源码并正式发布 GitHub；构建版本为 `1.5.1`／`versionCode=9`，原包名及签名保持一致。Markdown 正文为 `docs/releases/v1.5.1.md`。
- 执行 `test lint :app:prepareReleaseAssets --offline --max-workers=1 --console=plain` 成功（3 分 45 秒）；74 个套件共 518 项单测全部通过，13 份 lint 报告无 error，保留相同的 6 条既有 warning。构建启用 R8 与资源裁剪，未生成调试或独立架构 APK。
- 唯一正式通用包 `zhishi-1.5.1-universal.apk` 为 5,684,494 字节，SHA-256 `8b53d7f42fae02c0d6434aa86b459f9cf58bdcb3281ff4ee3f66e09fb5c5f55e`，与 `SHA256SUMS.txt` 一致。包名 `com.qgming.zlib`、`1.5.1`／`versionCode=9`、minSdk 33、targetSdk 37、非 debuggable；原签名证书 SHA-256 `d964fb1414f44fc8eb0f4a3c4835a5c8fb70e4527e3f039bc70b0e1ccaca032e`。
- 四种 ABI 各两份原生库、16 KB ZIP 对齐及六份离线许可全文字节均已核对。本轮没有再次安装或操作设备，真实下载、EPUB 展示与第三方阅读器行为保留为用户手动验收项，详见 `TESTING.md` 第 26 节。

