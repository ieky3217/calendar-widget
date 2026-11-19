package jp.eiky.calwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ウィジェット用の小さな絵（ウィジェットは図形を描けないので、アプリ側でビットマップにする） */
object Graphics {

    const val SUN = 0xFFF2C230.toInt()
    const val MOON_LIGHT = 0xFFF4E7B0.toInt()
    const val MOON_DARKTEXT = 0xFFD9B84A.toInt()

    private fun d(ctx: Context) = ctx.resources.displayMetrics.density

    private fun bmp(w: Float, h: Float): Bitmap =
        Bitmap.createBitmap(max(2, w.roundToInt()), max(2, h.roundToInt()), Bitmap.Config.ARGB_8888)

    private fun minuteOfDay(t: LocalDateTime): Float = t.hour * 60f + t.minute

    // ---------- 24時間の帯（昼＝黄、現在＝縦線） ----------
    fun dayStrip(
        ctx: Context, lengthDp: Float, thicknessDp: Float, vertical: Boolean,
        sunrise: LocalDateTime?, sunset: LocalDateTime?, now: LocalDateTime, textColor: Int,
    ): Bitmap {
        val dn = d(ctx)
        val len = lengthDp * dn
        val th = thicknessDp * dn
        val over = 4f * dn // 現在線のはみ出し
        val cross = th + over * 2
        val b = if (vertical) bmp(cross, len) else bmp(len, cross)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rectAlong(from: Float, to: Float, inset: Float, color: Int) {
            p.color = color
            if (vertical) c.drawRect(inset, from, cross - inset, to, p)
            else c.drawRect(from, inset, to, cross - inset, p)
        }
        rectAlong(0f, len, over, WidgetRenderer.withAlpha(textColor, 0x33))
        if (sunrise != null && sunset != null) {
            val a = minuteOfDay(sunrise) / 1440f * len
            val z = minuteOfDay(sunset) / 1440f * len
            if (z > a) rectAlong(a, z, over, SUN)
        }
        val n = minuteOfDay(now) / 1440f * len
        val lw = 2f * dn
        rectAlong(max(0f, n - lw / 2), min(len, n + lw / 2), 0f, textColor)
        return b
    }

    // ---------- 月の満ち欠け ----------
    fun moon(ctx: Context, sizeDp: Float, age: Double, textColor: Int): Bitmap {
        val dn = d(ctx)
        val s = sizeDp * dn
        val b = bmp(s, s)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = s / 2f - dn
        val cx = s / 2f
        val cy = s / 2f
        p.color = WidgetRenderer.withAlpha(textColor, 0x2E)
        c.drawCircle(cx, cy, r, p)

        val phase = 2 * Math.PI * age / Moon.SYNODIC
        val cosv = cos(phase)
        val rx = (r * abs(cosv)).toFloat()
        val waxing = age < Moon.SYNODIC / 2
        val gibbous = cosv < 0
        val circle = RectF(cx - r, cy - r, cx + r, cy + r)
        val ellipse = RectF(cx - rx, cy - r, cx + rx, cy + r)
        val path = Path()
        path.moveTo(cx, cy - r)
        if (waxing) {
            path.arcTo(circle, -90f, 180f)                       // 右半分（上→右→下）
            path.arcTo(ellipse, 90f, if (gibbous) 180f else -180f) // 欠け際（下→上）
        } else {
            path.arcTo(circle, -90f, -180f)                      // 左半分（上→左→下）
            path.arcTo(ellipse, 90f, if (gibbous) -180f else 180f)
        }
        path.close()
        p.color = if (WidgetRenderer.isLight(textColor)) MOON_LIGHT else MOON_DARKTEXT
        c.drawPath(path, p)
        return b
    }

