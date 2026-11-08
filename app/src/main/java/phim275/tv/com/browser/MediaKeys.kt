package phim275.tv.com.browser

import android.view.KeyEvent

/** Ánh xạ phím remote → lệnh điều khiển video trên trang (thuần logic, có unit test). */
object MediaKeys {
    const val SEEK_SECONDS = 10

    enum class Action(val id: String) {
        TOGGLE("toggle"),
        PLAY("play"),
        PAUSE("pause"),
        FORWARD("forward"),
        REWIND("rewind"),
    }

    /** Phím media chuyên dụng (Play/Pause, tua…) — luôn điều khiển video. */
    fun forMediaKey(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Action.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PLAY -> Action.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> Action.PAUSE
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> Action.FORWARD
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> Action.REWIND
        else -> null
    }

    /**
     * Khi đang xem video toàn màn hình và con trỏ đang ẩn: ◀ ▶ tua, OK tạm dừng/phát.
     * ▲ ▼ không ánh xạ → hiện con trỏ để bấm các nút của trình phát.
     */
    fun forFullscreenKey(keyCode: Int, cursorVisible: Boolean): Action? {
        if (cursorVisible) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> Action.FORWARD
            KeyEvent.KEYCODE_DPAD_LEFT -> Action.REWIND
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Action.TOGGLE
            else -> null
        }
    }
}
