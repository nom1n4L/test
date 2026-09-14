package com.skorlogi.app.data

/**
 * Civil-date maths without java.time, which needs API 26 or desugaring. The
 * conversion is Howard Hinnant's days_from_civil / civil_from_days.
 */
object Dates {

    fun toEpochDay(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097L + doe.toLong() - 719468L
    }

    /** Returns year, month, day. */
    fun fromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468L
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    /** Parses the feeds' `dd/mm/yy` and `dd/mm/yyyy` dates. Returns null if unusable. */
    fun parseFeedDate(text: String): Long? {
        val t = text.trim()
        if (t.length < 8) return null
        val parts = t.split('/')
        if (parts.size != 3) return null
        val d = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val yRaw = parts[2].toIntOrNull() ?: return null
        if (d !in 1..31 || m !in 1..12) return null
        // Two-digit years in these files are all 2000s; the archives start in the 1990s
        // but we never request seasons that old.
        val y = when {
            yRaw >= 1000 -> yRaw
            yRaw >= 90 -> 1900 + yRaw
            else -> 2000 + yRaw
        }
        return toEpochDay(y, m, d)
    }

    private val DAY_NAMES = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
    private val MONTH_NAMES = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
        "Jul", "Agu", "Sep", "Okt", "Nov", "Des",
    )

    fun format(epochDay: Long): String {
        val (y, m, d) = fromEpochDay(epochDay)
        return "$d ${MONTH_NAMES[m - 1]} $y"
    }

    fun formatWithDay(epochDay: Long): String {
        // 1970-01-01 was a Thursday, index 3 in a Monday-first array.
        val dow = (((epochDay + 3) % 7 + 7) % 7).toInt()
        return "${DAY_NAMES[dow]}, ${format(epochDay)}"
    }

    fun formatShort(epochDay: Long): String {
        val (_, m, d) = fromEpochDay(epochDay)
        return "$d ${MONTH_NAMES[m - 1]}"
    }

    /** Today in UTC. The feeds are day-resolution, so the timezone nuance does not matter. */
    fun today(): Long = System.currentTimeMillis() / 86_400_000L
}
