package phim275.tv.com.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Lớp phủ vẽ con trỏ chuột ảo. Không nhận focus, không chặn sự kiện chạm. */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var cursorX = -1f
        private set
    var cursorY = -1f
        private set

    private val density = resources.displayMetrics.density
    private val size = 30f * density
    private val arrow = Path().apply {
        // Mũi tên chuột cổ điển, đỉnh tại (0,0).
        moveTo(0f, 0f)
        lineTo(0f, 0.92f)
        lineTo(0.25f, 0.70f)
        lineTo(0.42f, 1.05f)
        lineTo(0.56f, 0.98f)
        lineTo(0.40f, 0.64f)
        lineTo(0.72f, 0.64f)
        close()
    }
    private val scaledArrow = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f * density, 0f, 1.5f * density, 0x99000000.toInt())
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val matrix = android.graphics.Matrix()

    init {
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_SOFTWARE, null) // cần cho setShadowLayer
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (cursorX < 0 || cursorX > w || cursorY > h) moveTo(w / 2f, h / 2f)
    }

    fun moveTo(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cursorY = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    fun showCursor() {
        animate().cancel()
        alpha = 1f
    }

    fun fadeOut() {
        animate().alpha(0f).setDuration(400).start()
    }

    override fun onDraw(canvas: Canvas) {
        if (cursorX < 0) return
        matrix.reset()
        matrix.setScale(size, size)
        matrix.postTranslate(cursorX, cursorY)
        arrow.transform(matrix, scaledArrow)
        canvas.drawPath(scaledArrow, fill)
        canvas.drawPath(scaledArrow, stroke)
    }
}
