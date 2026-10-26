package com.bjlo.browser

import android.app.DownloadManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var cursor: CursorOverlayView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var downloads: DownloadInstaller
    private var dnsFilter: DnsFilter? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var defaultUserAgent: String = ""
    private var touchDownTime = 0L

    private val isTv: Boolean by lazy {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature("android.hardware.touchscreen") ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L) downloads.onDownloadComplete(id)
        }
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> chromeClient.onHideCustomView()
                webView.canGoBack() -> webView.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this, isTv)
        webView = findViewById(R.id.webView)
        addressBar = findViewById(R.id.addressBar)
        progress = findViewById(R.id.progress)
        cursor = findViewById(R.id.cursor)
        fullscreenContainer = findViewById(R.id.fullscreen)
        downloads = DownloadInstaller(this)

        rebuildDnsFilter()
        setupWebView()
        setupAddressBar()
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu() }

        onBackPressedDispatcher.addCallback(this, backCallback)
        ContextCompat.registerReceiver(
            this, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> refreshCursor() }

        if (!openFromIntent(intent)) webView.loadUrl(START_URL)
        webView.requestFocus()
        refreshCursor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        downloads.onResume()
        refreshCursor()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // ya estaba desregistrado
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun openFromIntent(i: Intent?): Boolean {
        val data = i?.data ?: return false
        if (i.action != Intent.ACTION_VIEW) return false
        loadUrl(data.toString())
        return true
    }

    // ------------------------------------------------------------------ WebView

    private fun setupWebView() {
        val s = webView.settings
        defaultUserAgent = WebSettings.getDefaultUserAgent(this)

        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(true)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true

        // Seguridad
        s.allowFileAccess = false              // file:///android_asset y android_res siguen funcionando
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.safeBrowsingEnabled = true

        // Bloqueo de ventanas emergentes
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        applyUserAgent()
        webView.webViewClient = BjloClient()
        webView.webChromeClient = chromeClient
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            askDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun applyUserAgent() {
        webView.settings.userAgentString = if (prefs.desktopMode) DESKTOP_UA else defaultUserAgent
    }

    private inner class BjloClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (request.url.scheme?.lowercase()) {
                "http", "https" -> {
                    if (downloads.isApk(url, null, null)) {
                        askDownload(url, webView.settings.userAgentString, null, null)
                        true
                    } else false
                }
                "about", "blob" -> false
                "file" -> !url.startsWith("file:///android_asset/")
                else -> true // intent:, market:, tel:, etc. se bloquean (evita redirecciones engañosas)
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!prefs.adBlock || request.isForMainFrame) return null
            val host = request.url.host ?: return null
            val blocked = dnsFilter?.isBlocked(host) ?: false
            return if (blocked) emptyResponse() else null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            updateAddress(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            updateAddress(url)
            if (prefs.adBlock) view.evaluateJavascript(CosmeticFilter.SCRIPT, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                showError(view, request.url.toString(), error.description?.toString())
            }
        }

        @Deprecated("Solo se usa en Android 5.x")
        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && view != null && failingUrl != null) {
                showError(view, failingUrl, description)
            }
        }
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun showError(view: WebView, url: String, description: String?) {
        val safeUrl = android.text.Html.escapeHtml(url)
        val safeDesc = android.text.Html.escapeHtml(description ?: "")
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="background:#0A0A0C;color:#E8EAED;font-family:sans-serif;text-align:center;padding:12vh 24px">
            <h2>${getString(R.string.error_title)}</h2>
            <p style="color:#8A8F98;word-break:break-all">$safeUrl</p>
            <p style="color:#8A8F98">$safeDesc</p></body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // Nunca se abren pestañas/ventanas secundarias.
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean = false

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) return
            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenContainer.visibility = View.VISIBLE
            setSystemBarsHidden(true)
            refreshCursor()
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            fullscreenContainer.removeView(v)
            fullscreenContainer.visibility = View.GONE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            setSystemBarsHidden(false)
            webView.requestFocus()
            refreshCursor()
        }
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ barra de direcciones

    private fun setupAddressBar() {
        addressBar.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                if (event == null || event.action == KeyEvent.ACTION_DOWN) submitAddress()
                true
            } else false
        }
    }

    private fun submitAddress() {
        hideKeyboard()
        loadUrl(UrlRouter.resolve(addressBar.text.toString()))
        webView.requestFocus()
    }

    private fun loadUrl(url: String) {
        if (!isOnline()) Toast.makeText(this, R.string.offline, Toast.LENGTH_SHORT).show()
        webView.loadUrl(url)
    }

    private fun updateAddress(url: String?) {
        if (addressBar.hasFocus()) return
        addressBar.setText(if (url == null || url.startsWith("file:///android_asset")) "" else url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    // ------------------------------------------------------------------ descargas

    private fun askDownload(url: String, userAgent: String?, contentDisposition: String?, mime: String?) {
        val apk = downloads.isApk(url, mime, contentDisposition)
        val name = downloads.suggestName(url, contentDisposition, mime, apk)
        val ua = userAgent ?: webView.settings.userAgentString

        if (!apk) {
            if (downloads.enqueue(url, ua, mime, name, false)) {
                Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val host = Uri.parse(url).host ?: "?"
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.dlg_apk_title)
            .setMessage(getString(R.string.dlg_apk_msg, name, host))
            .setPositiveButton(R.string.dlg_download) { _, _ ->
                if (downloads.enqueue(url, ua, mime, name, true)) {
                    Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ menú y ajustes

    private fun showMenu() {
        fun yn(b: Boolean) = getString(if (b) R.string.on else R.string.off)
        val items = arrayOf(
            getString(R.string.menu_dns),
            getString(R.string.menu_adblock, yn(prefs.adBlock)),
            getString(R.string.menu_cursor, yn(prefs.cursorMode)),
            getString(R.string.menu_desktop, yn(prefs.desktopMode)),
            getString(R.string.menu_home),
            getString(R.string.menu_reload),
            getString(R.string.menu_clear),
            getString(R.string.menu_about)
        )
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDnsDialog()
                    1 -> {
                        prefs.adBlock = !prefs.adBlock
                        webView.reload()
                    }
                    2 -> {
                        prefs.cursorMode = !prefs.cursorMode
                        refreshCursor()
                    }
                    3 -> {
                        prefs.desktopMode = !prefs.desktopMode
                        applyUserAgent()
                        webView.reload()
                    }
                    4 -> webView.loadUrl(START_URL)
                    5 -> webView.reload()
                    6 -> clearBrowsingData()
                    7 -> showAbout()
                }
            }
            .setNegativeButton(R.string.dlg_close, null)
            .show()
    }

    private fun showDnsDialog() {
        val providers = DnsProvider.values()
        val labels = providers.map { it.label }.toTypedArray()
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.dns_title)
            .setSingleChoiceItems(labels, providers.indexOf(prefs.dns)) { dialog, which ->
                dialog.dismiss()
                applyDns(providers[which])
            }
            .setNegativeButton(R.string.dlg_close, null)
            .show()
    }

    private fun applyDns(provider: DnsProvider) {
        if (provider == DnsProvider.NEXTDNS && prefs.nextDnsId.isEmpty()) {
            askNextDnsId()
            return
        }
        prefs.dns = provider
        rebuildDnsFilter()
        Toast.makeText(this, getString(R.string.dns_applied, provider.label), Toast.LENGTH_SHORT).show()
    }

    private fun askNextDnsId() {
        val input = EditText(this).apply {
            hint = getString(R.string.nextdns_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.nextdns_title)
            .setMessage(R.string.nextdns_msg)
            .setView(input)
            .setPositiveButton(R.string.dlg_save) { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    prefs.nextDnsId = id
                    applyDns(DnsProvider.NEXTDNS)
                }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun rebuildDnsFilter() {
        dnsFilter = DnsFilter(prefs.dns, prefs.nextDnsId)
    }

    private fun clearBrowsingData() {
        webView.clearCache(true)
        webView.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_msg, version))
            .setPositiveButton(R.string.dlg_ok, null)
            .show()
    }

    // ------------------------------------------------------------------ cursor virtual (control remoto)

    private fun cursorActive(): Boolean =
        prefs.cursorMode && customView == null && (currentFocus == null || currentFocus === webView)

    private fun refreshCursor() {
        cursor.cursorEnabled = cursorActive()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        if (cursorActive() && handleCursorKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleCursorKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (e.action == KeyEvent.ACTION_DOWN) moveCursor(e.keyCode, e.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) sendTouch(MotionEvent.ACTION_DOWN)
                if (e.action == KeyEvent.ACTION_UP) sendTouch(MotionEvent.ACTION_UP)
                return true
            }
        }
        return false
    }

    private fun moveCursor(keyCode: Int, repeat: Int) {
        val density = resources.displayMetrics.density
        val step = 10f * density * (1f + minOf(repeat, 24) / 4f)
        val edge = 6f * density
        val scrollStep = (step * 1.5f).toInt()

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> when {
                cursor.cy > edge -> cursor.moveBy(0f, -step)
                webView.canScrollVertically(-1) -> webView.scrollBy(0, -scrollStep)
                repeat == 0 -> addressBar.requestFocus() // arriba del todo: salta a la barra
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                cursor.cy < cursor.height - edge -> cursor.moveBy(0f, step)
                else -> webView.scrollBy(0, scrollStep)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                cursor.cx > edge -> cursor.moveBy(-step, 0f)
                else -> webView.scrollBy(-scrollStep, 0)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                cursor.cx < cursor.width - edge -> cursor.moveBy(step, 0f)
                else -> webView.scrollBy(scrollStep, 0)
            }
        }
    }

    private fun sendTouch(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val ev = MotionEvent.obtain(touchDownTime, now, action, cursor.cx, cursor.cy, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    companion object {
        private const val START_URL = "file:///android_asset/start.html"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36"
    }
}
