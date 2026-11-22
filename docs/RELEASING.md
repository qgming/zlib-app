# 正式发布

源码与发布渠道：`https://github.com/qgming/zlib-app`。最低 Android 13，包名 `com.qgming.zlib`，沿用原签名。

## 版本

`app/build.gradle.kts` 的 `appVersionName` 使用 SemVer，`appVersionCode` 每次公开发布递增。
Git 标签为 `v<appVersionName>`，例如 `v1.0.0`。同一版本的所有架构与通用包使用相同 versionCode，便于互相覆盖安装和后续更新。

## 构建

使用 AGENTS.md 规定的工具链，并在本机通过未提交的 `key.properties` 配置原签名。

```powershell
.\gradlew.bat test lint :app:prepareReleaseAssets -PsplitApks=true
```

`app/build/release/` 生成以下独立可安装包，而非要求组合安装的 split APK：

- `zhishi-<version>-arm64-v8a.apk`
- `zhishi-<version>-armeabi-v7a.apk`
- `zhishi-<version>-x86_64.apk`
- `zhishi-<version>-x86.apk`
- `zhishi-<version>-universal.apk`
- `SHA256SUMS.txt`

普通调试构建不带 `-PsplitApks=true`，仍输出一个通用 APK。
正式包启用 R8 代码优化及资源裁剪，依赖的 consumer rules 自动合并；SharedView Kit 的 ROM 接口及占位实现必须保留。`app/build/outputs/mapping/release/` 保留 mapping 供发布版本问题定位，不上传为 APK 资产。

## 校验

1. `test`、`lint` 通过；保留已知 warning 的记录，不将未实测设备标为已验收。
2. 对每个 APK 执行 SDK `apksigner verify --verbose --print-certs`，确认各包及已安装版本的签名一致。
3. 用 `aapt dump badging` 检查包名、versionName、versionCode、minSdk、targetSdk，正式包不得含 `debuggable`。
4. 检查 ZIP 内 `lib/<abi>/` 仅包含对应架构；universal 包应包含四种架构，且各包原生库完整。
5. 用 `zipalign -c -P 16 -v 4 <apk>` 检查 APK 对齐；核对 `SHA256SUMS.txt`。
6. 在设备上覆盖安装相应架构版本；不能仅凭构建成功宣称所有 ABI 真机测试通过。

## 上传

提交前检查 `git diff --cached`，禁止提交本机配置、密钥、凭据、下载文件、构建目录及 APK。
将代码推送到 main，创建对应标签，再通过 GitHub Releases 上传五个 APK 和校验文件。
先上传为 draft 并核对完整性，确认后发布为正式版本；更新正文保存在 `docs/releases/<tag>.md`，与线上保持一致。

## 应用内更新

客户端仅查询公开正式 Release，排除 draft 和 prerelease。按设备支持顺序选择文件名中含精确 ABI 的 APK，找不到时回退唯一通用包。
APK 的包名、签名须匹配已安装应用，versionCode 必须更大，versionName 须匹配 Release 标签。GitHub 返回资产摘要时同时校验 SHA-256。
下载在用户点击更新后才开始，完成后保存在已授权 SAF 下载目录；用户再次点击安装，由系统确认。不会静默安装。

发布后核对应用的真实日志、最新版本状态和旧版更新到新版的下载／安装链路；发生网络限流时必须如实记录，不能用模拟数据代替线上结果。

## v1.0.0 构建记录

- 181 项单测通过，lint 无 error，仅既有 `ObsoleteSdkInt` warning；R8 与资源裁剪构建通过。
- 五种 APK 的原签名、包名／版本、最低系统、非调试属性、ABI、16KB ZIP 对齐及 SHA-256 均已核对。
- APK 大小：arm64-v8a 4,532,150 字节；armeabi-v7a 4,527,638 字节；x86_64 4,529,184 字节；x86 4,528,182 字节；universal 4,628,014 字节。
- x86_64 Android 17 / 16KB 模拟器：覆盖安装、真实账号登录、阅读列表、设置及关于页通过。发布时物理设备已断开，arm64 优化正式包和其它架构设备不据此标记通过。
