package com.lucaslu.tickhub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.GitHubApi
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.holidayDataStore by preferencesDataStore(name = "tickhub_holidays")
/** Persist all fetched years as one JSON array so current+next year can coexist. */
private val KEY_HOLIDAY_DATA = stringPreferencesKey("holiday_data_json")
private val KEY_HOLIDAY_FETCHED_FOR = stringPreferencesKey("holiday_fetched_years") // "2026,2027"

@Serializable
data class HolidayDay(
    val name: String,
    val date: String,
    @SerialName("isOffDay") val isOffDay: Boolean
)

@Serializable
data class HolidayYearData(
    val year: Int,
    val days: List<HolidayDay>
)

/**
 * Chinese statutory holiday calendar. Data source: NateScarlet/holiday-cn,
 * which is generated from the State Council official notices.
 * - isOffDay = true  -> statutory holiday / day off
 * - isOffDay = false -> makeup working day (weekend that must work)
 *
 * Bundled 2026 data works offline; app tries to refresh the current year from
 * GitHub on startup so new years don't require an app release.
 */
object HolidayCalendar {

    private val json = Json { ignoreUnknownKeys = true }
    // Atomic single-field cache (the old pair of @Volatile fields could be
    // observed mid-update). Map also avoids the 4-year loop thrashing a
    // single-slot cache during computeNextFire.
    @Volatile private var cache: Map<Int, HolidayYearData> = emptyMap()


    private suspend fun readAll(context: Context): MutableList<HolidayYearData> {
        val raw = context.holidayDataStore.data.first()[KEY_HOLIDAY_DATA]
        if (raw.isNullOrBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<HolidayYearData>>(raw).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    suspend fun load(context: Context, year: Int): HolidayYearData {
        cache[year]?.let { return it }
        val all = readAll(context)
        val found = all.firstOrNull { it.year == year }
        // No bundled fallback: holiday data only exists after the user opts in
        // and a refresh succeeds. Without it the rule probes instead of guessing.
        val data = found ?: HolidayYearData(year, emptyList())
        cache = cache + (year to data)
        return data
    }

    /** Result of a holiday-data refresh, surfaced to the user via notification. */
    data class RefreshResult(val ok: Boolean, val years: List<Int>, val changed: Boolean)

    /**
     * Fetch current + next year holiday data. Next-year notices are usually
     * published by early December, so we attempt both but tolerate failure.
     * A year is fetched at most once (persisted in fetched-years) unless forced.
     * Years are merged by year key, so no conflicts between adjacent years.
     */
    suspend fun refresh(context: Context, force: Boolean = false): RefreshResult {
        val nowYear = LocalDate.now().year
        val targets = listOf(nowYear, nowYear + 1)
        val prefs = context.holidayDataStore.data.first()
        val fetched = (prefs[KEY_HOLIDAY_FETCHED_FOR] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val all = readAll(context)
        var changed = false
        val fetchedYears = mutableSetOf<String>()
        val successYears = mutableListOf<Int>()
        val api = GitHubApi()
        for (year in targets) {
            if (!force && fetched.contains(year.toString()) && all.any { it.year == year && it.days.isNotEmpty() }) {
                successYears.add(year) // already have this year, no network needed
                continue
            }
            when (val result = api.fetchHolidayYear(year)) {
                is ApiResult.Success -> {
                    val data = result.data
                    if (data.days.isEmpty()) {
                        // 200 OK with an empty payload is not usable data.
                        continue
                    }
                    val idx = all.indexOfFirst { it.year == year }
                    if (idx >= 0) all[idx] = data else all.add(data)
                    fetchedYears.add(year.toString()) // only mark SUCCESS years
                    successYears.add(year)
                    changed = true
                }
                is ApiResult.Error -> { /* next year may not be published yet, ignore */ }
            }
        }
        if (changed) {
            // Prune stale years on every successful refresh. computeNextFire only
            // ever reads year-1..year+2, so older years are pure accumulated cache.
            // Keep year-1 (cross-year New Year), current and next year; drop the rest.
            val keepRange = (nowYear - 1..nowYear + 1)
            val pruned = all.filter { it.year in keepRange }
            val newFetched = (fetched + fetchedYears)
                .filter { it.toIntOrNull() in keepRange }
                .joinToString(",")
            context.holidayDataStore.edit { p ->
                p[KEY_HOLIDAY_DATA] = json.encodeToString(pruned)
                p[KEY_HOLIDAY_FETCHED_FOR] = newFetched
            }
            cache = emptyMap() // force reload next time
        }
        // ok = at least the CURRENT year has usable data (next year may be unpublished
        // until mid-December — that alone is not a failure worth notifying).
        val haveCurrent = successYears.contains(nowYear) ||
            all.any { it.year == nowYear && it.days.isNotEmpty() }
        return RefreshResult(ok = haveCurrent, years = successYears.distinct(), changed = changed)
    }

    /** Is this date a statutory working day (includes makeup work weekends)? */
    fun isLegalWorkday(data: HolidayYearData, date: LocalDate): Boolean {
        val entry = data.days.firstOrNull { it.date == date.toString() }
        if (entry != null) return !entry.isOffDay // makeup workday (isOffDay=false) is a workday
        return date.dayOfWeek.value <= 5
    }

    /** Is this date a statutory holiday (day off)? */
    fun isLegalHoliday(data: HolidayYearData, date: LocalDate): Boolean {
        val entry = data.days.firstOrNull { it.date == date.toString() }
        return entry?.isOffDay == true
    }

}
