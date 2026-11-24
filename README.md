# TickHub

Android app that turns your phone into a reliable GitHub Actions scheduler.

把手机变成 GitHub Actions 的调度中心：登录 GitHub → 扫描仓库与工作流 → 配置触发规则（时间 + 周期）→ 手机按时 `POST /dispatches` 触发工作流，替代 unreliable 的 `on.schedule`。

## 当前状态

| 项 | 状态 |
| --- | --- |
| 需求文档 | ✅ 完成（`docs/TickHub-开发需求文档.md`） |
| 仓库与 CI | ✅ 完成（公开仓库 + 签名 Secrets + Actions 构建） |
| 工程骨架 | ✅ 完成（Gradle 9.7 / AGP 9.3.1 / Kotlin 2.4.20） |
| Device Flow 登录 | 🚧 M1 |
| 仓库/工作流扫描 | 🚧 M1 |
| 规则配置 | ✅ M2 完成（5 种周期 + 12 项单测） |
| 调度引擎 | ✅ M3 完成（闹钟+等网络+dispatch+补触发，待真机验证） |
| 真机验收 | 🚧 M5 |
| 正式版 | 🚧 M6 |

当前分支的 APK 是**测试版**（`v0.x.x-betaN`），仅供作者与受邀者使用。

## 构建

构建完全走 GitHub Actions，本地无需 Android SDK：

- 打 tag `v0.1.0-beta1` 并 push → Actions 自动构建签名 APK 并发布为 prerelease。
- 或在 Actions 页手动触发 `workflow_dispatch` → 产出未发布的 APK artifact。

签名密钥存于仓库 Secrets，有效期至 2054 年，后续更新可直接覆盖安装。

## 文档

完整需求、架构、数据模型、API 契约、风险与移交指南见 [`docs/TickHub-开发需求文档.md`](docs/TickHub-开发需求文档.md)。

## 权限说明

APP 需要以下权限才能可靠定时：

- 自启动（国产 ROM 必须手动开启）
- 电池无限制 / 忽略电池优化
- 通知权限（Android 13+）

APP 只在触发时发一个 HTTPS 请求，业务结果由各仓库原有的 WxPusher 推送。