    // ---------- 気圧グラフ（細線・目盛り付き） ----------
    fun pressureChart(
        ctx: Context, wDp: Float, hDp: Float, pr: WxPressure, axes: Boolean,
        textColor: Int, futureColor: Int,
    ): Bitmap {
        val dn = d(ctx)
        val w = wDp * dn
        val h = hDp * dn
        val b = bmp(w, h)
        val c = Canvas(b)
        val vals = pr.values
        val valid = vals.filter { !it.isNaN() }
        if (valid.size < 2) return b

        val std = 1013.25
        var lo = min(valid.minOrNull()!!, std) - 1.0
        var hi = max(valid.maxOrNull()!!, std) + 1.0
        if (hi - lo < 6) { val m = (hi + lo) / 2; lo = m - 3; hi = m + 3 }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetRenderer.withAlpha(textColor, 0x80)
            textSize = 9.5f * dn
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val left = if (axes) text.measureText("1016") + 6 * dn else 0f
        val bottom = if (axes) text.textSize + 5 * dn else 1f
        val top = if (axes) 4 * dn else 2 * dn
        val right = 3 * dn
        val pw = w - left - right
        val ph = h - top - bottom
        val n = vals.size
        fun x(i: Int) = left + pw * i / (n - 1).toFloat()
        fun y(v: Double) = top + ph * ((hi - v) / (hi - lo)).toFloat()

        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        if (axes) {
            // グリッド（約4hPaごと）
            val step = if (hi - lo > 16) 8.0 else 4.0
            thin.color = WidgetRenderer.withAlpha(textColor, 0x1F)
            thin.strokeWidth = 0.5f * dn
            var g = floor(hi / step) * step
            while (g > lo) {
                val yy = y(g)
                c.drawLine(left, yy, w - right, yy, thin)
                c.drawText("${g.toInt() % 100}".padStart(2, '0'), left - text.measureText("00") - 4 * dn, yy + text.textSize / 3, text)
                g -= step
            }
            // 軸
            thin.color = WidgetRenderer.withAlpha(textColor, 0x80)
            thin.strokeWidth = 0.75f * dn
            c.drawLine(left, top, left, top + ph, thin)
            c.drawLine(left, top + ph, w - right, top + ph, thin)
            // 時刻の目盛り（6時間ごと）
            for (i in 0 until n) {
                val t = pr.times[i]
                if (t.hour % 6 == 0) {
                    val xx = x(i)
                    c.drawLine(xx, top + ph, xx, top + ph + 3 * dn, thin)
                    val label = t.hour.toString().padStart(2, '0')
                    c.drawText(label, xx - text.measureText(label) / 2, h - 1 * dn, text)
                }
            }
        }

        // 標準気圧（破線）
        thin.color = WidgetRenderer.withAlpha(textColor, 0x73)
        thin.strokeWidth = 0.75f * dn
        thin.pathEffect = DashPathEffect(floatArrayOf(3 * dn, 3 * dn), 0f)
        c.drawLine(left, y(std), w - right, y(std), thin)
        thin.pathEffect = null

        // 実測（実線）と予報（点線）
        fun line(from: Int, to: Int, color: Int, dashed: Boolean) {
            val path = Path()
            var started = false
            for (i in from..to) {
                val v = vals[i]
                if (v.isNaN()) continue
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * dn
                this.color = color
                strokeJoin = Paint.Join.ROUND
                if (dashed) pathEffect = DashPathEffect(floatArrayOf(4 * dn, 2 * dn), 0f)
            }
            c.drawPath(path, pp)
        }
        val ni = pr.nowIndex.coerceIn(0, n - 1)
        line(0, ni, textColor, false)
        line(ni, n - 1, futureColor, true)

        // 現在
        thin.color = textColor
        c.drawLine(x(ni), top, x(ni), top + ph, thin)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor }
        if (!vals[ni].isNaN()) c.drawCircle(x(ni), y(vals[ni]), 2.2f * dn, dot)

        // この先の最低点（下がるときだけ）
        var mi = -1
        for (i in ni + 1 until n) if (!vals[i].isNaN() && (mi < 0 || vals[i] < vals[mi])) mi = i
        if (mi > 0 && !vals[ni].isNaN() && vals[ni] - vals[mi] >= 1.5) {
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f * dn; color = futureColor
            }
            c.drawCircle(x(mi), y(vals[mi]), 2.6f * dn, ring)
            if (axes) {
                val lab = String.format("%.1f", vals[mi])
                val tp = Paint(text).apply { color = futureColor }
                val tx = min(x(mi) - tp.measureText(lab) / 2, w - right - tp.measureText(lab))
                val ty = y(vals[mi]) - 5 * dn
                c.drawText(lab, max(left, tx), max(top + tp.textSize, ty), tp)
            }
        }
        return b
    }
}
