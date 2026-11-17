package jp.eiky.calwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * ・ウィジェット追加時／長押し→「設定」から開く → そのウィジェットの設定
 * ・アプリアイコンから開く → 既定値＋全ウィジェットに一括適用
 */
class SettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val isConfig get() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var prefs: WidgetPrefs

    private val bgOptions = listOf(
        R.id.rb_bg_black to 0xFF000000.toInt(),
        R.id.rb_bg_gray to 0xFF2A2A2A.toInt(),
        R.id.rb_bg_white to 0xFFFFFFFF.toInt(),
        R.id.rb_bg_navy to 0xFF14213D.toInt(),
    )
    private val textOptions = listOf(
        R.id.rb_text_white to 0xFFFFFFFF.toInt(),
        R.id.rb_text_black to 0xFF111111.toInt(),
    )
    private val accentOptions = listOf(
        R.id.rb_ac_red to 0xFFE53935.toInt(),
        R.id.rb_ac_blue to 0xFF1E88E5.toInt(),
        R.id.rb_ac_yellow to 0xFFF2C230.toInt(),
        R.id.rb_ac_white to 0xFFFFFFFF.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        prefs = if (isConfig) Prefs.load(this, widgetId) else Prefs.loadDefaults(this)

        setContentView(R.layout.activity_settings)

        bindRadio(R.id.rg_bg, bgOptions, prefs.bgColor) { prefs = prefs.copy(bgColor = it) }
        bindRadio(R.id.rg_text, textOptions, prefs.textColor) { prefs = prefs.copy(textColor = it) }
        bindRadio(R.id.rg_accent, accentOptions, prefs.accent) { prefs = prefs.copy(accent = it) }
        bindRadio(
            R.id.rg_week, listOf(R.id.rb_sun to 0, R.id.rb_mon to 1), if (prefs.mondayStart) 1 else 0,
        ) { prefs = prefs.copy(mondayStart = it == 1) }
        bindRadio(
            R.id.rg_small, listOf(R.id.rb_small_month to 1, R.id.rb_small_week to 0), if (prefs.smallMonth) 1 else 0,
        ) { prefs = prefs.copy(smallMonth = it == 1) }

        val alphaLabel = findViewById<TextView>(R.id.alpha_label)
        val seek = findViewById<SeekBar>(R.id.seek_alpha)
        seek.progress = prefs.bgAlpha
        alphaLabel.text = alphaText(prefs.bgAlpha)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                prefs = prefs.copy(bgAlpha = value)
                alphaLabel.text = alphaText(value)
                refreshPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Switch>(R.id.sw_holiday).apply {
            isChecked = prefs.holidays
            setOnCheckedChangeListener { _, checked ->
                prefs = prefs.copy(holidays = checked)
                refreshPreview()
            }
        }

        val saveBtn = findViewById<Button>(R.id.btn_save)
        val pinBtn = findViewById<Button>(R.id.btn_pin)
        val hint = findViewById<TextView>(R.id.hint)
        if (isConfig) {
            saveBtn.text = "このウィジェットに適用"
            pinBtn.visibility = View.GONE
            hint.text = "あとから変更するときは、ウィジェットを長押し →「設定」。"
        } else {
            saveBtn.text = "保存（すべてのウィジェットに適用）"
            hint.text = "ホーム画面を長押し →「ウィジェット」→「カレンダーウィジェット」からも追加できます。" +
                "追加後はウィジェットの端をドラッグして 2×1・4×1・2×2・4×3 などに自由にリサイズできます。"
        }
        saveBtn.setOnClickListener { save() }
        pinBtn.setOnClickListener { pin() }

        refreshPreview()
    }

    private fun alphaText(v: Int) = if (v == 0) "背景の濃さ：0%（完全に透明）" else "背景の濃さ：$v%"

    private fun bindRadio(groupId: Int, options: List<Pair<Int, Int>>, current: Int, onPick: (Int) -> Unit) {
        val group = findViewById<RadioGroup>(groupId)
        val selected = options.firstOrNull { it.second == current }?.first ?: options.first().first
        group.check(selected)
        group.setOnCheckedChangeListener { _, checkedId ->
            options.firstOrNull { it.first == checkedId }?.let {
                onPick(it.second)
                refreshPreview()
            }
        }
    }

    private fun refreshPreview() {
        showPreview(
            R.id.preview_week,
            if (prefs.smallMonth) WidgetRenderer.Kind.MONTH_XS else WidgetRenderer.Kind.WEEK_S,
        )
        showPreview(R.id.preview_month, WidgetRenderer.Kind.MONTH_S)
    }

    private fun showPreview(containerId: Int, kind: WidgetRenderer.Kind) {
        val container = findViewById<FrameLayout>(containerId)
        container.removeAllViews()
        val rv = WidgetRenderer.buildKind(this, 0, prefs, 0, kind, false)
        container.addView(rv.apply(this, container))
    }

    private fun save() {
        Prefs.save(this, null, prefs) // 次に追加するウィジェットの既定値にもする
        if (isConfig) {
            Prefs.save(this, widgetId, prefs)
            CalendarWidgetProvider.updateWidget(this, AppWidgetManager.getInstance(this), widgetId)
            CalendarWidgetProvider.scheduleMidnight(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        } else {
            CalendarWidgetProvider.allIds(this).forEach { Prefs.save(this, it, prefs) }
            CalendarWidgetProvider.updateAll(this)
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pin() {
        Prefs.save(this, null, prefs)
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, CalendarWidgetProvider::class.java), null, null)
        } else {
            Toast.makeText(this, "ホーム画面を長押し →「ウィジェット」から追加してください", Toast.LENGTH_LONG).show()
        }
    }
}
