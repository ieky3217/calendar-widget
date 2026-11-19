package jp.eiky.calwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.time.ZoneId

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) } // まずキャッシュで即表示
        scheduleTick(context)
        fetchAsync(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        render(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Prefs.delete(context, it) }
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                Toast.makeText(context, "天気を更新しています…", Toast.LENGTH_SHORT).show()
                fetchAsync(context)
            }
            ACTION_TICK -> {
                updateAll(context)
                scheduleTick(context)
            }
            ACTION_GHOST -> {
                val key = intent.getStringExtra(EXTRA_GHOST_KEY) ?: return
                // 驚いた顔 → 少し待って消える
                Ghosts.setSurprised(context, key)
                updateAll(context)
                val pending = goAsync()
                val app = context.applicationContext
                Thread {
                    try {
                        Thread.sleep(700)
                        Ghosts.dismiss(app, key)
                        updateAll(app)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    /** BroadcastReceiver の中から通信する（goAsyncで延長） */
    private fun fetchAsync(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                WeatherRepo.fetch(app)
                updateAll(app)
                scheduleTick(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "jp.eiky.calwidget.WX_REFRESH"
        const val ACTION_TICK = "jp.eiky.calwidget.WX_TICK"
        const val ACTION_GHOST = "jp.eiky.calwidget.WX_GHOST"
        const val EXTRA_GHOST_KEY = "ghost_key"

        fun allIds(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WeatherWidgetProvider::class.java))

        /** 天気データを使うウィジェット（天気・日の出／月・気圧）をまとめて更新 */
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            allIds(ctx).forEach { render(ctx, mgr, it) }
            SkyWidgetProvider.updateAll(ctx)
            PressureWidgetProvider.updateAll(ctx)
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            mgr.updateAppWidget(id, WeatherRenderer.build(ctx, id, Prefs.load(ctx, id), mgr.getAppWidgetOptions(id)))
        }

        /** アプリ側（設定画面）から通信して全ウィジェットを更新 */
        fun fetchInBackground(ctx: Context, done: ((Boolean) -> Unit)? = null) {
            val app = ctx.applicationContext
            Thread {
                val ok = WeatherRepo.fetch(app)
                updateAll(app)
                scheduleTick(app)
                done?.invoke(ok)
            }.start()
        }

        private fun tickIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, WeatherWidgetProvider::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                ctx, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** 次におばけが出入りする時刻に書き換える */
        fun scheduleTick(ctx: Context) {
            if (allIds(ctx).isEmpty() && SkyWidgetProvider.allIds(ctx).isEmpty() &&
                PressureWidgetProvider.allIds(ctx).isEmpty()
            ) return
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val at = Ghosts.nextBoundary(WeatherRepo.cached(ctx))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000L
            val pi = tickIntent(ctx)
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
            }
        }
    }
}
