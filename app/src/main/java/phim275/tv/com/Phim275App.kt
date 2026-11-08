package phim275.tv.com

import android.app.Application
import phim275.tv.com.diag.CrashLog

/**
 * Lưu ý: Application.onCreate chạy trong MỌI process của app, kể cả các process con của Gecko
 * (tab, gpu, media…). Chỉ làm việc nhẹ ở đây; GeckoRuntime được tạo trong MainActivity.
 */
class Phim275App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
