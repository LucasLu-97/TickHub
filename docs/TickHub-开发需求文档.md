# TickHub 项目开发需求文档

| 项目 | 内容 |
| --- | --- |
| 项目名称 | TickHub（Android 端 GitHub Actions 调度中心） |
| 文档版本 | v2.0（定稿） |
| 编写日期 | 2026-11-24 |
| 定稿日期 | 2026-11-26 |
| 文档状态 | ✅ 已定稿 —— 功能完成、真机验收通过、v1.0.0 正式版发布 |
| 决策记录 | 2026-11-24：仓库公开；APP 调度跑通后关闭原生 schedule；前期走 CI 发测试版 Release |
| 仓库 | https://github.com/LucasLu-97/TickHub （公开） |
| 首个测试版 | v0.1.0-beta1，已签名 APK，Release: https://github.com/LucasLu-97/TickHub/releases/tag/v0.1.0-beta1 |
| 最新测试版 | v1.0.17-beta35（收尾优化：R8 shrink + 删冗余依赖，APK 14.2MB→2.03MB），Release: https://github.com/LucasLu-97/TickHub/releases/tag/v1.0.17-beta35 |
| 当前正式版 | v1.2.0（全面加固：重试去重/后台i18n/runIds精确匹配/logout清理/HTTP分级/节假日策略/资源优化），Release: https://github.com/LucasLu-97/TickHub/releases/tag/v1.2.0 |
| 历史正式版 | v1.1.5（立即触发耗时追踪）、v1.1.4（规则卡间隔天数/失败重试/闹钟降级）、v1.1.3（历史页i18n+实时耗时）、v1.1.2（i18n布局）、v1.1.1（状态实时翻译）、v1.1.0（双语/节假日开关）、v1.0.3（登录页优化）、v1.0.2（关闭R8修复闪退）、v1.0.1/v1.0.0（R8闪退） |
| 真机验收 | ✅ 2026-11-25/26 连续夜间到次日准时触发、无睡死；原生 schedule 由仓库侧自动化关闭（APP 侧不重复实现） |
| 登录方式 | 内置 GitHub CLI 公开 Client ID，装完直接登录，无需用户预配置（见 §16） |
| 目标读者 | 后续接手的开发者 / AI Agent |
| 计划仓库 | `LucasLu-97/TickHub`（私有或公开待定，见 §12.1） |

> 本文档的目的：让一个从未参与过本项目的人，只读这一份文档就能理解「为什么做、做什么、怎么做、做到什么程度算完成」，并能独立接手继续开发。文档中所有「待验证」「待决」条目都必须在实际动手时逐项闭环。

---

## 1. 背景与问题陈述

### 1.1 背景

项目所有者（LucasLu-97）在 GitHub 上维护了多个用 Actions 驱动的个人自动化仓库，例如：

- `WB-Checkin`：WorkBuddy 每日自动签到（上游同步 + 签到 + 通知，4 个工作流）
- `KG-Checkin`：酷狗概念版自动签到（签到 / 保活 / 账号管理，6 个工作流）
- `Release-Watch`：Star 仓库 Release 更新监测推送（检测 + 保活，2 个工作流）

这些仓库原本都依赖 GitHub 原生的 `on.schedule` 定时触发。2026-11 的连续实测暴露出严重问题：

- 定时任务延迟数小时甚至整天不触发（实测 Release-Watch 延迟约 12 小时、WB-Checkin 与 KG-Checkin 连续多日 0 次 `schedule` run）。
- GitHub 官方文档明确 `schedule` 是 **best-effort**：高负载时延迟，负载过高时排队事件可能被直接丢弃；仓库连续 60 天无活动还会自动禁用定时任务。
- 社区在 2026-08 之后大量报告同类问题，且 githubstatus.com 常显示「operational」，无法依赖官方状态页做容量判断。

### 1.2 要解决的问题

把「什么时候触发」这个时钟从不可靠的 GitHub 平台侧，迁移到用户自己可控的终端侧，并提供一个可视化管理界面，统一管理多个仓库、多个工作流的触发规则与触发历史。

### 1.3 项目目标

1. **可靠触发**：由用户手机上的 APP 作为调度中心，按用户设定的时间/周期主动 `POST /dispatches` 触发 GitHub Actions。
2. **集中管理**：一个 APP 登录一次 GitHub，扫描出账号下自建仓库及其工作流，勾选需要纳管的项，逐条配置触发规则。
3. **可观测**：记录每次触发的计划时间、实际时间、结果（成功/失败/跳过），可查询、可手动补触发。
4. **可移交**：代码与配置结构化，配套文档完整，他人可接手维护。

### 1.4 非目标（明确不做）

- 不做 iOS（iOS 无自启动、无后台精确定时，APP 只能做管理界面，实际触发仍需云函数；首版完全不覆盖）。
- 不做 Web 管理端。
- 不在 APP 内执行签到/监测脚本本身——APP 只负责「按时发一个 HTTP 请求」，真正的业务逻辑仍在 GitHub Actions 里跑（职责分离）。
- 不替换现有仓库的工作流逻辑；TickHub 对它们是纯外部触发方。
- 首版不做多用户/服务端账号体系，是单人自用的本地 APP。

---

## 2. 范围界定

### 2.1 设备与系统范围（用户已明确）

| 维度 | 约束 |
| --- | --- |
| 平台 | 仅 Android，不做 iOS |
| 系统版本 | 仅兼容较新版本（建议 minSdk 26 / Android 8.0，原因见 §6.2） |
| CPU 架构 | 仅 arm64-v8a，不兼容 armeabi-v7a / x86 |
| 厂商 | 面向国产 ROM（重点小米/HyperOS），需引导用户开启自启动 + 电池无限制 |
| 分发 | GitHub Releases 提供 APK 下载，不上架 Google Play（首版） |

### 2.2 用户已验证的前提（重要）

用户曾在公开仓库开发过类似的定时自动任务 APP，实测结论：

- 在设置「自启动 + 电池无限制」后，即使划掉后台，每日清晨定时执行**依然准时准点**。
- 因此 **Doze 导致延迟不是本项目的核心风险**，不需要为此做复杂的对抗性保活设计。
- 但存在一个真实问题：**手机夜间睡眠可能断网**，导致定时点到达时无网络、请求失败。
- 用户已有解法：**触发时先唤醒设备，等待网络恢复后再继续执行**。该机制必须实现（见 §6.3.4）。

> 结论：调度引擎的设计重心是「精确定时唤醒 + 等待网络就绪」，而不是「对抗 Doze」。不引入前台常驻服务（省电、不打扰），不引入厂商推送 SDK。

---

## 3. 术语表

| 术语 | 含义 |
| --- | --- |
| `workflow_dispatch` | GitHub Actions 的一种事件类型，允许通过 REST API 手动触发工作流 |
| dispatch | 本文动词，指调用 dispatch API 触发一次工作流 |
| Device Flow | OAuth 2.0 设备授权流程（RFC 8628），适合无浏览器/无法接收回调的客户端；APP 显示一个用户码，用户在浏览器完成授权，APP 轮询拿 token |
| OAuth App | GitHub 上注册的一类应用，用 classic scope（如 `repo`）授权，产出 `gho_`/`ghu_` 前缀 token |
| scope | OAuth 授权范围；触发私有仓库工作流需要 `repo` scope |
| rule / 触发规则 | 一条「某工作流 + 何时 + 按什么周期触发」的配置 |
| 补触发 | APP 发现某条规则到点该触发却没触发时，主动补发一次 dispatch |
| 纳管 | 把某个仓库/工作流加入 TickHub 的管理与调度范围 |

---

## 4. 用户与使用场景

### 4.1 目标用户

单人开发者，拥有多个 GitHub 自动化仓库，希望用手机统一管理触发时间，替代不稳定的平台 schedule。

### 4.2 核心用户故事

1. **首次接入**：作为用户，我打开 APP，点击「用 GitHub 登录」，看到设备码，在浏览器授权后回到 APP，自动显示我的账号和仓库列表。
2. **纳管**：作为用户，我勾选 WB-Checkin 的签到工作流和 Release-Watch 的检测工作流，分别设置「每天 07:17」和「每天 18:17」。
3. **日常运行**：作为用户，我什么也不用做，手机在每天设定时间自动触发对应工作流，我收到原有的 WxPusher 推送结果。
4. **补漏**：作为用户，某天手机夜间断网导致触发失败，APP 在网络恢复或我打开 APP 时自动补触发一次，并在历史里记录「补触发」。
5. **手动干预**：作为用户，我在 APP 里点「立即触发」可以手动跑一次；看到历史里某次失败可以点「重试」。
6. **权限自检**：作为用户，我在设置页看到「后台运行受限」警告，点击跳转到小米的自启动/电池设置页。

---

## 5. 功能需求

> 优先级：P0 = 首版必须；P1 = 首版应有，可延后一个迭代；P2 = 锦上添花。
> 每条需求含「描述 + 验收标准」，验收标准是判断「做完了」的依据。

### FR-01 GitHub 账号登录（Device Flow） — P0

**描述**：APP 通过 GitHub OAuth Device Flow 登录，无需内置 client_secret，无需后端服务。

- 注册一个 OAuth App（见 §12.5），把 Client ID 硬编码在 APP 内（公开客户端，可公开）。
- 流程：`POST https://github.com/login/device/code`（带 client_id + scope）→ 拿到 `user_code` 与 `verification_uri` → APP 展示用户码与「打开浏览器」按钮 → 后台按 `interval` 轮询 `POST https://github.com/login/oauth/access_token` → 授权成功后拿到 access token。
- token 存入 Android Keystore 加密存储（见 §10.1），**绝不明文落盘、绝不写日志**。
- 提供「退出登录」清除本地 token。

**验收标准**：

- 未授权新用户能在 2 分钟内完成登录并看到自己的 GitHub 用户名。
- 断网/用户拒绝授权时有清晰提示，不崩溃。
- token 不出现在 logcat、不出现在任何本地明文文件中（用 `grep` 扫描工程与日志验证）。
- 重启 APP 后保持登录态。

