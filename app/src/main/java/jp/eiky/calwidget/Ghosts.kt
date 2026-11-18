package jp.eiky.calwidget

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 時間帯・天気・季節によって現れるおばけたち。
 * 重なったときの優先順位：季節 → レア → 天気 → 時間帯（時間帯は1%でキンピカに化ける）
 */
object Ghosts {

    enum class Def(val label: String, val phrase: String, val res: Int, val surprised: Int) {
        // 時間帯
        AKUBI("アクビ", "ふぁ〜あ", R.drawable.gh_akubi, R.drawable.gh_akubi_s),
        HARAPEKO("ハラペコ", "はらへった", R.drawable.gh_harapeko, R.drawable.gh_harapeko_s),
        POCCHI("ポッチ", "おやつは？", R.drawable.gh_pocchi, R.drawable.gh_pocchi_s),
        HITOTSUME("ヒトツメ", "またあした", R.drawable.gh_hitotsume, R.drawable.gh_hitotsume_s),
        NEMURI("ネムリ", "すやぁ", R.drawable.gh_nemuri, R.drawable.gh_nemuri_s),
        OBAKE("おばけ", "うらめしや…", R.drawable.gh_obake, R.drawable.gh_obake_s),
        // レア
        KUROSUKE("クロスケ", "…みてる", R.drawable.gh_kurosuke, R.drawable.gh_kurosuke_s),
        KINPIKA("キンピカ", "あたり！", R.drawable.gh_kinpika, R.drawable.gh_kinpika_s),
        ZOROME("ゾロメ", "ぴったり", R.drawable.gh_zorome, R.drawable.gh_zorome_s),
        TSUKIMI("ツキミ", "いい月だね", R.drawable.gh_tsukimi, R.drawable.gh_tsukimi_s),
        // 天気
        AMAGAPPA("アマガッパ", "ぴちょん", R.drawable.gh_amagappa, R.drawable.gh_amagappa_s),
        YUKINKO("ユキンコ", "さむっ", R.drawable.gh_yukinko, R.drawable.gh_yukinko_s),
        GOROSUKE("ゴロスケ", "ゴロゴロ", R.drawable.gh_gorosuke, R.drawable.gh_gorosuke_s),
        TOROKE("トロケ", "とけそう", R.drawable.gh_toroke, R.drawable.gh_toroke_s),
        // 季節
        KABOCHAN("カボチャン", "トリック？", R.drawable.gh_kabochan, R.drawable.gh_kabochan_s),
        CHOCHIN("チョウチン", "おかえり", R.drawable.gh_chochin, R.drawable.gh_chochin_s),
        SANTA("サンタロウ", "メリー", R.drawable.gh_santa, R.drawable.gh_santa_s),
        KOUHAKU("コウハク", "あけおめ", R.drawable.gh_kouhaku, R.drawable.gh_kouhaku_s),
    }

    data class Slot(val def: Def, val start: LocalDateTime, val end: LocalDateTime) {
        val key: String get() = "${def.name}@$start"
        fun activeAt(t: LocalDateTime) = !t.isBefore(start) && t.isBefore(end)
    }

    /** 画面に出すもの */
    data class Active(val key: String, val res: Int, val phrase: String)

    private const val FILE = "ghosts"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- 時刻で決まるもの ----------

    private fun seasonal(d: LocalDate): List<Slot> {
        val out = ArrayList<Slot>()
        val m = d.monthValue
        val day = d.dayOfMonth
        if (m == 10 && day == 31) out.add(Slot(Def.KABOCHAN, d.atStartOfDay(), d.plusDays(1).atStartOfDay()))
        if (m == 8 && day in 13..16) out.add(Slot(Def.CHOCHIN, d.atTime(18, 0), d.plusDays(1).atStartOfDay()))
        if (m == 12 && day in 24..25) out.add(Slot(Def.SANTA, d.atTime(18, 0), d.atTime(22, 0)))
        if (m == 1 && day == 1) out.add(Slot(Def.KOUHAKU, d.atStartOfDay(), d.atTime(12, 0)))
        return out
    }

