package jp.eiky.calwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.time.LocalDate
import java.time.ZoneId

class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
        scheduleMidnight(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        updateWidget(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Prefs.delete(context, it) }
    }

    override fun onEnabled(context: Context) = scheduleMidnight(context)

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NAV -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val delta = intent.getIntExtra(EXTRA_DELTA, 0)
                Prefs.setOffset(context, id, if (delta == 0) 0 else Prefs.offset(context, id) + delta)
                updateWidget(context, AppWidgetManager.getInstance(context), id)
            }
            ACTION_MIDNIGHT,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val mgr = AppWidgetManager.getInstance(context)
                ids(context, mgr).forEach { Prefs.setOffset(context, it, 0) }
                updateAll(context)
                scheduleMidnight(context)
            }
        }
    }

    companion object {
        const val ACTION_NAV = "jp.eiky.calwidget.NAV"
        const val ACTION_MIDNIGHT = "jp.eiky.calwidget.MIDNIGHT"
        const val EXTRA_DELTA = "delta"

        private fun ids(ctx: Context, mgr: AppWidgetManager): IntArray =
            mgr.getAppWidgetIds(ComponentName(ctx, CalendarWidgetProvider::class.java))

        fun allIds(ctx: Context): IntArray = ids(ctx, AppWidgetManager.getInstance(ctx))

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx, mgr).forEach { updateWidget(ctx, mgr, it) }
        }

        fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = WidgetRenderer.build(
                ctx, id, Prefs.load(ctx, id), Prefs.offset(ctx, id), mgr.getAppWidgetOptions(id),
            )
            mgr.updateAppWidget(id, views)
        }

        private fun midnightIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, CalendarWidgetProvider::class.java).setAction(ACTION_MIDNIGHT)
            return PendingIntent.getBroadcast(
                ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** 日付が変わった直後に再描画（正確アラーム権限が不要な方式なので数分遅れることがあります） */
        fun scheduleMidnight(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val next = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000L
            am.setAndAllowWhileIdle(AlarmManager.RTC, next, midnightIntent(ctx))
        }
    }
}