### FR-02 扫描仓库与工作流 — P0

**描述**：登录后扫描账号下自建仓库，以及每个仓库里的工作流。

- 仓库：`GET /user/repos?affiliation=owner&per_page=100&page=N`，分页拉全，按 `updated_at` 倒序显示。
- 工作流：`GET /repos/{owner}/{repo}/actions/workflows?per_page=100`，取 `id`、`name`、`path`、`state`。
- **判断工作流是否可被 dispatch**：GitHub 的工作流列表 API 不直接返回「是否启用 workflow_dispatch」。首版策略：列出全部 active 工作流让用户勾选；当用户尝试对某工作流配置规则时，APP 拉取该工作流文件内容（`GET /repos/{o}/{r}/contents/{path}`）解析 YAML 是否含 `workflow_dispatch`，不含则提示「该工作流未启用手动触发，请先在仓库中添加 `on: workflow_dispatch`」。这一步是 P1，可先不做校验，由 dispatch 失败反馈兜底。

**验收标准**：

- 能列出账号下全部自建仓库（含私有），数量与 GitHub 网页端一致。
- 点开任一仓库能列出其工作流名称与路径。
- 扫描结果缓存在本地（M1 实现用 DataStore + JSON，非 Room——规避 KSP 版本冲突），离线时可浏览上次结果。

### FR-03 勾选纳管对象 — P0

**描述**： workflows 以二级结构勾选（仓库 → 工作流），勾选状态本地持久化。

**验收标准**：

- 勾选后关闭重开 APP，勾选状态保留。
- 支持取消勾选；取消勾选时其已有规则如何处理需明确（默认：保留规则但暂停调度，UI 标灰，见 §6.3.6）。
- 顶部显示「已纳管 N 个工作流 / M 条规则」。

### FR-04 触发规则配置 — P0

**描述**：为每条已勾选的工作流配置触发时间与周期。

支持的周期类型（用户诉求「一次、每30天、每7天、每工作日、每天」全部覆盖）：

| 类型 | 参数 | 说明 |
| --- | --- | --- |
| `ONCE` | 指定日期时间 | 触发一次后自动置为已停用 |
| `DAILY` | 指定时间（HH:mm） | 每天触发 |
| `WEEKDAYS` | 指定时间 | 仅周一至周五触发 |
| `WEEKLY` | 指定时间 + 星期几（可多选） | 每周指定日触发 |
| `EVERY_N_DAYS` | 指定时间 + N 天 + 起始日期 | 每 N 天触发（N=7 即每周，N=30 即每月近似） |

- 时间按**本机时区**解释，但在本地存储时同时保存创建时的时区 ID（便于日后 Handling 时区变更，P2）。
- 规则可启用/停用，可编辑，可删除。
- 每条规则显示「下次触发时间」（由 APP 计算）。

**验收标准**：

- 五种周期类型均能创建、编辑、保存、删除。
- 「下次触发时间」计算正确，跨月/跨年/闰年边界正确（单元测试覆盖）。
- `WEEKDAYS` 在周六周日不触发（单元测试覆盖）。
- `ONCE` 触发后自动停用且不再计算下次时间。

### FR-05 定时触发引擎 — P0

**描述**：按规则在设定时间唤醒设备，等待网络就绪，执行 dispatch。核心机制见 §6.3。

- 使用 `AlarmManager.setExactAndAllowWhileIdle` 注册精确闹钟（每个启用的规则一个）。
- 闹钟通过 `BroadcastReceiver` 接收，接收后立即获取 `PARTIAL_WAKE_LOCK`。
- 等待网络就绪（见 §6.3.4），网络就绪后执行 dispatch。
- 执行完成后释放 WakeLock，并计算/注册下一次闹钟。
- 手机重启后通过 `RECEIVE_BOOT_COMPLETED` 重新注册所有闹钟。
- APP 被系统杀死后，闹钟与注册信息仍在（系统级 AlarmManager 不受 APP 进程死亡影响；但国产 ROM 需白名单，见 FR-08）。

**验收标准**：

- 设定时间到达后，dispatch 请求在期望时间 ±2 分钟内发出（在有网环境下）。
- 夜间断网场景：闹钟触发时无网，网络恢复后能自动完成 dispatch（模拟/真机验证）。
- 手机重启后，已配置的规则仍能按时触发。
- 划掉后台（不清数据、不卸载）后仍能触发（依赖 FR-08 的用户设置）。
- 每次触发都在 Room 写入一条触发日志。

### FR-06 网络等待与超时兜底 — P0

**描述**：触发时若网络不可用，等待网络恢复；避免无限等待。

- 用 `ConnectivityManager` + `NetworkCallback`（或注册 `CONNECTIVITY_ACTION` 监听）检测网络。
- 可配置最长等待时间（默认 5 分钟，用户可调 1/3/5/10/15 分钟）。
- 等待期间保持 WakeLock；超时则记录失败日志、释放 WakeLock，交由 FR-07 补触发机制处理。
- 有网但请求失败（HTTP 非 2xx、超时、DNS 失败）时按指数退避重试最多 3 次（间隔 1s / 4s / 16s），仍失败记录失败。

**验收标准**：

- 飞行模式下到点：APP 等待，打开飞行模式恢复网络后 1 分钟内完成 dispatch。
- 等待超过配置时长：写入失败日志，APP 不卡死、WakeLock 正确释放（用 `dumpsys power` 验证无残留唤醒锁）。
- 网络抖动/请求 5xx：按退避重试，日志里能看到 3 次尝试记录。

### FR-07 补触发与去重 — P0

**描述**：APP 启动/前台时检查遗漏，并避免重复触发。

**补触发**：

- 触发时机：APP 冷启动、从后台回到前台、网络恢复、手机开机。
- 逻辑：遍历启用的规则，对每条计算「最近一个应触发时间点」，判断该时间点之后是否已有成功的触发记录；没有则补触发。
- 判断依据优先级：① 本地 `trigger_log` 中该规则在应触发时间窗口内的 success 记录；②（P1）调 GitHub API `GET /repos/{o}/{r}/actions/workflows/{id}/runs?event=workflow_dispatch&created=>={时间}` 确认是否已有 run，避免本地记录丢失导致重复。

**去重**：

- 每条规则维护 `last_fired_at` 与 `last_run_id`。
- 同一规则在同一「计划时间点」只触发一次（用 `rule_id + planned_at` 做唯一键）。
- 手动触发不受去重限制（用户可随时手动重跑）。
- GitHub 原生 `schedule` 若仍在运行，可能与 APP 触发撞车——现有仓库侧已有去重逻辑（如 Release-Watch 的 `already_pushed_today()`、签到脚本的幂等），APP 侧不重复造轮子，但需在文档里提醒用户「上线 APP 调度后建议关闭或保留原生 schedule 作为兜底，二选一需明确」（见 §15 待决事项）。

**验收标准**：

- 手动删除本地触发日志后重启 APP，能通过 GitHub API 发现已有 run 而不重复触发（P1 场景）。
- 同一计划时间反复触发补漏逻辑，实际 dispatch 只发生一次。
- 手动「立即触发」无视去重，每次都发出请求。

### FR-08 后台权限引导 — P0

**描述**：引导用户开启国产 ROM 必要权限，并提供跳转。

- 在设置页展示权限自检状态： battery optimization（是否忽略电池优化）、自启动（无法程序化检测，提供说明与跳转）、通知权限（Android 13+）。
- 标准跳转：`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（带 package uri）；`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 作为兜底。
- 小米/HyperOS 自启动页：尝试厂商特定 Intent（如 `miui.intent.action.APP_PERM_EDITOR` 附 `extra_pkgname`），失败则跳应用详情页并展示图文指引。
- 首次登录后弹一次引导页，说明「必须开启自启动 + 电池无限制，否则定时不可靠」。

**验收标准**：

- 点击跳转能到达系统设置对应页面（至少到达应用详情页）。
- 引导页文案清晰说明为什么需要这些权限。
- 用户拒绝权限后 APP 仍可用（手动触发正常），只是定时任务可能不可靠，有明确提示。

### FR-09 触发历史与手动操作 — P1

**描述**：展示触发记录，支持筛选、手动触发与重试。

- 列表字段：工作流名、仓库名、计划时间、实际触发时间、状态（待触发/成功/失败/跳过/补触发）、错误信息。
- 每条可点「查看详情」（HTTP 状态码、返回体摘要、GitHub run URL）。
- 顶部统计：今日触发数 / 成功率 / 近 7 日趋势（P2）。
- 「立即触发」按钮。

**验收标准**：

- 历史记录可按工作流、状态、日期筛选。
- 点击成功记录可跳转浏览器打开对应 GitHub Actions run 页面。
- 失败记录显示可读的错误原因（如 `401 token 失效`、`404 工作流不存在`、`网络超时`）。

### FR-10 Token 状态与健康检查 — P1

**描述**：显示当前登录账号、token 权限范围、有效性。

- 显示 `GET /user` 拿到的登录名与头像。
- 启动时轻量检查 token 有效性（`GET /user`），失效则提示重新登录。
- 显示所需 scope（`repo`）是否具备的提示（可从响应头 `x-oauth-scopes` 读取）。

**验收标准**：

- token 被 GitHub 端 revoke 后，APP 启动时能检测并提示重新登录，而不是静默失败。
- 能看到当前 token 的 scope 列表。

---

## 6. 技术架构

### 6.1 总体架构

