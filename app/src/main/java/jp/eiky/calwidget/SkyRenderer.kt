package jp.eiky.calwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 日の出・日の入りと月の満ち欠け */
object SkyRenderer {

    enum class Kind { S, WIDE, TALL, NARROW }

    private val HM = DateTimeFormatter.ofPattern("H:mm")
    private val WEEK = arrayOf("日", "月", "火", "水", "木", "金", "土")

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
        val light = WidgetRenderer.isLight(pal.text)
        val dim = WidgetRenderer.withAlpha(pal.text, 0xB3)
        val sunColor = if (light) Graphics.SUN else 0xFFB07A00.toInt()
        val moonColor = if (light) Graphics.MOON_LIGHT else 0xFF8A6D1A.toInt()

        val rv = RemoteViews(
            pkg,
            when (kind) {
                Kind.S -> R.layout.widget_sky_s
                Kind.WIDE -> R.layout.widget_sky_wide
                Kind.TALL -> R.layout.widget_sky_tall
                Kind.NARROW -> R.layout.widget_sky_narrow
            },
        )
        WidgetRenderer.applyBg(rv, p)

        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val data = WeatherRepo.cached(ctx)
        val dToday = data?.days?.firstOrNull { it.date == today }
        val dTomorrow = data?.days?.firstOrNull { it.date == today.plusDays(1) }
        val sunrise = dToday?.sunrise
        val sunset = dToday?.sunset

        // 次の出来事（日の出 or 日の入り）
        val (label, at) = when {
            sunrise != null && now.isBefore(sunrise) -> "日の出" to sunrise
            sunset != null && now.isBefore(sunset) -> "日の入り" to sunset
            else -> "日の出" to dTomorrow?.sunrise
        }
        val left = at?.let {
            val m = Duration.between(now, it).toMinutes().coerceAtLeast(0)
            "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
        }
        val remain = when {
            left == null -> "取得中…"
            kind == Kind.WIDE -> "${label}まで $left"
            kind == Kind.NARROW -> "あと$left"
            else -> "あと $left"
        }
        val other = if (label == "日の入り") sunrise?.let { "日の出 ${it.format(HM)}" }
        else sunset?.let { "日の入り ${it.format(HM)}" }
        val dayLen = if (sunrise != null && sunset != null) {
            val m = Duration.between(sunrise, sunset).toMinutes()
            "昼 ${m / 60}:${(m % 60).toString().padStart(2, '0')}"
        } else ""

        // 月（学術的な数値を主役に、和名は小さく）
        val age = Moon.age(now)
        val illum = Moon.illumination(now)
        val stage = Moon.stage(now)
        val wamei = Moon.name(age)
        val full = Moon.next(Moon.Phase.FULL, now)
        val fullText = "望 ${full.monthValue}/${full.dayOfMonth} ${full.format(HM)}"
        val ageText = "月齢 %.1f".format(age)
        val otherShort = if (label == "日の入り") sunrise?.let { "↑${it.format(HM)}" }
        else sunset?.let { "↓${it.format(HM)}" }

        fun text(viewId: Int, t: CharSequence, color: Int) {
            rv.setTextViewText(viewId, t)
            rv.setTextColor(viewId, color)
        }

        if (kind != Kind.WIDE) text(R.id.event_label, label, dim)
        text(R.id.event_time, at?.format(HM) ?: "--:--", sunColor)
        text(R.id.remain, remain, dim)
        text(R.id.place, WeatherRepo.place(ctx).name, pal.text)

        val stripLen: Float
        val stripTh: Float
        val vertical = kind == Kind.NARROW
        when (kind) {
            Kind.S -> { stripLen = wDp - 24; stripTh = 3f }
            Kind.WIDE -> { stripLen = (wDp - 28 - 96 - 14 - 52 - 78).coerceAtLeast(60f); stripTh = 6f }
            Kind.TALL -> { stripLen = wDp - 24; stripTh = 4f }
            Kind.NARROW -> { stripLen = hDp - 24; stripTh = 2f }
        }
        rv.setImageViewBitmap(
            R.id.strip,
            Graphics.dayStrip(ctx, stripLen, stripTh, vertical, sunrise, sunset, now, pal.text),
        )

        val moonDp = when (kind) {
            Kind.S -> 30f
            Kind.WIDE -> 40f
            Kind.TALL -> 44f
            Kind.NARROW -> 40f
        }
        rv.setImageViewBitmap(R.id.moon, Graphics.moon(ctx, moonDp, age, pal.text))

        when (kind) {
            Kind.S -> Unit
            Kind.WIDE -> {
                text(R.id.sub1, other ?: "", dim)
                text(R.id.sub2, dayLen, dim)
                text(R.id.moon_name, ageText, moonColor)
                text(R.id.moon_age, wamei, WidgetRenderer.withAlpha(pal.text, 0x8C))
                text(R.id.moon_next, fullText, dim)
            }
            Kind.TALL -> {
                text(R.id.moon_name, ageText, moonColor)
                text(R.id.moon_age, "輝面比 %.2f".format(illum), dim)
                text(R.id.moon_next, "$stage　$fullText", dim)
                text(R.id.moon_jp, wamei, WidgetRenderer.withAlpha(pal.text, 0x8C))
                text(R.id.sub1, other ?: "", dim)
                rv.setInt(R.id.divider, "setBackgroundColor", WidgetRenderer.withAlpha(pal.text, 0x2E))
            }
            Kind.NARROW -> {
                text(R.id.sub1, otherShort ?: "", dim)
                text(R.id.moon_name, "月齢%.1f".format(age), moonColor)
                val sb = android.text.SpannableStringBuilder("望 ${full.monthValue}/${full.dayOfMonth}\n")
                val st = sb.length
                sb.append(wamei)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.9f), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(WidgetRenderer.withAlpha(pal.text, 0x8C)),
                    st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text(R.id.moon_next, sb, dim)
            }
        }

        if (interactive) rv.setOnClickPendingIntent(R.id.root, refreshIntent(ctx, id))
        return rv
    }

    private fun refreshIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, SkyWidgetProvider::class.java)
            .setAction(WxBaseProvider.ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id * 10 + 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** ウィジェットの実寸（dp）。縦持ちでは 幅＝最小幅、高さ＝最大高さ */
object WidgetSize {
    fun of(options: Bundle?): Pair<Float, Float> {
        val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        return (if (w > 0) w.toFloat() else 170f) to (if (h > 0) h.toFloat() else 90f)
    }
}
