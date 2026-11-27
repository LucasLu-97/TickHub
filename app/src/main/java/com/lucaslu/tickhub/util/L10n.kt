package com.lucaslu.tickhub.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-level localization. Keys are the original Chinese strings (readable in code);
 * values map to English. Language is independent of the system locale and stored
 * in DataStore. Composables use [t] so they recompose on switch; non-UI code uses
 * [tNow] which reads the current language synchronously.
 */
object L10n {
    enum class Lang(val code: String) { ZH("zh"), EN("en") }

    private val Context.l10nDataStore by preferencesDataStore(name = "tickhub_l10n")
    private val langKey = stringPreferencesKey("language")

    private val _lang = MutableStateFlow(Lang.ZH)
    val lang: StateFlow<Lang> = _lang

    suspend fun load(context: Context) {
        val code = try { context.l10nDataStore.data.first()[langKey] } catch (e: Exception) { null }
        _lang.value = if (code == "en") Lang.EN else Lang.ZH
    }

    suspend fun set(context: Context, lang: Lang) {
        _lang.value = lang
        context.l10nDataStore.edit { it[langKey] = lang.code }
    }

    /** Current-language lookup (synchronous, for non-Compose code). */
    fun tNow(key: String): String = if (_lang.value == Lang.EN) en[key] ?: key else key

    /** Explicit English lookup (used by Compose recomposition). */
    fun tEn(key: String): String = en[key] ?: key

