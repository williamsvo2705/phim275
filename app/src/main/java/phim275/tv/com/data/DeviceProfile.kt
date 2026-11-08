package phim275.tv.com.data

import android.app.ActivityManager
import android.content.Context

/** Nhận biết máy RAM thấp (TV box 1GB) để bật chế độ tiết kiệm RAM. */
object DeviceProfile {
    /** Dưới ngưỡng này coi là máy RAM thấp (box 1GB thường báo 800–1000MB dùng được). */
    const val LOW_RAM_THRESHOLD_BYTES = 1_536L * 1024 * 1024

    enum class LowRamMode(val id: String, val label: String) {
        AUTO("auto", "Tự động (bật khi máy dưới 1,5GB RAM)"),
        ON("on", "Luôn bật"),
        OFF("off", "Tắt"),
        ;

        companion object {
            fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }

    /** Thuần logic — có unit test. */
    fun shouldUseLowRam(mode: LowRamMode, totalRamBytes: Long, isLowRamDevice: Boolean): Boolean = when (mode) {
        LowRamMode.ON -> true
        LowRamMode.OFF -> false
        LowRamMode.AUTO -> isLowRamDevice || (totalRamBytes in 1 until LOW_RAM_THRESHOLD_BYTES)
    }

    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun isLowRam(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        return shouldUseLowRam(AppSettings(context).lowRamMode, totalRamBytes(context), am.isLowRamDevice)
    }
}
