package jp.eiky.calwidget

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 月の計算（J. Meeus『Astronomical Algorithms』の式による近似）
 * 黄経差（月と太陽の離角）を求め、月齢・輝面比・朔／上弦／望／下弦の時刻を出す。
 * 誤差は黄経差で約0.1°、時刻で十数分程度。
 */
object Moon {
    const val SYNODIC = 29.530588853

    enum class Phase(val label: String, val angle: Double) {
        NEW("朔", 0.0), FIRST("上弦", 90.0), FULL("望", 180.0), LAST("下弦", 270.0)
    }

    // ---------- 基本の式 ----------
    private fun jd(t: LocalDateTime): Double {
        val ms = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return ms / 86_400_000.0 + 2440587.5
    }

    private fun fromJd(jd: Double): LocalDateTime {
        val ms = ((jd - 2440587.5) * 86_400_000.0).toLong()
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    }

    private fun rad(deg: Double) = Math.toRadians(deg)
    private fun norm(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

    /** 黄経差（0〜360°）：0=朔, 90=上弦, 180=望, 270=下弦 */
    private fun elongationAt(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        val d = norm(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t + t * t * t / 545868.0)
        val m = norm(357.5291092 + 35999.0502909 * t - 0.0001536 * t * t)
        val mp = norm(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t + t * t * t / 69699.0)
        val corr = 6.289 * sin(rad(mp)) - 2.100 * sin(rad(m)) + 1.274 * sin(rad(2 * d - mp)) +
            0.658 * sin(rad(2 * d)) + 0.214 * sin(rad(2 * mp)) + 0.110 * sin(rad(d))
        return norm(d + corr)
    }

    /** 黄経差が target に達する時刻（start から前後へ探す） */
    private fun findPhase(startJd: Double, target: Double, forward: Boolean): Double {
        fun f(j: Double): Double {
            var x = elongationAt(j) - target
            while (x > 180) x -= 360
            while (x < -180) x += 360
            return x
        }
        val step = if (forward) 0.5 else -0.5
        var a = startJd
        var fa = f(a)
        for (i in 0 until 70) {
            val b = a + step
            val fb = f(b)
            // 時間が進む向きで、負→正に変わるところ（±180の折り返しは除く）
            val (early, late) = if (forward) fa to fb else fb to fa
            if (early < 0 && late >= 0 && late - early < 90) {
                var lo = minOf(a, b)
                var hi = maxOf(a, b)
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (f(mid) < 0) lo = mid else hi = mid
                }
                return (lo + hi) / 2
            }
            a = b
            fa = fb
        }
        return startJd
    }

    // ---------- 使う側 ----------
    fun elongation(t: LocalDateTime): Double = elongationAt(jd(t))

    /** 輝面比（0〜1） */
    fun illumination(t: LocalDateTime): Double = (1 - cos(rad(elongation(t)))) / 2

    /** 月齢：直前の朔からの経過日数 */
    fun age(t: LocalDateTime): Double {
        val j = jd(t)
        val lastNew = findPhase(j, 0.0, forward = false)
        return (j - lastNew).coerceIn(0.0, SYNODIC)
    }

    /** 次の月相（朔・上弦・望・下弦）の時刻 */
    fun next(phase: Phase, from: LocalDateTime): LocalDateTime =
        fromJd(findPhase(jd(from), phase.angle, forward = true))

    /** 今の段階：主要な月相の前後12時間ならその名前、それ以外は「上弦→望」など */
    fun stage(t: LocalDateTime): String {
        val e = elongation(t)
        for (p in Phase.values()) {
            var dd = abs(e - p.angle)
            if (dd > 180) dd = 360 - dd
            if (dd <= 6.1) return p.label // 約12時間ぶん
        }
        return when {
            e < 90 -> "朔→上弦"
            e < 180 -> "上弦→望"
            e < 270 -> "望→下弦"
            else -> "下弦→朔"
        }
    }

    /** 満月の夜か（その日の22時に、望の前後12時間以内） */
    fun isFullMoonNight(d: LocalDate): Boolean {
        val e = elongation(d.atTime(22, 0))
        return abs(e - 180.0) <= 6.1
    }

    /** 次の望の日 */
    fun nextFullMoon(today: LocalDate): LocalDate = next(Phase.FULL, today.atStartOfDay()).toLocalDate()

    /** 和名（伝統的な呼び名。決まった名がない日は日数で呼ぶ） */
    fun name(age: Double): String = when (age.roundToInt().coerceIn(0, 30)) {
        0, 30 -> "新月"
        1 -> "二日月"
        2 -> "三日月"
        3, 4, 5, 6 -> "夕月"
        7, 8 -> "上弦の月"
        9 -> "十日夜"
        10 -> "十一日月"
        11 -> "十二日月"
        12 -> "十三夜"
        13 -> "小望月"
        14 -> "十五夜"
        15 -> "十六夜"
        16 -> "立待月"
        17 -> "居待月"
        18 -> "寝待月"
        19 -> "更待月"
        20 -> "二十一日月"
        21 -> "二十二日月"
        22, 23 -> "下弦の月"
        24 -> "二十五日月"
        25 -> "二十六夜"
        26, 27 -> "有明月"
        28 -> "暁月"
        else -> "晦日月"
    }
}
