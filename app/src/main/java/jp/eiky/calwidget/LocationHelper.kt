package jp.eiky.calwidget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** おおよその現在地（市区町村レベル）を取るための小道具 */
object LocationHelper {

    fun hasForeground(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** ウィジェット（裏側）から位置を取るには「常に許可」が必要 */
    fun hasBackground(c: Context): Boolean =
        hasForeground(c) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    /**
     * 直近の位置（30分以内）があればそれを、なければ最大 timeoutMs 待って新しく取得。
     * メインスレッドから呼ばないこと。
     */
    @SuppressLint("MissingPermission")
    fun current(c: Context, timeoutMs: Long): Location? {
        if (!hasForeground(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull {
                try {
                    lm.getLastKnownLocation(it)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 30 * 60 * 1000L) return last
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last

        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return last
        }
        val result = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            lm.getCurrentLocation(provider, null, executor) { loc ->
                result.set(loc)
                latch.countDown()
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // 取れなければ直近の位置で妥協
        } finally {
            executor.shutdown()
        }
        return result.get() ?: last
    }

    /** 緯度経度 → 市区町村名。メインスレッドから呼ばないこと */
    fun placeName(c: Context, lat: Double, lon: Double): String? = try {
        @Suppress("DEPRECATION")
        Geocoder(c, Locale.JAPAN).getFromLocation(lat, lon, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    } catch (e: Exception) {
        null
    }
}
