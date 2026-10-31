package vn.edu.uit.mobile.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toJavaLocalDate
import vn.edu.uit.mobile.core.designsystem.R

/** Locale of the selected interface language, not the device locale. */
@Composable
fun appLocale(): Locale = LocalConfiguration.current.locales[0]

/** e.g. "26 Oct, 2026" in English and "26 tháng 10, 2026" in Vietnamese. */
@Composable
fun mediumDate(date: LocalDate): String = date.toJavaLocalDate().format(DateTimeFormatter.ofPattern(stringResource(R.string.date_medium_pattern), appLocale()))

/** e.g. "October 2026" in English and "Tháng 10, 2026" in Vietnamese. */
@Composable
fun monthYear(date: LocalDate): String = date.toJavaLocalDate().format(DateTimeFormatter.ofPattern(stringResource(R.string.date_month_year_pattern), appLocale()))

/** Two-letter weekday label for the selected language. */
@Composable
fun shortWeekday(day: DayOfWeek): String {
    val labels = stringArrayResource(R.array.weekdays_short)
    return labels.getOrElse(day.isoDayNumber - 1) { "" }
}

/** Full weekday name, e.g. "Monday" or "Thứ Hai". */
@Composable
fun longWeekday(date: LocalDate): String = date.toJavaLocalDate().dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, appLocale())

/** Abbreviated month label, e.g. "Oct" or "Thg 10". */
@Composable
fun shortMonth(date: LocalDate): String = date.toJavaLocalDate().format(DateTimeFormatter.ofPattern(stringResource(R.string.date_month_short_pattern), appLocale()))