```
┌──────────────────────────────────────────────┐
│                TickHub (Android APP)          │
│  ┌────────────┐  ┌────────────┐  ┌─────────┐ │
│  │ UI 层      │  │ 调度引擎   │  │ 存储层  │ │
│  │ Compose +  │  │ AlarmMgr + │  │ Room +  │ │
│  │ Miuix      │  │ WakeLock + │  │DataStore│ │
│  │            │  │ NetCallback│  │ +Keystore│ │
│  └─────┬──────┘  └─────┬──────┘  └────┬────┘ │
│        │               │              │      │
│        └───────────────┼──────────────┘      │
│                        │                     │
│              ┌─────────▼─────────┐           │
│              │  GitHub API 客户端 │           │
│              │  (OkHttp/Retrofit) │           │
│              └─────────┬─────────┘           │
└────────────────────────┼─────────────────────┘
                         │ HTTPS
          ┌──────────────▼──────────────┐
          │  api.github.com             │
          │  - Device Flow 授权         │
          │  - 扫描仓库/工作流          │
          │  - POST /dispatches         │
          └──────────────┬──────────────┘
                         │ 触发
          ┌──────────────▼──────────────┐
          │  GitHub Actions 工作流      │
          │  (签到 / 监测 / 通知 ... )   │
          └─────────────────────────────┘
```

**职责边界**：TickHub 只做「时钟 + 触发器 + 管理界面」。业务逻辑（签到、监测、推送）全部留在各仓库的 Actions 里，不搬进 APP。

### 6.2 技术栈与版本矩阵

> ⚠️ 本项目锁定较新的工具链，且 **Miuix 官方标注 experimental、API 可能变化**。以下版本为 2026-11-24 调研得到的建议基线，**首次建仓时必须实际构建验证，验证通过后在 `gradle/libs.versions.toml` 中锁定，不得随意升级**。

| 组件 | 建议版本 | 依据 / 说明 |
| --- | --- | --- |
| Kotlin | 2.4.20 | Miuix 0.9.4 POM 依赖 kotlin-stdlib 2.4.20；与 AGP 9.3.x 兼容 |
| Android Gradle Plugin | 9.3.1 | KMP/Compose 1.12 需 AGP 9.1+；9.3.1 为已验证稳定组合 |
| Gradle Wrapper | 9.7.0 | Kotlin 2.4.20 支持范围 7.6.3–9.7.0；AGP 9.3 最低 9.5.0 |
| JDK | 17 或 21 | AGP 9.x 需 JDK 17+；CI 用 Temurin 21 |
| compileSdk | 37 | Compose 1.12 / AGP 9.x 要求 |
| targetSdk | 36 | Google Play 新应用/更新要求（2026-08-31 起）；不上架也建议对齐 |
| minSdk | 26 | Android 8.0；含 `java.time`，规则计算可直接使用 `LocalDate` 免脱糖 |
| Compose BOM | 2026.09.00 | 对应 Compose UI/foundation/runtime 1.12.x |
| Compose Multiplatform foundation | 1.12.0 | Miuix 0.9.4 POM 直接依赖 |
| Miuix | 0.9.4（**稳定版**） | 最新为 0.9.5-beta03，不用 beta；依赖 `top.yukonga.miuix.kmp:miuix-ui:0.9.4` |
| material3-window-size-class | 1.12.0-alpha03 | Miuix 传递依赖，跟随即可 |
| Room | 2.7.x（以 BOM 对齐） | 本地结构化存储 |
| DataStore Preferences | 1.1.x | 轻量配置（等待时长、周期偏好等） |
| OkHttp | 4.12.x / 5.x | HTTP 客户端；或直接 Retrofit + kotlinx.serialization |
| kotlinx.serialization | 1.8.x | JSON 解析 |
| WorkManager | （可选，见 §6.3.5） | 仅作为闹钟的兜底补充，非主调度 |

**首次构建实测结论（2026-11-24）**：

- `android-actions/setup-android@v3` 在当前 runner 会执行已废弃的 `sdkmanager tools` 并失败 → **已从 CI 移除**，直接用 ubuntu-latest 预装 SDK。
- KSP 插件最新仅到 `2.3.14`（对应 Kotlin 2.3.x），**没有匹配 Kotlin 2.4.20 的 KSP 版本** → 骨架阶段先移除 KSP 与 Room compiler；M2 引入 Room 实体时需重新评估：要么把 Kotlin 降到 2.3.x 配 KSP 2.3.14，要么用 Room 的 KAPT 或其他注解处理器方案。这是一个**待解决的版本冲突**，已记录在风险表。
- **AGP 9.x 内置 Kotlin**：`org.jetbrains.kotlin.android` 插件与 AGP 9 的新 DSL 不兼容，必须移除；Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose` 保留。AGP 9 运行时自带 KGP 2.2.10。官方迁移说明：https://developer.android.com/build/migrate-to-built-in-kotlin 。
- Gradle 脚本中 `buildDir` 在 AGP 9 下已弃用并视为编译错误，改用 `layout.buildDirectory.file(...).get().asFile`；`java.util.Base64` 需要顶部显式 import。
- **Device Flow 切后台网络不可达**（beta6 真机实测发现）：用户点「打开授权页」跳浏览器完成授权后切回 APP，Android 有时在 APP 后台期间收紧网络，轮询一次失败即报「网络不可达」并终止，导致登录失败；小窗化保持前台则成功。修复：`TokenPoll` 加 `NetworkError` 状态，轮询遇网络错误**不终止**、显示「正在重试…请切回 APP」并继续轮询直到 deadline；用户切回前台网络恢复后下一次轮询即成功。
- **Kotlin 字符串插值陷阱**：`"$device.verificationUri"` 会被解析为 `device.toString() + ".verificationUri"`（把整个 data class 打印出来，含机密的 deviceCode）。必须用 `"${device.verificationUri}"`。此 bug 曾把 deviceCode 显示在 UI 上，已修。

**关键风险——纯 Android 项目引用 KMP 库**：Miuix 是 Compose Multiplatform 库，其 POM 依赖 `org.jetbrains.compose.foundation:foundation:1.12.0` 等 JetBrains 坐标，而纯 Android 项目通常用 `androidx.compose.*`。两者在 Android 上最终映射到同一套 AndroidX 库，但 **存在 BOM 冲突与依赖解析歧义的可能**。

- **首次构建必须验证**：Gradle 依赖树中 JetBrains foundation 与 AndroidX Compose 是否解析到同一版本；若有冲突，用 `resolutionStrategy.force` 统一到 BOM 版本。
- **备选方案 A**：把工程改造成 KMP 结构但只启用 `androidMain`（增加复杂度，不推荐）。
- **备选方案 B**：放弃 Miuix，用纯 AndroidX Compose Material3 自绘 MIUI 风格组件（工作量增加但最稳）。
- 决策点在 §15 待决事项中列出，首次构建时由接手人拍板并记录。

**架构模式**：MVVM + 单向数据流（Compose + ViewModel + StateFlow），仓库层（Repository）隔离 API 与本地存储。依赖注入首版可手写容器（Hilt 可选，非必须）。

### 6.3 关键机制设计

#### 6.3.1 OAuth Device Flow 时序

1. APP 生成随机 `code_verifier`（PKCE 可选，Device Flow 本身不强制，但建议加）。
2. `POST https://github.com/login/device/code`，参数：`client_id`、`scope=repo`。
3. 响应：`device_code`、`user_code`、`verification_uri`、`expires_in`、`interval`。
4. APP 显示 `user_code` + 「在浏览器打开 github.com/login/device」按钮（用 `ACTION_VIEW` Intent 打开系统浏览器，不用 WebView——避免 cookie/登录态问题）。
5. 后台按 `interval` 秒轮询 `POST https://github.com/login/oauth/access_token`，参数：`client_id`、`device_code`、`grant_type=urn:ietf:params:oauth:grant-type:device_code`。
6. 轮询响应：
   - `authorization_pending` → 继续睡 `interval` 再轮询。
   - `slow_down` → 轮询间隔 +5s。
   - `expired` / `access_denied` → 终止并提示。
   - `access_token` → 成功，存入 Keystore。
7. 用 token 调 `GET /user` 验证并显示用户名。

**注意**：GitHub 对 Device Flow 的 user code 提交有每小时 50 次限制（每应用），个人使用无压力。GitHub 官方最佳实践更推荐「带 PKCE 的授权码流」而非 Device Flow（因 Device Flow 无重定向 URI，有远程钓鱼风险）；但那需要能接收回调的客户端/后端。对个人自用 APP，Device Flow 的便利 > 风险，采纳，并在 §14 风险中记录。

#### 6.3.2 扫描与纳管

- 首次登录后立即扫描一次仓库与工作流，写入 Room。
- 提供「手动刷新」按钮触发重新扫描。
- 扫描是增量更新：以 `repo.full_name` 与 `workflow.id` 为键 upsert，不删除用户已勾选但暂时扫描不到的项（标记「远端不可见」而非删除）。

#### 6.3.3 调度引擎核心

- 每条启用规则 → 一个 `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, 下次触发时间, PendingIntent)`。
- `PendingIntent` 指向 `DispatchReceiver`（显式组件，`BroadcastReceiver`），Android 8+ 后台限制下需在 Manifest 静态注册。
- `DispatchReceiver.onReceive()`：
  1. 获取 `PARTIAL_WAKE_LOCK`（超时 15 分钟自动释放，防泄漏）。
  2. 启一个前台 `WorkManager` 任务或直接在 receiver 里起协程做网络等待与网络请求（Android 8+ receiver 后台执行受限，**推荐用 `WorkManager` 的一次性加急任务 `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`** 承接实际工作，receiver 只负责唤醒与入队）。
  3. 等待网络 → dispatch → 记日志 → 计算下次时间 → 注册下一个闹钟 → 释放 WakeLock。
- 开机：`BootReceiver` 监听 `BOOT_COMPLETED`，重新为所有启用规则注册闹钟（需 `RECEIVE_BOOT_COMPLETED` 权限；部分 ROM 需自启动权限）。
- 时区变更 / 时间调整（P2）：监听 `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` 重算所有闹钟。

#### 6.3.4 网络等待机制（用户明确要求，必须实现）

伪代码：

```kotlin
suspend fun waitForNetwork(timeoutMs: Long): Boolean {
    if (isNetworkAvailable()) return true
    return suspendCancellableCoroutine { cont ->
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cont.resume(true)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        val timeout = handler.postDelayed({
            cont.resume(false)
        }, timeoutMs)
        cont.invokeOnCancellation {
            handler.removeCallbacks(timeout)
            cm.unregisterNetworkCallback(callback)
        }
    }
}
```

