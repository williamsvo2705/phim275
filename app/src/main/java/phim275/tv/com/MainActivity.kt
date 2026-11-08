package phim275.tv.com

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import phim275.tv.com.browser.BrowserRuntime
import phim275.tv.com.browser.BrowserRuntime.UblockState
import phim275.tv.com.browser.BrowserTab
import phim275.tv.com.browser.ErrorPages
import phim275.tv.com.browser.SitePolicy
import phim275.tv.com.browser.TabManager
import phim275.tv.com.browser.TabPolicy
import phim275.tv.com.browser.UblockBridge
import phim275.tv.com.browser.UrlInput
import phim275.tv.com.cursor.CursorController
import phim275.tv.com.cursor.CursorView
import phim275.tv.com.data.AppSettings
import phim275.tv.com.data.AppSettings.NavMode
import phim275.tv.com.data.LinkStore
import phim275.tv.com.data.SearchEngine
import phim275.tv.com.diag.CrashLog
import phim275.tv.com.diag.Diagnostics
import phim275.tv.com.ui.MenuItem
import phim275.tv.com.ui.showChoice
import phim275.tv.com.ui.showConfirm
import phim275.tv.com.ui.showMenu
import phim275.tv.com.ui.showProgress
import phim275.tv.com.update.ApkInstaller
import phim275.tv.com.update.UpdateInfo
import phim275.tv.com.update.UpdateManager
import java.net.URLEncoder

class MainActivity : Activity(), CursorController.Callbacks, TabManager.Listener {

    private lateinit var geckoView: GeckoView
    private lateinit var cursorView: CursorView
    private lateinit var cursor: CursorController
    private lateinit var toolbar: View
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var diagPanel: TextView
    private lateinit var btnTabs: TextView
    private lateinit var btnBookmark: TextView

    private lateinit var settings: AppSettings
    private lateinit var bookmarks: LinkStore
    private lateinit var history: LinkStore
    private lateinit var diagnostics: Diagnostics
    private lateinit var updater: UpdateManager
    private lateinit var runtime: GeckoRuntime
    private lateinit var tabs: TabManager

    private var pageScrollY = 0
    private var fullScreen = false
    private var pageStartMs = 0L
    private var lastBackPressMs = 0L
    private var backLongPressHandled = false
    private var centerLongPressFired = false
    private var touchDownTime = 0L
    private var lastHoverMs = 0L
    private var topPushAccumPx = 0f
    private var blockedPopupUrl: String? = null
    private var blockedPopupAtMs = 0L
    private var toast: Toast? = null

    private val handler = Handler(Looper.getMainLooper())
    private val diagUpdater = object : Runnable {
        override fun run() {
            if (diagPanel.visibility == View.VISIBLE) {
                UblockBridge.refreshStats()
                updateDiagnostics()
                handler.postDelayed(this, DIAG_REFRESH_MS)
            }
        }
    }

    /** Giữ OK mà trang không mở menu chuột phải (không phải liên kết/ảnh) → mở menu của app. */
    private val centerLongPressFallback = Runnable {
        centerLongPressFired = true
        showMainMenu()
    }
    private val ublockObserver: (UblockState) -> Unit = { state -> onUblockState(state) }

    private val currentTab: BrowserTab? get() = tabs.current
    private val currentSession: GeckoSession? get() = tabs.current?.session
    private val currentUrl: String? get() = tabs.current?.url

