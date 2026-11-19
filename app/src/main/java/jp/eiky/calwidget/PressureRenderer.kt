package jp.eiky.calwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import java.time.LocalDateTime
import kotlin.math.abs

/** 気圧：今の値・変化の向き・前後12時間のグラフ・観測係のひとこと */
object PressureRenderer {

    enum class Kind { S, WIDE, TALL, NARROW }

    enum class Trend(val label: String) {
        FAST_DOWN("↓↓ 急降下"), DOWN("↓ 低下"), STABLE("→ 安定"), UP("↑ 上昇"), FAST_UP("↑↑ 急上昇")
    }

    /** 気圧の読み取り結果 */
    data class Reading(
        val now: Double,
        val trend: Trend,
        val deltaText: String,   // 「23時まで −8.2」など
        val shortDelta: String,  // 「↓ −8.2」など
        val message: String,
    )

    // ---------- ひとこと ----------
    private val MSG = mapOf(
        "FAST_DOWN" to listOf("ぐんぐん下がるよ", "むりしないでね", "はやめにおやすみ", "きょうはゆるっといこ", "あったかくしてね"),
        "DOWN" to listOf("かさ、持った？", "くもさん接近中", "空がおねむかも", "ゆっくり下り坂", "洗濯物ちゅうい"),
        "STABLE" to listOf("ごきげんな空", "のんびりいこ", "しずかな一日", "いい感じの空気", "へいわな気圧"),
        "UP" to listOf("晴れてくるかも", "空すっきり", "気分も上向き", "のびーってしよ", "まどあけよ"),
        "FAST_UP" to listOf("ぐいっと回復！", "空がはりきってる", "一気に晴れそう", "風に気をつけて"),
        "BOTTOM" to listOf("ここが底だよ", "あとは上がるだけ", "もうすこしの辛抱"),
        "PEAK" to listOf("ここがてっぺん", "このあと下り坂", "いまのうちにおでかけ"),
        "STANDARD" to listOf("いま標準気圧！", "きっちり1気圧"),
        "LOW" to listOf("低気圧さん来たよ", "空がざわざわ", "おうち日和かも"),
        "TYPHOON" to listOf("台風なみの低さ！", "そとはあぶないよ", "しっかり備えてね"),
        "HIGH" to listOf("おさんぽ日和", "高気圧さんの中", "空がひろいね"),
        "RAIN" to listOf("しとしとタイム", "雨音きこえる？", "かさの出番だよ"),
        "MORNING" to listOf("おはよ、気圧チェック", "きょうも観測するよ"),
        "NIGHT" to listOf("きょうもおつかれさま", "夜の空はしずか"),
    )

    fun read(data: WxData, now: LocalDateTime = LocalDateTime.now()): Reading? {
        val pr = data.pressure ?: return null
        val v = pr.values
        val ni = pr.nowIndex
        val p0 = data.now.pressure.takeUnless { it.isNaN() } ?: v.getOrNull(ni)?.takeUnless { it.isNaN() } ?: return null

        fun at(i: Int): Double? = v.getOrNull(i)?.takeUnless { it.isNaN() }
        val d6 = (at(ni + 6) ?: at(v.lastIndex))?.minus(p0) ?: 0.0
        val trend = when {
            d6 <= -5 -> Trend.FAST_DOWN
            d6 <= -1.5 -> Trend.DOWN
            d6 >= 5 -> Trend.FAST_UP
            d6 >= 1.5 -> Trend.UP
            else -> Trend.STABLE
        }

        // この先12時間の最低・最高
        val future = (ni + 1..v.lastIndex).mapNotNull { i -> at(i)?.let { i to it } }
        val minF = future.minByOrNull { it.second }
        val maxF = future.maxByOrNull { it.second }
        fun hourOf(i: Int) = pr.times.getOrNull(i)?.hour
        val (deltaText, shortDelta) = when {
            trend == Trend.FAST_DOWN || trend == Trend.DOWN -> minF?.let {
                val dd = it.second - p0
                "${hourOf(it.first)}時まで ${sign(dd)}" to "↓ ${sign(dd)}"
            } ?: ("" to "")
            trend == Trend.FAST_UP || trend == Trend.UP -> maxF?.let {
                val dd = it.second - p0
                "${hourOf(it.first)}時まで ${sign(dd)}" to "↑ ${sign(dd)}"
            } ?: ("" to "")
            else -> {
                val span = future.maxOfOrNull { abs(it.second - p0) } ?: 0.0
                "12時間 ±%.1f".format(span) to "→ ±%.1f".format(span)
            }
        }

        // 節目（今が底・てっぺん）
        val past6 = (ni - 6 until ni).mapNotNull { at(it) }
        val next6 = (ni + 1..ni + 6).mapNotNull { at(it) }
        val isBottom = past6.isNotEmpty() && next6.isNotEmpty() &&
            past6.all { it >= p0 } && next6.all { it >= p0 } &&
            past6.maxOrNull()!! - p0 >= 2 && next6.maxOrNull()!! - p0 >= 1.5
        val isPeak = past6.isNotEmpty() && next6.isNotEmpty() &&
            past6.all { it <= p0 } && next6.all { it <= p0 } &&
            p0 - past6.minOrNull()!! >= 2 && p0 - next6.minOrNull()!! >= 1.5

        val state = when {
            isBottom -> "BOTTOM"
            isPeak -> "PEAK"
            abs(p0 - 1013.25) <= 0.3 -> "STANDARD"
            p0 < 985 -> "TYPHOON"
            p0 < 1000 -> "LOW"
            WxCode.cat(data.now.code) == WxCode.Cat.RAIN -> "RAIN"
            p0 > 1025 -> "HIGH"
            else -> trend.name
        }
        val pool = ArrayList(MSG[state] ?: MSG["STABLE"]!!)
        if (now.hour in 5..8) pool.addAll(MSG["MORNING"]!!)
        if (now.hour >= 21) pool.addAll(MSG["NIGHT"]!!)
        val pick = (now.dayOfYear * 31 + now.hour) % pool.size
        return Reading(p0, trend, deltaText, shortDelta, pool[pick])
    }