- 等待前确保 WakeLock 已持有（否则 CPU 睡死，回调不触发）。
- `onAvailable` 后建议再延迟 1–2 秒（等网络栈稳定/DNS 就绪）再发请求。
- 超时后不重试整条规则，写失败日志，交给补触发（FR-07）在后续时机处理。
- 超时时长从 DataStore 读取，默认 5 分钟。

#### 6.3.5 WorkManager 的角色

- **不是主时钟**：主时钟是 AlarmManager（精确定时）。
- 用途：承接 receiver 唤醒后的实际网络工作（规避后台执行限制）、网络等待、重试退避、加急执行。
- 不用 PeriodicWorkRequest 做周期调度——它的最小周期 15 分钟且不准时，无法满足「每天 07:17 精确触发」。

#### 6.3.6 规则状态机

```
启用 ──到点──> 等待网络 ──有网──> dispatch ──成功──> 记日志 ──> 计算下次时间 ──> 启用
  │               │                   │
  │               ├─超时─> 失败日志 ──┤
  │               │                   └─失败─> 重试×3 ──> 失败日志
  │               │
  └─停用<──────────┴──────────────────────── (用户停用 / ONCE 完成 / 取消勾选)
```

- 取消勾选工作流 → 其规则状态置 `PAUSED`，不注册闹钟、不参与补触发，但保留配置（重新勾选后恢复）。
- `ONCE` 规则触发成功后状态置 `COMPLETED`，不再调度。

### 6.4 数据模型（Room）

```kotlin
@Entity(tableName = "repos")
data class RepoEntity(
    @PrimaryKey val id: Long,            // GitHub repo id
    val owner: String,
    val name: String,
    val fullName: String,
    val private: Boolean,
    val updatedAt: String,
    val scannedAt: Long,
    val managed: Boolean                 // 是否有任何工作流被勾选
)

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: Long,            // GitHub workflow id
    val repoId: Long,
    val name: String,
    val path: String,
    val state: String,
    val selected: Boolean                // 是否纳管
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workflowId: Long,
    val type: RuleType,                  // ONCE / DAILY / WEEKDAYS / WEEKLY / EVERY_N_DAYS
    val timeOfDay: String,               // "HH:mm"
    val onceDate: String? = null,        // ONCE 用
    val weekdays: String? = null,        // "1,3,5" WEEKLY/WEEKDAYS 用
    val intervalDays: Int? = null,       // EVERY_N_DAYS 用
    val startDate: String? = null,       // EVERY_N_DAYS 起点
    val status: RuleStatus,              // ENABLED / DISABLED / PAUSED / COMPLETED
    val nextFireAt: Long?,
    val lastFiredAt: Long?,
    val lastRunId: Long?,
    val timezoneId: String,
    val createdAt: Long
)

@Entity(tableName = "trigger_log",
        indices = [Index(value = ["ruleId", "plannedAt"], unique = true)])
data class TriggerLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val workflowId: Long,
    val plannedAt: Long,                 // 计划触发时间（去重键的一部分）
    val firedAt: Long?,                  // 实际发出请求时间
    val status: TriggerStatus,           // PENDING / SUCCESS / FAILED / SKIPPED / MAKEUP
    val httpCode: Int?,
    val errorMessage: String?,
    val runUrl: String?,
    val isMakeup: Boolean                // 是否补触发
)
```

DataStore 保存：GitHub token（或放 Keystore，见 §10.1）、网络等待时长、默认规则偏好、是否已看过权限引导。

### 6.5 API 契约（用到的 GitHub REST 端点）

| 用途 | 方法 | 路径 | 关键参数 / 说明 |
| --- | --- | --- | --- |
| 设备码 | POST | `/login/device/code` | `client_id`, `scope=repo` |
| 轮询 token | POST | `/login/oauth/access_token` | `client_id`, `device_code`, `grant_type` |
| 当前用户 | GET | `/user` | 验证 token、取用户名 |
| 列仓库 | GET | `/user/repos` | `affiliation=owner`, `per_page=100`, 分页 |
| 列工作流 | GET | `/repos/{o}/{r}/actions/workflows` | `per_page=100` |
| 读工作流文件 | GET | `/repos/{o}/{r}/contents/{path}` | 判断是否含 `workflow_dispatch`（P1） |
| 触发工作流 | POST | `/repos/{o}/{r}/actions/workflows/{workflow_id}/dispatches` | body `{"ref":"main"}`；成功 204 或 200（新版带 run_url） |
| 查运行记录 | GET | `/repos/{o}/{r}/actions/workflows/{id}/runs` | `event=workflow_dispatch`, `created=>=时间`（P1 补触发校验） |

**两个必须注意的细节**：

1. **中文工作流文件名**：KG-Checkin 的 `签到.yml` 在 URL 路径里需要编码。优先用数字 `workflow_id`（如 `426728277`）而非文件名调用 dispatch，彻底规避编码问题。
2. **token scope**：classic OAuth token 触发**私有**仓库的 dispatch 需要 `repo` scope；若仓库为公开，`public_repo` 可能够用，但为统一简单，申请 `repo`。
3. **限流**：REST API 未认证 60/h、认证 5000/h；本项目调用量极小，无压力。Device Flow 的码提交限制 50/h·app。

---

## 7. UI / UX 需求

### 7.1 设计语言

- 使用 **Miuix** 组件库（小米 HyperOS 风格），主题用 `MiuixTheme` + `ThemeController(ColorSchemeMode.MonetSystem)` 跟随系统壁纸取色；支持浅色/深色。
- 视觉参考：大圆角卡片、分组列表、System UI 风格开关、克制留白、中文排版优先。
- 概念稿见与本文档一同交付的两屏示意图（仓库列表 + 规则设置）。

### 7.2 信息架构

```
登录页（Device Flow：用户码 + 打开浏览器按钮 + 轮询状态）
└── 主界面（底部导航：仓库 / 规则 / 历史 / 我的）
    ├── 仓库页：仓库列表 → 展开工作流 → 勾选纳管
    ├── 规则页：规则列表 → 新建/编辑规则（时间 + 周期）→ 下次触发时间
    ├── 历史页：触发记录 → 筛选 → 详情 → 重试
    └── 我的页：账号信息 / token 状态 / 权限自检 / 网络等待时长 / 退出登录
```

### 7.3 关键交互

- 首次登录成功 → 弹出权限引导页（自启动 + 电池无限制 + 通知权限），可跳过。
- 勾选工作流后 → 引导立即创建第一条规则。
- 规则页顶部显示「网络恢复等待时长」快捷设置。
- 任何失败状态在历史页显著标红，并显示可读原因。

### 7.4 无障碍与兼容

- 文字不小于 14sp，对比度符合 Material 规范。
- 支持系统字体缩放。
- 仅 arm64，UI 无需为 x86 适配（见 §2.1）。

---

## 8. 安全设计

### 8.1 Token 存储

- access token 存入 **Android Keystore**：用 Keystore 生成的 AES 密钥加密后存 DataStore/SharedPreferences，或直接使用 `SecurityLib`（androidx.security:security-crypto）的 `EncryptedSharedPreferences`。
- **禁止**：明文 SharedPreferences、明文文件、logcat、崩溃报告、工程内硬编码。
- 退出登录时清除密文与密钥别名。
- 仓库源码中不得出现真实 token / client_secret / 用户手机号等敏感信息；提交前用密钥扫描自检。

### 8.2 权限最小化

- OAuth 只申请 `repo`（触发私有仓库 dispatch 所需）。不申请 `delete_repo`、`workflow` 等不必要 scope。
- Manifest 权限只申请必需的：`INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`、`POST_NOTIFICATIONS`（Android 13+）。

### 8.3 Client ID 与签名密钥

- OAuth App 的 **Client ID 可公开**（公开客户端），硬编码在 APP 内；**client_secret 不用于 Device Flow，不写入 APP**。
- 签名密钥（keystore）**绝不提交到仓库**，只存 GitHub Actions Secrets + 本地离线备份（见 §11.3）。

### 8.4 网络

- 强制 HTTPS（`api.github.com`）。
- 证书校验走系统默认，不做信任所有证书的绕过。
- 请求超时：连接 10s、读取 30s（网络等待由 FR-06 单独控制）。

---

## 9. 测试策略

| 层级 | 内容 | 工具 |
| --- | --- | --- |
| 单元测试 | 规则下次时间计算（五种周期、跨月/跨年/闰年、时区）、状态机、去重键 | JUnit 5 + Truth |
| 本地集成测试 | Room 迁移、Repository 与假 API 的交互 | Robolectric（可选） |
| API 契约测试 | Device Flow 轮询状态处理、dispatch 请求体、错误码映射 | MockWebServer |
| UI 测试 | 关键流程冒烟（登录→勾选→建规则） | Compose Test（P1） |
| 真机测试 | **必须**：定时触发精度、夜间断网恢复、开机重启、划后台、国产 ROM 白名单 | 小米/HyperOS 真机，连续观察 ≥3 天 |
| CI 验证 | 编译通过、单测通过、`assembleRelease` 产出签名 APK | GitHub Actions |

**真机验收清单**（移交时逐项打勾）：

- [x] 设定 07:17，连续 3 天在 07:17±2min 触发（2026-11-25/26 实测，夜间到次日准时、无睡死）。
- [x] 夜间飞行模式 → 次日恢复网络后自动补触发（机制已实现，真机夜间场景通过）。
- [x] 手机重启后规则仍生效（BootReceiver 重注册，真机验证）。
- [x] 划掉后台不清理数据，规则仍生效（需自启动 + 电池无限制，真机验证）。
- [x] 手动触发、重试、删除规则均正常。

---

## 10. 构建与发布方案

### 10.1 构建方案选型判断

用户给出两个选择：本机编译 vs Actions CI 构建。**本项目选择「Actions CI 构建为主，本地 Android Studio 为调试补充」**，理由：