    // ───────────────────────────── Vòng đời ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = Diagnostics(this) // đo RAM nền trước khi Gecko khởi động
        settings = AppSettings(this)
        bookmarks = LinkStore.bookmarks(this)
        history = LinkStore.history(this)
        updater = UpdateManager(this)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)
        cursorView = findViewById(R.id.cursor)
        toolbar = findViewById(R.id.toolbar)
        urlBar = findViewById(R.id.url_bar)
        progress = findViewById(R.id.progress)
        diagPanel = findViewById(R.id.diag_panel)
        btnTabs = findViewById(R.id.btn_tabs)
        btnBookmark = findViewById(R.id.btn_bookmark)
        cursor = CursorController(cursorView, this)

        runtime = BrowserRuntime.get(this)
        tabs = TabManager(runtime, geckoView, ::configureSession, this)
        setupToolbar()
        applyNavMode()
        BrowserRuntime.observeUblock(ublockObserver)

        tabs.newTab(intent?.dataString ?: homeUrl(), settings.desktopModeDefault)
        enterPageMode()
        scheduleAutoUpdateCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { tabs.newTab(it, settings.desktopModeDefault) }
    }

    override fun onResume() {
        super.onResume()
        currentSession?.setActive(true)
    }

    override fun onPause() {
        currentSession?.setActive(false)
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hệ thống sắp thiếu RAM: giải phóng các tab nền trước khi Android giết process.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) tabs.discardBackground()
    }

    override fun onDestroy() {
        BrowserRuntime.removeObserver(ublockObserver)
        handler.removeCallbacksAndMessages(null)
        cursor.release()
        if (isFinishing) tabs.closeAll()
        super.onDestroy()
    }

    @Deprecated("Activity API cũ, đủ dùng cho nhận dạng giọng nói")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { navigate(it) }
        }
    }

    // ───────────────────────────── Tab & phiên duyệt web ─────────────────────────────

    private fun configureSession(s: GeckoSession) {
        s.navigationDelegate = navigationDelegate
        s.progressDelegate = progressDelegate
        s.contentDelegate = contentDelegate
        s.scrollDelegate = scrollDelegate
        s.permissionDelegate = permissionDelegate
        s.promptDelegate = promptDelegate
        s.textInput.setDelegate(textInputDelegate)
    }

    // ───────────────────────────── Nhập chữ vào trang ─────────────────────────────

    private val imm by lazy { getSystemService(InputMethodManager::class.java) }
    private var pageInputActive = false

    /** Bàn phím vẫn không hiện sau khi trang yêu cầu → mở ô nhập của app. */
    private val imeCheck = Runnable {
        if (pageInputActive && !isImeVisible()) {
            pageInputActive = false
            showTextEntryDialog(forPage = true)
        }
    }

    /**
     * Thay delegate mặc định của GeckoView: delegate mặc định chỉ "xin" hiện bàn phím (SHOW_IMPLICIT),
     * nhiều TV/máy chiếu bỏ qua → bấm vào ô tìm kiếm không có bàn phím. Ở đây yêu cầu tường minh,
     * và nếu vẫn không hiện thì dùng ô nhập dự phòng.
     */
    private val textInputDelegate = object : GeckoSession.TextInputDelegate {
        override fun restartInput(session: GeckoSession, reason: Int) {
            if (tabs.isCurrent(session)) imm.restartInput(geckoView)
        }

        override fun showSoftInput(session: GeckoSession) {
            if (!tabs.isCurrent(session) || isToolbarMode()) return
            pageInputActive = true
            geckoView.requestFocus()
            imm.showSoftInput(geckoView, 0)
            handler.removeCallbacks(imeCheck)
            handler.postDelayed(imeCheck, IME_CHECK_MS)
        }

        override fun hideSoftInput(session: GeckoSession) {
            if (!tabs.isCurrent(session)) return
            pageInputActive = false
            handler.removeCallbacks(imeCheck)
            imm.hideSoftInputFromWindow(geckoView.windowToken, 0)
        }

        override fun updateSelection(session: GeckoSession, selStart: Int, selEnd: Int, compositionStart: Int, compositionEnd: Int) {
            if (tabs.isCurrent(session)) imm.updateSelection(geckoView, selStart, selEnd, compositionStart, compositionEnd)
        }

        override fun updateExtractedText(session: GeckoSession, request: ExtractedTextRequest, text: ExtractedText) {
            if (tabs.isCurrent(session)) imm.updateExtractedText(geckoView, request.token, text)
        }

        override fun updateCursorAnchorInfo(session: GeckoSession, info: CursorAnchorInfo) {
            if (tabs.isCurrent(session)) imm.updateCursorAnchorInfo(geckoView, info)
        }
    }

    /** Android 11+ kiểm tra được bàn phím có đang hiện không; máy cũ hơn coi như đã hiện. */
    private fun isImeVisible(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            geckoView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            true
        }

    /**
     * Ô nhập chữ của app (EditText thường luôn bật được bàn phím TV).
     * forPage = true: điền chữ vào ô đang chọn trên trang; false: tìm kiếm / mở địa chỉ.
     */
    private fun showTextEntryDialog(forPage: Boolean) {
        val input = EditText(this).apply {
            setSingleLine()
            textSize = 22f
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = if (forPage) "Gõ nội dung cần nhập" else getString(R.string.url_hint)
            if (!forPage) inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        fun submit(dialog: android.content.DialogInterface, pressEnter: Boolean) {
            val text = input.text.toString()
            dialog.dismiss()
            if (forPage) handler.post { commitTextToPage(text, pressEnter) } else navigate(text)
        }
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (forPage) "Nhập chữ vào ô trên trang" else "Tìm kiếm hoặc nhập địa chỉ web")
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
        if (forPage) {
            builder.setPositiveButton("Nhập & Enter") { d, _ -> submit(d, true) }
            builder.setNeutralButton("Chỉ nhập") { d, _ -> submit(d, false) }
        } else {
            builder.setPositiveButton("Đi") { d, _ -> submit(d, true) }
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submit(dialog, true)
            go
        }
        dialog.show()
        input.requestFocus()
    }

    /** Gửi cả chuỗi vào ô đang được chọn trên trang (sự kiện phím ACTION_MULTIPLE), rồi Enter nếu cần. */
    private fun commitTextToPage(text: String, pressEnter: Boolean) {
        if (isToolbarMode()) enterPageMode() // trả focus về trang trước khi gửi chữ
        geckoView.requestFocus()
        if (text.isNotEmpty()) {
            geckoView.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
        if (pressEnter) sendKeyToPage(KeyEvent.KEYCODE_ENTER, 0)
    }

    override fun onCurrentTabChanged(tab: BrowserTab) {
        pageScrollY = 0
        progress.visibility = View.GONE
        urlBar.setText(displayUrl(tab.url)) // đổi tab → luôn hiện URL của tab mới
        updateTabButton()
        updateBookmarkButton()
    }

    override fun onTabsChanged() = updateTabButton()

    private fun updateTabButton() {
        btnTabs.text = getString(R.string.btn_tabs_count, tabs.tabs.size)
    }

    private fun updateBookmarkButton() {
        val url = currentUrl
        val marked = url != null && bookmarks.contains(url)
        btnBookmark.setText(if (marked) R.string.btn_bookmark_remove else R.string.btn_bookmark_add)
    }

    private fun navigate(text: String) {
        UrlInput.toLoadableUrl(text, settings.searchEngine.template)?.let { currentSession?.loadUri(it) }
    }

    private fun onUblockState(state: UblockState) {
        when (state) {
            is UblockState.Ready -> tabs.refreshExtensionActiveTab()
            is UblockState.Failed -> showToast(getString(R.string.ublock_failed, state.message))
            UblockState.Loading -> Unit
        }
    }

    private fun ublockStatusText(): String = when (val s = BrowserRuntime.ublockState) {
        UblockState.Loading -> "đang tải…"
        is UblockState.Ready -> "${s.extension.metaData.version} ✓" + if (UblockBridge.isConnected) " · cầu nối ✓" else " · cầu nối …"
        is UblockState.Failed -> "LỖI: ${s.message}"
    }

    private fun openUblockPage(path: String) {
        val state = BrowserRuntime.ublockState
        if (state is UblockState.Ready) {
            tabs.newTab(state.extension.metaData.baseUrl + path, desktopMode = true)
        } else {
            showToast(getString(R.string.ublock_loading))
        }
    }

    // ───────────────────────────── Delegate GeckoView ─────────────────────────────

    private val navigationDelegate = object : NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            val tab = tabs.tabOf(session) ?: return
            tab.url = url
            tab.title = null
            if (tabs.isCurrent(session)) {
                if (!urlBar.hasFocus()) urlBar.setText(displayUrl(url))
                updateBookmarkButton()
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tabs.tabOf(session)?.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tabs.tabOf(session)?.canGoForward = canGoForward
        }

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            // Lệnh nội bộ từ trang chủ, ví dụ phim275://search → mở hộp tìm kiếm của app.
            if (uri.startsWith("phim275:")) {
                handler.post { showTextEntryDialog(forPage = false) }
                return GeckoResult.deny()
            }
            if (SitePolicy.isExternalAppScheme(uri)) {
                diagnostics.blockedAppRedirects++
                showToast(getString(R.string.scheme_blocked, SitePolicy.schemeOf(uri)))
                return GeckoResult.deny()
            }
            if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                handleNewWindow(session, uri, request.hasUserGesture)
                return GeckoResult.deny()
            }
            return GeckoResult.allow()
        }

        override fun onSubframeLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? =
            if (SitePolicy.isExternalAppScheme(request.uri)) GeckoResult.deny() else GeckoResult.allow()

        /** Lưới an toàn: không bao giờ để trang tự tạo tab mới. */
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            handleNewWindow(session, uri, hasUserGesture = true)
            return null
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? =
            GeckoResult.fromValue(ErrorPages.dataUri(uri, error))
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (!tabs.isCurrent(session)) return
            pageStartMs = SystemClock.uptimeMillis()
            progress.progress = 0
            progress.visibility = View.VISIBLE
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (tabs.isCurrent(session)) this@MainActivity.progress.progress = progress
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (!tabs.isCurrent(session)) return
            progress.visibility = View.GONE
            if (pageStartMs > 0) diagnostics.lastPageLoadMs = SystemClock.uptimeMillis() - pageStartMs
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            val tab = tabs.tabOf(session) ?: return
            tab.title = title
            val url = tab.url
            if (LinkStore.isRecordable(url)) history.add(url!!, title)
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            this@MainActivity.fullScreen = fullScreen
            if (fullScreen) enterPageMode()
        }

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) {
            handler.removeCallbacks(centerLongPressFallback)
            centerLongPressFired = true
            showContextMenu(element)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) = startDownload(response)

        override fun onCrash(session: GeckoSession) = recoverSession(session)

        override fun onKill(session: GeckoSession) = recoverSession(session)
    }

    private fun recoverSession(session: GeckoSession) {
        if (tabs.isCurrent(session)) showToast(getString(R.string.session_crashed))
        handler.post { tabs.recover(session) }
    }

    private val scrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
            if (tabs.isCurrent(session)) pageScrollY = scrollY
        }
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? = when (perm.permission) {
            // Cho phép video tự phát (trang phim); từ chối thông báo đẩy, vị trí, v.v.
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            -> GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)
            else -> GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback,
        ) = callback.reject()

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) = callback.reject()
    }

    private val promptDelegate = object : PromptDelegate {
        /** alert() — trang quảng cáo/lừa đảo hay spam; tắt im lặng. */
        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse>? =
            GeckoResult.fromValue(prompt.dismiss())

        /** Popup bị bộ chặn của Gecko giữ lại (window.open không do người dùng bấm). */
        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse>? {
            prompt.targetUri?.let { rememberBlockedPopup(it) }
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        }

        /** "Bạn có chắc muốn rời trang?" — luôn cho rời. */
        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<PromptResponse>? = GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse>? =
            GeckoResult.fromValue(prompt.dismiss())

        /** confirm() — vài trang dùng để xác nhận độ tuổi. */
        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse>? {
            val result = GeckoResult<PromptResponse>()
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(R.string.ok) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton(R.string.cancel) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        /** Thẻ <select> (chọn tập phim, server…) — danh sách điều khiển được bằng remote. */
        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse>? {
            val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
            fun collect(choices: Array<PromptDelegate.ChoicePrompt.Choice>) {
                for (c in choices) {
                    val children = c.items
                    when {
                        children != null -> collect(children)
                        !c.separator && !c.disabled -> flat += c
                    }
                }
            }
            collect(prompt.choices)
            if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())

            val result = GeckoResult<PromptResponse>()
            val labels = flat.map { it.label }.toTypedArray()
            val builder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(prompt.title)
                .setOnCancelListener { result.complete(prompt.dismiss()) }
            if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flat.size) { flat[it].selected }
                builder.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        result.complete(prompt.confirm(flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            } else {
                builder.setSingleChoiceItems(labels, flat.indexOfFirst { it.selected }) { dialog, which ->
                    result.complete(prompt.confirm(flat[which]))
                    dialog.dismiss()
                }
            }
            builder.show()
            return result
        }
    }

    // ───────────────────────────── Chặn popup ─────────────────────────────

    private fun handleNewWindow(session: GeckoSession, uri: String, hasUserGesture: Boolean) {
        val pageUrl = tabs.tabOf(session)?.url
        when (SitePolicy.decideNewWindow(pageUrl, uri, hasUserGesture)) {
            SitePolicy.NewWindowDecision.OPEN_IN_CURRENT_TAB -> handler.post { session.loadUri(uri) }
            SitePolicy.NewWindowDecision.BLOCK -> rememberBlockedPopup(uri)
        }
    }

    private fun rememberBlockedPopup(uri: String) {
        diagnostics.blockedPopups++
        blockedPopupUrl = uri
        blockedPopupAtMs = SystemClock.uptimeMillis()
        showToast(getString(R.string.popup_blocked, SitePolicy.hostOf(uri) ?: uri.take(40)))
    }

    private fun recentBlockedPopup(): String? =
        blockedPopupUrl?.takeIf { SystemClock.uptimeMillis() - blockedPopupAtMs < POPUP_RESCUE_WINDOW_MS }

    // ───────────────────────────── Thanh công cụ ─────────────────────────────

    private fun setupToolbar() {
        findViewById<View>(R.id.btn_back).setOnClickListener { currentSession?.goBack() }
        findViewById<View>(R.id.btn_forward).setOnClickListener { currentSession?.goForward() }
        findViewById<View>(R.id.btn_reload).setOnClickListener { currentSession?.reload() }
        findViewById<View>(R.id.btn_home).setOnClickListener {
            currentSession?.loadUri(homeUrl())
            enterPageMode()
        }
        findViewById<View>(R.id.btn_voice).setOnClickListener { startVoiceSearch() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnTabs.setOnClickListener { showTabsMenu() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMainMenu() }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                navigate(urlBar.text.toString())
                enterPageMode()
            }
            isGo
        }
    }

    private fun isToolbarMode() = toolbar.visibility == View.VISIBLE

    private fun enterToolbarMode() {
        topPushAccumPx = 0f
        toolbar.visibility = View.VISIBLE
        urlBar.setText(displayUrl(currentUrl))
        updateBookmarkButton()
        urlBar.requestFocus()
    }

    private fun enterPageMode() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlBar.windowToken, 0)
        toolbar.visibility = View.GONE
        geckoView.requestFocus()
        if (settings.navMode == NavMode.CURSOR) cursor.poke()
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói từ khóa hoặc tên trang web")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
            enterPageMode()
        } catch (_: Exception) {
            showToast("TV này không hỗ trợ tìm kiếm bằng giọng nói")
        }
    }

    // ───────────────────────────── Menu ─────────────────────────────

    private fun showMainMenu() {
        val url = currentUrl
        val isWebPage = LinkStore.isRecordable(url)
        // Hỏi uBO trạng thái chặn của trang trước khi dựng menu.
        if (isWebPage && UblockBridge.isConnected) {
            UblockBridge.getSiteEnabled(url!!) { enabled -> buildMainMenu(url, enabled) }
        } else {
            buildMainMenu(url, null)
        }
    }

    private fun buildMainMenu(url: String?, adBlockEnabled: Boolean?) {
        val isWebPage = LinkStore.isRecordable(url)
        val items = mutableListOf<MenuItem>()
        recentBlockedPopup()?.let { popup ->
            items += MenuItem("↗ Mở popup vừa bị chặn: ${SitePolicy.hostOf(popup) ?: popup.take(40)}") {
                blockedPopupUrl = null
                tabs.newTab(popup, settings.desktopModeDefault)
            }
        }
        items += MenuItem("🔍 Tìm kiếm / nhập địa chỉ") { showTextEntryDialog(forPage = false) }
        items += MenuItem("⌨ Nhập chữ vào ô đang chọn trên trang (khi bàn phím không hiện)") { showTextEntryDialog(forPage = true) }
        items += MenuItem("＋ Tab mới") { tabs.newTab(homeUrl(), settings.desktopModeDefault) }
        items += MenuItem("▤ Tab đang mở (${tabs.tabs.size})") { showTabsMenu() }
        items += MenuItem("★ Bookmark") { showBookmarksMenu() }
        items += MenuItem("🕘 Lịch sử") { showHistoryMenu() }
        if (isWebPage) {
            when (adBlockEnabled) {
                true -> items += MenuItem("🛡 Chặn quảng cáo trên trang này: BẬT  (bấm để tắt)") { setSiteAdBlock(url!!, false) }
                false -> items += MenuItem("🛡 Chặn quảng cáo trên trang này: TẮT  (bấm để bật)") { setSiteAdBlock(url!!, true) }
                null -> Unit
            }
            items += MenuItem("✂ Ẩn một phần tử trên trang (chọn bằng con trỏ)") { pickElement(zap = false) }
            items += MenuItem("🧽 Xóa tạm phần tử che màn hình") { pickElement(zap = true) }
            items += MenuItem("🐞 Báo trang lỗi / còn quảng cáo") { reportPage(url!!) }
        }
        val tab = currentTab
        if (tab != null) {
            items += MenuItem(if (tab.desktopMode) "🖥 Đang xem bản Desktop → chuyển Mobile" else "📱 Đang xem bản Mobile → chuyển Desktop") {
                tabs.setDesktopMode(tab, !tab.desktopMode)
            }
        }
        items += MenuItem("⚙ Cài đặt") { showSettingsMenu() }
        items += MenuItem("📊 Chẩn đoán (bật/tắt)") { toggleDiagnostics() }
        showMenu("Phim275", items)
    }

    private fun showContextMenu(element: ContextElement) {
        val items = mutableListOf<MenuItem>()
        val link = element.linkUri
        if (link != null) {
            items += MenuItem("Mở liên kết") { currentSession?.loadUri(link) }
            items += MenuItem("Mở liên kết trong tab mới") { tabs.newTab(link, settings.desktopModeDefault) }
            items += MenuItem("Thêm liên kết vào bookmark") {
                bookmarks.add(link, element.linkText ?: element.title)
                showToast("Đã thêm vào bookmark")
            }
        }
        val src = element.srcUri
        if (src != null && element.type != ContextElement.TYPE_NONE) {
            val what = when (element.type) {
                ContextElement.TYPE_VIDEO -> "video"
                ContextElement.TYPE_AUDIO -> "âm thanh"
                else -> "ảnh"
            }
            items += MenuItem("Mở $what trong tab mới") { tabs.newTab(src, settings.desktopModeDefault) }
        }
        items += MenuItem("☰ Menu trang…") { showMainMenu() }
        if (items.size == 1) showMainMenu() else showMenu(element.linkText?.take(60) ?: link?.take(60), items)
    }

    private fun showTabsMenu() {
        val items = mutableListOf(MenuItem("＋ Tab mới") { tabs.newTab(homeUrl(), settings.desktopModeDefault) })
        tabs.tabs.forEach { tab ->
            val mark = if (tab === currentTab) "● " else if (tab.isLive) "○ " else "◌ "
            items += MenuItem(mark + tab.label().take(70)) { showTabActions(tab) }
        }
        if (tabs.tabs.size > 1) items += MenuItem("✖ Đóng các tab khác") { tabs.closeOthers() }
        showMenu("Tab đang mở · tối đa ${TabPolicy.MAX_TABS}", items)
    }

    private fun showTabActions(tab: BrowserTab) {
        val items = mutableListOf(MenuItem("Chuyển tới tab này") {
            tabs.select(tab)
            enterPageMode()
        })
        if (tabs.tabs.size > 1) items += MenuItem("Đóng tab") { tabs.close(tab) }
        showMenu(tab.label().take(60), items)
    }

    private fun toggleBookmark() {
        val url = currentUrl
        if (!LinkStore.isRecordable(url)) {
            showToast("Không thể bookmark trang này")
            return
        }
        if (bookmarks.contains(url!!)) {
            bookmarks.remove(url)
            showToast("Đã xóa khỏi bookmark")
        } else {
            bookmarks.add(url, currentTab?.title)
            showToast("Đã thêm vào bookmark — hiện trên Trang chủ")
        }
        updateBookmarkButton()
    }

    private fun showBookmarksMenu() {
        val all = bookmarks.all()
        if (all.isEmpty()) {
            showToast("Chưa có bookmark. Mở thanh địa chỉ và bấm ☆ để thêm trang.")
            return
        }
        showMenu("Bookmark (${all.size})", all.map { link ->
            MenuItem("${link.title.take(50)}  ·  ${SitePolicy.hostOf(link.url) ?: ""}") {
                showMenu(link.title.take(60), listOf(
                    MenuItem("Mở") { currentSession?.loadUri(link.url) },
                    MenuItem("Mở trong tab mới") { tabs.newTab(link.url, settings.desktopModeDefault) },
                    MenuItem("Xóa bookmark") {
                        bookmarks.remove(link.url)
                        updateBookmarkButton()
                    },
                ))
            }
        })
    }

    private fun showHistoryMenu() {
        val all = history.all()
        if (all.isEmpty()) {
            showToast("Lịch sử trống")
            return
        }
        val items = mutableListOf(MenuItem("🗑 Xóa toàn bộ lịch sử") {
            showConfirm("Xóa lịch sử?", "Toàn bộ ${all.size} trang đã xem sẽ bị xóa.", "Xóa") { history.clear() }
        })
        all.take(100).forEach { link ->
            items += MenuItem("${link.title.take(50)}  ·  ${SitePolicy.hostOf(link.url) ?: ""}") { currentSession?.loadUri(link.url) }
        }
        showMenu("Lịch sử", items)
    }

    private fun showSettingsMenu() {
        val items = listOf(
            MenuItem("Công cụ tìm kiếm: ${settings.searchEngine.label}") {
                showChoice("Công cụ tìm kiếm", SearchEngine.entries, settings.searchEngine, { it.label }) {
                    settings.searchEngine = it
                }
            },
            MenuItem("Mở tab mới ở chế độ: ${if (settings.desktopModeDefault) "Desktop" else "Mobile"}") {
                settings.desktopModeDefault = !settings.desktopModeDefault
                showToast("Tab mới sẽ mở ở chế độ ${if (settings.desktopModeDefault) "Desktop" else "Mobile"}")
            },
            MenuItem("Điều khiển: ${settings.navMode.label}") {
                showChoice("Cách điều khiển bằng remote", NavMode.entries, settings.navMode, { it.label }) {
                    settings.navMode = it
                    applyNavMode()
                }
            },
            MenuItem("DNS chặn quảng cáo (AdGuard): ${if (settings.adBlockDns) "BẬT" else "TẮT"}") {
                settings.adBlockDns = !settings.adBlockDns
                BrowserRuntime.setAdBlockDns(settings.adBlockDns)
                showToast("DNS chặn quảng cáo: ${if (settings.adBlockDns) "BẬT" else "TẮT"}")
            },
            MenuItem("Cập nhật bộ lọc quảng cáo ngay") {
                UblockBridge.updateFilters { ok ->
                    showToast(if (ok) "Đang tải bộ lọc mới trong nền…" else getString(R.string.ublock_loading))
                }
            },
            MenuItem("Tự kiểm tra bản mới mỗi ngày: ${if (settings.autoUpdateCheck) "BẬT" else "TẮT"}") {
                settings.autoUpdateCheck = !settings.autoUpdateCheck
            },
            MenuItem("Kiểm tra bản cập nhật Phim275 (đang dùng ${BuildConfig.VERSION_NAME})") { checkForUpdates(silent = false) },
            MenuItem("Xóa dữ liệu duyệt web (cookie, bộ nhớ đệm, lịch sử)") {
                showConfirm("Xóa dữ liệu duyệt web?", "Bạn sẽ bị đăng xuất khỏi các trang web.", "Xóa") { clearBrowsingData() }
            },
            MenuItem("Cài đặt nâng cao bộ chặn quảng cáo (uBlock Origin)") { openUblockPage("dashboard.html#3p-filters.html") },
            MenuItem("Giới thiệu & giấy phép mã nguồn mở") { tabs.newTab(ABOUT_URL, desktopMode = true) },
        )
        showMenu("Cài đặt", items)
    }

    private fun setSiteAdBlock(url: String, enabled: Boolean) {
        UblockBridge.setSiteEnabled(url, enabled) { result ->
            if (result == null) {
                showToast(getString(R.string.ublock_loading))
            } else {
                showToast(if (result) "Đã BẬT chặn quảng cáo trên ${SitePolicy.hostOf(url)}" else "Đã TẮT chặn quảng cáo trên ${SitePolicy.hostOf(url)}")
                currentSession?.reload()
            }
        }
    }

    private fun pickElement(zap: Boolean) {
        UblockBridge.pickElement(zap) { ok ->
            if (!ok) {
                showToast(getString(R.string.ublock_loading))
            } else {
                showToast(
                    if (zap) "Di chuyển con trỏ tới phần tử cần xóa rồi bấm OK" else "Di chuyển con trỏ tới phần tử cần ẩn, bấm OK, rồi bấm \"Tạo\"",
                )
                applyNavMode(forceCursor = true)
                enterPageMode()
            }
        }
    }

    private fun reportPage(url: String) {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val crash = CrashLog.summary(this) ?: ""
        val report = REPORT_URL +
            "?u=" + enc(url) +
            "&v=" + enc("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") +
            "&d=" + enc(device) +
            "&c=" + enc(crash)
        tabs.newTab(report, desktopMode = true)
    }

    private fun clearBrowsingData() {
        runtime.storageController.clearData(StorageController.ClearFlags.ALL).accept(
            { showToast("Đã xóa dữ liệu duyệt web") },
            { showToast("Xóa dữ liệu thất bại: ${it?.message}") },
        )
        history.clear()
    }

    // ───────────────────────────── Tải file ─────────────────────────────

    private fun startDownload(response: WebResponse) {
        try {
            response.body?.close()
        } catch (_: Exception) {
        }
        val uri = response.uri
        if (!uri.startsWith("http")) {
            showToast("Không hỗ trợ tải loại file này")
            return
        }
        fun header(name: String) = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val fileName = URLUtil.guessFileName(uri, header("Content-Disposition"), header("Content-Type"))
        try {
            val request = DownloadManager.Request(Uri.parse(uri))
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            getSystemService(DownloadManager::class.java).enqueue(request)
            showToast("Đang tải về: $fileName (thư mục Download)")
        } catch (e: Exception) {
            showToast("Không tải được: ${e.message}")
        }
    }

    // ───────────────────────────── Tự cập nhật ─────────────────────────────

    private fun scheduleAutoUpdateCheck() {
        if (!settings.autoUpdateCheck) return
        if (System.currentTimeMillis() - settings.lastUpdateCheckMs < UpdateManager.AUTO_CHECK_INTERVAL_MS) return
        handler.postDelayed({ checkForUpdates(silent = true) }, AUTO_UPDATE_DELAY_MS)
    }

    private fun checkForUpdates(silent: Boolean) {
        if (!silent) showToast("Đang kiểm tra bản cập nhật…")
        updater.check { result ->
            settings.lastUpdateCheckMs = System.currentTimeMillis()
            when (result) {
                is UpdateManager.CheckResult.Available -> showConfirm(
                    "Có bản mới: Phim275 ${result.info.versionName}",
                    "Bạn đang dùng ${BuildConfig.VERSION_NAME}. Tải và cài bản mới ngay? (khoảng 90MB)",
                    "Cập nhật",
                ) { startUpdate(result.apk) }
                UpdateManager.CheckResult.UpToDate -> if (!silent) showToast("Bạn đang dùng bản mới nhất (${BuildConfig.VERSION_NAME})")
                is UpdateManager.CheckResult.Failed -> if (!silent) showToast("Không kiểm tra được: ${result.message}")
            }
        }
    }

    private fun startUpdate(apk: UpdateInfo.Apk) {
        if (!ApkInstaller.ensurePermission(this)) {
            showToast("Hãy bật \"Cho phép cài ứng dụng\" cho Phim275, rồi vào Cài đặt → Kiểm tra bản cập nhật")
            return
        }
        val dialog = showProgress("Đang tải bản cập nhật", "0%")
        updater.download(apk, onProgress = { dialog.setMessage("$it%") }) { file, error ->
            dialog.dismiss()
            if (file == null) {
                showToast("Tải bản cập nhật thất bại: $error")
            } else {
                try {
                    ApkInstaller.install(this, file)
                } catch (e: Exception) {
                    showToast("Không cài được: ${e.message}")
                }
            }
        }
    }

    // ───────────────────────────── Chẩn đoán ─────────────────────────────

    private fun toggleDiagnostics() {
        if (diagPanel.visibility == View.VISIBLE) {
            diagPanel.visibility = View.GONE
        } else {
            diagPanel.visibility = View.VISIBLE
            handler.removeCallbacks(diagUpdater)
            handler.post(diagUpdater)
        }
    }

    private fun updateDiagnostics() {
        val blocked = UblockBridge.blockedCount.takeIf { it >= 0 }?.toString() ?: "—"
        val extra = listOf(
            "uBO chặn : $blocked yêu cầu",
            "Tab      : ${tabs.tabs.size} (đang trong RAM: ${tabs.tabs.count { it.isLive }})",
            "Crash    : ${CrashLog.summary(this) ?: "chưa có"}",
        )
        diagPanel.text = diagnostics.snapshot(ublockStatusText(), currentUrl, extra)
    }

    // ───────────────────────────── Phím remote ─────────────────────────────

    private fun applyNavMode(forceCursor: Boolean = false) {
        val useCursor = forceCursor || settings.navMode == NavMode.CURSOR
        cursorView.visibility = if (useCursor) View.VISIBLE else View.GONE
        if (useCursor) cursor.poke()
    }

    private fun isCursorMode() = cursorView.visibility == View.VISIBLE

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isToolbarMode()) {
            when (code) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) enterPageMode()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) showMainMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) enterPageMode()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (isCursorMode()) {
            if (cursor.handleKey(event)) return true
        } else if (code in CursorController.DIRECTION_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN) moveFocusWithTab(code)
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            -> {
                handleCenterKey(event)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleBackKey(event)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_UP) enterToolbarMode()
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (event.action == KeyEvent.ACTION_UP) enterToolbarMode()
                return true
            }
            KeyEvent.KEYCODE_VOICE_ASSIST -> {
                if (event.action == KeyEvent.ACTION_UP) startVoiceSearch()
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) scrollPage(-PAGE_SCROLL_FRACTION)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) scrollPage(PAGE_SCROLL_FRACTION)
                return true
            }
        }
        if (isCursorMode()) cursor.poke()
        return super.dispatchKeyEvent(event)
    }

    /**
     * OK: chế độ con trỏ → chạm thật tại con trỏ (nhấn = chạm, giữ = chạm lâu → menu chuột phải của trang).
     * Chế độ phím Tab → gửi Enter cho phần tử đang được chọn.
     */
    private fun handleCenterKey(event: KeyEvent) {
        if (!isCursorMode()) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                    centerLongPressFired = false
                    handler.postDelayed(centerLongPressFallback, LONG_PRESS_MENU_MS)
                }
                event.action == KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(centerLongPressFallback)
                    if (!centerLongPressFired) sendKeyToPage(KeyEvent.KEYCODE_ENTER, 0)
                }
            }
            return
        }
        when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                centerLongPressFired = false
                touchDownTime = SystemClock.uptimeMillis()
                dispatchTouch(touchDownTime, touchDownTime, MotionEvent.ACTION_DOWN, cursor.x, cursor.y)
                handler.postDelayed(centerLongPressFallback, LONG_PRESS_MENU_MS)
            }
            event.action == KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(centerLongPressFallback)
                if (touchDownTime != 0L) {
                    // Nhấn quá nhanh (remote gửi DOWN/UP liền nhau) vẫn giữ tối thiểu một chút để trang nhận là cú chạm.
                    val held = SystemClock.uptimeMillis() - touchDownTime
                    val downTime = touchDownTime
                    val x = cursor.x
                    val y = cursor.y
                    touchDownTime = 0L
                    handler.postDelayed({
                        dispatchTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
                    }, (MIN_TAP_MS - held).coerceAtLeast(0))
                }
            }
        }
        cursor.poke()
    }

    /** Back: thoát toàn màn hình → lùi trang → nhấn 2 lần để thoát. Giữ Back: mở thanh địa chỉ. */
    private fun handleBackKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    backLongPressHandled = false
                } else if (!backLongPressHandled) {
                    backLongPressHandled = true
                    enterToolbarMode()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (backLongPressHandled) return
                val now = SystemClock.uptimeMillis()
                val tab = currentTab
                when {
                    fullScreen -> currentSession?.exitFullScreen()
                    tab?.canGoBack == true -> currentSession?.goBack()
                    tab != null && tabs.tabs.size > 1 -> tabs.close(tab) // tab mở từ menu: Back ở trang đầu = đóng tab
                    now - lastBackPressMs < EXIT_CONFIRM_WINDOW_MS -> finish()
                    else -> {
                        lastBackPressMs = now
                        showToast(getString(R.string.press_back_again))
                    }
                }
            }
        }
    }

    private fun moveFocusWithTab(code: Int) {
        val backward = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT
        sendKeyToPage(KeyEvent.KEYCODE_TAB, if (backward) KeyEvent.META_SHIFT_ON else 0)
    }

    private fun sendKeyToPage(keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    // ───────────────────────────── Con trỏ ảo → trang web ─────────────────────────────

    private fun dispatchTouch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        if (x < 0 || y < 0) return
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        geckoView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    override fun onCursorMoved(x: Float, y: Float) {
        if (y > 2f) topPushAccumPx = 0f
        val now = SystemClock.uptimeMillis()
        if (now - lastHoverMs < HOVER_THROTTLE_MS) return
        lastHoverMs = now
        // Sự kiện rê chuột để menu thả xuống / tooltip của trang hoạt động.
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        geckoView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun onEdgePush(dx: Float, dy: Float) {
        // Đẩy con trỏ lên mép trên khi trang đã ở đầu → mở thanh địa chỉ.
        if (dy < 0 && dx == 0f && pageScrollY <= 0) {
            topPushAccumPx += -dy
            if (topPushAccumPx > TOP_PUSH_TO_TOOLBAR_PX * cursorView.width / 1920f) enterToolbarMode()
            return
        }
        wheelScroll(cursor.x, cursor.y, dx, dy)
    }

    private fun scrollPage(fraction: Double) {
        val x = if (isCursorMode()) cursor.x else geckoView.width / 2f
        val y = if (isCursorMode()) cursor.y else geckoView.height / 2f
        wheelScroll(x, y, 0f, (geckoView.height * fraction).toFloat())
    }

    /**
     * Cuộn bằng sự kiện con lăn chuột tại vị trí con trỏ: Gecko cuộn đúng khung nằm dưới con trỏ
     * (danh sách tập phim, iframe…). dx/dy tính bằng pixel, dương = sang phải/xuống.
     */
    private fun wheelScroll(x: Float, y: Float, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x.coerceIn(1f, geckoView.width - 2f)
            this.y = y.coerceIn(1f, geckoView.height - 2f)
            // Quy ước Android: VSCROLL dương = cuộn lên; HSCROLL dương = cuộn sang phải.
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / wheelFactorV)
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / wheelFactorH)
        }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        geckoView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private val wheelFactorV by lazy { ViewConfiguration.get(this).scaledVerticalScrollFactor }
    private val wheelFactorH by lazy { ViewConfiguration.get(this).scaledHorizontalScrollFactor }

    // ───────────────────────────── Tiện ích ─────────────────────────────

    /** Trang chủ nhận bookmark + công cụ tìm kiếm qua phần #hash (trang tĩnh trong APK). */
    private fun homeUrl(): String {
        val marks = JSONArray()
        bookmarks.all().take(HOME_BOOKMARKS).forEach { marks.put(JSONObject().put("u", it.url).put("t", it.title)) }
        val data = JSONObject()
            .put("s", settings.searchEngine.template)
            .put("n", settings.searchEngine.label)
            .put("b", marks)
        return HOME_URL + "#" + enc(data.toString())
    }

    private fun displayUrl(url: String?): String = when {
        url == null -> ""
        url.startsWith(HOME_URL) -> ""
        url.startsWith("data:") -> ""
        else -> url
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun showToast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    companion object {
        const val HOME_URL = "resource://android/assets/home/index.html"
        const val ABOUT_URL = "resource://android/assets/home/about.html"
        const val REPORT_URL = "https://phim275.vercel.app/report"
        private const val REQ_VOICE = 1001
        private const val IME_CHECK_MS = 700L
        private const val HOME_BOOKMARKS = 18
        private const val DIAG_REFRESH_MS = 2000L
        private const val MIN_TAP_MS = 40L
        private const val LONG_PRESS_MENU_MS = 900L
        private const val HOVER_THROTTLE_MS = 60L
        private const val POPUP_RESCUE_WINDOW_MS = 60_000L
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
        private const val AUTO_UPDATE_DELAY_MS = 15_000L
        private const val TOP_PUSH_TO_TOOLBAR_PX = 220f
        private const val PAGE_SCROLL_FRACTION = 0.8
    }
}
