# TickHub

> 把手机变成 GitHub Actions 的可靠调度中心 · Turn your phone into a reliable GitHub Actions scheduler

[English](README_EN.md) · 简体中文

用 Android 手机替代 GitHub 不可靠的 `on.schedule`：登录 GitHub → 扫描仓库与工作流 → 配置触发规则（时间 + 周期）→ 手机用系统闹钟按时 `POST /dispatches` 触发工作流。支持网络等待、补触发、国产 ROM 后台保活、真实执行时长追踪与结果通知。

## 状态

| 项 | 状态 |
| --- | --- |
| 版本 | **v2.2.3 正式版**（终极优化版，签名 APK 可覆盖安装）（签名 APK，可覆盖安装） |
| 需求 / 架构文档 | ✅ [`docs/TickHub-开发需求文档.md`](docs/TickHub-开发需求文档.md) |
| CI | ✅ GitHub Actions 自动构建、单测、签名、发布 Release |
| 登录 | ✅ GitHub OAuth Device Flow（内置公开 Client ID，装完即用） |
| 扫描与纳管 | ✅ 仓库 / 工作流扫描、勾选持久化、立即触发 |
| 规则 | ✅ 6 种周期（单次 / 每天 / 每周 / 法定工作日 / 法定节假日 / 每隔 X 天）、多时段、随机漂移 |
| 调度引擎 | ✅ 精确闹钟 + 网络等待 + dispatch + 补触发 + 开机重注册 |
| 真机验收 | ✅ 连续夜间到次日准时触发，无睡死；重启 / 划后台仍生效 |
| 原生 schedule | 由仓库侧自动化关闭，APP 不重复实现 |
| 体积 / 功耗 | ✅ APK 约 2.13 MB（R8 瘦身）；结果轮询 5s 间隔（任务完成即停，无残留唤醒） |

## 为什么需要它

GitHub Actions 的 `on.schedule` 在免费额度下常被延迟甚至跳过（高峰期排队），对定时签到 / 定时拉取这类任务不可靠。TickHub 把调度权放回你自己的手机：用系统级精确闹钟到点唤醒，等待网络就绪后调用 GitHub 的 `workflow_dispatch` API，失败重试、断网等待、漏触发补跑，并追踪工作流真实执行结果。

## 技术栈

- **语言**：Kotlin 2.4（100% Kotlin，无 Java 遗留）
- **UI**：Jetpack Compose + Material 3，MIUI 风格字体与配色
- **架构**：单 Activity + Compose 状态驱动；共享 `AppGraph`（进程单例）持有基础设施与状态，5 个领域 ViewModel（Auth/Repo/Rule/History/Settings）分治；Repository 层统一数据访问
- **调度**：`AlarmManager.setExactAndAllowWhileIdle` + `BroadcastReceiver` + `WorkManager`（执行与结果追踪）
- **存储**：DataStore Preferences（配置 / 顺序 / 规则）+ Android Keystore（token 加密）
- **网络**：OkHttp + kotlinx.serialization（无 Retrofit 冗余依赖）
- **构建**：Gradle 9.7 / AGP 9.3.1；release 启用 R8 代码/资源压缩 + 完整 keep 规则（关闭混淆以保堆栈可读与序列化安全），产出签名 APK 供用户直接安装

代码约 6400 行，分层清晰：`data`（模型 / 存储）→ `domain`（规则计算）→ `network`（API）→ `repository` → `scheduler`（调度）→ `ui`（界面）。

## 安装

