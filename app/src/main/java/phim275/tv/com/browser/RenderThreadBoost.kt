package phim275.tv.com.browser

import android.os.Process
import android.util.Log
import java.io.File

/**
 * Nâng độ ưu tiên các luồng vẽ của Gecko (Renderer, Compositor…) lên mức của luồng giao diện.
 *
 * Đo trên máy chiếu H93A (chip MStar m7632): luồng JavaScript của trang chạy ở nice -10 (app đang hiện),
 * còn luồng vẽ của Gecko chỉ ở -1/0 — ngang luồng thu hồi RAM của kernel. Khi hls.js chuyển đổi video
 * hoặc máy thiếu RAM, luồng vẽ phải chờ → khung hình lên màn hình trễ rồi dồn, xem toàn màn hình thấy giật.
 * Android tự đặt luồng vẽ (RenderThread) của app ở -10; ở đây làm tương tự cho Gecko.
 *
 * Chỉ thấy được luồng trong chính process app: chế độ RAM thấp gộp process GPU của Gecko vào process chính.
 */
object RenderThreadBoost {
    private const val TAG = "Phim275Boost"
    private const val PRIORITY = -10 // = luồng giao diện của app đang hiện (top-app)

    /** Tên luồng (comm, tối đa 15 ký tự) của bộ vẽ WebRender / compositor trong Gecko. */
    private val NAMES = setOf(
        "Renderer", "WRRenderBackend", "WRSceneBuilder", "WRSceneBuilderLP", "Compositor", "ImageBridgeChld",
    )

    /** Trả về số luồng vừa được nâng (0 = không có / đã nâng rồi). Rẻ: vài chục file nhỏ trong /proc. */
    fun boost(): Int {
        var boosted = 0
        val tasks = File("/proc/self/task").listFiles() ?: return 0
        for (task in tasks) {
            val tid = task.name.toIntOrNull() ?: continue
            val name = try {
                File(task, "comm").readText().trim()
            } catch (_: Exception) {
                continue
            }
            if (name !in NAMES) continue
            try {
                if (Process.getThreadPriority(tid) > PRIORITY) {
                    Process.setThreadPriority(tid, PRIORITY)
                    boosted++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không nâng được luồng $name ($tid): ${e.message}")
            }
        }
        if (boosted > 0) Log.i(TAG, "Đã ưu tiên $boosted luồng vẽ của Gecko (nice $PRIORITY)")
        return boosted
    }
}
