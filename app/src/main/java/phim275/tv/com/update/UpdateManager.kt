package phim275.tv.com.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import phim275.tv.com.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Kiểm tra và tải bản cập nhật từ phim275.vercel.app (→ GitHub Releases). */
class UpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo, val apk: UpdateInfo.Apk) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    fun check(callback: (CheckResult) -> Unit) {
        io.execute {
            val result = try {
                val info = UpdateInfo.parse(httpGetText(LATEST_URL))
                val abi = UpdateInfo.abiOf(BuildConfig.VERSION_CODE)
                val apk = abi?.let { info.apks[it] }
                when {
                    !UpdateInfo.isNewer(info.versionCode, BuildConfig.VERSION_CODE) -> CheckResult.UpToDate
                    apk == null -> CheckResult.Failed("Không có bản cho kiến trúc $abi")
                    else -> CheckResult.Available(info, apk)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Kiểm tra cập nhật lỗi", e)
                CheckResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            main.post { callback(result) }
        }
    }

    /** Tải APK về bộ nhớ đệm và kiểm tra SHA-256. [onProgress] nhận 0..100. */
    fun download(apk: UpdateInfo.Apk, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit) {
        io.execute {
            val target = File(appContext.cacheDir, "update.apk")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val conn = open(apk.url)
                val total = conn.contentLengthLong
                var read = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = (read * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    main.post { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != apk.sha256) {
                    target.delete()
                    main.post { onDone(null, "File tải về bị hỏng (sai mã kiểm tra)") }
                } else {
                    main.post { onDone(target, null) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tải cập nhật lỗi", e)
                target.delete()
                main.post { onDone(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun httpGetText(url: String): String = open(url).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    /** HttpURLConnection tự theo chuyển hướng https→https (Vercel → GitHub → CDN). */
    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Phim275/${BuildConfig.VERSION_NAME}")
            }
            when (val code = conn.responseCode) {
                in 200..299 -> return conn
                301, 302, 303, 307, 308 -> {
                    current = URL(current, conn.getHeaderField("Location"))
                    conn.disconnect()
                }
                else -> throw IllegalStateException("HTTP $code")
            }
        }
        throw IllegalStateException("Quá nhiều lần chuyển hướng")
    }

    companion object {
        private const val TAG = "Phim275Update"
        const val LATEST_URL = "https://phim275.vercel.app/latest.json"
        private const val MAX_REDIRECTS = 6
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
