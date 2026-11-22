# 界面动画

## 主导航与统一入口

ADR-039：设置及下载管理顶部等入口复用设计系统组件；卡片内部不绘制行间分割线，左侧图标为 Normal 常规粗细、基准 26dp，图标到卡片左缘和右侧文字各 15dp。账号主标题显示名称，副标题显示邮箱。

ADR-041：底部阅读／书架／设置使用 150ms 的整页横向滑动，距离为当前页面完整宽度；标题、顶栏操作、书架分组和内容一起移动，不叠加淡入淡出。向右侧导航项切换时，新页从右进入、旧页向左退出，反向切换相反；RTL 镜像。底栏保持固定并采样移动页面，保留 Miuix 原生指示器动画；内容底部预留实测底栏高度及安全区。页内滚动和顶栏状态按 tab 分别保留，快速切换可中断动画，同页重复选择不重播，双击阅读继续回顶。没有新增横滑切 tab 手势；Compose 动画遵循系统动画时长缩放。

## ITGSA SharedView Kit

用户指定参考：[SharedViewDemo](https://gitee.com/itgsa_sharedviewkit/SharedViewDemo)，核对提交 `b449a426a340f1827d942592bddf0a416eae3ac4`（2026-11-22）。这是金标联盟的一镜到底 Activity 级动画 SDK，独立于 Miuix；Miuix `0.9.5-beta03` 的 miuix-nav 未封装该 SDK。SDK 的返回动画由设备 ROM 执行，不能用普通 Compose 页面动画的验证代替。

ADR-040：app 固定引用仓库随附的 `1.0.0.5` AAR，来源与 SHA-256 见 `app/libs/README.md`。不改变现有 Android/Kotlin/Miuix 版本，不增加 HTTP 仓库或动态版本。

ADR-041：无缝动画默认尝试启用，不增加设置项；仅依靠 ROM 提供的 SharedView Kit 能力，不增加应用内模拟方案。运行时能力不足或快照失败仍使用普通 Activity 转场。

### 打开与返回

1. 通过 `ViewAnimationManager.isViewAnimationSupported()` 判断能力，返回 `>0` 才尝试 SDK。没有品牌或系统版本硬编码；不支持的设备不记录额外封面绘制层。
2. 首页热门／推荐、搜索、收藏、云端下载记录、本机文件均通过统一 `ZlibBookOpenRequest` 交由 app 打开详情。支持设备采集实际 Compose 封面的 Bitmap，包括真实封面及经典占位书封；不截取整屏或账号信息。
3. 从 `getViewAnimationExtras()[EXTRA_BOUNDS]` 读取 WINDOW／SCREEN 声明，显式 token 优先；默认 SCREEN。启动及返回使用相同规则，矩形单位 px；圆角按 8dp × density 转 px；填充色使用列表页真实底色。
4. 调用 Bitmap 重载 `makeViewAnimationOptions(bitmap, bounds, radius, bundle, listener)`，将生成的 options 传入 `startActivity`。图书详情改用非导出的 `BookDetailActivity`，页面仍复用现有 Miuix/Compose `DetailScreen`。
5. Intent 只包含图书引用、书名、作者、封面 URL 和随机转场标识；Bitmap、来源坐标查询器仅在内存保存，不包含 Cookie、remix key、密码、SAF URI 或下载签名。进程重建后仍能按引用重新加载详情。
6. 详情立即使用来源图书的基础信息显示封面和书名，继续通过原 Repository 拉取完整详情与云端收藏状态；完整详情未到达前不提供下载按钮。
7. 返回时在 `finish()` 前重新读取来源封面坐标，并调用 `overrideViewAnimationTransition`。来源已 detach、不可见或无完整矩形时，只写设备声明支持的返回／兜底类型。不同参数的支持集合分别判断；不假设 back 的类型也支持 fallback。
8. 设备不支持、快照失败、SDK 返回 null／不可用时使用普通 Activity 打开及返回。快速连点只启动一次，退出后清理内存引用；不在 ROM 仍可能使用快照时主动 recycle Bitmap。

### 许可边界

SharedViewDemo 示例采用 MIT；其 NOTICE 将 `gsai.sdk:sharedview-kit` 列为独立 SDK，授权信息以 SDK 发布说明为准。SDK AAR 未附完整授权文本，不能将示例的 MIT 自动延伸到该二进制。本次已按用户指令接入本地调试，公开 Release 前应确认 SDK 分发授权。没有复制 Demo 的图片素材。

### 验收

- 坐标元数据解析、缺失声明、back／fallback 独立支持集合有 JVM 回归测试。
- 2026-11-22 已连接设备的独立 SDK 能力探针返回 `protocol=0`，extras 为 null；表明该调用环境未声明支持。应用内已打开普通详情 Activity 并显示封面与完整图书数据；未据此认定支持 ROM 的一镜到底已通过。
- 2026-11-22 主导航录屏已确认整页标题／分组／内容左右滑动、底栏固定，正反及连续切换无崩溃，时长已按用户反馈调整为 150ms；滚动位置、同页重复选择及双击回顶仍需在转场结束后独立验收。
- ROM 支持设备须专项验证：首次打开与返回、快速返回、连续点击、已缓存／加载中／缺失封面、手势返回、横竖屏／分屏、来源移出可见区域和进程重建。当前未将这些支持设备专属场景标为已验证。
