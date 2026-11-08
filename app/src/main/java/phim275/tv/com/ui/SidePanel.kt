package phim275.tv.com.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import phim275.tv.com.R

/** Một dòng trong bảng menu. on = true/false hiện chip BẬT/TẮT; value = chữ phụ bên phải. */
class PanelItem(
    val label: String,
    val icon: Int? = null,
    val value: String? = null,
    val on: Boolean? = null,
    /** Giữ bảng mở sau khi bấm (dùng cho công tắc cài đặt — action tự vẽ lại trang). */
    val keepOpen: Boolean = false,
    /** Tô icon màu nhấn (mục đang chọn, hành động nổi bật). */
    val accent: Boolean = false,
    val action: () -> Unit,
)

class PanelSection(val title: String?, val items: List<PanelItem>)

/**
 * Bảng menu trượt từ cạnh phải kiểu Android TV: chia nhóm, có icon, chip trạng thái,
 * menu lồng nhau (Back quay về cấp trên), focus không lọt ra trang web phía sau.
 */
class SidePanel(context: Context) : FrameLayout(context) {

    private class Page(val title: String, val subtitle: String?, val build: () -> List<PanelSection>) {
        var focusIndex = 0
    }

    private val stack = ArrayDeque<Page>()
    private var pushCounter = 0
    private var closing = false
    private val rows = ArrayList<View>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = context.getColor(id)
    private fun dimen(id: Int) = resources.getDimension(id)

