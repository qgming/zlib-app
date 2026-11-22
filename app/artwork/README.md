# 芝士读书图标

原始图稿为用户于 2026-11-22 提供的 `zhishi.svg`。

- 原始图稿 SHA-256：`a21fd6b9cf68ddb8002c0d0e28c38c28b18ae8a73efa5a625c15db2a06e07508`
- `drawable/ic_zhishi_foreground.xml`：保留芝士／书本路径及渐变，使用 even-odd 填充实现原 SVG mask 的透明镂空，不绘制背景；关于页直接使用。
- `drawable/ic_launcher_foreground.xml`：同一前景内缩 8dp，适配桌面自适应图标裁切。
- `drawable/ic_launcher_background.xml`：原图奶油色渐变，交由系统图标蒙版裁切。
- `mipmap/ic_launcher.xml`：组合桌面背景与前景，供普通及圆形启动图标共用；项目最低 Android 13，无需版本限定目录，单色主题图标复用透明前景轮廓。

资源不依赖运行时 SVG 解析或网络；修改图标时一并更新源文件及对应矢量。

仓库副本仅移除行尾空白以通过 Git 检查，图形内容不变；仓库 SVG SHA-256：`8765ae30b279183813f30d45bc635b5f670d38fa214fb779831edbbbbd0fcab4`。
