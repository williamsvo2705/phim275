package phim275.tv.com.data

import android.content.Context

/** Cài đặt người dùng, lưu trong SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phim275_settings", Context.MODE_PRIVATE)

    enum class NavMode(val id: String, val label: String) {
        /** Mặc định: phím mũi tên nhảy giữa các khối bấm được (kiểu Android TV). */
        SPATIAL("spatial", "Chuyển khối bằng phím mũi tên (kiểu Android TV)"),
        CURSOR("cursor", "Con trỏ chuột"),
        ;

        companion object {
            /** "tab" là chế độ phím Tab cũ (0.2.x) → chuyển sang chế độ chuyển khối. */
            fun fromId(id: String?) = when (id) {
                CURSOR.id -> CURSOR
                else -> SPATIAL
            }
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

    /** Hệ số cỡ chữ trang web (1.0 = 100%). */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    /** Chế độ tiết kiệm RAM cho TV box yếu (bộ lọc gọn, cache nhỏ, ít tab). */
    var lowRamMode: DeviceProfile.LowRamMode
        get() = DeviceProfile.LowRamMode.fromId(prefs.getString(KEY_LOW_RAM, null))
        set(value) = prefs.edit().putString(KEY_LOW_RAM, value.id).apply()

    /** Mở lại các tab của lần trước khi khởi động app. */
    var restoreTabs: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_TABS, true)
        set(value) = prefs.edit().putBoolean(KEY_RESTORE_TABS, value).apply()

    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** Đã xem màn hình hướng dẫn lần đầu. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_SEARCH = "search_engine"
        const val KEY_DESKTOP = "desktop_default"
        const val KEY_NAV = "nav_mode"
        const val KEY_DNS = "adblock_dns"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_RESTORE_TABS = "restore_tabs"
        const val KEY_LOW_RAM = "low_ram_mode"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
