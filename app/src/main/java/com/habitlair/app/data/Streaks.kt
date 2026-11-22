package com.habitlair.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Dates {
    val dayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun today(): Long = LocalDate.now().toEpochDay()

    fun fromMillis(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    fun fmtTime(min: Int): String {
        val h = min / 60
        val m = min % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%02d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
    }

    fun fmtDate(d: Long): String {
        val ld = LocalDate.ofEpochDay(d)
        val p = if (ld.year == LocalDate.now().year) "EEE, MMM d" else "EEE, MMM d, yyyy"
        return ld.format(DateTimeFormatter.ofPattern(p))
    }

    fun daysLabel(mask: Int): String = when (mask) {
        127 -> "Daily"
        31 -> "Weekdays"
        96 -> "Weekends"
        0 -> "Never"
        else -> dayShort.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }.joinToString(" ")
    }
}

data class StreakInfo(val current: Int, val best: Int, val total: Int, val rate30: Int)

object Streaks {
    fun compute(h: Habit, done: Set<Long>, today: Long): StreakInfo {
        val created = Dates.fromMillis(h.createdAt)
        val start = maxOf(minOf(created, done.minOrNull() ?: Long.MAX_VALUE), today - 3650)

        var current = 0
        var d = today
        while (d >= start) {
            if (h.isScheduled(d)) {
                if (d in done) current++ else if (d != today) break
            }
            d--
        }

        var best = 0
        var run = 0
        d = start
        while (d <= today) {
            if (h.isScheduled(d)) {
                if (d in done) { run++; if (run > best) best = run } else if (d != today) run = 0
            }
            d++
        }

        var sched = 0
        var hit = 0
        for (x in maxOf(start, today - 29)..today) {
            if (h.isScheduled(x)) { sched++; if (x in done) hit++ }
        }
        val rate = if (sched == 0) 0 else hit * 100 / sched
        return StreakInfo(current, best, done.size, rate)
    }
}
