package phim275.tv.com.browser

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import phim275.tv.com.browser.MediaKeys.Action

class MediaKeysTest {

    @Test fun `phím media chuyên dụng`() {
        assertEquals(Action.TOGGLE, MediaKeys.forMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(Action.PLAY, MediaKeys.forMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(Action.PAUSE, MediaKeys.forMediaKey(KeyEvent.KEYCODE_MEDIA_STOP))
        assertEquals(Action.FORWARD, MediaKeys.forMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(Action.REWIND, MediaKeys.forMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertNull(MediaKeys.forMediaKey(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test fun `toàn màn hình, con trỏ ẩn - mũi tên trái phải để tua, OK để dừng`() {
        assertEquals(Action.FORWARD, MediaKeys.forFullscreenKey(KeyEvent.KEYCODE_DPAD_RIGHT, cursorVisible = false))
        assertEquals(Action.REWIND, MediaKeys.forFullscreenKey(KeyEvent.KEYCODE_DPAD_LEFT, cursorVisible = false))
        assertEquals(Action.TOGGLE, MediaKeys.forFullscreenKey(KeyEvent.KEYCODE_DPAD_CENTER, cursorVisible = false))
        // ▲▼ để hiện con trỏ
        assertNull(MediaKeys.forFullscreenKey(KeyEvent.KEYCODE_DPAD_UP, cursorVisible = false))
    }

    @Test fun `con trỏ đang hiện thì mũi tên điều khiển con trỏ như thường`() {
        assertNull(MediaKeys.forFullscreenKey(KeyEvent.KEYCODE_DPAD_RIGHT, cursorVisible = true))
        assertNull(MediaKeys.forFullscreenKey(KeyEvent.KEYCODE_DPAD_CENTER, cursorVisible = true))
    }
}
