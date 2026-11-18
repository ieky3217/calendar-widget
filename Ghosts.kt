package jp.eiky.calwidget

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime

/** 時間帯ごとに現れるおばけたち */
object Ghosts {

    enum class Def(val phrase: String, val res: Int, val surprised: Int) {
        KUROSUKE("…みてる", R.drawable.gh_kurosuke, R.drawable.gh_kurosuke_s),   // 4:44の1分間（レア）
        AKUBI("ふぁ〜あ", R.drawable.gh_akubi, R.drawable.gh_akubi_s),             // 5:00〜6:00
        HARAPEKO("はらへった", R.drawable.gh_harapeko, R.drawable.gh_harapeko_s),  // 12:00〜13:00
        POCCHI("おやつは？", R.drawable.gh_pocchi, R.drawable.gh_pocchi_s),         // 15:00〜15:30
        HITOTSUME("またあした", R.drawable.gh_hitotsume, R.drawable.gh_hitotsume_s), // 日の入り±30分
        NEMURI("すやぁ", R.drawable.gh_nemuri, R.drawable.gh_nemuri_s),             // 23:00〜1:00
        OBAKE("うらめしや…", R.drawable.gh_obake, R.drawable.gh_obake_s),          // 2:00〜2:30
    }

    data class Slot(val def: Def, val start: LocalDateTime, val end: LocalDateTime) {
        val key: String get() = "${def.name}@$start"
    }

    /** 画面に出すもの */
    data class Active(val key: String, val res: Int, val phrase: String)

    private const val FILE = "ghosts"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun slots(now: LocalDateTime, sunsets: Map<LocalDate, LocalDateTime>): List<Slot> {
        val out = ArrayList<Slot>()
        val today = now.toLocalDate()
        for (d in listOf(today.minusDays(1), today, today.plusDays(1))) {
            fun at(h: Int, m: Int) = d.atTime(h, m)
            out.add(Slot(Def.KUROSUKE, at(4, 44), at(4, 45)))
            out.add(Slot(Def.AKUBI, at(5, 0), at(6, 0)))
            out.add(Slot(Def.HARAPEKO, at(12, 0), at(13, 0)))
            out.add(Slot(Def.POCCHI, at(15, 0), at(15, 30)))
            sunsets[d]?.let { out.add(Slot(Def.HITOTSUME, it.minusMinutes(30), it.plusMinutes(30))) }
            out.add(Slot(Def.NEMURI, at(23, 0), d.plusDays(1).atTime(1, 0)))
            out.add(Slot(Def.OBAKE, at(2, 0), at(2, 30)))
        }
        return out
    }

    private fun sunsets(data: WxData?): Map<LocalDate, LocalDateTime> =
        data?.days?.mapNotNull { d -> d.sunset?.let { d.date to it } }?.toMap() ?: emptyMap()

    fun current(c: Context, data: WxData?, now: LocalDateTime = LocalDateTime.now()): Active? {
        val slot = slots(now, sunsets(data)).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) } ?: return null
        val key = slot.key
        val s = sp(c)
        if (s.getString("dismissed", null) == key) return null
        val surprised = s.getString("surprised", null) == key
        val rainy = data != null && WxCode.cat(data.now.code).let { it == WxCode.Cat.RAIN || it == WxCode.Cat.SNOW }
        val res = when {
            slot.def == Def.OBAKE && rainy -> if (surprised) R.drawable.gh_obake_rain_s else R.drawable.gh_obake_rain
            surprised -> slot.def.surprised
            else -> slot.def.res
        }
        return Active(key, res, if (surprised) "ひゃっ" else slot.def.phrase)
    }

    /** 次に誰かが出入りする時刻（ウィジェットを書き換えるタイミング） */
    fun nextBoundary(data: WxData?, now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        slots(now, sunsets(data))
            .flatMap { listOf(it.start, it.end) }
            .filter { it.isAfter(now) }
            .minOrNull() ?: now.plusHours(1)

    fun setSurprised(c: Context, key: String) {
        sp(c).edit().putString("surprised", key).apply()
    }

    fun dismiss(c: Context, key: String) {
        sp(c).edit().putString("dismissed", key).remove("surprised").apply()
    }
}
