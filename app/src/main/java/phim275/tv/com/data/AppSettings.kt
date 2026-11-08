package phim275.tv.com.data

import android.content.Context

/** Cài đặt người dùng, lưu trong SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phim275_settings", Context.MODE_PRIVATE)

    enum class NavMode(val id: String, val label: String) {
        CURSOR("cursor", "Con trỏ chuột"),
        TAB_KEYS("tab", "Nhảy giữa các liên kết (phím Tab)"),
        ;

        companion object {
            fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: CURSOR
        }
    }

    var searchEngine: SearchEngine
        get() = SearchEngine.fromId(prefs.getString(KEY_SEARCH, null))
        set(value) = prefs.edit().putString(KEY_SEARCH, value.id).apply()

    var desktopModeDefault: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP, true)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP, value).apply()

    var navMode: NavMode
        get() = NavMode.fromId(prefs.getString(KEY_NAV, null))
        set(value) = prefs.edit().putString(KEY_NAV, value.id).apply()

    /** DNS-over-HTTPS của AdGuard (chặn tên miền quảng cáo + vượt chặn DNS nhà mạng). */
    var adBlockDns: Boolean
        get() = prefs.getBoolean(KEY_DNS, true)
        set(value) = prefs.edit().putBoolean(KEY_DNS, value).apply()

    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    private companion object {
        const val KEY_SEARCH = "search_engine"
        const val KEY_DESKTOP = "desktop_default"
        const val KEY_NAV = "nav_mode"
        const val KEY_DNS = "adblock_dns"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