    private fun sign(d: Double) = if (d >= 0) "+%.1f".format(d) else "−%.1f".format(-d)

    // ---------- 描画 ----------
    fun build(ctx: Context, id: Int, p: WidgetPrefs, options: Bundle?): RemoteViews {
        val (w, h) = WidgetSize.of(options)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(
                mapOf(
                    SizeF(40f, 30f) to buildKind(ctx, id, p, Kind.S, true, w, h),
                    SizeF(40f, 140f) to buildKind(ctx, id, p, Kind.NARROW, true, w, h),
                    SizeF(230f, 30f) to buildKind(ctx, id, p, Kind.WIDE, true, w, h),
                    SizeF(130f, 140f) to buildKind(ctx, id, p, Kind.TALL, true, w, h),
                )
            )
        } else {
            val kind = when {
                w >= 130 && h >= 140 -> Kind.TALL
                w < 130 && h >= 140 -> Kind.NARROW
                w >= 230 -> Kind.WIDE
                else -> Kind.S
            }
            buildKind(ctx, id, p, kind, true, w, h)
        }
    }

    fun buildKind(
        ctx: Context, id: Int, p: WidgetPrefs, kind: Kind, interactive: Boolean, wDp: Float, hDp: Float,
    ): RemoteViews {
        val pkg = ctx.packageName
        val pal = WidgetRenderer.palette(p)
        val dim = WidgetRenderer.withAlpha(pal.text, 0xB3)
        val rv = RemoteViews(
            pkg,
            when (kind) {
                Kind.S -> R.layout.widget_pres_s
                Kind.WIDE -> R.layout.widget_pres_wide
                Kind.TALL -> R.layout.widget_pres_tall
                Kind.NARROW -> R.layout.widget_pres_narrow
            },
        )
        WidgetRenderer.applyBg(rv, p)

        val data = WeatherRepo.cached(ctx)
        val r = data?.let { read(it) }
        val place = WeatherRepo.place(ctx).name

        fun text(viewId: Int, t: CharSequence, color: Int) {
            rv.setTextViewText(viewId, t)
            rv.setTextColor(viewId, color)
        }

        text(R.id.title, if (kind == Kind.WIDE) "気圧 · $place" else if (kind == Kind.TALL) "気圧" else "気圧", dim)
        text(R.id.unit, "hPa", dim)
        if (kind == Kind.TALL) text(R.id.place, place, pal.text)

        if (r == null) {
            text(R.id.value, "----", pal.text)
            text(R.id.delta, "取得中…", dim)
            text(R.id.msg, "", dim)
        } else {
            val valueText = if (kind == Kind.NARROW) "%.0f".format(r.now) else "%.1f".format(r.now)
            text(R.id.value, valueText, pal.text)
            val deltaLine = when (kind) {
                Kind.TALL -> if (r.deltaText.isEmpty()) r.trend.label
                else "${r.trend.label.substringBefore(' ')} ${r.deltaText}"
                Kind.WIDE -> r.deltaText.ifEmpty { r.trend.label }
                else -> r.shortDelta.ifEmpty { r.trend.label }
            }
            val down = r.trend == Trend.FAST_DOWN || r.trend == Trend.DOWN
            text(R.id.delta, deltaLine, if (down) pal.sat else dim)
            text(R.id.msg, r.message, pal.text)

            val (cw, ch, axes) = when (kind) {
                Kind.S -> Triple(wDp - 24, 18f, false)
                Kind.WIDE -> Triple((wDp - 28 - 90 - 14).coerceAtLeast(80f), 42f, true)
                Kind.TALL -> Triple(wDp - 24, (hDp - 20 - 14 - 42 - 16 - 13 - 30).coerceIn(36f, 110f), true)
                Kind.NARROW -> Triple(wDp - 20, (hDp - 20 - 14 - 30 - 13 - 16 - 30 - 28).coerceIn(30f, 100f), false)
            }
            data?.pressure?.let {
                rv.setImageViewBitmap(R.id.chart, Graphics.pressureChart(ctx, cw, ch, it, axes, pal.text, pal.sat))
            }
        }

        // 観測係：おばけが出ている時間はその子が担当
        val ghost = Ghosts.current(ctx, data)
        rv.setImageViewResource(R.id.obs, ghost?.res ?: R.drawable.gh_kansoku)
        if (kind == Kind.TALL) rv.setInt(R.id.divider, "setBackgroundColor", WidgetRenderer.withAlpha(pal.text, 0x2E))

        if (interactive) rv.setOnClickPendingIntent(R.id.root, refreshIntent(ctx, id))
        return rv
    }

    private fun refreshIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, PressureWidgetProvider::class.java)
            .setAction(WxBaseProvider.ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id * 10 + 8, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
