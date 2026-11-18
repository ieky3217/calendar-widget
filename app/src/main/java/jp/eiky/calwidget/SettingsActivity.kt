package jp.eiky.calwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RemoteViews
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * ・ウィジェット追加時／長押し→「設定」から開く → そのウィジェットの設定（カレンダー or 天気を自動判別）
 * ・アプリアイコンから開く → カレンダーの既定値＋全カレンダーウィジェットに一括適用
 *   「天気ウィジェットの設定へ」→ 天気モードで開き直す
 */
class SettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val isConfig get() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
    private var weatherMode = false
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
        weatherMode = if (isConfig) {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className ==
                WeatherWidgetProvider::class.java.name
        } else {
            intent?.getStringExtra(EXTRA_MODE) == MODE_WEATHER
        }
        prefs = if (isConfig) Prefs.load(this, widgetId) else Prefs.loadDefaults(this)

        setContentView(R.layout.activity_settings)

        bindRadio(R.id.rg_bg, bgOptions, prefs.bgColor) { prefs = prefs.copy(bgColor = it) }
        bindRadio(R.id.rg_text, textOptions, prefs.textColor) { prefs = prefs.copy(textColor = it) }
        bindRadio(
            R.id.rg_corner, listOf(R.id.rb_corner_none to 0, R.id.rb_corner_s to 1, R.id.rb_corner_l to 2), prefs.corner,
        ) { prefs = prefs.copy(corner = it) }
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

        findViewById<View>(R.id.cal_options).visibility = if (weatherMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.wx_options).visibility = if (weatherMode) View.VISIBLE else View.GONE
        if (weatherMode) {
            findViewById<TextView>(R.id.screen_title).text = "天気ウィジェット"
            setupWeather()
        }

        val saveBtn = findViewById<Button>(R.id.btn_save)
        val pinBtn = findViewById<Button>(R.id.btn_pin)
        val switchBtn = findViewById<Button>(R.id.btn_switch)
        val hint = findViewById<TextView>(R.id.hint)
        switchBtn.visibility = if (!isConfig && !weatherMode) View.VISIBLE else View.GONE
        switchBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_MODE, MODE_WEATHER))
        }
        if (isConfig) {
            saveBtn.text = "このウィジェットに適用"
            pinBtn.visibility = View.GONE
            hint.text = "あとから変更するときは、ウィジェットを長押し →「設定」。"
        } else {
            val what = if (weatherMode) "天気" else "カレンダー"
            saveBtn.text = "保存（すべての${what}ウィジェットに適用）"
            hint.text = "ホーム画面を長押し →「ウィジェット」→「カレンダーウィジェット」からも追加できます。" +
                "追加後はウィジェットの端をドラッグして 2×1・4×1・2×2・4×2 などに自由にリサイズできます。" +
                if (weatherMode) "\n天気は約30分ごとに自動更新。ウィジェットをタップするとすぐ更新します。" else ""
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

    // ---------- プレビュー ----------
    private fun refreshPreview() {
        if (weatherMode) {
            val data = WeatherRepo.cached(this)
            val place = WeatherRepo.place(this)
            showPreview(R.id.preview_week,
                WeatherRenderer.buildKind(this, 0, prefs, WeatherRenderer.Kind.S, false, data, place))
            showPreview(R.id.preview_month,
                WeatherRenderer.buildKind(this, 0, prefs, WeatherRenderer.Kind.TALL, false, data, place))
        } else {
            val small = if (prefs.smallMonth) WidgetRenderer.Kind.MONTH_XS else WidgetRenderer.Kind.WEEK_S
            showPreview(R.id.preview_week, WidgetRenderer.buildKind(this, 0, prefs, 0, small, false))
            showPreview(R.id.preview_month,
                WidgetRenderer.buildKind(this, 0, prefs, 0, WidgetRenderer.Kind.MONTH_S, false))
        }
    }

    private fun showPreview(containerId: Int, rv: RemoteViews) {
        val container = findViewById<FrameLayout>(containerId)
        container.removeAllViews()
        container.addView(rv.apply(this, container))
    }

    // ---------- 天気：地点 ----------
    private fun setupWeather() {
        updatePlaceLabel()
        val query = findViewById<EditText>(R.id.wx_query)
        findViewById<Button>(R.id.wx_search).setOnClickListener { search(query.text.toString()) }
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(query.text.toString()); true
            } else false
        }
        findViewById<Button>(R.id.wx_here).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                useLocation()
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            }
        }
        showGhosts()
        findViewById<Button>(R.id.wx_ghost_reset).setOnClickListener {
            Ghosts.reset(this)
            WeatherWidgetProvider.updateAll(this)
            showGhosts()
            status("おばけを呼び戻しました")
        }
        val autoSw = findViewById<Switch>(R.id.wx_auto)
        autoSw.isChecked = WeatherRepo.isAuto(this) && LocationHelper.hasBackground(this)
        autoSw.setOnCheckedChangeListener { _, on ->
            if (ignoreAutoSwitch) return@setOnCheckedChangeListener
            if (on) startAutoFlow() else {
                WeatherRepo.setAuto(this, false)
                updatePlaceLabel()
                status("自動追従をオフにしました（今の地点のまま固定）")
            }
        }
        if (WeatherRepo.cached(this) == null) {
            status("天気を取得中…")
            WeatherWidgetProvider.fetchInBackground(this) { ok ->
                runOnUiThread {
                    status(if (ok) "" else "天気を取得できませんでした（通信を確認してください）")
                    refreshPreview()
                }
            }
        }
    }

    private fun showGhosts() {
        findViewById<TextView>(R.id.wx_ghosts).text = Ghosts.schedule(this, WeatherRepo.cached(this))
    }

    private fun status(text: String) {
        findViewById<TextView>(R.id.wx_status).text = text
    }

    private fun updatePlaceLabel() {
        val auto = if (WeatherRepo.isAuto(this)) "（自動）" else ""
        findViewById<TextView>(R.id.wx_place).text = "現在の地点：${WeatherRepo.place(this).name}$auto"
    }

    private fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        status("検索中…")
        val results = findViewById<LinearLayout>(R.id.wx_results)
        Thread {
            val res = try {
                WeatherRepo.search(q)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                results.removeAllViews()
                when {
                    res == null -> status("検索できませんでした（通信を確認してください）")
                    res.isEmpty() -> status("見つかりませんでした")
                    else -> {
                        status("地点を選んでください")
                        for ((label, place) in res) {
                            val b = Button(this)
                            b.text = label
                            b.isAllCaps = false
                            b.setOnClickListener { choosePlace(place) }
                            results.addView(b)
                        }
                    }
                }
            }
        }.start()
    }

    private fun choosePlace(place: Place) {
        if (WeatherRepo.isAuto(this)) {
            WeatherRepo.setAuto(this, false) // 地点を手で選んだら自動追従は解除
            setAutoSwitch(false)
        }
        WeatherRepo.setPlace(this, place)
        updatePlaceLabel()
        findViewById<LinearLayout>(R.id.wx_results).removeAllViews()
        status("天気を取得中…")
        refreshPreview()
        WeatherWidgetProvider.fetchInBackground(this) { ok ->
            runOnUiThread {
                status(if (ok) "${place.name}の天気に切り替えました" else "天気を取得できませんでした（通信を確認してください）")
                refreshPreview()
            }
        }
    }

    // ---------- 天気：現在地の自動追従 ----------
    private var ignoreAutoSwitch = false

    private fun setAutoSwitch(on: Boolean) {
        ignoreAutoSwitch = true
        findViewById<Switch>(R.id.wx_auto).isChecked = on
        ignoreAutoSwitch = false
    }

    private fun startAutoFlow() {
        when {
            !LocationHelper.hasForeground(this) ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_AUTO_FG)
            !LocationHelper.hasBackground(this) -> askBackground()
            else -> enableAuto()
        }
    }

    private fun askBackground() {
        AlertDialog.Builder(this)
            .setTitle("位置情報を「常に許可」に")
            .setMessage("ウィジェットが裏側で現在地を確認できるよう、次の画面で「常に許可」を選んでください。")
            .setPositiveButton("次へ") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_AUTO_BG)
                } else {
                    enableAuto()
                }
            }
            .setNegativeButton("やめる") { _, _ -> autoFailed() }
            .setOnCancelListener { autoFailed() }
            .show()
    }

    private fun enableAuto() {
        WeatherRepo.setAuto(this, true)
        setAutoSwitch(true)
        status("現在地を確認中…")
        WeatherWidgetProvider.fetchInBackground(this) { ok ->
            runOnUiThread {
                updatePlaceLabel()
                status(if (ok) "自動追従をオンにしました（${WeatherRepo.place(this).name}）" else "天気を取得できませんでした（通信を確認してください）")
                refreshPreview()
            }
        }
    }

    private fun autoFailed() {
        WeatherRepo.setAuto(this, false)
        setAutoSwitch(false)
        status("「常に許可」にならなかったため、自動追従はオフのままです")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_AUTO_FG -> {
                if (granted) startAutoFlow() else autoFailed()
                return
            }
            REQ_AUTO_BG -> {
                if (LocationHelper.hasBackground(this)) enableAuto() else autoFailed()
                return
            }
        }
        if (requestCode != REQ_LOCATION) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            useLocation()
        } else {
            status("位置情報が許可されませんでした。地名検索で設定してください")
        }
    }

    @Suppress("MissingPermission")
    private fun useLocation() {
        val lm = getSystemService(LocationManager::class.java)
        if (lm == null) {
            status("現在地を取得できませんでした")
            return
        }
        status("現在地を取得中…")
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull {
                try {
                    lm.getLastKnownLocation(it)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
        if (last != null) {
            resolveAndSet(last)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            try {
                lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                    if (loc != null) resolveAndSet(loc)
                    else status("現在地を取得できませんでした（位置情報をオンにしてください）")
                }
            } catch (e: Exception) {
                status("現在地を取得できませんでした（位置情報をオンにしてください）")
            }
        } else {
            status("現在地を取得できませんでした（位置情報をオンにしてください）")
        }
    }

    private fun resolveAndSet(loc: Location) {
        status("地名を確認中…")
        Thread {
            val name = LocationHelper.placeName(this, loc.latitude, loc.longitude) ?: "現在地"
            runOnUiThread { choosePlace(Place(name, loc.latitude, loc.longitude)) }
        }.start()
    }

    // ---------- 保存・追加 ----------
    private fun save() {
        Prefs.save(this, null, prefs) // 次に追加するウィジェットの既定値にもする
        val mgr = AppWidgetManager.getInstance(this)
        if (isConfig) {
            Prefs.save(this, widgetId, prefs)
            if (weatherMode) {
                WeatherWidgetProvider.render(this, mgr, widgetId)
                WeatherWidgetProvider.fetchInBackground(this)
            } else {
                CalendarWidgetProvider.updateWidget(this, mgr, widgetId)
                CalendarWidgetProvider.scheduleMidnight(this)
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        } else {
            if (weatherMode) {
                WeatherWidgetProvider.allIds(this).forEach { Prefs.save(this, it, prefs) }
                WeatherWidgetProvider.updateAll(this)
            } else {
                CalendarWidgetProvider.allIds(this).forEach { Prefs.save(this, it, prefs) }
                CalendarWidgetProvider.updateAll(this)
            }
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pin() {
        Prefs.save(this, null, prefs)
        val mgr = getSystemService(AppWidgetManager::class.java)
        val provider = if (weatherMode) WeatherWidgetProvider::class.java else CalendarWidgetProvider::class.java
        if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, provider), null, null)
        } else {
            Toast.makeText(this, "ホーム画面を長押し →「ウィジェット」から追加してください", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_WEATHER = "weather"
        private const val REQ_LOCATION = 10
        private const val REQ_AUTO_FG = 11
        private const val REQ_AUTO_BG = 12
    }
}