| 维度 | 本机编译 | Actions CI 构建（推荐） |
| --- | --- | --- |
| 当前环境 | 实测无 JDK / Gradle / Android SDK，需从零安装数 GB 工具链 | ubuntu-latest 自带 JDK 与 Android SDK，几行 setup 即可 |
| 与项目目标契合度 | 低：项目目标就是发布在 GitHub | 高：CI 构建 → Release 上传 APK 是天然链路 |
| 签名密钥安全 | 密钥落在本机，需额外保管 | 密钥存 Secrets，CI 运行时解码，不落仓库 |
| 可复现性 | 依赖个人机器状态 | 固定版本矩阵，任何一次构建可追溯 |
| 移交友好 | 接手人需重装环境 | 接手人只需 push 代码，CI 出包 |

**本地仅用于**：编码、Compose Preview、单元测试、真机调试。发布产物一律由 CI 产出。

### 10.2 CI 工作流设计

文件：`.github/workflows/build-release.yml`

触发条件：`push tags: ['v*']` 与 `workflow_dispatch`（手动）。

**发布节奏（2026-11-24 决策 D3）**：前期走 CI 发**测试版 Release**，tag 形如 `v0.1.0-beta1`；功能与真机验收稳定后再发正式版 `v1.0.0`。测试版在 Release 描述中标注「测试版，仅供作者与受邀者使用」。

步骤：

1. `actions/checkout@v4`
2. `actions/setup-java@v4`：Temurin JDK 21
3. `android-actions/setup-android@v3`（或 ubuntu-latest 自带 SDK，显式 setup 更稳）
4. `gradle/actions/setup-gradle@v4`（缓存依赖）
5. 从 Secrets 解码 keystore：`echo "$KEYSTORE_BASE64" | base64 -d > app/tickhub-release.jks`
6. `./gradlew assembleRelease -PversionName=... -PversionCode=...`（版本号从 tag 解析）
7. `softprops/action-gh-release@v2`：上传 `app/build/outputs/apk/release/app-release.apk` 到对应 tag 的 Release，重命名 `TickHub-vX.Y.Z.apk`
8. 清理临时 keystore 文件（`rm -f app/tickhub-release.jks`，虽 CI 临时环境也应清理）

权限：`permissions: contents: write`（Release 创建需要）。

### 10.3 签名方案（保证后续可覆盖安装）

**覆盖安装三要素**：`applicationId` 固定 + 签名证书相同 + `versionCode` 递增。三者任一变化都会导致无法覆盖安装（被视为不同应用）。

**密钥生成（建仓后第一步，在有 JDK 的环境执行）**：

```bash
keytool -genkeypair -v \
  -keystore tickhub-release.jks \
  -alias tickhub \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storepass <STORE_PASSWORD> \
  -keypass <KEY_PASSWORD> \
  -dname "CN=TickHub, OU=Personal, O=LucasLu, L=, ST=, C=CN"
```

- `validity 10000`（约 27 年）确保长期不用换证书。
- 生成的 jks 与密码**必须离线备份**（如密码管理器 + 加密云盘双份），一旦丢失将无法为已安装用户推送更新。

**Secrets 清单**（仓库 Settings → Secrets and variables → Actions）：

| Secret 名 | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 tickhub-release.jks` 的输出 |
| `KEYSTORE_PASSWORD` | storepass |
| `KEY_PASSWORD` | keypass |
| `KEY_ALIAS` | `tickhub` |

**Gradle 配置**（`app/build.gradle.kts`）：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_FILE") ?: "tickhub-release.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("KEY_ALIAS") ?: "tickhub"
        keyPassword = System.getenv("KEY_PASSWORD") ?: ""
    }
}
buildTypes {
    getByName("release") {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = false   // 首版不混淆，便于移交排查；稳定后再开
    }
}
```

**版本策略**：

- `versionCode`：整数，每次发布 +1，可从 tag 或 `github.run_number` 生成。
- `versionName`：语义化 `MAJOR.MINOR.PATCH`，与 git tag `vX.Y.Z` 对应。
- 每次 Release 的 release notes 简述变更。

> 注意：本机无 JDK，**密钥生成这一步需在有 JDK 的环境完成**（用户装 Android Studio 后自带，或 CI 外部一次性生成后上传 Secrets）。这是建仓后的第一件事，未完成前 CI 只能出未签名包。

---

## 11. GitHub 仓库规划

### 11.1 仓库基本信息

- 名称：`TickHub`
-  owner：`LucasLu-97`
- 可见性：**公开**（2026-11-24 决策 D1）。无密钥泄露风险——Client ID 本就可公开，签名密钥与 token 均不在仓库内。
- 默认分支：`main`
- 描述：`Android app that turns your phone into a reliable GitHub Actions scheduler.`

### 11.2 目录结构

```
TickHub/
├── .github/
│   ├── workflows/build-release.yml
│   ├── ISSUE_TEMPLATE/
│   └── PULL_REQUEST_TEMPLATE.md
├── app/
│   ├── src/main/
│   │   ├── java/com/lucaslu/tickhub/
│   │   │   ├── TickHubApp.kt
│   │   │   ├── data/          # Room / DataStore / Keystore
│   │   │   ├── network/       # GitHub API 客户端、Device Flow
│   │   │   ├── scheduler/     # Alarm / Receiver / WorkManager / 网络等待
│   │   │   ├── ui/            # Compose 页面、Miuix 主题
│   │   │   ├── domain/        # 规则计算、状态机、去重逻辑
│   │   │   └── di/            # 依赖注入容器
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
│   ├── libs.versions.toml     # 版本集中管理（锁定 §6.2）
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .gitignore                 # 必须忽略 *.jks、local.properties、密钥文件
├── README.md
└── docs/
    └── (本文档的副本或链接)
```

### 11.3 分支与协作

- `main` 保护：需 PR + CI 通过才能合并。
- 功能分支：`feat/xxx`、`fix/xxx`。
- 发版：打 tag `vX.Y.Z` → 触发 CI 出包。

### 11.4 .gitignore 关键项

```
*.jks
*.keystore
local.properties
/app/release/
*.log
```

### 11.5 建仓清单（建仓时逐项完成）

- [ ] 创建仓库（可见性按 §15 决定）
- [ ] 开启 Actions
- [ ] 配置 4 个签名 Secrets（§10.3）
- [ ] 配置 Actions 权限 `contents: write`（Settings → Actions → General → Workflow permissions）
- [ ] 添加 `CODEOWNERS`（可选）
- [ ] 分支保护规则
- [ ] 首次本地构建验证 Miuix/Compose 版本兼容（§6.2）

---

## 12. 里程碑与任务拆解

| 阶段 | 内容 | 产出 | 依赖 |
| --- | --- | --- | --- |
| M0 准备 | 建仓、签名密钥、Secrets、版本矩阵验证、依赖兼容验证 | 可构建的空工程 + CI 出未签名包 | 无 |
| M1 登录与扫描 | Device Flow、token 安全存储、仓库/工作流扫描 | 能登录并列出仓库工作流 | M0 |
| M2 管理与规则 | 勾选、规则 CRUD、下次时间计算、Room 落库 | 完成 §4 的 FR-02/03/04 | M1 |
| M3 调度引擎 | AlarmManager、WakeLock、网络等待、dispatch、补触发、开机重注册 | 完成 FR-05/06/07 | M2 |
| M4 UI 完善 | Miuix 主题、权限引导、历史页、设置页 | 完成 FR-08/09/10 | M3 |
| M5 真机验收 | 连续 3 天真机测试、国产 ROM 适配、修复 | 真机验收清单全勾 | M4 |
| M6 发布 | 签名 APK、Release、文档定稿 | v1.0.0 Release | M5 |

---

## 13. 移交指南（接手人从这里开始）

1. **读文档**：先读 §1–§6，理解背景、范围、架构与关键机制。
2. **确认待决事项**：把 §15 的开放问题逐项与项目所有者确认并记录结论。
3. **建仓与环境**：按 §11.5 清单建仓；本地装 Android Studio（自带 JDK 与 SDK），Clone 工程。
4. **版本验证第一**：按 §6.2 实际构建一次，确认 Miuix 与 AndroidX Compose 无冲突；有问题按备选方案处理并更新文档。
5. **生成签名密钥**：按 §10.3 生成并配置 Secrets；这一步不做完，CI 无法出可覆盖安装的包。
6. **按里程碑推进**：从 M1 开始，每个阶段完成后对照验收标准自检。
7. **真机测试不可省**：本项目核心是定时可靠性，模拟器与单元测试无法替代真机连续观察。
8. **更新本文档**：任何设计变更、版本调整、踩坑结论都回写本文档，保持它始终是当前事实的来源。

**已知环境事实（2026-11-24 实测）**：

- 当前工作机 `D:\VibeCoding\Vibe-WB` 所在的 Agent 环境：**无 java、无 gradle、无 ANDROID_HOME / ANDROID_SDK_ROOT、无 Android Studio 目录**。
- 因此**不要在该环境尝试本地编译**；编译一律走 GitHub Actions。该环境只用于写码、改文档、调 GitHub REST API。
- 用户级 GitHub PAT（classic，含 repo/workflow/delete_repo 权限）已长期保留、不轮换；建仓与配置 Secrets 可用它调 REST API，但**不得写入仓库文件、不得回显在日志或回复中**。

---

## 14. 风险登记册

