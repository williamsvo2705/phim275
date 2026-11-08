package phim275.tv.com

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import phim275.tv.com.browser.BrowserRuntime
import phim275.tv.com.browser.BrowserRuntime.UblockState
import phim275.tv.com.browser.SitePolicy
import phim275.tv.com.browser.UrlInput
import phim275.tv.com.cursor.CursorController
import phim275.tv.com.cursor.CursorView
import phim275.tv.com.diag.Diagnostics

class MainActivity : Activity(), CursorController.Callbacks {

    private lateinit var geckoView: GeckoView
    private lateinit var cursorView: CursorView
    private lateinit var cursor: CursorController
    private lateinit var toolbar: View
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var diagPanel: TextView
    private lateinit var btnDesktop: TextView
    private lateinit var diagnostics: Diagnostics
    private lateinit var runtime: GeckoRuntime
    private var session: GeckoSession? = null

    private var currentUrl: String? = null
    private var canGoBack = false
    private var canGoForward = false
    private var pageScrollY = 0
    private var fullScreen = false
    private var desktopMode = true
    private var pageStartMs = 0L
    private var lastBackPressMs = 0L
    private var backLongPressHandled = false
    private var centerLongPressHandled = false
    private var lastHoverMs = 0L
    private var topPushAccumPx = 0f
    private var blockedPopupUrl: String? = null
    private var blockedPopupUntilMs = 0L
    private var toast: Toast? = null

    private val handler = Handler(Looper.getMainLooper())
    private val diagUpdater = object : Runnable {
        override fun run() {
            if (diagPanel.visibility == View.VISIBLE) {
                updateDiagnostics()
                handler.postDelayed(this, DIAG_REFRESH_MS)
            }
        }
    }
    private val ublockObserver: (UblockState) -> Unit = { state -> onUblockState(state) }