    /** Translate a key and substitute {0}, {1}... placeholders (synchronous).
     *  Single pass over the template so a parameter containing "{1}" cannot be
     *  re-substituted by later iterations. */
    fun tFormat(key: String, args: List<String>): String {
        val template = tNow(key)
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            if (template[i] == '{') {
                val end = template.indexOf('}', i + 1)
                if (end > i) {
                    val idx = template.substring(i + 1, end).toIntOrNull()
                    if (idx != null && idx in args.indices) {
                        sb.append(args[idx]); i = end + 1; continue
                    }
                }
            }
            sb.append(template[i]); i++
        }
        return sb.toString()
    }

    private val en = mapOf(
        // ---- common ----
        "取消" to "Cancel", "确定" to "OK", "删除" to "Delete", "全选" to "Select all",
        "保存" to "Save", "重试" to "Retry", "修改" to "Edit", "移除" to "Remove",
        "启用" to "Enable", "立即触发" to "Trigger", "编辑" to "Edit",
        "成功" to "Success", "失败" to "Failed", "跳过" to "Skipped", "补触发" to "Catch-up",
        "执行中" to "Running", "工作流" to "Workflow", "设置" to "Settings", "仓库" to "Repos",
        "规则" to "Rules", "历史" to "History", "未登录" to "Not signed in",
        "重新获取设备码" to "New code", "确认触发" to "Trigger",
        "确认立即触发" to "Trigger", "未分类" to "Uncategorized",
        "私有" to "Private", "公开" to "Public", "头像" to "Avatar",
        // ---- login ----
        "用 GitHub 登录" to "Sign in with GitHub",
        "用 GitHub 登录，把手机变成 Actions 调度中心" to "Sign in to schedule Actions",
        "GitHub Client ID 未注入，请联系开发者。" to "Client ID missing. Contact developer.",
        "正在请求设备码…" to "Requesting device code…",
        "在浏览器输入此设备码" to "Enter this code in browser",
        "设备码已复制" to "Device code copied",
        "复制设备码" to "Copy code",
        "打开 GitHub 授权页面" to "Open GitHub",
        "授权页面显示「GitHub CLI」属正常现象——TickHub 使用 GitHub 官方公开登录方式，请放心授权。" to " Seeing \"GitHub CLI\" is normal. Safe to authorize.",
        "请在浏览器输入设备码完成授权，完成后本 APP 会自动进入，无需其他操作" to "Enter code in browser; app continues automatically.",
        "正在等待授权完成，登录会自动继续，请稍候…" to "Waiting for authorization…",
        "正在确认授权结果，请稍候…" to "Confirming…",
        "GitHub 用户" to "GitHub user",
        "登录成功：" to "Signed in as: ",
        "设备码已过期，请重新发起" to "Device code expired, please retry",
        "你拒绝了授权" to "You denied the authorization",
        "授权超时，请重试" to "Authorization timed out",
        "GitHub Client ID 未配置，请先在 OAuth App 注册后重新构建" to "GitHub Client ID not configured. Register an OAuth App and rebuild.",
        "请求设备码失败：网络不可达" to "Failed to request device code: network unreachable",
        "响应缺少 access_token" to "Response missing access_token",
        // ---- repos ----
        "扫描仓库与工作流" to "Scan repos",
        "已纳管 " to "Managing ",
        " 个工作流 / " to " workflows / ",
        " 个仓库（下拉可刷新头像与纳管项）" to " repos (pull down to refresh)",
        "登录成功：{0}" to "Signed in as {0}",
        "登录成功：{0}（安全存储不可用，重启后需重新登录）" to "Signed in as {0} (secure storage unavailable — sign in again after restart)",
        "扫描完成" to "Scan complete",
        "扫描完成，{0} 个仓库的工作流读取失败" to "Scan complete, failed to read workflows of {0} repo(s)",
        "GitHub 访问频率受限，请稍后再试" to "GitHub rate limit reached, please retry later",
        "GitHub 服务暂时不可用，请稍后再试" to "GitHub service unavailable, please retry later",
        "扫描完成，" to "Scan complete, ",
        " 个仓库的工作流读取失败" to " repos failed to load workflows",
        "正在扫描仓库与工作流…" to "Scanning…",
        "将立即触发工作流「" to "This will trigger workflow \"",
        "」，确定继续？" to "\". Continue?",
        // ---- rules ----
        "触发规则" to "Trigger rules",
        "请先在「仓库」页勾选要纳管的工作流，再来创建规则。" to "Select workflows on Repos tab first.",
        "单次" to "Once", "每天" to "Daily", "每周" to "Weekly",
        "法定工作日" to "Weekdays", "法定节假日" to "Holidays",
        "每隔X天" to "Every N days", "下次：" to "Next: ",
        "工作流 #" to "Workflow #",
        "新建触发规则" to "New trigger rule", "编辑触发规则" to "Edit trigger rule",
        "① 选择仓库（点开后勾选工作流，可多选）" to "1. Select repo & workflows",
        "还没有纳管的工作流。请先在「仓库」页勾选工作流。" to "No workflows selected yet.",
        "② 触发时间（可添加多个时段）" to "2. Trigger times",
        "时间" to "Time ", "＋ 添加时间段" to "+ Add time slot",
        "③ 重复周期" to "3. Repeat",
        "选择星期（可多选）" to "Select days",
        "④ 随机漂移（规避签到规律检测）" to "4. Random delay",
        "不漂移" to "No jitter", "分" to " min",
        "请至少勾选一个工作流" to "Select a workflow",
        "请至少选择一个星期" to "Select a day",
        "日期：" to "Date: ", "每隔 " to "Every ", " 天" to " days", " 天（1=每天）" to " days (1 = daily)",
        "起始日期：" to "Start date: ", "间隔天数" to "Interval (days)",
        "选择时间" to "Select time", "选择日期" to "Select date",
        "一" to "Mon", "二" to "Tue", "三" to "Wed", "四" to "Thu", "五" to "Fri", "六" to "Sat", "日" to "Sun",
        // ---- history ----
        "触发历史" to "Trigger history",
        "最近 " to "Last ",
        " 条记录" to " records",
        "还没有触发记录。创建规则并到点后，记录会显示在这里。" to "No records yet.",
        "计划：" to "Planned: ", "实际：" to "Fired: ", "耗时 " to "Duration: ",
        // ---- settings ----
        "后台运行" to "Background running",
        "⚠ 电池优化开启，定时可能延迟" to "Battery optimization on - alarms may be delayed",
        "✓ 已加入电池白名单" to "Battery whitelist enabled",
        "国产 ROM 还需开启「自启动」权限，否则划掉后台后定时任务不会执行。" to "On Chinese ROMs, enable Auto-start or scheduled tasks stop when app is swiped away.",
        "电池无限制" to "Battery",
        "自启动设置" to "Auto-start",
        "精确闹钟" to "Exact alarms",
        "✓ 已允许精确闹钟，触发更准时" to "Exact alarms allowed for accurate triggers",
        "⚠ 未允许精确闹钟，触发可能延迟数分钟" to "Exact alarms off - triggers may be delayed",
        "去开启精确闹钟" to "Enable alarms",
        "TickHub · 精确闹钟未开启" to "TickHub · Exact alarms off",
        "请在设置开启精确闹钟，否则定时可能延迟数分钟" to "Enable exact alarms in Settings; triggers may be delayed otherwise",
        "执行状态监测" to "Execution status",
        "触发时显示进度与用时，仅前台刷新" to "Show live progress and elapsed time while the app is open",
        "执行结果通知" to "Result notifications",
        "任务完成后通知成功或失败，并显示真实用时" to "Notify success or failure with the real run duration when a task finishes",
        "触感反馈" to "Haptics",
        "开关、滑动、拨轮、页面切换的短震动反馈" to "Short vibration on toggles, swipes, wheel pickers and tab changes",
        "网络恢复机制" to "Network recovery",
        "到点唤醒手机，无网络时最多等待 3 分钟，联网后立即触发，避免夜间断网漏签。" to "Wakes the phone at the set time; waits up to 3 min offline, then fires as soon as the network returns.",
        "调试日志" to "Debug log",
        "记录运行过程，可导出排查问题" to "Records runtime events; export to troubleshoot problems",
        "退出登录" to "Sign out",
        "语言" to "Language",
        "中文" to "Chinese",
        "English" to "English",
        // ---- log screen ----
        "记录闹钟注册/触发、网络等待、dispatch 全过程，可导出分析故障" to "Export logs for diagnosis",
        "刷新" to "Refresh", "日志已复制，可粘贴发送" to "Log copied",
        "复制全部" to "Copy all", "分享日志文件" to "Share log file", "已清空" to "Cleared",
        "清空" to "Clear", "暂无日志文件" to "No log file yet", "TickHub 调试日志" to "TickHub debug log",
        "分享日志" to "Share log", "日志未初始化" to "Log not initialized", "（暂无日志）" to "(no logs yet)",
        "触发结果通知" to "Trigger result", "工作流触发执行完成后的成功/失败通知" to "Success or failure notification after a workflow run finishes",
        // ---- notifications / errors ----
        "执行成功" to "Run succeeded", "执行失败" to "Run failed",
        "TickHub · 执行结果未确认" to "TickHub · Result unconfirmed",
        "无法从 GitHub 获取执行结果，请到仓库页查看" to "Result unavailable; check Repos tab",
        "用时 " to "Duration ", " 用时 " to " · ", "未登录，token 缺失" to "Not signed in, token missing",
        " 触发失败：" to " failed: ", "分享失败：" to "Share failed: ",
        "等待网络超时（{0}分钟）" to "Network wait timed out ({0} min)",
        "仓库名无效：{0}" to "Invalid repo: {0}",
        "工作流 {0} 触发失败：{1}" to "Workflow {0} failed: {1}",
        "执行异常：{0}" to "Execution error: {0}",
        "规则未选择任何工作流" to "No workflow selected for this rule",
        "手动触发失败：{0}" to "Manual trigger failed: {0}",
        "读取日志失败：{0}" to "Failed to read log: {0}",
        "、" to ", ",
        "中国法定节假日" to "China holidays",
        "仅中国用户需要：开启后拉取节假日数据，支持「法定工作日/节假日」周期。非中国地区不支持此周期。" to "For China only: downloads holiday data to enable workday/holiday schedules. Not supported in other regions.",
        "仓库 " to "Repo ", "工作流 " to "Workflow ", " 工作流" to " workflows", "将立即触发「" to "This will trigger \"",
        "将立即触发工作流「" to "This will trigger workflow \"", "」，确定继续？" to "\". Continue?",
        "最近 " to "Last ", " 条记录" to " records",
        "法定工作日/节假日仅中国用户可用：请先在设置开启「中国法定节假日」。非中国地区不支持此周期。" to "Workday/holiday schedules are China-only. Enable China holidays in Settings first. Not available in other regions.",
        "节假日数据未就绪，请在设置开启「中国法定节假日」" to "Holiday data unavailable — enable China holidays in Settings",
        "等待网络超时（" to "Network wait timed out (", "分钟）" to " min)",
        "网络错误：" to "Network error: ", "解析响应失败：" to "Failed to parse response: ",
        "仓库名无效：" to "Invalid repo name: ",
        "工作流执行失败（GitHub 结论）" to "Workflow run failed (GitHub conclusion)",
    )
}

/** Composable lookup: recomposes when language changes. */
@Composable
fun t(key: String): String {
    val lang by L10n.lang.collectAsState()
    return if (lang == L10n.Lang.EN) L10n.tEn(key) else key
}