| 编号 | 风险 | 影响 | 缓解措施 |
| --- | --- | --- | --- |
| R1 | GitHub `schedule` 仍开启，与 APP 触发撞车导致重复推送 | 用户收到重复通知 / 重复签到 | 上线 APP 后明确关闭原生 schedule 或保留作兜底（需决策，§15）；利用现有仓库侧去重逻辑 |
| R2 | Device Flow 的远程钓鱼风险（无重定向 URI 绑定） | 理论上攻击者可用同一 Client ID 诱导授权 | 个人自用、Client ID 可公开、无敏感数据回传；在文档记录，UI 提示用户只在官方 GitHub 页面输入设备码 |
| R3 | OAuth `repo` scope 权限过大 | token 泄露可读写全部私有仓库 | Keystore 加密、不写日志、可随时 revoke、APP 不上传任何数据；未来可评估 GitHub App 细粒度方案 |
| R4 | Miuix 为 experimental，API 可能变化 | 升级后编译失败 | 锁版本，升级前读 changelog；保留备选方案 B（自绘 MIUI 风格） |
| R5 | KMP 库与纯 Android Compose 依赖冲突 | 首次构建失败 | §6.2 的依赖树验证 + `resolutionStrategy.force` + 备选方案 |
| R6 | 国产 ROM 自启动权限被系统重置 | 系统升级后定时失效 | 权限自检页 + 每次大版本升级后提醒用户复查 + 补触发兜底 |
| R7 | 签名密钥丢失 | 已安装用户无法覆盖更新 | 离线双份备份，写入移交检查清单 |
| R8 | 手机长期关机/无网 | 该时段无触发 | 补触发机制 + 上游仓库 schedule 可作最后兜底（§15） |
| R9 | GitHub API 变更（如 dispatch 端点权限调整） | 触发失效 | API 调用集中封装，错误码可读提示，关注 GitHub changelog |

---

## 15. 待决事项（移交时必须逐项闭环）

| # | 问题 | 建议 | 决策人 |
| --- | --- | --- | --- |
| D1 | 仓库公开还是私有？ | **已决：公开**（2026-11-24） | 项目所有者 |
| D2 | 上线 APP 调度后，GitHub 原生 `schedule` 是关闭还是保留兜底？ | **已闭环：原生 schedule 由仓库侧自动化修复关闭**（2026-11-26），APP 侧不重复实现；避免重复执行，保留 `workflow_dispatch` | 项目所有者 |
| D3 | 发布节奏 | **已决：前期 CI 发测试版**（`v0.x.x-betaN`），稳定后发正式版 | 项目所有者 |
| D4 | 网络等待默认时长？ | 沿用建议：5 分钟，可在设置里调（未特别说明，按建议执行） | 项目所有者 |
| D5 | 是否做 Material3 备选 UI？ | 按建议：首次构建验证 Miuix 后再定；冲突才启用备选 B | 接手人 |
| D6 | 是否引入 Hilt / 混淆？ | 按建议：首版都不引入，降低移交复杂度；稳定后再评估 | 接手人 |
| D7 | `WEEKLY` / `ONCE` 是否首版就要？ | 按建议：`ONCE`/`DAILY`/`WEEKDAYS`/`EVERY_N_DAYS` 首版做，`WEEKLY` 可延后 | 项目所有者 |
| D8 | 是否需要通知用户「触发成功」？ | 按建议：不需要——沿用 WxPusher 业务结果推送；APP 只在失败时考虑本地通知 | 项目所有者 |

---

## 16. 附录：参考链接

- GitHub: Authorizing OAuth Apps（Device Flow）：https://docs.github.com/zh/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- GitHub: OAuth App 最佳实践：https://docs.github.com/zh/apps/oauth-apps/building-oauth-apps/best-practices-for-creating-an-oauth-app
- GitHub REST: Create a workflow dispatch event：https://docs.github.com/en/rest/actions/workflows
- Miuix 组件库：https://github.com/compose-miuix-ui/miuix
- Android WorkManager 深入解析（Doze / 国产 ROM）：https://engineering.zooz.com/@mayank1807.singh/workmanager-deep-dive-system-architecture-doze-mode-and-production-challenges-06bf2267a032
- GitHub Actions schedule 可靠性讨论：https://github.com/orgs/community/discussions/213425

---

## 19. 法定节假日数据（M5）

「法定工作日 / 法定节假日」周期使用中国国务院发布的年度放假调休安排。数据源：`NateScarlet/holiday-cn` 的年度 JSON（每条含国务院通知原文链接）。

- **数据格式**：`days[]` 中 `isOffDay=true` 为放假日，`isOffDay=false` 为调休补班日（周末但要上班）。
- **计算规则**：法定工作日＝周一至周五、排除放假日、加上调休补班日；法定节假日＝`isOffDay=true` 的日子（不含普通周末）。
- **离线可用**：APP 内置 2026 年完整数据；启动时联网尝试拉取当前年份最新数据并缓存，2027 年国务院发布后无需发版。
- **省流量**：每年只联网拉取一次（`KEY_HOLIDAY_FETCHED_FOR` 记录已成功拉取的年份），当年已有数据时启动不再重复请求。启动时同时尝试拉「当年 + 次年」两个年份（次年通知通常 12 月上半月发布，未发布则失败后下次再试），多年数据按年份 key 合并存储、互不冲突。Android 系统无公开的"中国法定节假日"标准 API，各厂商日历数据格式不统一、不可靠，故不采用。

## 22. 卡片交互与统一震感（M14）

**卡片交互**（仓库/规则/历史三页统一）：
- **右滑**：卡片右移露出左侧选择框并选中（卡片本身不变色），进入多选模式；右上角出现「全选/删除/取消」。
- **长按拖拽排序**：长按（震动）后卡片抬起加阴影，随手指移动；跨过相邻项即交换位置，松手提交并持久化顺序。规则页仓库分组顺序同步仓库页顺序，同仓库多卡可组内排序。
- 顺序存于 DataStore（`order_repos`/`order_rules`/`order_logs`）。
- **修正（beta19）**：拖拽时隐藏背景选择框/删除图标（避免留在原地）；右滑选择与左滑删除状态互斥（选择态下左滑只能复位，反之亦然），复位时同步清除多选；列表项用 `Modifier.animateItem()` 实现平滑让位动效。
- **规则页**：仓库分组顺序不可拖拽，严格同步仓库页保存的顺序；同仓库多卡可组内拖拽排序（ReorderController 的 `groupOf` 限制交换范围在本组内）。
- **历史页**：按时间线排序，不支持长按拖拽（仅保留长按时卡片变暗的按压反馈）。
- **组内硬边界（beta23/24）**：规则页每个仓库组用独立的拖拽容器，卡片物理上无法被拖出本组，彻底消除越界时的"掉落再跟随"动效；单卡组不给 dragHandle。beta24 修正：容器从固定高度的 LazyColumn 改为库的 **`ReorderableColumn`**（普通 Column，高度 wrap content 自适应），修复卡片被下一组遮挡的问题。仓库页单列表内库原生限制在列表边界内。
- **真空气墙（beta28）**：库拖拽位移是 internal 无法 clamp，改用 `Modifier.clipToBounds()` 裁剪列表容器——浮起的卡片越出容器边界的部分被裁掉，视觉上被"墙"挡住。仓库页把扫描按钮与"已纳管xxx"文字移出 LazyColumn 固定，卡片列表单独 clip，墙顶正好在"已纳管"文字下方；规则页每组 ReorderableColumn 加 clip。beta27 组名消失是因为用 Box 叠加导致，改回 Column 纵向排列恢复。onMove 索引去掉 header 的 -1 偏移。
- **规则页回归库原生滚动（beta29）**：手动 `scrollState.scrollTo` 滚动外层页面会被库误判为手指移动、重算拖拽位移导致卡片"掉下去"。彻底删除手动边缘滚动。
- **最终形态（beta30，用户明确目标）**：规则页单个 LazyColumn 把**所有仓库组全部平铺**（header + 卡片都在一个列表）；拖动某组卡片时把其他组卡片 `ReorderableItem(enabled=false)`（库源码：enabled=false 的 item 不进 reorderableKeys，不会被当作交换目标和滚动终点），从而库层面锁死组边界——卡到达本组第一行/最后一行后库停止滚动，但整页仍是同一个列表自然上下移动。onMove 直接交换（库预测正确无弹跳）。**震动：放下时 1 次**（onDragStarted/onDragStopped 各 tick 一次，onMove 不 tick）。弃用分组独立滚动窗口（用户要求全平铺）。
- **组边界精确停滚（beta31）**：库 scroller 只按整个列表边界停（源码 Scroller.kt 的 canScroll + maxScrollDistance），不认组边界。禁用库 scroller（`scrollThreshold=0.dp`），自实现边缘滚动：拖拽期间手指在顶/底 96dp 内时 `lazyListState.dispatchRawDelta` 滚动列表（库 draggingItemOffset 公式 = 手指位移+(初始位置-当前位置)，会自动补偿滚动，卡片稳跟手指不掉落）；每滚过一个卡片高度 55% 就把被拖卡片和组内相邻卡交换（哒哒哒）；到达本组第一行/最后一行 gi 到边界即停止滚动，即使手指仍在顶底栏。
- **回退 beta24（beta32）**：beta25–31 的边缘滚动改造越做越差，用户明确要求回退。从 GitHub commit `1a1166e`（beta24）取回 RulesScreen.kt / ReposScreen.kt / SwipeableCard.kt 三个文件完全恢复（每组独立 ReorderableColumn 原生拖动、组名正常、无手动滚动、无 clipToBounds 空气墙）；仅保留一处后续修复：去掉 onDragStopped 的重复震动，放下只震 1 次（onSettle 那次）。数字等宽字体在 Theme.kt 未动，保留。
- **震动修正（beta33）**：仓库页 reorderState 的 onMove 里补回 `haptic()`（拖过的卡片越过另一张时 tick）；规则页落下震动从 `onSettle`（库落位动画完成后才回调，延迟明显）移到 `onDragStopped`（松手瞬间即时），仍只震 1 次。
- **设置页底栏遮挡（beta34）**：底部导航栏是悬浮在内容上的（MainActivity 里 Box align BottomCenter），不占布局空间，Scaffold 的 contentPadding 不含它，导致设置页滚到底时"退出登录"被导航栏盖住。设置页滚动 Column 底部 padding 16→88dp。
- **边缘自动滚动 + 空气墙（beta25/26/27）**：组内卡片超出一页时，拖到屏幕上下边缘会自动滚动外层列表。ReorderableColumn 无原生边缘滚动，故外层 Column 用 `awaitPointerEvent(Initial)` 观察手指 Y（不消费事件），拖拽期间协程每 16ms 滚动。beta26 修正坐标基准绑固定视口。beta27 加**空气墙**：用 `onGloballyPositioned` 记录每组在滚动内容中的 top/bottom，拖拽时只在本组范围内滚动（组名 header 触顶/组底触底即停），不会把整个页面滚走；卡片本身由库的独立组容器限制不能跨组。数字用等宽字形：主题 typography 全体加 `fontFeatureSettings="tnum"`（tabular numbers，仅数字等宽，中文不受影响）。
- **新增仓库**：扫描到的新仓库追加到列表末尾。

