package phim275.tv.com.browser

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import phim275.tv.com.BuildConfig
import phim275.tv.com.data.AppSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Một GeckoRuntime duy nhất cho cả app (Gecko không cho tạo nhiều runtime),
 * kèm uBlock Origin cài sẵn dạng extension tích hợp.
 */
object BrowserRuntime {
    private const val TAG = "Phim275Runtime"

    const val UBLOCK_ID = "uBlock0@raymondhill.net"
    const val UBLOCK_URI = "resource://android/assets/extensions/ublock/"

    /**
     * DNS-over-HTTPS của AdGuard: (1) vượt chặn DNS của nhà mạng, (2) chặn thêm tên miền
     * quảng cáo ngay từ tầng DNS. Chế độ FIRST: lỗi thì tự quay về DNS thường.
     */
    private const val DOH_URI = "https://dns.adguard-dns.com/dns-query"

    sealed interface UblockState {
        data object Loading : UblockState
        data class Ready(val extension: WebExtension) : UblockState
        data class Failed(val message: String) : UblockState
    }

    @Volatile
    var ublockState: UblockState = UblockState.Loading
        private set

    private val observers = CopyOnWriteArrayList<(UblockState) -> Unit>()
    private var runtime: GeckoRuntime? = null

    // Chỉ gọi trên main thread.
    fun get(context: Context): GeckoRuntime = runtime ?: create(context.applicationContext).also { runtime = it }

    /** Đăng ký nhận trạng thái uBO; gọi ngay một lần với trạng thái hiện tại. */
    // Chỉ gọi trên main thread.
    fun observeUblock(observer: (UblockState) -> Unit) {
        observers += observer
        observer(ublockState)
    }

    // Chỉ gọi trên main thread.
    fun removeObserver(observer: (UblockState) -> Unit) {
        observers -= observer
    }

    /**
     * Tinh chỉnh Gecko cho máy RAM thấp (máy chiếu/TV box ~2GB).
     * GeckoView chỉ nhận pref qua file YAML truyền bằng configFilePath.
     */
    private val LOW_MEMORY_PREFS = mapOf(
        // Không chạy sẵn một process nội dung dự phòng (~80MB).
        "dom.ipc.processPrelaunch.enabled" to "false",
        // Không giữ trang cũ trong bộ nhớ để Back nhanh (bfcache) — mỗi trang có thể vài chục MB.
        "browser.sessionhistory.max_total_viewers" to "0",
        // Giới hạn cache trong RAM (KB).
        "browser.cache.memory.capacity" to "32768",
    )

    private fun writeGeckoConfig(appContext: Context): String {
        val yaml = buildString {
            appendLine("prefs:")
            LOW_MEMORY_PREFS.forEach { (k, v) -> appendLine("  $k: $v") }
        }
        val file = java.io.File(appContext.filesDir, "geckoview-config.yaml")
        if (!file.exists() || file.readText() != yaml) file.writeText(yaml)
        return file.absolutePath
    }

    private fun create(appContext: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(writeGeckoConfig(appContext))
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            // Máy chiếu/TV box ~2GB RAM: không tách mỗi trang một process.
            .fissionEnabled(false)
            // uBO chạy ở process riêng: thiếu RAM thì hệ thống giết process này trước,
            // giao diện app vẫn sống (Gecko tự khởi động lại extension).
            .extensionsProcessEnabled(true)
            .lowMemoryDetection(true)
            .doubleTapZoomingEnabled(false)
            .inputAutoZoomEnabled(false)
            .trustedRecursiveResolverMode(trrMode(AppSettings(appContext).adBlockDns))
            .trustedRecursiveResolverUri(DOH_URI)
            .automaticFontSizeAdjustment(false)
            .fontSizeFactor(AppSettings(appContext).fontScale)
            .build()

        val rt = GeckoRuntime.create(appContext, settings)
        installUblock(rt)
        return rt
    }

    /** Bật/tắt DNS chặn quảng cáo ngay khi đang chạy (không cần khởi động lại). */
    fun setAdBlockDns(enabled: Boolean) {
        runtime?.settings?.setTrustedRecursiveResolverMode(trrMode(enabled))
    }

    /** Đổi cỡ chữ trang web ngay (1.0 = 100%). */
    fun setFontScale(scale: Float) {
        runtime?.settings?.setFontSizeFactor(scale)
    }

    private fun trrMode(enabled: Boolean) =
        if (enabled) GeckoRuntimeSettings.TRR_MODE_FIRST else GeckoRuntimeSettings.TRR_MODE_OFF

    private fun installUblock(rt: GeckoRuntime) {
        rt.webExtensionController.ensureBuiltIn(UBLOCK_URI, UBLOCK_ID).accept(
            { ext ->
                if (ext != null) {
                    Log.i(TAG, "uBlock Origin ${ext.metaData.version} sẵn sàng")
                    UblockBridge.attach(ext)
                    publish(UblockState.Ready(ext))
                } else {
                    publish(UblockState.Failed("extension null"))
                }
            },
            { error ->
                Log.e(TAG, "Không cài được uBlock Origin", error)
                publish(UblockState.Failed(error?.message ?: error.toString()))
            },
        )
    }

    private fun publish(state: UblockState) {
        ublockState = state
        observers.forEach { it(state) }
    }
}
