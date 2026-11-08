package phim275.tv.com.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * Kênh native messaging tới uBlock Origin (phía uBO: tools/ublock-bridge/phim275-bridge.js).
 * Mọi callback chạy trên main thread.
 */
object UblockBridge {
    private const val TAG = "Phim275Bridge"
    private const val NATIVE_APP = "phim275"
    private const val TIMEOUT_MS = 5000L

    private val main = Handler(Looper.getMainLooper())
    private var port: WebExtension.Port? = null
    private var nextId = 1
    private val pending = HashMap<Int, (JSONObject?) -> Unit>()

    /** Số yêu cầu uBO đã chặn kể từ khi khởi động (cập nhật qua [refreshStats]). */
    var blockedCount: Long = -1
        private set

    val isConnected: Boolean get() = port != null

    fun attach(extension: WebExtension) {
        extension.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(newPort: WebExtension.Port) {
                Log.i(TAG, "uBO đã kết nối")
                port = newPort
                newPort.setDelegate(portDelegate)
            }
        }, NATIVE_APP)
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, source: WebExtension.Port) {
            val json = message as? JSONObject ?: return
            val id = json.optInt("id", 0)
            if (id != 0) pending.remove(id)?.invoke(json)
        }

        override fun onDisconnect(source: WebExtension.Port) {
            if (port === source) port = null
            // Trả lời null cho các yêu cầu đang chờ để giao diện không bị treo.
            val waiting = pending.values.toList()
            pending.clear()
            waiting.forEach { it(null) }
        }
    }

    /** Gửi yêu cầu; [callback] nhận null nếu uBO chưa sẵn sàng / quá thời gian / lỗi. */
    fun request(type: String, fields: Map<String, Any?> = emptyMap(), callback: (JSONObject?) -> Unit = {}) {
        val p = port
        if (p == null) {
            callback(null)
            return
        }
        val id = nextId++
        val msg = JSONObject().put("id", id).put("type", type)
        fields.forEach { (k, v) -> msg.put(k, v) }
        pending[id] = { reply -> callback(reply?.takeIf { !it.has("error") }) }
        main.postDelayed({ pending.remove(id)?.invoke(null) }, TIMEOUT_MS)
        try {
            p.postMessage(msg)
        } catch (e: Exception) {
            Log.w(TAG, "postMessage lỗi", e)
            pending.remove(id)?.invoke(null)
        }
    }

    /** true = đang chặn quảng cáo trên trang này, false = trang được phép, null = không rõ. */
    fun getSiteEnabled(url: String, callback: (Boolean?) -> Unit) =
        request("getSiteState", mapOf("url" to url)) { callback(it?.optBoolean("enabled")) }

    fun setSiteEnabled(url: String, enabled: Boolean, callback: (Boolean?) -> Unit) =
        request("setSiteState", mapOf("url" to url, "enabled" to enabled)) { callback(it?.optBoolean("enabled")) }

    fun refreshStats() = request("stats") { reply ->
        if (reply != null) blockedCount = reply.optLong("blocked", -1)
    }

    fun updateFilters(callback: (Boolean) -> Unit) = request("updateFilters") { callback(it != null) }

    /** Điều khiển video trên trang hiện tại (mọi frame, kể cả iframe trình phát). */
    fun media(action: MediaKeys.Action, seconds: Int = MediaKeys.SEEK_SECONDS, callback: (Boolean) -> Unit = {}) =
        request("media", mapOf("action" to action.id, "seconds" to seconds)) { callback(it?.optBoolean("ok") == true) }

    /** zap = true: xóa tạm phần tử (mất khi tải lại); false: tạo bộ lọc vĩnh viễn. */
    fun pickElement(zap: Boolean, callback: (Boolean) -> Unit) =
        request("pickElement", mapOf("zap" to zap)) { callback(it?.optBoolean("ok") == true) }
}