    private fun rare(d: LocalDate): List<Slot> {
        val out = ArrayList<Slot>()
        out.add(Slot(Def.KUROSUKE, d.atTime(4, 44), d.atTime(4, 45)))
        out.add(Slot(Def.ZOROME, d.atTime(11, 11), d.atTime(11, 12)))
        out.add(Slot(Def.ZOROME, d.atTime(22, 22), d.atTime(22, 23)))
        if (isFullMoonNight(d)) out.add(Slot(Def.TSUKIMI, d.atTime(20, 0), d.plusDays(1).atStartOfDay()))
        return out
    }

    private fun timeSlots(d: LocalDate, sunsets: Map<LocalDate, LocalDateTime>): List<Slot> {
        val out = ArrayList<Slot>()
        out.add(Slot(Def.AKUBI, d.atTime(5, 0), d.atTime(6, 0)))
        out.add(Slot(Def.HARAPEKO, d.atTime(12, 0), d.atTime(13, 0)))
        out.add(Slot(Def.POCCHI, d.atTime(15, 0), d.atTime(15, 30)))
        sunsets[d]?.let { out.add(Slot(Def.HITOTSUME, it.minusMinutes(30), it.plusMinutes(30))) }
        out.add(Slot(Def.NEMURI, d.atTime(23, 0), d.plusDays(1).atTime(1, 0)))
        out.add(Slot(Def.OBAKE, d.atTime(2, 0), d.atTime(2, 30)))
        return out
    }

    /** 満月の夜か（その日の22時の月齢が満月から±半日以内） */
    private fun isFullMoonNight(d: LocalDate): Boolean {
        val ref = ZonedDateTime.of(2000, 1, 6, 18, 14, 0, 0, ZoneId.of("UTC")).toInstant()
        val t = d.atTime(22, 0).atZone(ZoneId.systemDefault()).toInstant()
        val days = (t.toEpochMilli() - ref.toEpochMilli()) / 86_400_000.0
        val synodic = 29.530588853
        val age = ((days % synodic) + synodic) % synodic
        return abs(age - synodic / 2) <= 0.5
    }

    // ---------- 天気で決まるもの ----------

    private fun weather(now: LocalDateTime, data: WxData?): List<Slot> {
        if (data == null) return emptyList()
        val out = ArrayList<Slot>()
        val today = now.toLocalDate()
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        val code = data.now.code
        // 雷：いま雷雨か、2時間以内に雷雨の予報
        if (code in 95..99 || data.hours.take(2).any { it.code in 95..99 }) {
            out.add(Slot(Def.GOROSUKE, hourStart, hourStart.plusHours(1)))
        }
        // 雨の降り始め：1時間前は降っていなくて、いま雨
        val prev = data.prevCode
        if (WxCode.cat(code) == WxCode.Cat.RAIN && prev != null && !WxCode.wet(prev)) {
            out.add(Slot(Def.AMAGAPPA, hourStart, hourStart.plusMinutes(30)))
        }
        // 雪の朝
        if (WxCode.cat(code) == WxCode.Cat.SNOW) {
            out.add(Slot(Def.YUKINKO, today.atTime(7, 0), today.atTime(9, 0)))
        }
        // 真夏日の昼
        val max = data.days.firstOrNull()?.max ?: Double.NaN
        if (!max.isNaN() && max >= 30.0) {
            out.add(Slot(Def.TOROKE, today.atTime(13, 0), today.atTime(14, 0)))
        }
        return out
    }

    // ---------- 選ぶ ----------

    private fun sunsets(data: WxData?): Map<LocalDate, LocalDateTime> =
        data?.days?.mapNotNull { d -> d.sunset?.let { d.date to it } }?.toMap() ?: emptyMap()

    private fun days(now: LocalDateTime) = now.toLocalDate().let { listOf(it.minusDays(1), it, it.plusDays(1)) }