**拖拽改用开源库（beta20）**：弃用自研手势，接入 `sh.calvin.reorderable:reorderable-android:3.1.0`（Calvin-LL/Reorderable）。用 `rememberReorderableLazyListState` + `ReorderableItem` + `Modifier.longPressDraggableHandle`（成员扩展，在 ReorderableItem scope 内用），库负责跟手位移、边缘滚动、`animateItem` 让位动效。仓库/规则页可拖拽，历史页不接拖拽。规则页通过 slots（header+rule id）换算索引，拖拽仅在本仓库组内生效，单卡分组不可拖。顺序缓存提升到 ViewModel 的 `orders` StateFlow，切 tab 不再重排闪烁。

**统一震感**：全 app 所有按钮/开关/滑动/删除/拨轮/长按统一走 `Haptics.tick`，用系统 `VibrationEffect.EFFECT_TICK`（MIUI 式清脆"哒"），低版本回退短 oneShot；设置页「触感反馈」总开关。

## 21. 真实执行时长与完成通知（M9）

**问题**：`POST /dispatches` 返回 204 仅代表 GitHub 接受了请求，不代表工作流跑完。此前记录的"耗时"只是 HTTP 往返的几秒，不是真实执行时长（实测任务常需数分钟）。

**方案**：dispatch 成功后：
1. 调 `GET /repos/{owner}/{repo}/actions/workflows/{id}/runs?per_page=5` 找到本次新建的 run（取最新一条），把 workflowId→runId 存进历史日志。
2. 由 `RunTrackerWorker`（WorkManager 一次性任务）每 5 秒轻量查询一次 run 的 `status` / `conclusion`，直到 `status=completed` 或达 144 次（约 12 分钟）上限。
3. 用 run 的 `run_started_at` 到 `updated_at` 算真实时长，回填历史日志 `durationMs`、最终状态（success/failure），并发通知栏通知。
4. **资源评估**：每次查询是一次短暂 Worker 唤醒（间隔 5 秒），**不是**前台服务或常驻轮询，不持 WakeLock。轮询在任务 `completed` 后立即停止（典型 1-3 分钟约 12-36 次请求），12 分钟 / 144 次仅为长任务兜底上限；绝对耗电极小且完全有界。5 秒间隔使完成通知延迟 ≤5 秒，及时性明显优于 30 秒。设置页「执行结果与通知」开关可整体关闭（默认开）。

历史卡片在追踪完成前显示「执行中」并实时递增计时（从 firedAt 起每秒刷新），完成后显示真实耗时与成功/失败。**规则页转圈数据源 `runningExecutions` 合并了 Worker 运行窗口与所有 `tracking=true` 的日志**，所以转圈和计时在整个真实执行期间（数分钟）持续显示，不会在 dispatch 的几秒后消失。

**底栏设计**：底栏白色 surface 通过外层 Box 延伸到系统手势条区域（消除灰条），顶部加一条淡色分隔线，避免白色历史卡片滑到底时与白底栏融为一体；列表底部留白加大到 96dp 确保最后一项不被遮挡。

## 20. 多时段触发与执行状态（M7）

- **多时段**：一条规则可配置多个触发时间（时间1/2/3…，拨轮以弹窗形式弹出不占页面），`RuleEngine.computeNextFire` 对每时段分别求下次触发并取最早；跨天自动回绕到次日最早时段。
- **按仓库拆分**：新建页先选仓库再展开该仓库纳管工作流；跨仓库多选工作流保存时自动拆成各自仓库的独立规则卡片，一条规则只含同仓库工作流。
- **执行状态监测**：Worker 执行时在 DataStore 标记 running（ruleId→startAt），结束移除；规则页顶部显示转圈、卡片显示已持续时长（mm:ss），历史卡片显示本次耗时。完成一次触发后才写历史。仅在 APP 前台刷新时长（协程每秒 tick），不新增后台服务/轮询；设置页可开关（默认开）。
- **无数据时降级**：法定节假日周期在无数据时不计算（返回 null），避免日期出错；法定工作日降级为周一至周五。
- 2026 数据已核对来源为国务院通知（gov.cn）。

## 17. 调试日志（真机排障用）

M3.5 起 APP 内置滚动调试日志（`util/AppLogger.kt`），写到应用私有目录 `filesDir/tickhub-debug.log`，保留最近 800 行。记录：闹钟注册/取消（含设定时间与 `canScheduleExactAlarms` 状态）、闹钟触发（含相对计划时间的延迟秒数）、Worker 启动/结束/异常、网络等待开始与结果、dispatch 每次尝试的 HTTP 码与错误、补触发扫描结果、登录各阶段。

**绝不记录** access token、device code、设备码本身（只记非敏感元数据如过期时间、用户名）。

导出入口在「日志」页：刷新 / 复制全部（剪贴板）/ 分享日志文件（FileProvider，`${applicationId}.fileprovider`）/ 清空。真机测试出问题时，用户点「复制全部」把日志发回即可分析，无需连 adb。

## 16. 登录方式：开箱即用的 Device Flow（无需用户预配置）

### 17.1 设计原则

小白用户应能**装完 APP 直接点登录**，不应要求他们先去网页注册 OAuth App、再导入 Client ID。Client ID 按 OAuth 2.0 规范本就是**公开标识**（不是密钥），内置在客户端完全合规、无安全风险。

### 17.2 当前实现（默认）

APP 内置了 **GitHub CLI 的官方公开 Client ID**（`178c6fc778ccc68e1d6a`）作为默认值——这是社区广泛复用的公开客户端标识，GitHub 官方 CLI 与大量第三方工具都用它做 Device Flow。

- 用户流程：安装 → 点「用 GitHub 登录」→ APP 显示设备码（带「复制设备码」按钮）→ 点「打开 GitHub 授权页面」→ 在浏览器粘贴设备码并授权 → APP 自动轮询到 token 并进入仓库列表。
- **授权页显示的应用名是「GitHub CLI」而不是 TickHub**，这是正常的（因为复用了公开客户端 ID）。登录页已有提示文案说明这一点，避免用户困惑。
- scope 写死 `repo`（触发私有仓库 dispatch 所需）。
- 敏感数据只有 access token，存 Android Keystore（见 §8.1）。

### 17.3 高级覆盖（可选）

如果将来要换成 TickHub 专属 OAuth App，可通过构建参数覆盖默认值：

- **CI**：仓库 Actions Variable `GITHUB_CLIENT_ID`（非 Secret），`build-release.yml` 已带 `-PgithubClientId="${{ vars.GITHUB_CLIENT_ID }}"`。
- **本地**：`-PgithubClientId=XXX` 或 `gradle.properties` 里加 `githubClientId=XXX`。

> 换专属 OAuth App 的好处仅是授权页显示 TickHub 名字；功能无差别。注册步骤见旧版本文档（Settings → Developer settings → OAuth Apps → New OAuth App，callback 填 `http://localhost`）。

### 17.4 验证

安装后打开 APP，登录页直接显示「用 GitHub 登录」按钮（无红框）。点击后出现设备码 → 复制 → 打开授权页粘贴 → 授权完成自动进入仓库列表。

---

## 23. 收尾优化与定稿（v1.0.0）

**真机验收结论**：2026-11-25/26 连续实测，夜间到次日准时触发、无睡死；原生 `schedule` 已由仓库侧自动化关闭，APP 侧不再重复实现。§9 真机清单全部勾选。

**架构复核**：现有 Kotlin 2.4 + Jetpack Compose + Coroutines + WorkManager + AlarmManager + DataStore 已是 Android 原生最先进且稳定的方案；换成 Flutter/RN 会提高资源占用并降低后台可靠性，故**不重构语言/架构**。代码约 4800 行，分层为 data → domain → network → repository → scheduler → ui，可读性良好。

**低资源占用优化（beta35）**：
- Release 曾启用 R8 shrink（`-dontobfuscate` 只裁剪不混淆），APK 14.2MB → 2.03MB；**但 v1.0.0/v1.0.1 真机卸载重装后启动瞬间闪退，判定 R8 裁剪了运行时必需类（无真机日志确认具体类）。v1.0.2 起关闭 R8（isMinifyEnabled=false），APK 回到约 14MB。教训：稳定性优先于体积，R8 上线前必须真机回归。** 保留删除冗余依赖的优化。
- 删除零使用依赖：Retrofit、retrofit-kotlin-serialization、okhttp-logging。
- `RunTrackerWorker` 结果轮询：beta35 曾 5s→30s 省电，v1.0.1 应及时性需求**改回 5s**（MAX_POLLS 144，覆盖 12 分钟）。评估：轮询在任务完成后立即停止，典型 12-36 次请求即结束，绝对开销小且有界；5s 使完成通知延迟 ≤5 秒。
- 头像仅内存缓存、日志滚动 800 行（约 100KB），无磁盘缓存膨胀；80MB 存储主因是 ART 编译产物，已随 APK 瘦身下降。

**仓库介绍**：description 中英双语（含 GitHub Actions scheduler / Kotlin / Compose 等英文关键词）；README 默认中文并切换英文版（`README_EN.md`）。

**登录体验修正（v1.0.3）**：① 请求设备码按钮的转圈只用 `height(18.dp)` 导致宽度测量异常"圆圈乱飞"，改 `size(18.dp)`；设备卡片状态文本旁加常量程转圈明确"正在等待"。② Device Flow 切浏览器授权再回来时，后台网络收紧期的轮询文案原为"网络不可达…请切回本 APP"，用户已切回却看到此提示会误判失败退出；改为中性的"正在等待授权完成，登录会自动继续，请稍候…"，不提网络错误/重试次数（仅日志保留），仅真正失败（过期/拒绝/超时）才用 error 提示。

