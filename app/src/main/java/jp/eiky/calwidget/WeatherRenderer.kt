package jp.eiky.calwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * 天気ウィジェットの描画（C案：地名・▲▼・気温・細い時間バー）
 * 時間帯によっておばけが現れ、タップすると驚いて消える。
 */
object WeatherRenderer {

    enum class Kind {
        S,      // 2×1
        WIDE,   // 4×1
        TALL,   // 2×2：2×1の内容＋5日間
        FULL,   // 4×2以上：4×1の内容＋5日間
        NARROW, // 1×2
    }

    private val WEEKDAYS = arrayOf("日", "月", "火", "水", "木", "金", "土")
    private val LABEL_IDS = intArrayOf(R.id.lab0, R.id.lab1, R.id.lab2, R.id.lab3)

    fun build(ctx: Context, id: Int, p: WidgetPrefs, options: Bundle?): RemoteViews {
        val data = WeatherRepo.cached(ctx)
        val place = WeatherRepo.place(ctx)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(
                mapOf(
                    SizeF(40f, 30f) to buildKind(ctx, id, p, Kind.S, true, data, place),
                    SizeF(40f, 140f) to buildKind(ctx, id, p, Kind.NARROW, true, data, place),
                    SizeF(230f, 30f) to buildKind(ctx, id, p, Kind.WIDE, true, data, place),
                    SizeF(130f, 140f) to buildKind(ctx, id, p, Kind.TALL, true, data, place),
                    SizeF(250f, 170f) to buildKind(ctx, id, p, Kind.FULL, true, data, place),
                )
            )
        } else {
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val kind = when {
                w >= 250 && h >= 170 -> Kind.FULL
                w >= 130 && h >= 140 -> Kind.TALL
                w < 130 && h >= 140 -> Kind.NARROW
                w >= 230 -> Kind.WIDE
                else -> Kind.S
            }
            buildKind(ctx, id, p, kind, true, data, place)
        }
    }

    fun buildKind(
        ctx: Context, id: Int, p: WidgetPrefs, kind: Kind, interactive: Boolean, data: WxData?, place: Place,
    ): RemoteViews {
        val pkg = ctx.packageName
        val pal = WidgetRenderer.palette(p)
        val lightText = WidgetRenderer.isLight(pal.text)
        val dim = WidgetRenderer.withAlpha(pal.text, 0xB3)
        val hiColor = if (lightText) 0xFFFF9E80.toInt() else 0xFFD84315.toInt()
        val loColor = if (lightText) 0xFF9FE1E8.toInt() else 0xFF0E7C86.toInt()

        val narrow = kind == Kind.NARROW
        val wideStyle = kind == Kind.WIDE || kind == Kind.FULL   // 上段に概況・下に時刻ラベル
        val hasNowLine = kind == Kind.S || kind == Kind.TALL      // 「今 …… 19時から雨」
        val hasLabels = wideStyle || narrow || kind == Kind.TALL
        val hasDaily = kind == Kind.TALL || kind == Kind.FULL

        val rv = RemoteViews(
            pkg,
            when (kind) {
                Kind.S -> R.layout.widget_weather_s
                Kind.WIDE -> R.layout.widget_weather_wide
                Kind.NARROW -> R.layout.widget_weather_narrow
                Kind.TALL -> R.layout.widget_weather_tall
                Kind.FULL -> R.layout.widget_weather_full
            },
        )
        WidgetRenderer.applyBg(rv, p)

        rv.setTextViewText(R.id.place, place.name)
        rv.setTextColor(R.id.place, pal.text)
        rv.setTextColor(R.id.temp, pal.text)
        rv.setTextColor(R.id.msg, dim)
        if (hasNowLine) {
            rv.setTextColor(R.id.now_label, dim)
            rv.setTextColor(R.id.summary, dim)
        }
        if (wideStyle) rv.setTextColor(R.id.summary, dim)
        rv.removeAllViews(R.id.bar)

        if (data == null) {
            rv.setTextViewText(R.id.temp, "--°")
            rv.setTextViewText(R.id.hilo, "")
            if (!narrow) rv.setTextViewText(R.id.summary, "取得中…")
            if (hasNowLine) rv.setTextViewText(R.id.now_label, "")
            if (hasLabels) LABEL_IDS.forEach { rv.setTextViewText(it, "") }
            if (hasDaily) rv.removeAllViews(R.id.daily_row)
        } else {
            rv.setTextViewText(R.id.temp, "${t(data.now.temp)}°")

            val today = data.days.firstOrNull()
            val deg = if (narrow) "" else "°"
            val hilo = SpannableStringBuilder()
            if (today != null) {
                colored(hilo, "▲${t(today.max)}$deg", hiColor)
                hilo.append(if (narrow) " " else "  ")
                colored(hilo, "▼${t(today.min)}$deg", loColor)
            }
            rv.setTextViewText(R.id.hilo, hilo)

            if (!narrow) rv.setTextViewText(R.id.summary, summary(data))
            if (hasNowLine) rv.setTextViewText(R.id.now_label, "今")

            // 時間バー：1時間＝1マス（12時間ぶん）
            for (h in data.hours) {
                val seg = RemoteViews(pkg, if (narrow) R.layout.bar_seg_v else R.layout.bar_seg_h)
                seg.setInt(R.id.seg, "setBackgroundColor", segColor(h))
                rv.addView(R.id.bar, seg)
            }

            // 時刻ラベル：3時間ごとに4つ。雨・雪があればその確率を青で
            if (hasLabels) {
                for (i in 0 until 4) {
                    val group = data.hours.drop(i * 3).take(3)
                    val sb = SpannableStringBuilder()
                    if (group.isNotEmpty()) {
                        sb.append("${group[0].hour}時")
                        val wet = group.filter { WxCode.wet(it.code) }
                        val pop = group.maxOf { it.pop }
                        if (wet.isNotEmpty() && pop >= 0) {
                            val word = if (narrow) {
                                if (wet.any { WxCode.cat(it.code) == WxCode.Cat.SNOW }) "\n雪" else "\n雨"
                            } else " "
                            colored(sb, "$word$pop%", pal.sat)
                        }
                    }
                    rv.setTextViewText(LABEL_IDS[i], sb)
                    rv.setTextColor(LABEL_IDS[i], dim)
                }
            }

            if (hasDaily) {
                rv.removeAllViews(R.id.daily_row)
                data.days.drop(1).take(5).forEachIndexed { i, d ->
                    val c = RemoteViews(pkg, R.layout.cell_wx_l)
                    c.setTextViewText(R.id.label, if (i == 0) "明日" else WEEKDAYS[d.date.dayOfWeek.value % 7])
                    c.setTextColor(R.id.label, dim)
                    c.setImageViewResource(R.id.icon, WxCode.icon(d.code, true))
                    c.setTextViewText(R.id.main, "▲${t(d.max)}")
                    c.setTextColor(R.id.main, hiColor)
                    c.setTextViewText(R.id.sub, "▼${t(d.min)}")
                    c.setTextColor(R.id.sub, loColor)
                    c.setTextViewText(R.id.pop, if (d.pop >= 0) "${d.pop}%" else "")
                    c.setTextColor(R.id.pop, pal.sat)
                    rv.addView(R.id.daily_row, c)
                }
            }
        }

        // おばけ
        val ghost = Ghosts.current(ctx, data)
        if (ghost != null) {
            rv.setImageViewResource(R.id.ghost, ghost.res)
            rv.setViewVisibility(R.id.ghost, View.VISIBLE)
            rv.setViewVisibility(R.id.bar_box, View.GONE)
            rv.setViewVisibility(R.id.msg, View.VISIBLE)
            rv.setTextViewText(R.id.msg, ghost.phrase)
            if (interactive) rv.setOnClickPendingIntent(R.id.ghost, ghostIntent(ctx, id, ghost.key))
        } else {
            rv.setViewVisibility(R.id.ghost, View.GONE)
            rv.setViewVisibility(R.id.bar_box, View.VISIBLE)
            rv.setViewVisibility(R.id.msg, View.GONE)
        }

        if (interactive) rv.setOnClickPendingIntent(R.id.root, refreshIntent(ctx, id))
        return rv
    }

    /** 「19時から雨」「15時ごろやむ」「しばらく晴れ」など */
    private fun summary(d: WxData): String {
        val nowCat = WxCode.cat(d.now.code)
        val hs = d.hours
        if (WxCode.wet(d.now.code)) {
            val stop = hs.firstOrNull { !WxCode.wet(it.code) }
            return when {
                stop != null -> "${stop.hour}時ごろやむ"
                nowCat == WxCode.Cat.SNOW -> "雪が続く"
                else -> "雨が続く"
            }
        }
        val wet = hs.firstOrNull { WxCode.wet(it.code) }
        if (wet != null) {
            val w = if (WxCode.cat(wet.code) == WxCode.Cat.SNOW) "雪" else "雨"
            return "${wet.hour}時から$w"
        }
        val change = hs.take(6).firstOrNull { WxCode.cat(it.code) != nowCat }
        return when {
            change != null && nowCat == WxCode.Cat.CLEAR -> "${change.hour}時から曇り"
            change != null -> "${change.hour}時から晴れ"
            nowCat == WxCode.Cat.CLEAR -> "しばらく晴れ"
            else -> "しばらく曇り"
        }
    }

    private fun segColor(h: WxHour): Int = when (WxCode.cat(h.code)) {
        WxCode.Cat.CLEAR -> if (h.isDay) 0xFFF2C230.toInt() else 0xFFF4E7B0.toInt()
        WxCode.Cat.CLOUD -> if (h.isDay) 0xFFB8BFC9.toInt() else 0xFF8A929E.toInt()
        WxCode.Cat.RAIN -> 0xFF4FA3FF.toInt()
        WxCode.Cat.SNOW -> 0xFFFFFFFF.toInt()
    }

    private fun colored(sb: SpannableStringBuilder, text: String, color: Int) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun t(v: Double): String = if (v.isNaN()) "--" else v.roundToInt().toString()

    private fun refreshIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, WeatherWidgetProvider::class.java)
            .setAction(WeatherWidgetProvider.ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id * 10 + 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ghostIntent(ctx: Context, id: Int, key: String): PendingIntent {
        val i = Intent(ctx, WeatherWidgetProvider::class.java)
            .setAction(WeatherWidgetProvider.ACTION_GHOST)
            .putExtra(WeatherWidgetProvider.EXTRA_GHOST_KEY, key)
        return PendingIntent.getBroadcast(
            ctx, id * 10 + 6, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
