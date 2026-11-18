package jp.eiky.calwidget

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime

data class Place(val name: String, val lat: Double, val lon: Double)
data class WxNow(val temp: Double, val code: Int, val isDay: Boolean, val pop: Int)
data class WxHour(val hour: Int, val temp: Double, val code: Int, val isDay: Boolean, val pop: Int)
data class WxDay(
    val date: LocalDate, val code: Int, val max: Double, val min: Double, val pop: Int,
    val sunset: LocalDateTime? = null,
)
data class WxData(val now: WxNow, val hours: List<WxHour>, val days: List<WxDay>, val fetchedAt: Long)

/** 天気データの取得（Open-Meteo：APIキー不要）とキャッシュ */
object WeatherRepo {
    private const val FILE = "weather"
    val DEFAULT_PLACE = Place("郡山市", 37.4005, 140.3597)

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun place(c: Context): Place {
        val s = sp(c)
        val name = s.getString("name", null) ?: return DEFAULT_PLACE
        val lat = s.getString("lat", null)?.toDoubleOrNull() ?: return DEFAULT_PLACE
        val lon = s.getString("lon", null)?.toDoubleOrNull() ?: return DEFAULT_PLACE
        return Place(name, lat, lon)
    }

    fun setPlace(c: Context, p: Place) {
        sp(c).edit()
            .putString("name", p.name)
            .putString("lat", p.lat.toString())
            .putString("lon", p.lon.toString())
            .remove("json")
            .apply()
    }

    /** 現在地を自動で追うか */
    fun isAuto(c: Context): Boolean = sp(c).getBoolean("auto", false)

    fun setAuto(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto", on).apply()
    }

    /** 自動追従がオンなら、位置を取り直して地点を更新（2km以上動いたときだけ地名も引き直す） */
    private fun refreshLocation(c: Context) {
        if (!isAuto(c) || !LocationHelper.hasBackground(c)) return
        val loc = LocationHelper.current(c, 4000L) ?: return
        val cur = place(c)
        val dist = FloatArray(1)
        android.location.Location.distanceBetween(cur.lat, cur.lon, loc.latitude, loc.longitude, dist)
        if (sp(c).contains("name") && dist[0] < 2000f) return
        val name = LocationHelper.placeName(c, loc.latitude, loc.longitude) ?: "現在地"
        sp(c).edit()
            .putString("name", name)
            .putString("lat", loc.latitude.toString())
            .putString("lon", loc.longitude.toString())
            .apply()
    }

    fun cached(c: Context): WxData? {
        val s = sp(c)
        val json = s.getString("json", null) ?: return null
        return try {
            parse(json, s.getLong("at", 0L))
        } catch (e: Exception) {
            null
        }
    }

    /** 通信して最新データを保存。メインスレッドから呼ばないこと */
    fun fetch(c: Context): Boolean = try {
        try {
            refreshLocation(c)
        } catch (e: Exception) {
            // 位置が取れなくても前回の地点で続行
        }
        val p = place(c)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunset" +
            "&timezone=auto&forecast_days=7"
        val body = httpGet(url)
        parse(body, 0L) // 壊れたデータは保存しない
        sp(c).edit().putString("json", body).putLong("at", System.currentTimeMillis()).apply()
        true
    } catch (e: Exception) {
        false
    }

    /** 地名検索。結果は（表示用ラベル, 地点） */
    fun search(query: String): List<Pair<String, Place>> {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=ja&format=json")
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Pair<String, Place>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            if (name.isEmpty()) continue
            val parts = listOf(name, o.optString("admin1"), o.optString("country")).filter { it.isNotEmpty() }
            out.add(parts.joinToString("・") to Place(name, o.getDouble("latitude"), o.getDouble("longitude")))
        }
        return out
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "CalendarWidget/1.0")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, at: Long): WxData {
        val o = JSONObject(body)
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val temps = h.getJSONArray("temperature_2m")
        val codes = h.getJSONArray("weather_code")
        val pops = h.getJSONArray("precipitation_probability")
        val days = h.getJSONArray("is_day")

        val curHour = cur.getString("time").take(13) // 例 2026-11-18T12
        var idx = 0
        for (i in 0 until times.length()) {
            if (times.getString(i).take(13) == curHour) { idx = i; break }
        }

        val now = WxNow(
            temp = cur.getDouble("temperature_2m"),
            code = cur.optInt("weather_code", 3),
            isDay = cur.optInt("is_day", 1) == 1,
            pop = pops.optInt(idx, -1),
        )

        // 次の正時から12時間ぶん（時間バー用）
        val hours = ArrayList<WxHour>()
        for (k in 1..12) {
            val i = idx + k
            if (i >= times.length()) break
            hours.add(
                WxHour(
                    hour = times.getString(i).substring(11, 13).toInt(),
                    temp = temps.optDouble(i, Double.NaN),
                    code = codes.optInt(i, 3),
                    isDay = days.optInt(i, 1) == 1,
                    pop = pops.optInt(i, -1),
                )
            )
        }

        val d = o.getJSONObject("daily")
        val dTime = d.getJSONArray("time")
        val dCode = d.getJSONArray("weather_code")
        val dMax = d.getJSONArray("temperature_2m_max")
        val dMin = d.getJSONArray("temperature_2m_min")
        val dPop = d.getJSONArray("precipitation_probability_max")
        val dSunset = d.optJSONArray("sunset")
        val daily = (0 until dTime.length()).map {
            WxDay(
                date = LocalDate.parse(dTime.getString(it)),
                code = dCode.optInt(it, 3),
                max = dMax.optDouble(it, Double.NaN),
                min = dMin.optDouble(it, Double.NaN),
                pop = dPop.optInt(it, -1),
                sunset = dSunset?.optString(it)?.takeIf { s -> s.length >= 16 }?.let { s ->
                    try {
                        LocalDateTime.parse(s.take(16))
                    } catch (e: Exception) {
                        null
                    }
                },
            )
        }
        return WxData(now, hours, daily, at)
    }
}

/** WMO天気コード → 日本語・アイコン */
object WxCode {
    enum class Cat { CLEAR, CLOUD, RAIN, SNOW }

    fun cat(code: Int): Cat = when (code) {
        0, 1, 2 -> Cat.CLEAR
        3, 45, 48 -> Cat.CLOUD
        in 71..77, 85, 86 -> Cat.SNOW
        in 51..67, in 80..82, in 95..99 -> Cat.RAIN
        else -> Cat.CLOUD
    }

    fun wet(code: Int): Boolean = cat(code).let { it == Cat.RAIN || it == Cat.SNOW }

    fun label(code: Int): String = when (code) {
        0 -> "快晴"
        1 -> "晴れ"
        2 -> "晴れ時々曇り"
        3 -> "曇り"
        45, 48 -> "霧"
        in 51..57 -> "霧雨"
        in 61..67 -> "雨"
        in 71..77 -> "雪"
        in 80..82 -> "にわか雨"
        85, 86 -> "にわか雪"
        in 95..99 -> "雷雨"
        else -> "—"
    }

    fun icon(code: Int, day: Boolean): Int = when (code) {
        0, 1 -> if (day) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        2 -> if (day) R.drawable.wx_partly_day else R.drawable.wx_partly_night
        45, 48 -> R.drawable.wx_fog
        in 51..67, in 80..82 -> R.drawable.wx_rain
        in 71..77, 85, 86 -> R.drawable.wx_snow
        in 95..99 -> R.drawable.wx_thunder
        else -> R.drawable.wx_cloudy
    }
}
