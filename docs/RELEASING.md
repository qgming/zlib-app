# 正式发布

源码与发布渠道：`https://github.com/qgming/zlib-app`。最低 Android 13，包名 `com.qgming.zlib`，沿用原签名。

## 版本

`app/build.gradle.kts` 的 `appVersionName` 使用 SemVer，`appVersionCode` 每次公开发布递增。
Git 标签为 `v<appVersionName>`，例如 `v1.0.0`。同一版本的所有架构与通用包使用相同 versionCode，便于互相覆盖安装和后续更新。

当前正式版为 `1.3.0`（`versionCode=5`），于2026-11-24 00:58:18（北京时间，UTC为2026-11-23 16:58:18）发布为 `v1.3.0` 并设为Latest，标签指向构建源码提交 `d236ad8036b500f292799bedc4e4bb864a66451e`。上一正式版为2026-11-23发布的 `v1.2.0`（`versionCode=4`），标签指向 `5784a9862152041539a65b3506852e0dabcc2222`；`v1.1.0`（`versionCode=3`）标签指向 `6c1ccb2103990b4d8fe8fed4dc8eeed4102b96ac`。

开发版本已提升为 `1.3.1`（`versionCode=6`），用于搜索来源头修复与搜索历史胶囊布局的本地构建和模拟器验收（ADR-076/077）；尚未创建标签、推送或发布。

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
先上传为 draft 并核对完整性，确认后发布为正式版本；更新正文保存在 `docs/releases/<tag>.txt`，使用纯文本，可用“1、”等序号，不使用Markdown标题、项目符号或强调语法，并与线上保持一致。正文按“功能／优化／修复”分组，只写用户能感知的行为变化；不写安装步骤、架构选择、签名与校验说明，这些内容由 README 与下载页承担。v1.0.0、v1.1.0 的正文已校正为该格式，GitHub 上已发布的旧正文需同步更新到一致。

## 应用内更新

客户端仅查询公开正式 Release，排除 draft 和 prerelease。按设备支持顺序选择文件名中含精确 ABI 的 APK，找不到时回退唯一通用包。
该行为已存在于正式1.2.0，因此其arm64-v8a等独立安装包能直接更新到只提供 `zhishi-1.3.0-universal.apk` 的Release，不需要过渡版本。Release中只能有一个没有ABI后缀的APK，避免通用候选不唯一；非APK的校验文件和更新日志不参与候选。
APK 的包名、签名须匹配已安装应用，versionCode 必须更大，versionName 须匹配 Release 标签。GitHub 返回资产摘要时同时校验 SHA-256。
下载在用户点击更新后才开始，完成后保存在已授权 SAF 下载目录；用户再次点击安装，由系统确认。不会静默安装。

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
