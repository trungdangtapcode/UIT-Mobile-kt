package vn.edu.uit.mobile.common

import kotlinx.datetime.*
import vn.edu.uit.mobile.model.DaaExamSlot

val UitTimeZone: TimeZone = TimeZone.of("Asia/Ho_Chi_Minh")
object UitLessonTimeCalculator {
    private val starts = listOf("07:30", "08:15", "09:00", "10:00", "10:45", "13:00", "13:45", "14:30", "15:30", "16:15").map(LocalTime::parse)
    private val ends = listOf("08:15", "09:00", "09:45", "10:45", "11:30", "13:45", "14:30", "15:15", "16:15", "17:00").map(LocalTime::parse)
    fun start(period: Int): LocalTime {
        require(period in 1..10)
        return starts[period - 1]
    }
    fun end(period: Int): LocalTime {
        require(period in 1..10)
        return ends[period - 1]
    }
    fun examStart(shift: Int): LocalTime {
        require(shift in 1..4)
        return LocalTime.parse(listOf("07:30", "09:30", "13:30", "15:30")[shift - 1])
    }
    fun examStart(exam: DaaExamSlot): LocalTime = exam.startTime?.let(LocalTime::parse) ?: examStart(exam.examShift)
    fun parsePeriods(text: String): IntRange {
        val cleaned = text.replace(Regex("(?i)tiết|periods?"), "").trim()
        val range = Regex("^(10|[1-9])\\s*[-–]\\s*(10|[1-9])$").matchEntire(cleaned)
        val values = if (range != null) {
            listOf(range.groupValues[1].toInt(), range.groupValues[2].toInt())
        } else if (cleaned == "10") {
            listOf(10)
        } else {
            cleaned.filter(Char::isDigit).map { if (it == '0') 10 else it.digitToInt() }
        }
        require(values.isNotEmpty() && values.all { it in 1..10 } && values.zipWithNext().all { (a, b) -> b > a }) { "Invalid lesson periods" }
        return values.first()..values.last()
    }
}
fun dateAt(date: LocalDate, time: LocalTime): Long = LocalDateTime(date, time).toInstant(UitTimeZone).epochSeconds
fun today(): LocalDate = Clock.System.now().toLocalDateTime(UitTimeZone).date
fun parsePortalDate(text: String): LocalDate? = runCatching {
    val iso = Regex("\\d{4}-\\d{2}-\\d{2}").find(text)?.value
    if (iso != null) {
        LocalDate.parse(iso)
    } else {
        val m = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})").find(text) ?: return null
        val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        LocalDate(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
    }
}.getOrNull()