从 [Releases](https://github.com/LucasLu-97/TickHub/releases) 下载最新签名 APK，直接覆盖安装即可（同一签名）。最低 Android 8.0（API 26）。

## 权限说明

APP 需要以下权限才能保证定时可靠：

- **自启动**（国产 ROM 必须手动开启，否则划掉后台后定时任务不会执行）
- **电池无限制 / 忽略电池优化**
- **精确闹钟**权限（Android 12+）
- **通知权限**（Android 13+，用于执行结果通知）

APP 只在触发时发 HTTPS 请求，业务结果仍由各仓库原有的推送渠道发出。token 加密存储于 Keystore，不写日志、不上传。

## 使用说明

1. **登录**：打开 APP →「使用 GitHub 登录」→ 浏览器出现设备码，输入并授权 → 切回 APP（切后台期间可能提示"网络不可达，正在重试"，几秒后自动成功，属正常现象）。授权页显示的应用名是「GitHub CLI」属正常，因为复用了 GitHub 官方公开 Client ID。
2. **配置权限**：进「设置」页，开启「电池无限制」「自启动设置」「精确闹钟」。这三步是国产 ROM 后台定时可靠性的关键。
3. **扫描仓库**：回「仓库」页 →「扫描仓库与工作流」→ 展开目标仓库 → 勾选要纳管的工作流。点工作流「立即触发」可验证链路。
4. **创建规则**：进「规则」页 → 新建 → 选仓库展开并勾选工作流（跨仓库多选会自动拆成各自独立规则）→ 设触发时间（可加多个时段）→ 选周期 → 可选随机漂移 → 保存。
5. **验证**：先用「立即触发」确认「历史」页出现成功记录，再建一条几分钟后的规则验证自动触发。
6. **排障**：到点没触发时，进「设置」→「调试日志」→「复制全部」导出日志（含闹钟注册 / 触发、网络等待、dispatch 每次尝试的 HTTP 码与错误）。日志不含 token 或设备码。

### 卡片操作

- **左滑**卡片 → 右侧出现红色删除图标（常驻，点图标确认删除，点卡片取消）。
- **长按**卡片任意位置 → 进入多选模式，顶部出现「全选 / 删除 / 取消」。
- **长按拖拽手柄** → 卡片抬起，可拖动排序（仓库页仓库顺序、规则页同仓库多卡组内排序），顺序持久化。

## 构建

构建完全走 GitHub Actions，本地无需 Android SDK：

```bash
git tag v2.0.0 && git push origin v2.0.0
```

Actions 自动完成：单测 → R8 关键 keep 规则校验 → R8 瘦身的签名 **APK** → 发布 Release。也可在 Actions 页手动触发 `workflow_dispatch` 产出 artifact。签名密钥存于仓库 Secrets，有效期至 2054 年。

本地构建（需 Android SDK）：

```bash
# 当前仅产出 APK（暂不上架商店）
./gradlew assembleRelease \
  -PversionName=2.2.0 -PversionCode=2020300 \
  -PgithubClientId=<你的 GitHub OAuth Client ID>
```

## 开源许可

## 使用边界与隐私

- 本项目仅供个人合法自动化使用；请确保目标工作流及涉及的第三方服务符合其服务条款，开发者不承担因第三方账号策略导致的后果。
- 授权请使用 GitHub 官方 `github.com/login/device` 页面输入设备码；不要在第三方页面输入。
- 调试日志**不含 token 或设备码**，但可能包含 GitHub 用户名、仓库名与工作流名，分享前请确认接收方可信。

本项目以 [Apache License 2.0](LICENSE) 开源——允许商用、修改、分发，只需保留版权与许可声明。OAuth Client ID 为 GitHub 官方公开值（非密钥），可直接使用；如需自有应用，构建时通过 `-PgithubClientId` 注入。

## 上架应用商店（尚未进行）

当前按项目规划优先 GitHub 开源发布，暂不上架商店；CI 只产出签名 APK，**不产出 AAB / mapping.txt**。后续如需上架，需补充：`bundleRelease` 产 AAB、隐私政策 URL、数据安全表单、内容分级、目标受众声明，并上传 mapping.txt 用于崩溃还原。

## 文档

完整需求、架构、数据模型、API 契约、风险与移交指南见 [`docs/TickHub-开发需求文档.md`](docs/TickHub-开发需求文档.md)。
