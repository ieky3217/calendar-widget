package jp.eiky.calwidget

import android.content.Context

/** ウィジェット1個ぶんの見た目設定 */
data class WidgetPrefs(
    val bgColor: Int = 0xFF000000.toInt(),
    val bgAlpha: Int = 55,               // 0=完全透明 / 100=不透明
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val accent: Int = 0xFFE53935.toInt(), // 今日の丸の色
    val mondayStart: Boolean = false,
    val holidays: Boolean = true,
    val smallMonth: Boolean = true,      // 1段サイズ(2×1など)でも月表示にする
)

/** SharedPreferences への保存。id=null は「既定値（新しいウィジェット用）」 */
object Prefs {
    private const val FILE = "calwidget"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(id: Int?, name: String) = if (id == null) "def_$name" else "w${id}_$name"

    fun loadDefaults(c: Context): WidgetPrefs = read(c, null, WidgetPrefs())

    fun load(c: Context, id: Int): WidgetPrefs {
        val d = loadDefaults(c)
        return if (sp(c).contains(key(id, "set"))) read(c, id, d) else d
    }

    private fun read(c: Context, id: Int?, d: WidgetPrefs): WidgetPrefs {
        val s = sp(c)
        return WidgetPrefs(
            bgColor = s.getInt(key(id, "bg"), d.bgColor),
            bgAlpha = s.getInt(key(id, "alpha"), d.bgAlpha),
            textColor = s.getInt(key(id, "text"), d.textColor),
            accent = s.getInt(key(id, "accent"), d.accent),
            mondayStart = s.getBoolean(key(id, "monday"), d.mondayStart),
            holidays = s.getBoolean(key(id, "holidays"), d.holidays),
            smallMonth = s.getBoolean(key(id, "smallMonth"), d.smallMonth),
        )
    }

    fun save(c: Context, id: Int?, p: WidgetPrefs) {
        sp(c).edit()
            .putBoolean(key(id, "set"), true)
            .putInt(key(id, "bg"), p.bgColor)
            .putInt(key(id, "alpha"), p.bgAlpha)
            .putInt(key(id, "text"), p.textColor)
            .putInt(key(id, "accent"), p.accent)
            .putBoolean(key(id, "monday"), p.mondayStart)
            .putBoolean(key(id, "holidays"), p.holidays)
            .putBoolean(key(id, "smallMonth"), p.smallMonth)
            .apply()
    }

    /** 月表示で ‹ › を押したときの「今月からのずれ」 */
    fun offset(c: Context, id: Int): Int = sp(c).getInt(key(id, "offset"), 0)

    fun setOffset(c: Context, id: Int, v: Int) {
        sp(c).edit().putInt(key(id, "offset"), v).apply()
    }

    fun delete(c: Context, id: Int) {
        val s = sp(c)
        val prefix = "w${id}_"
        val e = s.edit()
        s.all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) }
        e.apply()
    }
}
