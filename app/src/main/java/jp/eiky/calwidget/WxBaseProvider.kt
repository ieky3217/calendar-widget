package jp.eiky.calwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** 天気データ（Open-Meteo）を使うウィジェットの共通部分：日の出・月／気圧 */
abstract class WxBaseProvider : AppWidgetProvider() {

    abstract fun render(ctx: Context, mgr: AppWidgetManager, id: Int)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
        WeatherWidgetProvider.scheduleTick(context)
        fetchAsync(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Prefs.delete(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            Toast.makeText(context, "更新しています…", Toast.LENGTH_SHORT).show()
            fetchAsync(context)
        }
    }

    private fun fetchAsync(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                WeatherRepo.fetch(app)
                WeatherWidgetProvider.updateAll(app) // 天気・日の出／月・気圧をまとめて更新
                WeatherWidgetProvider.scheduleTick(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "jp.eiky.calwidget.WXBASE_REFRESH"

        fun ids(ctx: Context, cls: Class<*>): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))
    }
}

class SkyWidgetProvider : WxBaseProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = renderOne(ctx, mgr, id)

    companion object {
        fun allIds(ctx: Context) = WxBaseProvider.ids(ctx, SkyWidgetProvider::class.java)

        fun renderOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            mgr.updateAppWidget(id, SkyRenderer.build(ctx, id, Prefs.load(ctx, id), mgr.getAppWidgetOptions(id)))
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            allIds(ctx).forEach { renderOne(ctx, mgr, it) }
        }
    }
}

class PressureWidgetProvider : WxBaseProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = renderOne(ctx, mgr, id)

    companion object {
        fun allIds(ctx: Context) = WxBaseProvider.ids(ctx, PressureWidgetProvider::class.java)

        fun renderOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            mgr.updateAppWidget(id, PressureRenderer.build(ctx, id, Prefs.load(ctx, id), mgr.getAppWidgetOptions(id)))
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            allIds(ctx).forEach { renderOne(ctx, mgr, it) }
        }
    }
}