    // ───────────────────────────── Vòng đời ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = Diagnostics(this) // đo RAM nền trước khi Gecko khởi động
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)
        cursorView = findViewById(R.id.cursor)
        toolbar = findViewById(R.id.toolbar)
        urlBar = findViewById(R.id.url_bar)
        progress = findViewById(R.id.progress)
        diagPanel = findViewById(R.id.diag_panel)
        btnDesktop = findViewById(R.id.btn_desktop)
        cursor = CursorController(cursorView, this)

        runtime = BrowserRuntime.get(this)
        setupToolbar()
        createSession()
        BrowserRuntime.observeUblock(ublockObserver)

        session?.loadUri(intent?.dataString ?: HOME_URL)
        enterPageMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { session?.loadUri(it) }
    }

    override fun onResume() {
        super.onResume()
        session?.setActive(true)
    }

    override fun onPause() {
        session?.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        BrowserRuntime.removeObserver(ublockObserver)
        handler.removeCallbacksAndMessages(null)
        cursor.release()
        if (isFinishing) {
            geckoView.releaseSession()
            session?.close()
            session = null
        }
        super.onDestroy()
    }

    // ───────────────────────────── Phiên duyệt web ─────────────────────────────

    private fun createSession() {
        val mode = if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        val viewport = if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(mode)
                .viewportMode(viewport)
                .suspendMediaWhenInactive(true)
                .build(),
        )
        s.navigationDelegate = navigationDelegate
        s.progressDelegate = progressDelegate
        s.contentDelegate = contentDelegate
        s.scrollDelegate = scrollDelegate
        s.permissionDelegate = permissionDelegate
        s.promptDelegate = promptDelegate
        s.open(runtime)
        geckoView.setSession(s)
        session = s
        if (BrowserRuntime.ublockState is UblockState.Ready) {
            runtime.webExtensionController.setTabActive(s, true)
        }
        updateDesktopButton()
    }

    /** Process nội dung bị crash/bị hệ thống giết vì thiếu RAM: tạo phiên mới và tải lại. */
    private fun recreateSession() {
        val url = currentUrl
        geckoView.releaseSession()
        session?.close()
        createSession()
        session?.loadUri(url ?: HOME_URL)
    }

    private fun onUblockState(state: UblockState) {
        when (state) {
            is UblockState.Ready -> session?.let { runtime.webExtensionController.setTabActive(it, true) }
            is UblockState.Failed -> showToast(getString(R.string.ublock_failed, state.message))
            UblockState.Loading -> Unit
        }
    }

    private fun ublockStatusText(): String = when (val s = BrowserRuntime.ublockState) {
        UblockState.Loading -> "đang tải…"
        is UblockState.Ready -> "${s.extension.metaData.version} ✓"
        is UblockState.Failed -> "LỖI: ${s.message}"
    }

    private fun openUblockPage(path: String) {
        val state = BrowserRuntime.ublockState
        if (state is UblockState.Ready) {
            session?.loadUri(state.extension.metaData.baseUrl + path)
        } else {
            showToast(getString(R.string.ublock_loading))
        }
    }

    private val navigationDelegate = object : NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            currentUrl = url
            if (!urlBar.hasFocus()) urlBar.setText(displayUrl(url))
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@MainActivity.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            this@MainActivity.canGoForward = canGoForward
        }

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            if (SitePolicy.isExternalAppScheme(uri)) {
                diagnostics.blockedAppRedirects++
                showToast(getString(R.string.scheme_blocked, SitePolicy.schemeOf(uri)))
                return GeckoResult.deny()
            }
            if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                handleNewWindow(uri, request.hasUserGesture)
                return GeckoResult.deny()
            }
            return GeckoResult.allow()
        }

        override fun onSubframeLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? =
            if (SitePolicy.isExternalAppScheme(request.uri)) GeckoResult.deny() else GeckoResult.allow()

        /** Lưới an toàn: nếu Gecko vẫn xin mở cửa sổ mới thì không bao giờ tạo tab. */
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            handleNewWindow(uri, hasUserGesture = true)
            return null
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? = null
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            pageStartMs = SystemClock.uptimeMillis()
            progress.progress = 0
            progress.visibility = View.VISIBLE
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            this@MainActivity.progress.progress = progress
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            progress.visibility = View.GONE
            if (pageStartMs > 0) diagnostics.lastPageLoadMs = SystemClock.uptimeMillis() - pageStartMs
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            this@MainActivity.fullScreen = fullScreen
            if (fullScreen) enterPageMode()
        }

        override fun onCrash(session: GeckoSession) {
            showToast(getString(R.string.session_crashed))
            handler.post { recreateSession() }
        }

        override fun onKill(session: GeckoSession) {
            showToast(getString(R.string.session_crashed))
            handler.post { recreateSession() }
        }
    }

    private val scrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
            pageScrollY = scrollY
        }
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? = when (perm.permission) {
            // Cho phép video tự phát (trang phim), từ chối thông báo đẩy, vị trí, v.v.
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

        /** prompt() — hiếm khi cần trên TV. */
        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse>? =
            GeckoResult.fromValue(prompt.dismiss())

        /** confirm() — vài trang dùng để hỏi xác nhận độ tuổi; hiện hộp thoại cho người dùng chọn. */
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

        /** Thẻ <select> (chọn tập phim, server…) — hiện danh sách điều khiển được bằng remote. */
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
                val selected = flat.indexOfFirst { it.selected }
                builder.setSingleChoiceItems(labels, selected) { dialog, which ->
                    result.complete(prompt.confirm(flat[which]))
                    dialog.dismiss()
                }
            }
            builder.show()
            return result
        }
    }

    // ───────────────────────────── Chặn popup ─────────────────────────────

    private fun handleNewWindow(uri: String, hasUserGesture: Boolean) {
        when (SitePolicy.decideNewWindow(currentUrl, uri, hasUserGesture)) {
            SitePolicy.NewWindowDecision.OPEN_IN_CURRENT_TAB -> handler.post { session?.loadUri(uri) }
            SitePolicy.NewWindowDecision.BLOCK -> rememberBlockedPopup(uri)
        }
    }

    private fun rememberBlockedPopup(uri: String) {
        diagnostics.blockedPopups++
        blockedPopupUrl = uri
        blockedPopupUntilMs = SystemClock.uptimeMillis() + POPUP_RESCUE_WINDOW_MS
        showToast(getString(R.string.popup_blocked, SitePolicy.hostOf(uri) ?: uri.take(40)))
    }

    /** Giữ OK ngay sau khi chặn: người dùng chủ động muốn mở popup đó (ví dụ link thật). */
    private fun tryOpenBlockedPopup(): Boolean {
        val url = blockedPopupUrl ?: return false
        if (SystemClock.uptimeMillis() > blockedPopupUntilMs) return false
        blockedPopupUrl = null
        session?.loadUri(url)
        return true
    }

    // ───────────────────────────── Thanh công cụ ─────────────────────────────

    private fun setupToolbar() {
        findViewById<View>(R.id.btn_back).setOnClickListener { session?.goBack() }
        findViewById<View>(R.id.btn_forward).setOnClickListener { session?.goForward() }
        findViewById<View>(R.id.btn_reload).setOnClickListener { session?.reload() }
        findViewById<View>(R.id.btn_home).setOnClickListener {
            session?.loadUri(HOME_URL)
            enterPageMode()
        }
        btnDesktop.setOnClickListener { toggleDesktopMode() }
        findViewById<View>(R.id.btn_filters).setOnClickListener {
            openUblockPage("dashboard.html#3p-filters.html")
            enterPageMode()
        }
        findViewById<View>(R.id.btn_diag).setOnClickListener { toggleDiagnostics() }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                UrlInput.toLoadableUrl(urlBar.text.toString())?.let { session?.loadUri(it) }
                enterPageMode()
            }
            isGo
        }
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        session?.settings?.apply {
            setUserAgentMode(if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            setViewportMode(if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        }
        updateDesktopButton()
        session?.reload()
    }

    /** Nút hiển thị chế độ sẽ chuyển sang khi bấm. */
    private fun updateDesktopButton() {
        btnDesktop.setText(if (desktopMode) R.string.btn_mobile else R.string.btn_desktop)
    }

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
        diagPanel.text = diagnostics.snapshot(ublockStatusText(), currentUrl)
    }

    private fun isToolbarMode() = toolbar.visibility == View.VISIBLE

    private fun enterToolbarMode() {
        topPushAccumPx = 0f
        toolbar.visibility = View.VISIBLE
        urlBar.setText(displayUrl(currentUrl))
        urlBar.requestFocus()
    }

    private fun enterPageMode() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlBar.windowToken, 0)
        toolbar.visibility = View.GONE
        geckoView.requestFocus()
        cursor.poke()
    }

    // ───────────────────────────── Phím remote ─────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isToolbarMode()) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) enterPageMode()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) enterPageMode()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (cursor.handleKey(event)) return true
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
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) scrollPage(-PAGE_SCROLL_FRACTION)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) scrollPage(PAGE_SCROLL_FRACTION)
                return true
            }
        }
        cursor.poke()
        return super.dispatchKeyEvent(event)
    }

    private fun handleCenterKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centerLongPressHandled = false
                } else if (!centerLongPressHandled) {
                    centerLongPressHandled = true
                    tryOpenBlockedPopup()
                }
            }
            KeyEvent.ACTION_UP -> if (!centerLongPressHandled) tap(cursor.x, cursor.y)
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
                when {
                    fullScreen -> session?.exitFullScreen()
                    canGoBack -> session?.goBack()
                    now - lastBackPressMs < EXIT_CONFIRM_WINDOW_MS -> finish()
                    else -> {
                        lastBackPressMs = now
                        showToast(getString(R.string.press_back_again))
                    }
                }
            }
        }
    }

    // ───────────────────────────── Con trỏ ảo → trang web ─────────────────────────────

    /** Giả lập một cú chạm tại vị trí con trỏ. */
    private fun tap(x: Float, y: Float) {
        if (x < 0 || y < 0) return
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        handler.postDelayed({
            dispatchTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        }, TAP_DURATION_MS)
    }

    private fun dispatchTouch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
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
        // Gửi sự kiện rê chuột để menu thả xuống / tooltip của trang hoạt động.
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        geckoView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun onEdgePush(dx: Float, dy: Float) {
        // Đẩy con trỏ lên mép trên khi trang đã ở đầu → mở thanh địa chỉ.
        if (dy < 0 && dx == 0f && pageScrollY <= 0) {
            topPushAccumPx += -dy
            if (topPushAccumPx > TOP_PUSH_TO_TOOLBAR_PX * cursorScale()) enterToolbarMode()
            return
        }
        wheelScroll(cursor.x, cursor.y, dx, dy)
    }

    private fun scrollPage(fraction: Double) {
        wheelScroll(cursor.x, cursor.y, 0f, (geckoView.height * fraction).toFloat())
    }

    /**
     * Cuộn bằng sự kiện con lăn chuột tại vị trí con trỏ: Gecko sẽ cuộn đúng khung
     * nằm dưới con trỏ (danh sách tập phim, iframe, khung bình luận…), không chỉ trang gốc.
     * dx/dy tính bằng pixel, dương = cuộn sang phải/xuống.
     */
    private fun wheelScroll(x: Float, y: Float, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val px = x.coerceIn(1f, geckoView.width - 2f)
        val py = y.coerceIn(1f, geckoView.height - 2f)
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = px
            this.y = py
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

    private fun cursorScale() = cursorView.width / 1920f

    // ───────────────────────────── Tiện ích ─────────────────────────────

    private fun displayUrl(url: String?): String = if (url == null || url == HOME_URL) "" else url

    private fun showToast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    companion object {
        const val HOME_URL = "resource://android/assets/home/index.html"
        private const val DIAG_REFRESH_MS = 2000L
        private const val TAP_DURATION_MS = 40L
        private const val HOVER_THROTTLE_MS = 60L
        private const val POPUP_RESCUE_WINDOW_MS = 4000L
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
        private const val TOP_PUSH_TO_TOOLBAR_PX = 220f
        private const val PAGE_SCROLL_FRACTION = 0.8
    }
}
