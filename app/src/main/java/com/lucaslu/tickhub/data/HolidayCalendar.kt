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

    /** Bundled 2026 data (State Council notice). */
    private val bundled2026 = """
    {
      "year": 2026,
      "days": [
        {"name":"元旦","date":"2026-01-01","isOffDay":true},
        {"name":"元旦","date":"2026-01-02","isOffDay":true},
        {"name":"元旦","date":"2026-01-03","isOffDay":true},
        {"name":"元旦","date":"2026-01-04","isOffDay":false},
        {"name":"春节","date":"2026-02-14","isOffDay":false},
        {"name":"春节","date":"2026-02-15","isOffDay":true},
        {"name":"春节","date":"2026-02-16","isOffDay":true},
        {"name":"春节","date":"2026-02-17","isOffDay":true},
        {"name":"春节","date":"2026-02-18","isOffDay":true},
        {"name":"春节","date":"2026-02-19","isOffDay":true},
        {"name":"春节","date":"2026-02-20","isOffDay":true},
        {"name":"春节","date":"2026-02-21","isOffDay":true},
        {"name":"春节","date":"2026-02-22","isOffDay":true},
        {"name":"春节","date":"2026-02-23","isOffDay":true},
        {"name":"春节","date":"2026-02-28","isOffDay":false},
        {"name":"清明节","date":"2026-04-04","isOffDay":true},
        {"name":"清明节","date":"2026-04-05","isOffDay":true},
        {"name":"清明节","date":"2026-04-06","isOffDay":true},
        {"name":"劳动节","date":"2026-05-01","isOffDay":true},
        {"name":"劳动节","date":"2026-05-02","isOffDay":true},
        {"name":"劳动节","date":"2026-05-03","isOffDay":true},
        {"name":"劳动节","date":"2026-05-04","isOffDay":true},
        {"name":"劳动节","date":"2026-05-05","isOffDay":true},
        {"name":"劳动节","date":"2026-05-09","isOffDay":false},
        {"name":"端午节","date":"2026-06-19","isOffDay":true},
        {"name":"端午节","date":"2026-06-20","isOffDay":true},
        {"name":"端午节","date":"2026-06-21","isOffDay":true},
        {"name":"国庆节/中秋节","date":"2026-09-20","isOffDay":false},
        {"name":"中秋节","date":"2026-09-25","isOffDay":true},
        {"name":"中秋节","date":"2026-09-26","isOffDay":true},
        {"name":"中秋节","date":"2026-09-27","isOffDay":true},
        {"name":"国庆节","date":"2026-10-01","isOffDay":true},
        {"name":"国庆节","date":"2026-10-02","isOffDay":true},
        {"name":"国庆节","date":"2026-10-03","isOffDay":true},
        {"name":"国庆节","date":"2026-10-04","isOffDay":true},
        {"name":"国庆节","date":"2026-10-05","isOffDay":true},
        {"name":"国庆节","date":"2026-10-06","isOffDay":true},
        {"name":"国庆节","date":"2026-10-07","isOffDay":true},
        {"name":"国庆节","date":"2026-10-10","isOffDay":false}
      ]
    }
    """.trimIndent()

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
        val data = found ?: if (year == 2026) {
            json.decodeFromString<HolidayYearData>(bundled2026)
        } else {
            HolidayYearData(year, emptyList())
        }
        cache = cache + (year to data)
        return data
    }

    /** Result of a holiday-data refresh, surfaced to the user via notification. */
    data class RefreshResult(val ok: Boolean, val years: List<Int>)

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
            val newFetched = (fetched + fetchedYears).joinToString(",")
            context.holidayDataStore.edit { p ->
                p[KEY_HOLIDAY_DATA] = json.encodeToString(all)
                p[KEY_HOLIDAY_FETCHED_FOR] = newFetched
            }
            cache = emptyMap() // force reload next time
        }
        // ok = at least the CURRENT year has usable data (next year may be unpublished
        // until mid-December — that alone is not a failure worth notifying).
        val ok = successYears.contains(nowYear) || all.any { it.year == nowYear && it.days.isNotEmpty() }
        return RefreshResult(ok = ok, years = successYears.distinct())
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
