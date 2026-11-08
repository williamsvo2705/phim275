package phim275.tv.com.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * Extension điều hướng "chuyển khối" (assets/extensions/tvnav): content script chọn phần tử bằng
 * phím mũi tên; khi bấm OK, nó gửi vị trí phần tử (tỉ lệ 0..1 của màn hình) về đây để app chạm thật.
 */
object TvNavBridge {
    private const val TAG = "Phim275TvNav"
    const val EXTENSION_ID = "tvnav@phim275.tv"
    const val EXTENSION_URI = "resource://android/assets/extensions/tvnav/"
    private const val NATIVE_APP = "phim275tv"

    private val main = Handler(Looper.getMainLooper())

    /** Nhận (fx, fy) ∈ [0,1] — vị trí cần chạm trên GeckoView. */
    var tapListener: ((Float, Float) -> Unit)? = null

    /** Trang bật/tắt phóng to video bằng CSS (khi trình phát chặn Fullscreen API). */
    var pseudoFullscreenListener: ((Boolean) -> Unit)? = null

    /** Giữ OK ở chế độ chuyển khối: (liên kết của khối hoặc null, chữ trên khối). */
    var contextListener: ((String?, String) -> Unit)? = null

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).accept(
            { ext ->
                ext?.setMessageDelegate(delegate, NATIVE_APP)
                Log.i(TAG, "Điều hướng chuyển khối sẵn sàng")
            },
            { error -> Log.e(TAG, "Không cài được extension điều hướng", error) },
        )
    }

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val json = message as? JSONObject ?: return GeckoResult.fromValue(false)
            if (json.optString("type") == "context") {
                val href = if (json.isNull("href")) null else json.optString("href").takeIf { it.isNotBlank() }
                val text = json.optString("text")
                main.post { contextListener?.invoke(href, text) }
                return GeckoResult.fromValue(true)
            }
            if (json.optString("type") == "pseudoFullscreen") {
                val on = json.optBoolean("on")
                main.post { pseudoFullscreenListener?.invoke(on) }
                return GeckoResult.fromValue(true)
            }
            if (json.optString("type") != "tap") return GeckoResult.fromValue(false)
            val fx = json.optDouble("fx", -1.0).toFloat()
            val fy = json.optDouble("fy", -1.0).toFloat()
            val listener = tapListener
            if (listener == null || fx !in 0f..1f || fy !in 0f..1f) return GeckoResult.fromValue(false)
            main.post { listener(fx, fy) }
            return GeckoResult.fromValue(true)
        }
    }
}
