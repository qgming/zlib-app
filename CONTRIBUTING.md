# 贡献指南

## 1. 先读

- `AGENTS.md`：硬约束（工具链、安全红线、架构规则）
- `docs/ARCHITECTURE.md`：模块与状态机
- `docs/EAPI.md`：端点与错误
- `docs/PAPI.md`：书单端点、鉴权和会话隔离
- `docs/DECISIONS.md`：已定决策

## 2. 环境

- JDK 21
- Android SDK Platform 37.2、Build-Tools 37.0.0
- `local.properties`：
  ```properties
  sdk.dir=D:/Android/Sdk
  ```
- 本地签名：按 [发布说明](docs/RELEASING.md) 创建私有 `key.properties`，维护者沿用原签名。**不要提交密钥、密码或本机路径配置**。

## 3. 代码风格

- Kotlin 官方风格 + 4 空格缩进；Composable 用 PascalCase，普通函数 camelCase。
- 一个文件一个主类；包名按 feature 分层（`data/domain/ui`）。
- 不引入新依赖不写 ADR；不用 `GlobalScope`；不吞异常；不在 Composable 里发请求。
- 用户可见文案放 `strings.xml`（zh-CN + en），不要硬编码中文。
- 日志用 `SafeLog`，禁止打印凭据/签名 URL。

## 4. 提交

- 小步提交，commit message 用 `type(scope): summary`，例如 `feat(network): add domain probe`。
- 一个 PR 只做一件事；重构与功能分开。
- 改决策时同 PR 更新 `docs/DECISIONS.md`；改端点时更新对应的 `docs/EAPI.md` 或 `docs/PAPI.md`。

## 5. PR 检查

- [ ] `.\gradlew.bat test lint` 通过
- [ ] `.\gradlew.bat :app:prepareReleaseAssets` 通过，生成签名正式通用 APK；常规打包不附带调试包或独立架构包
- [ ] 没有提交 `key.properties` / `local.properties` / `*.jks` / secrets
- [ ] 新增逻辑有测试；网络/数据库变更覆盖异常路径
- [ ] 没有绕过 `AGENTS.md` §3 的安全红线
- [ ] 文档与代码一致

## 6. 报告问题

请附：版本/commit、设备与 Android 版本、复现步骤、脱敏日志（不要贴 Cookie/密码/签名 URL）。安全问题请私下联系维护者，不要在公开 issue 贴凭据。
