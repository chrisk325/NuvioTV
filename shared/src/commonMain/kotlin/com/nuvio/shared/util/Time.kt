package com.nuvio.shared.util

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?""")

/**
 * Parses the leading ISO-8601 date (optionally with time, treated as UTC) of an add-on
 * `released` value into epoch milliseconds. Returns null when no date is found.
 */
fun parseReleaseEpochMs(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val match = ISO_DATE.find(value) ?: return null
    val (y, m, d) = match.destructured.let { Triple(it.component1().toInt(), it.component2().toInt(), it.component3().toInt()) }
    if (m !in 1..12 || d !in 1..31) return null
    val hour = match.groupValues[4].toIntOrNull() ?: 0
    val minute = match.groupValues[5].toIntOrNull() ?: 0
    val second = match.groupValues[6].toIntOrNull() ?: 0
    val days = daysFromCivil(y, m, d)
    return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L
}

/** True only when [raw] parses to a moment after [nowMs]. Unknown dates count as released. */
fun isReleaseInFuture(raw: String?, nowMs: Long = currentTimeMillis()): Boolean {
    val release = parseReleaseEpochMs(raw) ?: return false
    return release > nowMs
}

// Howard Hinnant's days_from_civil.
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}
