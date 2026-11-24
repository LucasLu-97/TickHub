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
private val KEY_HOLIDAY_JSON = stringPreferencesKey("holiday_json")
private val KEY_HOLIDAY_YEAR = intPreferencesKey("holiday_year")

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
    private var cache: HolidayYearData? = null
    private var cacheYear: Int = 0

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

    suspend fun load(context: Context, year: Int): HolidayYearData {
        if (cache != null && cacheYear == year) return cache!!
        // try persisted data for this year
        val prefs = context.holidayDataStore.data.first()
        val savedYear = prefs[KEY_HOLIDAY_YEAR] ?: 0
        val savedJson = prefs[KEY_HOLIDAY_JSON]
        if (savedYear == year && !savedJson.isNullOrBlank()) {
            try {
                val parsed = json.decodeFromString<HolidayYearData>(savedJson)
                cache = parsed; cacheYear = year
                return parsed
            } catch (e: Exception) { /* fall through */ }
        }
        // bundled fallback
        val data = if (year == 2026) {
            json.decodeFromString<HolidayYearData>(bundled2026)
        } else {
            // for other years, use bundled 2026 as shape but no days -> fall back to weekday math
            HolidayYearData(year, emptyList())
        }
        cache = data; cacheYear = year
        return data
    }

    /** Refresh from GitHub. Returns true if succeeded. */
    suspend fun refresh(context: Context, year: Int): Boolean {
        val result = GitHubApi().fetchHolidayYear(year)
        if (result is ApiResult.Success) {
            val data = result.data
            context.holidayDataStore.edit { prefs ->
                prefs[KEY_HOLIDAY_JSON] = json.encodeToString(data)
                prefs[KEY_HOLIDAY_YEAR] = year
            }
            cache = data; cacheYear = year
            return true
        }
        return false
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

    fun isKnownYear(data: HolidayYearData): Boolean = data.days.isNotEmpty()
}