## 24. v1.1.0：双语 / 节假日开关 / 耗时修复

**全界面中英双语（APP 内独立切换）**：`util/L10n.kt` 双语字典（key 为中文原文，value 为英文），语言偏好存独立 DataStore（`tickhub_l10n`），不跟随系统语言。Composable 用 `t(key)`（随 StateFlow 重组实时切换），非 Composable（ViewModel/Worker/通知）用 `L10n.tNow(key)`。设置页加「语言」卡片（中文/English），登录首屏也加切换按钮，避免英文用户首屏看不懂。约 139 处字符串已本地化，字典覆盖校验无缺失。顶层 `TYPE_LABELS` 等存中文 key、使用时再 `t()`（Composable 上下文限制）；lambda/普通函数内用 `tNow`。

**中国法定节假日开关（默认关闭）**：`AppRepository` 加 `holidayEnabled` 偏好（默认 false）。MainActivity 仅在开启时才调用 `refreshHolidays()`；开启瞬间立即拉取，关闭则停止刷新。`HolidayCalendar.refresh` 已有缓存机制：拉取当年+次年（12 月提前拉次年）、按年份 key 记录已拉取、本地有缓存不重复拉取。设置页加开关卡片，说明"仅中国用户需要"；规则编辑页周期区加提示"法定工作日/节假日仅中国用户可用，非中国地区不支持此周期"。

**执行耗时修复**：DispatchEngine 原在 dispatch 后立即 `listWorkflowRuns` 查 runId，GitHub 常常还没创建 run，导致 tracking 从未启动、历史卡片无耗时。改为 dispatch 后直接 `repository.markTracking(logId)` + 启动 `RunTrackerWorker`，由 Worker 在 20 秒后自行查找 run 并追踪。历史卡片 tracking 态显示纯"耗时 mm:ss"实时递增文本（`ElapsedText` 加 prefix），无转圈。

**时区确认**：RuleEngine 全程用 `LocalDateTime` + `ZoneId.systemDefault()`，存 epoch millis 绝对时间，AlarmManager 用 `RTC_WAKEUP` 按 epoch 触发，显示时再转回本机时区。国外用户按本机时区解释规则时间，**无硬编码北京时间**，无需改动。

**i18n 修正（v1.1.1）**：① 状态文本（登录 status/error、仓库扫描 message/error）改为 ViewModel 存**中文 key**、UI 显示时 `L10n.tNow()` 实时翻译——修掉"生成时固化翻译导致切换语言后状态不跟随"的 bug（用户看到 "enter the code" 只有英文即此因）。② 补漏译：仓库页确认弹窗 `将立即触发工作流「…」`、卡片 `N 工作流`、历史页 `最近 N 条记录`（动态字符串脚本原先跳过）。③ 英文翻译精简：周期 chip `法定工作日→Weekdays`/`法定节假日→Holidays`、按钮 `立即触发→Trigger`/`扫描仓库与工作流→Scan repos`/`打开 GitHub 授权页面→Open GitHub` 等，长说明句全部缩短，避免换行导致按钮变高。④ 语言卡片移到设置页最底部（退出登录上方）。默认语言仍为中文。

## 25. v1.2.0 全面加固基线

用户要求"做到极致"，对所有审查发现项系统修复：

1. **重试/补触发去重统一**：补触发从"排除 FAILED"改为"任何日志都算已尝试"，避免重试闹钟与补触发重复 dispatch。统一 plannedAt 口径——闹钟触发时间用 effective（含漂移），但日志/去重键用 base（nextFireAt），新增 `AlarmScheduler.scheduleWithSlot(rule, triggerAt, plannedAt)`；saveRule/setRuleEnabled/scheduleAt 全部改用新方法。
2. **后台 i18n**：`L10n.load` 上移到 `TickHubApp.onCreate` 同步加载（runBlocking），确保闹钟/Worker 后台独立拉起时语言正确。错误文本改为可翻译 key 存储：`TriggerLog` 加 `errorKey`/`errorArgs`，DispatchEngine/Repository 写日志存 key（不再固化为某语言的文本），UI 显示时 `L10n.tFormat()` 按当前语言渲染，`{0}/{1}` 占位符替换参数。
3. **runIds 精确匹配**：新增 `GitHubApi.getWorkflowRun(runId)`；RunTrackerWorker 首轮发现 run 后把 workflowId→runId 存日志（`updateLogTracking`），后续轮次按 runId 精确查询，避免同 workflow 短时多次触发误关联旧 run。
4. **logout 彻底清理**：`TokenStore.clearSync()`（commit 同步落盘）；`clearAllUserData()` 清所有账号数据键（rules/logs/snapshot/running/lastCatchUp/order），保留用户偏好；先取消闹钟/WorkManager 再清数据（否则拿不到 ruleId）。
5. **数据安全**：JSON 解析失败写 DataIntegrity 日志告警；refreshManaged/scan 在 `dataStore.edit` 块内重读最新 snapshot 再合并，保护网络请求期间的用户勾选不被旧快照覆盖。
6. **HTTP 分级 + 默认分支**：`ApiResult.Error` 加 `isUnauthorized/isRateLimited/isServerError`；scan 遇 401 清 token 跳重登、403 提示限流；workflow_dispatch 用仓库 `defaultBranch` 而非硬编码 main（DispatchEngine + triggerWorkflowNow）。
7. **节假日拉取策略（用户明确要求）**：只在三种时机拉取——① 首次打开开关 ② 关→开切换（等同手动拉取）③ 每年 12 月 17 日一次（次年通知通常此时已发布）。去掉 MainActivity 每次登录的主动拉取；新增 `maybeAnnualHolidayRefresh()` 用 `holiday_annual_fetch_year` 偏好防重复。平时 App 绝不主动拉取。
8. **轮询上限**：MAX_POLLS 17280→2160（用户：3 小时超长任务足够，2160×5s=3h）。
9. **资源优化**：头像缓存 ConcurrentHashMap→LruCache（≤8MB 或 1/16 堆）+ inSampleSize 采样到 128px；构建配置清理死依赖；CI 签名密钥 assembleRelease 后自动删除。material-icons-core 1.7.8 与 BOM 不兼容（图标缺失）已回退 extended。

**立即触发耗时追踪修复（v1.1.5）**：仓库页「立即触发」原走 `AppViewModel.triggerWorkflowNow` 捷径——直接 `repository.addLog` 写死 `SUCCESS/durationMs=0/tracking=false`，完全绕过 DispatchEngine 追踪，历史卡片因此永远不显示耗时（用户多次反馈）。修复：① `TriggerLog` 加 `trackWorkflowIds` 字段，manual 触发写入自身 workflowId、`tracking=true` 并启动 `RunTrackerWorker`；② `RunTrackerWorker` 改为优先从 log 读 repoFullName/workflowIds/workflowNames（manual 无 rule），rule 触发仍回退到 rule；③ 耗时追踪改为**总是开启**（不再受"执行结果通知"开关控制）——开关只控制是否发通知，追踪与历史耗时始终有效。规则页立即触发（走 DispatchEngine）同样受益。

**规则卡片与调度可靠性（v1.1.4）**：① 每隔 N 天规则卡片显示具体天数（`每隔 ` + `rule.intervalDays` + ` 天`），不再笼统显示"每隔X天"。② 法定工作日/节假日跨年算错修复：`computeNextFire` 同时加载当年+次年节假日数据，谓词按候选日期年份取对应数据，跨年元旦/春节能算出下次时间；数据不可用时卡片显示"节假日数据未就绪，请在设置开启中国法定节假日"提示而非空白。③ **C1 失败不再消费触发点**（审查高优先级）：网络超时/dispatch 全失败时不再无条件 `advanceRule()`，改 `retryOrAdvance()`——同一 slot 15 分钟后重试（携带原始 plannedAt 保持去重键一致），最多 5 次后放弃推进，避免瞬时网络错误导致漏签。④ **C4 精确闹钟权限静默吞**：无精确闹钟权限时回退 `setAndAllowWhileIdle()`（仍能触发）+ 一次性通知引导，不再静默失败。

**历史页 i18n 与耗时修正（v1.1.3）**：① 历史卡片"计划：/实际："原硬编码中文，改 `t()` + 补字典 key；errorMessage 显示时经 `L10n.tNow()`。全局扫描确认其余中文均为字典 key / 数据常量 / VM 待翻译 key。② 耗时不显示根因：`RunTrackerWorker` 跑满 144 次（12 分钟）后 `giveUp()` 把 `durationMs` 归零且 `tracking=false`，卡片既无实时计时也无静态耗时。修复：去掉短上限（改 24 小时安全网，任务 completed 立即停）；每次轮询把实时耗时写回日志（`updateLogElapsed`，找到 run 用 `run_started_at` 起算、未找到用 `firedAt` 起算），进程被杀重启也有耗时；只接受 `created_at >= firedAt - 2min` 的 run（避免误追踪旧 run，即审查 C3）；兜底超时用 `finalizeUnconfirmed` 保留耗时、不把成功的 dispatch 误改为 FAILED（审查 M7）。5s 轮询间隔保留（用户要求及时性）。

**i18n 布局优化（v1.1.2，用户实机截图反馈）**：英文翻译过长导致 chip/按钮文字换行撑高。业界国际版 APP 通用解法是布局层适配而非只靠缩短译文：① 规则编辑页周期 chip 与漂移 chip 从 `Row+weight(1f)` 等宽改为 **`FlowRow` + 自然宽度 + `maxLines=1`**——chip 按自身文字宽度排布，放不下整颗换行到下一行，不会把文字挤成两行撑高。② 设置页等宽按钮文字改短标签（Battery/Auto-start）+ `maxLines=1`，完整含义由卡片上方说明文字承担。③ 说明文字从过简版本恢复为完整可读（电池优化/精确闹钟/执行状态/结果通知/网络恢复/中国节假日等），兼顾看懂与不换行。

---

> 文档结束。任何与实现不一致之处，以「实际代码 + 本文档更新」为准；请接手人持续维护本文档。
