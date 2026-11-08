package phim275.tv.com.cursor

import android.view.Choreographer
import android.view.KeyEvent

/**
 * Điều khiển con trỏ ảo bằng D-pad: giữ phím để chạy nhanh dần,
 * chạm mép màn hình thì cuộn trang.
 */
class CursorController(
    private val view: CursorView,
    private val callbacks: Callbacks,
) : Choreographer.FrameCallback {

    interface Callbacks {
        /** Con trỏ đã di chuyển (dùng để gửi sự kiện hover cho trang). */
        fun onCursorMoved(x: Float, y: Float)

        /** Con trỏ bị đẩy quá mép màn hình — yêu cầu cuộn trang theo (dx, dy) pixel. */
        fun onEdgePush(dx: Float, dy: Float)
    }

    private val pressed = LinkedHashSet<Int>()
    private var holdStartNanos = 0L
    private var lastFrameNanos = 0L
    private var running = false
    private val hideRunnable = Runnable { view.fadeOut() }

    val x: Float get() = view.cursorX
    val y: Float get() = view.cursorY

    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in DIRECTION_KEYS) return false
        view.showCursor()
        view.removeCallbacks(hideRunnable)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (pressed.add(code)) {
                val now = System.nanoTime()
                if (pressed.size == 1) holdStartNanos = now
                // Nhấn nhanh một lần = dịch một bước nhỏ để chỉnh chính xác.
                val step = CursorPhysics.TAP_STEP_PX * CursorPhysics.scaleFor(view.width)
                val (dx, dy) = direction(code)
                moveBy(dx * step, dy * step)
                startLoop(now)
            }
            KeyEvent.ACTION_UP -> {
                pressed.remove(code)
                if (pressed.isEmpty()) scheduleHide()
            }
        }
        return true
    }

    /** Hiện lại con trỏ (khi có phím bất kỳ) rồi tự ẩn sau một lúc. */
    fun poke() {
        view.showCursor()
        scheduleHide()
    }

    fun release() {
        pressed.clear()
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        view.removeCallbacks(hideRunnable)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (pressed.isEmpty()) {
            running = false
            return
        }
        val dt = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos
        val held = (frameTimeNanos - holdStartNanos) / 1e9f
        val speed = CursorPhysics.speed(held) * CursorPhysics.scaleFor(view.width)
        var dirX = 0f
        var dirY = 0f
        for (code in pressed) {
            val (dx, dy) = direction(code)
            dirX += dx
            dirY += dy
        }
        moveBy(dirX.coerceIn(-1f, 1f) * speed * dt, dirY.coerceIn(-1f, 1f) * speed * dt)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startLoop(nowNanos: Long) {
        if (running) return
        running = true
        lastFrameNanos = nowNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val maxX = (view.width - 1).toFloat()
        val maxY = (view.height - 1).toFloat()
        val targetX = view.cursorX + dx
        val targetY = view.cursorY + dy
        val overflowX = when {
            targetX < 0f -> targetX
            targetX > maxX -> targetX - maxX
            else -> 0f
        }
        val overflowY = when {
            targetY < 0f -> targetY
            targetY > maxY -> targetY - maxY
            else -> 0f
        }
        view.moveTo(targetX, targetY)
        if (overflowX != 0f || overflowY != 0f) {
            callbacks.onEdgePush(overflowX * EDGE_SCROLL_GAIN, overflowY * EDGE_SCROLL_GAIN)
        }
        callbacks.onCursorMoved(view.cursorX, view.cursorY)
    }

    private fun scheduleHide() {
        view.removeCallbacks(hideRunnable)
        view.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    private fun direction(code: Int): Pair<Float, Float> = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
        KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
        KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
        KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
        else -> 0f to 0f
    }

    companion object {
        val DIRECTION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
        private const val EDGE_SCROLL_GAIN = 2.2f
        private const val AUTO_HIDE_MS = 5000L
    }
}
