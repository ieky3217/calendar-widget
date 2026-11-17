package jp.eiky.calwidget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.TreeMap

/**
 * 日本の祝日（2019〜2099年想定）。
 * 春分・秋分は計算式による推定値。法改正による臨時の変更があれば追記してください。
 */
object JpHolidays {
    private val cache = HashMap<Int, Map<LocalDate, String>>()

    fun isHoliday(d: LocalDate): Boolean = forYear(d.year).containsKey(d)

    fun name(d: LocalDate): String? = forYear(d.year)[d]

    @Synchronized
    fun forYear(y: Int): Map<LocalDate, String> = cache.getOrPut(y) { compute(y) }

    private fun nthMonday(y: Int, m: Int, n: Int): LocalDate =
        LocalDate.of(y, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, DayOfWeek.MONDAY))

    private fun vernal(y: Int): Int = (20.8431 + 0.242194 * (y - 1980) - ((y - 1980) / 4)).toInt()
    private fun autumnal(y: Int): Int = (23.2488 + 0.242194 * (y - 1980) - ((y - 1980) / 4)).toInt()

    private fun compute(y: Int): Map<LocalDate, String> {
        val h = TreeMap<LocalDate, String>()
        fun add(m: Int, d: Int, name: String) { h[LocalDate.of(y, m, d)] = name }

        add(1, 1, "元日")
        h[nthMonday(y, 1, 2)] = "成人の日"
        add(2, 11, "建国記念の日")
        if (y >= 2020) add(2, 23, "天皇誕生日")
        add(3, vernal(y), "春分の日")
        add(4, 29, "昭和の日")
        add(5, 3, "憲法記念日")
        add(5, 4, "みどりの日")
        add(5, 5, "こどもの日")
        when (y) {
            2020 -> add(7, 23, "海の日")
            2021 -> add(7, 22, "海の日")
            else -> h[nthMonday(y, 7, 3)] = "海の日"
        }
        when (y) {
            2020 -> add(8, 10, "山の日")
            2021 -> add(8, 8, "山の日")
            else -> add(8, 11, "山の日")
        }
        h[nthMonday(y, 9, 3)] = "敬老の日"
        add(9, autumnal(y), "秋分の日")
        when (y) {
            2020 -> add(7, 24, "スポーツの日")
            2021 -> add(7, 23, "スポーツの日")
            2019 -> h[nthMonday(y, 10, 2)] = "体育の日"
            else -> h[nthMonday(y, 10, 2)] = "スポーツの日"
        }
        add(11, 3, "文化の日")
        add(11, 23, "勤労感謝の日")
        if (y == 2019) {
            add(4, 30, "国民の休日")
            add(5, 1, "天皇の即位の日")
            add(5, 2, "国民の休日")
            add(10, 22, "即位礼正殿の儀")
        }

        // 国民の休日（祝日に挟まれた平日）
        var d = LocalDate.of(y, 1, 2)
        val end = LocalDate.of(y, 12, 30)
        val between = ArrayList<LocalDate>()
        while (!d.isAfter(end)) {
            if (!h.containsKey(d) && d.dayOfWeek != DayOfWeek.SUNDAY &&
                h.containsKey(d.minusDays(1)) && h.containsKey(d.plusDays(1))
            ) between.add(d)
            d = d.plusDays(1)
        }
        between.forEach { h[it] = "国民の休日" }

        // 振替休日（日曜の祝日 → 次の祝日でない日）
        val sundays = h.keys.filter { it.dayOfWeek == DayOfWeek.SUNDAY }
        for (s in sundays) {
            var sub = s.plusDays(1)
            while (h.containsKey(sub)) sub = sub.plusDays(1)
            if (sub.year == y) h[sub] = "振替休日"
        }
        return h
    }
}
