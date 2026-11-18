package jp.eiky.calwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object WidgetRenderer {

    /** 表示パターンと文字サイズ(sp) */
    enum class Kind(val title: Float, val label: Float, val day: Float) {
        WEEK_S(10f, 9f, 13f),   // 2×1 など小さい横長：今週1行
        WEEK_L(12f, 10f, 16f),  // 4×1 など横に広い：今週1行（大きめ）
        MONTH_XS(22f, 9f, 11f), // 2×1 など1段：月表示（年月は左の縦列）
        MONTH_S(12f, 9f, 11f),  // 2×2〜3×2 程度：月表示
        MONTH_L(15f, 11f, 14f), // 4×3 以上：月表示（大きめ）
    }

    private val WEEKDAYS = arrayOf("日", "月", "火", "水", "木", "金", "土")

    data class Pal(val text: Int, val sun: Int, val sat: Int)

    /** ウィジェットに渡す RemoteViews。Android 12+ はサイズ別レイアウトをまとめて渡す */
    fun build(ctx: Context, id: Int, p: WidgetPrefs, offset: Int, options: Bundle?): RemoteViews {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val small = if (p.smallMonth) {
                listOf(SizeF(40f, 30f) to Kind.MONTH_XS)
            } else {
                listOf(SizeF(40f, 30f) to Kind.WEEK_S, SizeF(230f, 30f) to Kind.WEEK_L)
            }
            val all = small + listOf(SizeF(130f, 140f) to Kind.MONTH_S, SizeF(250f, 230f) to Kind.MONTH_L)
            RemoteViews(all.associate { (size, kind) -> size to buildKind(ctx, id, p, offset, kind, true) })
        } else {
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            buildKind(ctx, id, p, offset, kindFor(w, h, p), true)
        }
    }

    private fun kindFor(w: Int, h: Int, p: WidgetPrefs): Kind = when {
        w >= 250 && h >= 230 -> Kind.MONTH_L
        w >= 130 && h >= 140 -> Kind.MONTH_S
        p.smallMonth -> Kind.MONTH_XS
        w >= 230 -> Kind.WEEK_L
        else -> Kind.WEEK_S
    }

    fun buildKind(
        ctx: Context, id: Int, p: WidgetPrefs, offset: Int, kind: Kind, interactive: Boolean,
    ): RemoteViews = when (kind) {
        Kind.WEEK_S, Kind.WEEK_L -> buildWeek(ctx, id, p, kind, interactive)
        Kind.MONTH_XS, Kind.MONTH_S, Kind.MONTH_L -> buildMonth(ctx, id, p, offset, kind, interactive)
    }

    // ---------- 週（1行）表示 ----------
    private fun buildWeek(ctx: Context, id: Int, p: WidgetPrefs, kind: Kind, interactive: Boolean): RemoteViews {
        val pkg = ctx.packageName
        val pal = palette(p)
        val today = LocalDate.now()
        val rv = RemoteViews(pkg, R.layout.widget_week)
        applyBg(rv, p)

        val title = if (kind == Kind.WEEK_L) "${today.year}年${today.monthValue}月" else "${today.monthValue}月"
        rv.setTextViewText(R.id.title, title)
        rv.setTextColor(R.id.title, pal.text)
        rv.setTextViewTextSize(R.id.title, TypedValue.COMPLEX_UNIT_SP, kind.title)

        rv.removeAllViews(R.id.week_row)
        val start = weekStartOf(today, p)
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            val cell = RemoteViews(pkg, R.layout.cell_week)
            val li = labelIndex(i, p)
            cell.setTextViewText(R.id.wd, WEEKDAYS[li])
            cell.setTextColor(R.id.wd, labelColor(li, pal))
            cell.setTextViewTextSize(R.id.wd, TypedValue.COMPLEX_UNIT_SP, kind.label)
            setDay(cell, d, today, p, pal, true, kind)
            rv.addView(R.id.week_row, cell)
        }
        if (interactive) rv.setOnClickPendingIntent(R.id.root, openCalendar(ctx, id, today))
        return rv
    }

    // ---------- 月表示 ----------
    private fun buildMonth(
        ctx: Context, id: Int, p: WidgetPrefs, offset: Int, kind: Kind, interactive: Boolean,
    ): RemoteViews {
        val pkg = ctx.packageName
        val pal = palette(p)
        val today = LocalDate.now()
        val ym = YearMonth.from(today).plusMonths(offset.toLong())
        val xs = kind == Kind.MONTH_XS
        val rv = RemoteViews(pkg, if (xs) R.layout.widget_month_xs else R.layout.widget_month)
        applyBg(rv, p)

        if (xs) {
            rv.setTextViewText(R.id.title, ym.monthValue.toString())
            rv.setTextViewText(R.id.year, "${ym.year}")
            rv.setTextColor(R.id.year, pal.text)
            rv.setTextViewText(R.id.month_unit, "月")
            rv.setTextColor(R.id.month_unit, pal.text)
        } else {
            rv.setTextViewText(R.id.title, "${ym.year}年${ym.monthValue}月")
        }
        rv.setTextColor(R.id.title, pal.text)
        rv.setTextViewTextSize(R.id.title, TypedValue.COMPLEX_UNIT_SP, kind.title)
        for (arrow in intArrayOf(R.id.prev, R.id.next)) {
            rv.setTextColor(arrow, pal.text)
            rv.setTextViewTextSize(arrow, TypedValue.COMPLEX_UNIT_SP, if (xs) 14f else kind.title + 4f)
        }

        rv.removeAllViews(R.id.dow_row)
        for (i in 0 until 7) {
            val li = labelIndex(i, p)
            val label = RemoteViews(pkg, R.layout.cell_label)
            label.setTextViewText(R.id.label, WEEKDAYS[li])
            label.setTextColor(R.id.label, labelColor(li, pal))
            label.setTextViewTextSize(R.id.label, TypedValue.COMPLEX_UNIT_SP, kind.label)
            rv.addView(R.id.dow_row, label)
        }

        rv.removeAllViews(R.id.grid)
        val start = weekStartOf(ym.atDay(1), p)
        val total = ChronoUnit.DAYS.between(start, ym.atEndOfMonth()).toInt() + 1
        val rows = (total + 6) / 7
        for (r in 0 until rows) {
            val row = RemoteViews(pkg, R.layout.row_month)
            for (c in 0 until 7) {
                val d = start.plusDays((r * 7 + c).toLong())
                val cell = RemoteViews(pkg, if (xs) R.layout.cell_day_xs else R.layout.cell_day)
                setDay(cell, d, today, p, pal, YearMonth.from(d) == ym, kind)
                row.addView(R.id.row, cell)
            }
            rv.addView(R.id.grid, row)
        }

        if (interactive) {
            rv.setOnClickPendingIntent(R.id.prev, navIntent(ctx, id, -1, 2))
            rv.setOnClickPendingIntent(R.id.next, navIntent(ctx, id, 1, 3))
            rv.setOnClickPendingIntent(R.id.title, navIntent(ctx, id, 0, 4)) // 年月タップで今月に戻る
            val target = if (offset == 0) today else ym.atDay(1)
            rv.setOnClickPendingIntent(R.id.grid, openCalendar(ctx, id, target))
        }
        return rv
    }

    // ---------- 共通 ----------
    private fun setDay(
        cell: RemoteViews, d: LocalDate, today: LocalDate, p: WidgetPrefs, pal: Pal, inMonth: Boolean, kind: Kind,
    ) {
        cell.setTextViewText(R.id.day, d.dayOfMonth.toString())
        cell.setTextViewTextSize(R.id.day, TypedValue.COMPLEX_UNIT_SP, kind.day)
        if (d == today) {
            cell.setViewVisibility(R.id.dot, View.VISIBLE)
            cell.setInt(R.id.dot, "setColorFilter", p.accent or 0xFF000000.toInt())
            cell.setTextColor(R.id.day, if (isLight(p.accent)) Color.BLACK else Color.WHITE)
        } else {
            cell.setViewVisibility(R.id.dot, View.GONE)
            cell.setTextColor(R.id.day, dayColor(d, p, pal, inMonth))
        }
    }

    fun applyBg(rv: RemoteViews, p: WidgetPrefs) {
        rv.setImageViewResource(
            R.id.bg,
            when (p.corner) {
                0 -> R.drawable.widget_bg_none
                2 -> R.drawable.widget_bg
                else -> R.drawable.widget_bg_s
            },
        )
        rv.setInt(R.id.bg, "setColorFilter", p.bgColor or 0xFF000000.toInt())
        rv.setInt(R.id.bg, "setImageAlpha", (p.bgAlpha.coerceIn(0, 100) * 255) / 100)
    }

    fun palette(p: WidgetPrefs): Pal {
        val lightText = isLight(p.textColor)
        return Pal(
            text = p.textColor,
            sun = if (lightText) 0xFFFF6B6B.toInt() else 0xFFD32F2F.toInt(),
            sat = if (lightText) 0xFF6FA8FF.toInt() else 0xFF1E5BC6.toInt(),
        )
    }

    private fun dayColor(d: LocalDate, p: WidgetPrefs, pal: Pal, inMonth: Boolean): Int {
        val c = when {
            d.dayOfWeek == DayOfWeek.SUNDAY -> pal.sun
            p.holidays && JpHolidays.isHoliday(d) -> pal.sun
            d.dayOfWeek == DayOfWeek.SATURDAY -> pal.sat
            else -> pal.text
        }
        return if (inMonth) c else muted(c, isLight(pal.text))
    }

    private fun labelColor(index: Int, pal: Pal): Int = when (index) {
        0 -> pal.sun
        6 -> pal.sat
        else -> pal.text
    }

    /** 0=日 … 6=土 */
    private fun dow0(d: LocalDate): Int = d.dayOfWeek.value % 7

    private fun weekStartOf(d: LocalDate, p: WidgetPrefs): LocalDate {
        val ws = if (p.mondayStart) 1 else 0
        return d.minusDays(((dow0(d) - ws + 7) % 7).toLong())
    }

    private fun labelIndex(i: Int, p: WidgetPrefs): Int = (i + if (p.mondayStart) 1 else 0) % 7

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    /**
     * 前後の月の日付用：半透明にすると壁紙の色が透けて混ざるので、
     * 不透明なまま灰色寄りにして「薄く」見せる。
     */
    fun muted(c: Int, lightText: Boolean): Int {
        val g = if (lightText) 0x6E else 0xB4
        fun mix(v: Int) = (v * 0.4 + g * 0.6).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(c)), mix(Color.green(c)), mix(Color.blue(c)))
    }

    fun isLight(c: Int): Boolean =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c) > 150

    private fun openCalendar(ctx: Context, id: Int, date: LocalDate): PendingIntent {
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time").appendPath(millis.toString()).build()
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx, id * 10 + 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun navIntent(ctx: Context, id: Int, delta: Int, code: Int): PendingIntent {
        val intent = Intent(ctx, CalendarWidgetProvider::class.java)
            .setAction(CalendarWidgetProvider.ACTION_NAV)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(CalendarWidgetProvider.EXTRA_DELTA, delta)
        return PendingIntent.getBroadcast(
            ctx, id * 10 + code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
