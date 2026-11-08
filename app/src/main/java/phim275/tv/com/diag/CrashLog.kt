package phim275.tv.com.diag

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ghi lại crash Java gần nhất ra file để hiển thị trong bảng Chẩn đoán và kèm theo khi báo lỗi.
 * (Không gửi đi đâu cả — người dùng chủ động báo lỗi qua mã QR.)
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.writeText("$time [${thread.name}]\n$sw")
            } catch (_: Throwable) {
                // Không để lỗi ghi log che mất crash gốc.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Dòng tóm tắt, ví dụ "2026-11-08 12:00:01 · java.lang.IllegalStateException: …". */
    fun summary(context: Context): String? {
        val text = read(context) ?: return null
        val lines = text.lineSequence().toList()
        val time = lines.firstOrNull()?.substringBefore(" [") ?: return null
        val cause = lines.getOrNull(1)?.take(90) ?: ""
        return "$time · $cause"
    }

    fun read(context: Context): String? =
        File(context.applicationContext.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()
}
