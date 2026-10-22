package io.github.aedev.flow.data.local

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// "21 okt 2026, 13:39:00 CEST", "Oct 21, 2026, 1:39:00 PM CEST", "21.10.2026, 13:39:00 MESZ".
private val takeoutDatePattern =
    Regex(
        """(?:(\d{1,2})\.?\s*(\p{L}{3,})\.?\s+(\d{4})|(\p{L}{3,})\.?\s+(\d{1,2}),?\s+(\d{4})|(\d{1,2})[./-](\d{1,2})[./-](\d{4}))""" +
            """,?\s+(?:\p{L}{1,3}\s+)?(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp]\.?\s?[Mm]\.?)?\s*([A-Za-z]{1,5}(?:[+-]\d{1,2}(?::?\d{2})?)?)?""",
    )
private val zoneWithOffset = Regex("""([A-Za-z]+)(?:([+-]\d{1,2})(?::?(\d{2}))?)?""")
private val markup = Regex("<[^>]+>")

private val monthPrefixes =
    mapOf(
        "jan" to 1, "feb" to 2, "fév" to 2, "fev" to 2, "mrt" to 3, "mar" to 3, "mär" to 3, "mrz" to 3,
        "apr" to 4, "avr" to 4, "abr" to 4, "mei" to 5, "may" to 5, "mai" to 5, "jun" to 6, "jui" to 6,
        "jul" to 7, "aug" to 8, "aoû" to 8, "aou" to 8, "ago" to 8, "sep" to 9, "set" to 9, "okt" to 10,
        "oct" to 10, "nov" to 11, "dec" to 12, "dez" to 12, "déc" to 12, "dic" to 12,
    )

private val zoneHours =
    mapOf(
        "UTC" to 0, "GMT" to 0, "Z" to 0, "WET" to 0, "WEST" to 1, "BST" to 1, "CET" to 1, "MEZ" to 1,
        "CEST" to 2, "MESZ" to 2, "EET" to 2, "EEST" to 3, "EST" to -5, "EDT" to -4, "CST" to -6,
        "CDT" to -5, "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7,
    )

private fun monthOf(word: String): Int? {
    val lower = word.lowercase()
    if (lower.startsWith("juil")) return 7
    return monthPrefixes[lower.take(3)]
}

/**
 * When a Takeout activity entry happened, from the text after its link up to the end of its cell.
 * Takeout writes the date in the account's language; the day-month-year, month-day-year and numeric
 * forms with a 12 or 24 hour time are read. A zone Flow does not know is taken as [zone]. Null when no
 * date with a time is there, so the caller can fall back to an order-only stamp.
 */
internal fun takeoutEntryTime(
    cell: String,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    val text =
        cell
            .replace("&nbsp;", " ")
            .replace(' ', ' ')
            .replace(' ', ' ')
    // The date is the last line of the cell, after the title and the channel.
    return text.split(markup).asReversed().firstNotNullOfOrNull { parseTakeoutDate(it.trim(), zone) }
}

private fun parseTakeoutDate(
    text: String,
    zone: ZoneId,
): Long? {
    if (text.isEmpty()) return null
    val g = takeoutDatePattern.find(text)?.groupValues ?: return null
    val (day, month, year) =
        when {
            g[1].isNotEmpty() -> Triple(g[1].toInt(), monthOf(g[2]), g[3].toInt())
            g[4].isNotEmpty() -> Triple(g[5].toInt(), monthOf(g[4]), g[6].toInt())
            else -> Triple(g[7].toInt(), g[8].toInt(), g[9].toInt())
        }
    if (month == null) return null
    var hour = g[10].toInt()
    val ampm = g[13].lowercase().filter { it == 'a' || it == 'p' }
    if (ampm == "p" && hour < 12) hour += 12
    if (ampm == "a" && hour == 12) hour = 0
    return try {
        val local = LocalDateTime.of(year, month, day, hour, g[11].toInt(), g[12].ifEmpty { "0" }.toInt())
        val offset = offsetOf(g[14])
        (if (offset != null) local.toInstant(offset) else local.atZone(zone).toInstant()).toEpochMilli()
    } catch (_: DateTimeException) {
        null
    }
}

private fun offsetOf(name: String): ZoneOffset? {
    val (base, hours, minutes) = zoneWithOffset.matchEntire(name)?.destructured ?: return null
    val upper = base.uppercase()
    return when {
        hours.isNotEmpty() && (upper == "UTC" || upper == "GMT") -> {
            val sign = if (hours.startsWith("-")) -1 else 1
            ZoneOffset.ofHoursMinutes(hours.toInt(), sign * (minutes.ifEmpty { "0" }.toInt()))
        }
        else -> zoneHours[upper]?.let { ZoneOffset.ofHours(it) }
    }
}
