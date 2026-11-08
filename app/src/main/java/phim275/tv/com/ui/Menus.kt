package phim275.tv.com.ui

import android.app.Activity
import android.app.AlertDialog

/** Một mục trong menu dạng danh sách (điều khiển bằng remote: ▲▼ chọn, OK bấm, Back đóng). */
class MenuItem(val label: String, val action: () -> Unit)

private const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

fun Activity.showMenu(title: String?, items: List<MenuItem>): AlertDialog =
    AlertDialog.Builder(this, DIALOG_THEME)
        .setTitle(title)
        .setItems(items.map { it.label }.toTypedArray()) { dialog, which ->
            dialog.dismiss()
            items[which].action()
        }
        .show()

fun Activity.showConfirm(title: String, message: String, positive: String, onConfirm: () -> Unit): AlertDialog =
    AlertDialog.Builder(this, DIALOG_THEME)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positive) { _, _ -> onConfirm() }
        .setNegativeButton("Hủy", null)
        .show()

fun <T> Activity.showChoice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit): AlertDialog =
    AlertDialog.Builder(this, DIALOG_THEME)
        .setTitle(title)
        .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(selected)) { dialog, which ->
            dialog.dismiss()
            onPick(options[which])
        }
        .show()

fun Activity.showProgress(title: String, message: String): AlertDialog =
    AlertDialog.Builder(this, DIALOG_THEME)
        .setTitle(title)
        .setMessage(message)
        .setCancelable(false)
        .show()