    /** 1%の確率でキンピカ（同じ時間帯の中では結果が変わらないよう、キーから決める） */
    private fun isKinpika(slot: Slot): Boolean = ((slot.key.hashCode() and 0x7fffffff) % 100) == 0

    private fun pick(now: LocalDateTime, data: WxData?): Slot? {
        val ds = days(now)
        ds.flatMap { seasonal(it) }.firstOrNull { it.activeAt(now) }?.let { return it }
        ds.flatMap { rare(it) }.firstOrNull { it.activeAt(now) }?.let { return it }
        weather(now, data).firstOrNull { it.activeAt(now) }?.let { return it }
        val sun = sunsets(data)
        val slot = ds.flatMap { timeSlots(it, sun) }.firstOrNull { it.activeAt(now) } ?: return null
        return if (isKinpika(slot)) slot.copy(def = Def.KINPIKA) else slot
    }

    fun current(c: Context, data: WxData?, now: LocalDateTime = LocalDateTime.now()): Active? {
        val slot = pick(now, data) ?: return null
        val key = slot.key
        val s = sp(c)
        if (s.getString("dismissed", null) == key) return null
        val surprised = s.getString("surprised", null) == key
        val rainy = data != null && WxCode.wet(data.now.code)
        val res = when {
            slot.def == Def.OBAKE && rainy -> if (surprised) R.drawable.gh_obake_rain_s else R.drawable.gh_obake_rain
            surprised -> slot.def.surprised
            else -> slot.def.res
        }
        return Active(key, res, if (surprised) "ひゃっ" else slot.def.phrase)
    }

    /** 次に誰かが出入りしうる時刻（ウィジェットを書き換えるタイミング） */
    fun nextBoundary(data: WxData?, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val ds = days(now)
        val sun = sunsets(data)
        val fixed = ds.flatMap { seasonal(it) + rare(it) + timeSlots(it, sun) } + weather(now, data)
        // 天気のおばけは毎時0分・30分に判定し直す
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        val halfHours = listOf(hourStart.plusMinutes(30), hourStart.plusHours(1))
        return (fixed.flatMap { listOf(it.start, it.end) } + halfHours)
            .filter { it.isAfter(now) }
            .minOrNull() ?: now.plusMinutes(30)
    }

    /** 設定画面用：今日の出現予定と状態 */
    fun schedule(c: Context, data: WxData?, now: LocalDateTime = LocalDateTime.now()): String {
        val today = now.toLocalDate()
        val dismissed = sp(c).getString("dismissed", null)
        val active = pick(now, data)
        val fmt = DateTimeFormatter.ofPattern("H:mm")
        val all = seasonal(today) + rare(today) + timeSlots(today, sunsets(data)) +
            weather(now, data).filter { it.start.toLocalDate() == today }
        val lines = all.distinctBy { it.key }
            .sortedBy { it.start }
            .map {
                val state = when {
                    it.key == dismissed -> "（タップで退散済み）"
                    active != null && active.start == it.start && active.end == it.end &&
                        (active.def == it.def || active.def == Def.KINPIKA) ->
                        if (active.def == Def.KINPIKA) "← いま出現中（キンピカ！）" else "← いま出現中"
                    else -> ""
                }
                "${it.start.format(fmt)}〜${it.end.format(fmt)}　${it.def.label}$state"
            }
        val notes = ArrayList<String>()
        if (sunsets(data)[today] == null) notes.add("※日の入り時刻が未取得のためヒトツメは出ません")
        notes.add("ほかに、雨の降り始め・雪の朝・雷・30°超えの日に天気のおばけが出ます")
        return (lines + notes).joinToString("\n")
    }

    /** 退散させたおばけを呼び戻す */
    fun reset(c: Context) {
        sp(c).edit().remove("dismissed").remove("surprised").apply()
    }

    fun setSurprised(c: Context, key: String) {
        sp(c).edit().putString("surprised", key).apply()
    }

    fun dismiss(c: Context, key: String) {
        sp(c).edit().putString("dismissed", key).remove("surprised").apply()
    }
}