    private val scrim = View(context).apply { setBackgroundColor(color(R.color.scrim)) }
    private val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        setPadding(dp(20), dp(28), dp(20), dp(16))
        elevation = dp(16).toFloat()
    }
    private val titleView = TextView(context).apply {
        setTextColor(color(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.text_title))
        paint.isFakeBoldText = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitleView = TextView(context).apply {
        setTextColor(color(R.color.text_dim))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.text_caption))
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Gọi ngay khi bảng bắt đầu đóng (để Activity trả focus về trang web). */
    var onHidden: (() -> Unit)? = null

    val isShowing: Boolean get() = visibility == View.VISIBLE && !closing

    init {
        visibility = View.GONE
        isClickable = true // chặn chạm xuyên xuống trang
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sheet.addView(titleView)
        sheet.addView(subtitleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        sheet.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
        })
        addView(sheet, LayoutParams(dimen(R.dimen.panel_width).toInt(), LayoutParams.MATCH_PARENT, Gravity.END))
        scrim.setOnClickListener { hide() }
    }

    /** Mở bảng mới (xóa các cấp cũ). Nội dung dựng lại mỗi lần vẽ → công tắc luôn đúng trạng thái. */
    fun show(title: String, subtitle: String? = null, build: () -> List<PanelSection>) {
        stack.clear()
        push(title, subtitle, build)
    }

    /** Mở cấp con (Back quay về cấp hiện tại); nếu bảng đang đóng thì mở như bảng mới. */
    fun push(title: String, subtitle: String? = null, build: () -> List<PanelSection>) {
        if (!isShowing) stack.clear()
        stack.lastOrNull()?.focusIndex = focusedRowIndex()
        stack.addLast(Page(title, subtitle, build))
        pushCounter++
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        render(stack.last())
        if (visibility != View.VISIBLE || closing) slideIn()
    }

    /** Vẽ lại cấp hiện tại (sau khi đổi một công tắc), giữ nguyên dòng đang chọn. */
    fun refresh() {
        val page = stack.lastOrNull() ?: return
        page.focusIndex = focusedRowIndex()
        render(page)
    }

    /** Back: về cấp trên (vẽ lại để thấy giá trị mới), hoặc đóng bảng nếu đang ở cấp đầu. */
    fun back() {
        if (stack.size > 1) {
            stack.removeLast()
            render(stack.last())
        } else {
            hide()
        }
    }

    fun hide() {
        if (!isShowing) return
        closing = true
        stack.clear()
        // Trả focus về trang ngay: trong 160ms trượt ra, ▼/OK không được rơi vào các dòng đang biến mất.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        onHidden?.invoke()
        sheet.animate().translationX(sheet.width.toFloat()).setDuration(160).setInterpolator(null).withEndAction {
            if (closing) {
                closing = false
                visibility = View.GONE
                list.removeAllViews()
                rows.clear()
            }
        }.start()
        scrim.animate().alpha(0f).setDuration(160).start()
    }

    private fun slideIn() {
        closing = false
        sheet.animate().cancel()
        scrim.animate().cancel()
        if (visibility != View.VISIBLE) {
            sheet.translationX = dimen(R.dimen.panel_width)
            scrim.alpha = 0f
        }
        visibility = View.VISIBLE
        sheet.animate().translationX(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        scrim.animate().alpha(1f).setDuration(200).start()
    }

    private fun focusedRowIndex(): Int = rows.indexOfFirst { it.isFocused }.coerceAtLeast(0)

    private fun render(page: Page) {
        titleView.text = page.title
        subtitleView.text = page.subtitle ?: ""
        subtitleView.visibility = if (page.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        list.removeAllViews()
        rows.clear()
        page.build().forEachIndexed { sectionIndex, section ->
            if (section.items.isEmpty()) return@forEachIndexed
            if (section.title != null) list.addView(sectionHeader(section.title, first = sectionIndex == 0))
            section.items.forEach { item -> list.addView(row(item).also { rows += it }) }
        }
        // Giữ focus trong bảng: không cho ◀ ▶ hay đi quá đầu/cuối lọt sang trang web phía sau.
        rows.forEachIndexed { i, r ->
            r.nextFocusLeftId = r.id
            r.nextFocusRightId = r.id
            r.nextFocusUpId = if (i == 0) r.id else rows[i - 1].id
            r.nextFocusDownId = if (i == rows.lastIndex) r.id else rows[i + 1].id
        }
        val target = rows.getOrNull(page.focusIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
        if (page.focusIndex == 0) scroll.scrollTo(0, 0)
        target?.requestFocus()
        post { target?.requestFocus() }
    }

    private fun sectionHeader(text: String, first: Boolean) = TextView(context).apply {
        this.text = text.uppercase()
        setTextColor(color(R.color.text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.text_caption))
        letterSpacing = 0.08f
        paint.isFakeBoldText = true
        setPadding(dp(14), if (first) dp(2) else dp(14), dp(14), dp(6))
    }

    private fun row(item: PanelItem): View {
        val row = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.panel_row_bg)
            setPadding(dp(14), 0, dp(10), 0)
            minimumHeight = dimen(R.dimen.panel_row).toInt()
        }
        if (item.icon != null) {
            // icon = 0: chừa chỗ trống để chữ thẳng hàng với các dòng có icon (vd. lựa chọn chưa được chọn).
            row.addView(ImageView(context).apply {
                if (item.icon != 0) setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(color(if (item.accent) R.color.accent else R.color.text))
            }, LinearLayout.LayoutParams(dimen(R.dimen.panel_icon).toInt(), dimen(R.dimen.panel_icon).toInt()).apply {
                marginEnd = dp(14)
            })
        }
        row.addView(TextView(context).apply {
            text = item.label
            setTextColor(color(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.text_body))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(9), 0, dp(9))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chipText = when (item.on) {
            true -> "BẬT"
            false -> "TẮT"
            null -> item.value
        }
        if (!chipText.isNullOrBlank()) {
            row.addView(TextView(context).apply {
                text = chipText
                setTextColor(color(if (item.on == true) R.color.ok else R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.text_caption))
                paint.isFakeBoldText = item.on != null
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(160)
                setBackgroundResource(if (item.on == true) R.drawable.chip_on_bg else R.drawable.chip_bg)
                setPadding(dp(10), dp(3), dp(10), dp(3))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            })
        }
        row.setOnClickListener {
            val before = pushCounter
            item.action()
            // Hành động không mở cấp con và không yêu cầu giữ bảng → đóng bảng.
            if (!item.keepOpen && pushCounter == before) hide()
        }
        return row
    }
}
