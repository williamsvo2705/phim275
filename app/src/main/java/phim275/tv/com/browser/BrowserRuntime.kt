package phim275.tv.com.browser

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import phim275.tv.com.BuildConfig
import phim275.tv.com.data.AppSettings
import phim275.tv.com.data.DeviceProfile
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
        // Bộ nhớ đệm ảnh đã giải mã: mặc định tới ~¼ RAM (≈400MB trên máy 1,6GB) → giới hạn 128MB.
        "image.mem.surfacecache.max_size_kb" to "131072",
        // Bộ đệm video MSE (HLS/DASH — hầu hết trang phim): mặc định giữ tới 150MB video + 20MB audio
        // (cả đoạn đã xem) trong process trang → xem lâu là RAM tăng dần. 80MB vẫn dư cho 1080p.
        "media.mediasource.eviction_threshold.video" to (80L * 1024 * 1024).toString(),
        "media.mediasource.eviction_threshold.audio" to (12L * 1024 * 1024).toString(),
        // Gộp compositor (WebRender) vào process chính thay vì process GPU riêng: bớt ~200MB trên máy chiếu
        // 1,6GB và app nâng được ưu tiên luồng vẽ (RenderThreadBoost) → khung hình video đều hơn.
        "layers.gpu-process.enabled" to "false",
        // Không đoán trước / tải trước các trang có thể mở (tốn mạng, CPU, RAM trên máy yếu).
        "network.predictor.enabled" to "false",
        "network.prefetch-next" to "false",
        "network.http.speculative-parallel-limit" to "0",
    )

    /** Chế độ RAM thấp (TV box 1GB): đè lên LOW_MEMORY_PREFS. */
    private val TINY_RAM_PREFS = mapOf(
        "image.mem.surfacecache.max_size_kb" to "49152",     // ảnh đã giải mã: 48MB
        "browser.cache.memory.capacity" to "8192",           // cache RAM: 8MB
        "browser.sessionhistory.max_entries" to "15",        // lịch sử Back mỗi tab (mặc định 50)
        "media.memory_cache_max_size" to "16384",            // đệm video trong RAM: 16MB (phần còn lại ra đĩa)
        "media.mediasource.eviction_threshold.video" to (40L * 1024 * 1024).toString(), // MSE: ~1 phút 1080p
        "media.mediasource.eviction_threshold.audio" to (8L * 1024 * 1024).toString(),
        "javascript.options.mem.gc_incremental_slice_ms" to "10",
        "image.mem.decode_bytes_at_a_time" to "65536",       // giải mã ảnh từng phần nhỏ
        "layout.css.prefers-reduced-motion" to "1",          // bớt hiệu ứng chuyển động
    )

    @Volatile
    var isLowRam: Boolean = false
        private set

    /**
     * Pref Gecko thêm để chẩn đoán trên máy thật (bản release không có about:config): mỗi dòng `tên.pref: giá trị`
     * trong Android/data/phim275.tv.com/files/gecko-prefs.yaml — chỉ app và `adb shell` ghi được thư mục này.
     */
    private fun extraPrefs(appContext: Context): Map<String, String> {
        val file = appContext.getExternalFilesDir(null)?.let { java.io.File(it, "gecko-prefs.yaml") } ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return try {
            file.readLines().mapNotNull { line ->
                EXTRA_PREF_LINE.matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2] }
            }.toMap().also { if (it.isNotEmpty()) android.util.Log.i("Phim275Runtime", "Pref Gecko thêm: $it") }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val EXTRA_PREF_LINE = Regex("""([a-z0-9_.\-]+):\s*(-?\d+|true|false|"[^"]*")""")

    private fun writeGeckoConfig(appContext: Context, lowRam: Boolean): String {
        val prefs = (if (lowRam) LOW_MEMORY_PREFS + TINY_RAM_PREFS else LOW_MEMORY_PREFS) + extraPrefs(appContext)
        val yaml = buildString {
            appendLine("prefs:")
            prefs.forEach { (k, v) -> appendLine("  $k: $v") }
        }
        val file = java.io.File(appContext.filesDir, "geckoview-config.yaml")
        if (!file.exists() || file.readText() != yaml) file.writeText(yaml)
        return file.absolutePath
    }

    private fun create(appContext: Context): GeckoRuntime {
        val lowRam = DeviceProfile.isLowRam(appContext)
        isLowRam = lowRam
        UblockBridge.lowMemoryLists = lowRam
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(writeGeckoConfig(appContext, lowRam))
            // Biến môi trường cho Gecko (GeckoThread đọc "env0", "env1"…): tắt bộ thu crash của Firefox —
            // Phim275 không gửi báo cáo crash của Gecko đi đâu, mà process crashhelper tốn ~40MB RAM.
            .extras(android.os.Bundle().apply { putString("env0", "MOZ_CRASHREPORTER_DISABLE=1") })
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            // Máy chiếu/TV box ~2GB RAM: không tách mỗi trang một process.
            .fissionEnabled(false)
            // Máy thường: uBO chạy ở process riêng (thiếu RAM thì hệ thống giết process này trước,
            // giao diện app vẫn sống). Box 1GB: chạy chung process chính để bớt một process (~40–60MB).
            .extensionsProcessEnabled(!lowRam)
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
        TvNavBridge.install(rt)
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
