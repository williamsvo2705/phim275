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

    /** Giữ OK ở chế độ chuyển khối: (liên kết của khối hoặc null, chữ trên khối, khối là trình phát video). */
    var contextListener: ((String?, String, Boolean) -> Unit)? = null

    /** Vị trí video chính đang xem: (URL trang của tab hoặc null, giây hiện tại, độ dài, ghi ngay, đã phát hết). */
    var playbackListener: ((String?, Double, Double, Boolean, Boolean) -> Unit)? = null

    /** Trang xem phim báo tập kế tiếp: (URL trang hoặc null, tên tập — null = không có tập sau). */
    var nextEpisodeListener: ((String?, String?) -> Unit)? = null

    /** Video vừa bắt đầu phát: (URL trang hoặc null, độ dài, giây lúc bắt đầu) → giây cần tua tới, hoặc null = để yên. */
    var resumeProvider: ((String?, Double, Double) -> Double?)? = null

    private const val TIMEOUT_MS = 3000L
    private var port: WebExtension.Port? = null
    private var nextId = 1
    private val pending = HashMap<Int, (JSONObject?) -> Unit>()

    /**
     * Tùy chọn video: [cmd] = "info" | "rate" (value = tốc độ) | "level" (value = chỉ số chất lượng).
     * [callback] (main thread) nhận trạng thái trình phát sau khi đổi, hoặc null nếu trang không có video / chưa sẵn sàng.
     */
    fun video(cmd: String, value: Any? = null, callback: (JSONObject?) -> Unit) {
        val p = port
        if (p == null) {
            callback(null)
            return
        }
        val id = nextId++
        pending[id] = callback
        main.postDelayed({ pending.remove(id)?.invoke(null) }, TIMEOUT_MS)
        try {
            p.postMessage(JSONObject().put("id", id).put("type", "video").put("cmd", cmd).put("value", value ?: JSONObject.NULL))
        } catch (e: Exception) {
            Log.w(TAG, "postMessage lỗi", e)
            pending.remove(id)?.invoke(null)
        }
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, source: WebExtension.Port) {
            val json = message as? JSONObject ?: return
            val callback = pending.remove(json.optInt("id", 0)) ?: return
            callback(if (json.optBoolean("ok")) json.optJSONObject("info") else null)
        }

        override fun onDisconnect(source: WebExtension.Port) {
            if (port === source) port = null
            val waiting = pending.values.toList()
            pending.clear()
            waiting.forEach { it(null) }
        }
    }

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
        override fun onConnect(newPort: WebExtension.Port) {
            port = newPort
            newPort.setDelegate(portDelegate)
        }

        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val json = message as? JSONObject ?: return GeckoResult.fromValue(false)
            val page = if (json.isNull("page")) null else json.optString("page").takeIf { it.isNotBlank() }
            if (json.optString("type") == "mediaPos") {
                val t = json.optDouble("t", Double.NaN)
                val d = json.optDouble("d", Double.NaN)
                val flush = json.optBoolean("flush")
                val ended = json.optBoolean("ended")
                if (t.isNaN() || d.isNaN()) return GeckoResult.fromValue(false)
                if (flush) Log.i(TAG, "Vị trí video %.0f/%.0f giây%s (%s)".format(t, d, if (ended) ", hết phim" else "", page ?: "tab hiện tại"))
                main.post { playbackListener?.invoke(page, t, d, flush, ended) }
                return GeckoResult.fromValue(true)
            }
            if (json.optString("type") == "mediaResume") {
                val d = json.optDouble("d", Double.NaN)
                val start = json.optDouble("c", 0.0)
                if (d.isNaN()) return GeckoResult.fromValue(0)
                Log.i(TAG, "Video bắt đầu phát ở %.0f/%.0f giây (%s)".format(start, d, page ?: "tab hiện tại"))
                val result = GeckoResult<Any>()
                main.post { result.complete(resumeProvider?.invoke(page, d, start) ?: 0) }
                return result
            }
            if (json.optString("type") == "nextEp") {
                val label = if (json.isNull("label")) null else json.optString("label")
                Log.i(TAG, "Tập tiếp theo: %s (%s)".format(label ?: "không có", page ?: "tab hiện tại"))
                main.post { nextEpisodeListener?.invoke(page, label) }
                return GeckoResult.fromValue(true)
            }
            if (json.optString("type") == "context") {
                val href = if (json.isNull("href")) null else json.optString("href").takeIf { it.isNotBlank() }
                val text = json.optString("text")
                val media = json.optBoolean("media")
                main.post { contextListener?.invoke(href, text, media) }
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
