package phim275.tv.com.browser

import android.util.Base64
import org.mozilla.geckoview.WebRequestError

/** Trang lỗi tiếng Việt, cỡ chữ lớn cho màn hình TV. Trả về data: URI cho GeckoView. */
object ErrorPages {

    fun dataUri(failedUrl: String?, error: WebRequestError): String {
        val (title, hint) = describe(error)
        val safeUrl = escape(failedUrl ?: "")
        val html = """
            <!doctype html><html lang="vi"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title>
            <style>
              body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0e1116;color:#f2f4f8;
                   font-family:system-ui,Roboto,sans-serif;text-align:center}
              main{max-width:900px;padding:32px}
              .icon{font-size:72px}
              h1{font-size:40px;margin:12px 0}
              p{font-size:22px;color:#9aa4b2;line-height:1.6}
              code{display:block;margin-top:18px;font-size:17px;color:#f2f4f8;word-break:break-all;opacity:.8}
              button{margin-top:28px;padding:18px 36px;border-radius:14px;border:4px solid transparent;background:#e50914;
                color:#fff;font-size:24px;font-weight:700;font-family:inherit}
              button:focus{border-color:#fff;outline:none}
            </style></head><body><main>
              <div class="icon">⚠️</div><h1>$title</h1><p>$hint</p>
              <code>$safeUrl</code>
              <!-- autofocus: ở chế độ chuyển khối chỉ cần bấm OK (Enter) để thử lại. -->
              <button autofocus data-u="$safeUrl" onclick="location.href=this.dataset.u">⟳ Thử lại</button>
            </main></body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8;base64," +
            Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun describe(error: WebRequestError): Pair<String, String> = when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            "Không tìm thấy trang web" to "Kiểm tra lại địa chỉ, hoặc kết nối mạng của TV."
        WebRequestError.ERROR_CONNECTION_REFUSED, WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT ->
            "Không kết nối được" to "Máy chủ của trang web không phản hồi. Thử lại sau ít phút."
        WebRequestError.ERROR_NET_TIMEOUT ->
            "Trang tải quá lâu" to "Mạng đang chậm hoặc trang web quá tải."
        WebRequestError.ERROR_OFFLINE ->
            "Mất kết nối mạng" to "TV đang không có Internet. Kiểm tra Wi-Fi rồi thử lại."
        WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_SECURITY_BAD_CERT ->
            "Kết nối không an toàn" to "Chứng chỉ bảo mật của trang không hợp lệ. Có thể đồng hồ của TV bị sai giờ."
        WebRequestError.ERROR_MALFORMED_URI ->
            "Địa chỉ không hợp lệ" to "Địa chỉ web bị sai định dạng."
        else ->
            "Không mở được trang" to "Đã xảy ra lỗi khi tải trang (mã ${error.code})."
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
