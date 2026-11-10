package phim275.tv.com

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Khởi động lại app (đổi chế độ RAM thấp cần tạo lại GeckoRuntime).
 *
 * Android 14+ chặn mở activity từ nền: cách cũ (hẹn AlarmManager rồi tự tắt process) bị bỏ qua vì lúc
 * hẹn giờ chạy, app không còn hiển thị. Ở đây làm như ProcessPhoenix: mở activity trung gian (process
 * riêng, không giao diện) — nó đang hiển thị nên được phép mở lại app — đợi process cũ tắt rồi mở MainActivity.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldPid = intent.getIntExtra(EXTRA_PID, -1)
        if (oldPid > 0) Process.killProcess(oldPid) // phòng khi process cũ chưa kịp tự tắt
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) startActivity(launch)
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PID = "phim275.restart.pid"

        /** Gọi từ process chính: mở activity trung gian rồi tắt process hiện tại. */
        fun restart(context: Context) {
            context.startActivity(
                Intent(context, RestartActivity::class.java)
                    .putExtra(EXTRA_PID, Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Runtime.getRuntime().exit(0)
        }
    }
}
