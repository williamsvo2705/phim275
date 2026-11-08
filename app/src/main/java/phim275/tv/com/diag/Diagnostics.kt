package phim275.tv.com.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import phim275.tv.com.BuildConfig
import java.util.Locale

/**
 * Số liệu chẩn đoán hiển thị trên màn hình — dùng để đo hiệu năng trên máy thật
 * (người dùng chụp ảnh bảng này gửi lại khi test).
 */
class Diagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)

    /** RAM trống trước khi khởi động Gecko — để tính lượng RAM app đã dùng thêm. */
    private val baselineAvailBytes = memoryInfo().availMem

    var lastPageLoadMs: Long? = null
    var blockedPopups = 0
    var blockedAppRedirects = 0

    fun snapshot(ublockStatus: String, currentUrl: String?): String {
        val mem = memoryInfo()
        val dm = appContext.resources.displayMetrics
        return buildString {
            appendLine("Phim275 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Thiết bị : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI      : ${Build.SUPPORTED_ABIS.joinToString()} · app 64-bit=${Process.is64Bit()}")
            appendLine("Màn hình : ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi")
            appendLine("GeckoView: ${org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION}")
            appendLine("uBlock   : $ublockStatus")
            appendLine(
                "RAM      : tổng ${mb(mem.totalMem)} · trống ${mb(mem.availMem)}" +
                    if (mem.lowMemory) "  ⚠ THIẾU RAM" else "",
            )
            appendLine("RAM app  : +${mb(baselineAvailBytes - mem.availMem)} từ lúc mở · UI ${Debug.getPss() / 1024} MB")
            appendLine("Tải trang: ${lastPageLoadMs?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "—"}")
            appendLine("Đã chặn  : $blockedPopups popup · $blockedAppRedirects chuyển app")
            append("URL      : ${currentUrl?.take(70) ?: "—"}")
        }
    }

    private fun memoryInfo() = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

    private fun mb(bytes: Long) = "${bytes / (1024 * 1024)} MB"
}
