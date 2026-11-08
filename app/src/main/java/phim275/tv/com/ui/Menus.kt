package phim275.tv.com.ui

import android.app.Activity
import android.app.AlertDialog
import phim275.tv.com.R

/** Danh sách đơn giản một nhóm (mở mới hoặc lồng vào bảng đang mở). */
fun SidePanel.menu(title: String, subtitle: String? = null, items: () -> List<PanelItem>) =
    push(title, subtitle) { listOf(PanelSection(null, items())) }

/** Hỏi xác nhận ngay trong bảng: Back/Hủy quay về cấp trước. */
fun SidePanel.confirm(title: String, message: String, positive: String, icon: Int = R.drawable.ic_check, onConfirm: () -> Unit) =
    push(title, message) {
        listOf(
            PanelSection(null, listOf(
                PanelItem(positive, icon, accent = true, action = onConfirm),
                PanelItem("Hủy", R.drawable.ic_close, keepOpen = true) { back() },
            )),
        )
    }

/** Chọn một giá trị: dấu ✓ ở giá trị hiện tại; chọn xong quay về cấp trước (đã vẽ lại với giá trị mới). */
fun <T> SidePanel.choose(title: String, subtitle: String?, options: List<T>, selected: () -> T, label: (T) -> String, onPick: (T) -> Unit) =
    push(title, subtitle) {
        val current = selected()
        listOf(
            PanelSection(null, options.map { option ->
                val on = option == current
                PanelItem(label(option), if (on) R.drawable.ic_check else 0, accent = on, keepOpen = true) {
                    back()
                    onPick(option) // có thể mở thêm cấp (vd. hỏi khởi động lại) đè lên cấp cha
                    refresh() // vẽ lại cấp trên cùng với giá trị vừa chọn
                }
            }),
        )
    }

private const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

/** Hộp tiến trình (tải bản cập nhật) — không cho đóng bằng Back. */
fun Activity.showProgress(title: String, message: String): AlertDialog =
    AlertDialog.Builder(this, DIALOG_THEME)
        .setTitle(title)
        .setMessage(message)
        .setCancelable(false)
        .show()
