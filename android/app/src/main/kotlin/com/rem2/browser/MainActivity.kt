@file:Suppress("DEPRECATION")
package com.rem2.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.rem2.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AccountEntry(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val password: String,
    val username: String = ""
)

@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS        = "rem2_prefs"
        private const val KEY_ACCOUNTS = "accounts_v3"
        private const val SERVER_URL   = "https://zkdjjc--hemv5x7n7p.replit.app"
        private const val MAIL_PASS    = "Mailtm2025Tool"
        private const val COCCOC_UA    =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"

        private val COOKIE_URLS = listOf(
            "https://replit.com",
            "https://replit.com/",
            "https://replit.com/~",
            "https://replit.com/repls"
        )

        private const val KEY_TAB_CURRENT  = "tab_current"
        private const val KEY_TAB1_URL     = "tab1_url"
        private const val KEY_TAB2_URL     = "tab2_url"
        private const val KEY_TAB2_INIT    = "tab2_initialized"
        private const val KEY_TAB1_CK_JSON = "tab1_cookies_j"
        private const val KEY_TAB2_CK_JSON = "tab2_cookies_j"
    }

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val accounts    = mutableListOf<AccountEntry>()
    private var panelOpen   = true
    private var showingVerify = false
    private var autoEmail   = ""
    private var autoUsername = ""
    private var flowRunning = false
    private var lastTab1TouchMs = 0L
    private var lastTab2TouchMs = 0L

    // ── Tab management ────────────────────────────────────────────────────────
    private var currentTab      = 1
    private var tab2Initialized = false
    private val tab1Cookies     = mutableMapOf<String, String>()
    private val tab2Cookies     = mutableMapOf<String, String>()
    // switchSeq: guard chống stale callbacks khi switch tab nhanh
    private var switchSeq       = 0

    // File chooser support
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != Activity.RESULT_OK -> null
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    // ── ClickBridge: JS → native MotionEvent, không cần trợ năng ─────────────
    inner class ClickBridge {
        /** JS gọi: window.ClickBridge.tapAt(cssX, cssY, devicePixelRatio) */
        @JavascriptInterface
        fun tapAt(cssX: Float, cssY: Float, dpr: Float) {
            val wv = activeWebView()
            runOnUiThread {
                val scale = wv.scale.coerceAtLeast(0.01f)
                simulateTap(wv, cssX * scale, cssY * scale)
            }
        }

        /** Tap tại % chiều rộng/cao WebView (0.0–1.0) */
        @JavascriptInterface
        fun tapAtPercent(xPct: Float, yPct: Float) {
            val wv = activeWebView()
            runOnUiThread {
                wv.post {
                    simulateTap(
                        wv,
                        wv.width  * xPct.coerceIn(0f, 1f),
                        wv.height * yPct.coerceIn(0f, 1f)
                    )
                }
            }
        }
    }

    private fun activeWebView() = if (currentTab == 1) binding.webView else binding.webView2

    /** Real touch event — không cần accessibility. */
    private fun simulateTap(wv: WebView, x: Float, y: Float) {
        val t = System.currentTimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        wv.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(t, t + 80L, MotionEvent.ACTION_UP, x, y, 0)
        wv.postDelayed({ wv.dispatchTouchEvent(up); up.recycle() }, 80)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CookieManager.getInstance().setAcceptCookie(true)
        binding.swipeRefresh2.visibility = View.INVISIBLE
        binding.webView2.onPause()      // Tab 2 bắt đầu ẩn — tiết kiệm pin ngay từ đầu
        loadAccounts()
        setupHeader()
        setupWebView(binding.webView,  isTab1 = true)
        setupWebView(binding.webView2, isTab1 = false)
        setupSwipeAndGestures()
        setupVerifyWebView()
        log("San sang. Nhan 'Tao tai khoan Replit tu dong' de bat dau.")
        restoreSessionState()
    }

    override fun onPause() {
        super.onPause()
        saveSessionState()
        binding.webView.onPause()
        binding.webView2.onPause()
        binding.webView.pauseTimers()   // dừng TẤT CẢ JS timers — tiết kiệm pin
    }

    override fun onResume() {
        super.onResume()
        binding.webView.resumeTimers()
        if (currentTab == 1) binding.webView.onResume() else binding.webView2.onResume()
    }

    override fun onStop() {
        super.onStop()
        binding.webView.onPause()
        binding.webView2.onPause()
    }

    override fun onBackPressed() {
        val wv = activeWebView()
        when {
            panelOpen && showingVerify && binding.verifyWebView.canGoBack() ->
                binding.verifyWebView.goBack()
            panelOpen -> { binding.logPanel.visibility = View.GONE; panelOpen = false }
            wv.canGoBack() -> wv.goBack()
            else -> super.onBackPressed()
        }
    }

    // ─── Session persistence ──────────────────────────────────────────────────

    private fun saveSessionState() {
        if (currentTab == 1) saveCookies(tab1Cookies) else saveCookies(tab2Cookies)
        CookieManager.getInstance().flush()
        fun mapToJson(m: Map<String, String>) =
            JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }.toString()
        prefs.edit()
            .putInt    (KEY_TAB_CURRENT,  currentTab)
            .putString (KEY_TAB1_URL,     binding.webView.url?.takeIf  { it != "about:blank" } ?: "")
            .putString (KEY_TAB2_URL,     binding.webView2.url?.takeIf { it != "about:blank" } ?: "")
            .putBoolean(KEY_TAB2_INIT,    tab2Initialized)
            .putString (KEY_TAB1_CK_JSON, mapToJson(tab1Cookies))
            .putString (KEY_TAB2_CK_JSON, mapToJson(tab2Cookies))
            .commit()
    }

    private fun restoreSessionState() {
        val savedTab = prefs.getInt    (KEY_TAB_CURRENT, 1)
        val tab1Url  = prefs.getString (KEY_TAB1_URL, "") ?: ""
        val tab2Url  = prefs.getString (KEY_TAB2_URL, "") ?: ""
        val tab2Init = prefs.getBoolean(KEY_TAB2_INIT, false)
        val ck1Json  = prefs.getString (KEY_TAB1_CK_JSON, "{}") ?: "{}"
        val ck2Json  = prefs.getString (KEY_TAB2_CK_JSON, "{}") ?: "{}"
        fun loadMap(json: String, target: MutableMap<String, String>) {
            try { val o = JSONObject(json); o.keys().forEach { target[it] = o.getString(it) } }
            catch (_: Exception) {}
        }
        loadMap(ck1Json, tab1Cookies)
        loadMap(ck2Json, tab2Cookies)
        tab2Initialized = tab2Init
        if (tab1Url.isEmpty() && tab2Url.isEmpty()) return
        if (savedTab == 2 && tab2Init) {
            binding.swipeRefresh.visibility  = View.INVISIBLE
            binding.swipeRefresh2.visibility = View.VISIBLE
            currentTab = 2; binding.btnTabCount.text = "2"
            val url = tab2Url.takeIf { it.isNotEmpty() } ?: "https://replit.com/signup"
            binding.webView2.loadUrl(url); binding.etUrl.setText(url)
        } else if (tab1Url.isNotEmpty()) {
            binding.webView.loadUrl(tab1Url); binding.etUrl.setText(tab1Url)
        }
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun setupHeader() {
        binding.btnToggleLog.setOnClickListener {
            panelOpen = !panelOpen
            binding.logPanel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        }
        binding.btnRefresh.setOnClickListener {
            val wv = activeWebView()
            val refresh = if (currentTab == 1) binding.swipeRefresh else binding.swipeRefresh2
            refresh.isRefreshing = true
            val cur = wv.url
            if (cur.isNullOrBlank() || cur == "about:blank") wv.loadUrl("https://replit.com/signup")
            else wv.reload()
        }
        binding.tabLog.setOnClickListener    { switchPanelTab(false) }
        binding.tabVerify.setOnClickListener { switchPanelTab(true)  }
        binding.btnCreateAccount.setOnClickListener { startCreateAccountFlow() }
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToUrl(binding.etUrl.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
                binding.etUrl.clearFocus()
                true
            } else false
        }
        binding.etUrl.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) binding.etUrl.selectAll() }
        binding.btnTabCount.setOnClickListener { switchBrowserTab(if (currentTab == 1) 2 else 1) }
    }

    // ─── Cookie helpers ───────────────────────────────────────────────────────

    private fun saveCookies(dst: MutableMap<String, String>) {
        dst.clear()
        val cm = CookieManager.getInstance()
        COOKIE_URLS.forEach { url -> cm.getCookie(url)?.takeIf { it.isNotEmpty() }?.let { dst[url] = it } }
    }

    private fun restoreCookies(src: Map<String, String>, seq: Int, onDone: (() -> Unit)? = null) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { _ ->
            if (seq != switchSeq) return@removeAllCookies   // stale — user switched again
            src.forEach { (url, cookies) ->
                cookies.split(";").forEach { kv ->
                    val t = kv.trim(); if (t.isNotEmpty()) cm.setCookie(url, t)
                }
            }
            cm.flush()
            runOnUiThread { if (seq == switchSeq) onDone?.invoke() }
        }
    }

    // ─── Tab switching ────────────────────────────────────────────────────────

    private fun switchBrowserTab(tab: Int) {
        if (tab == currentTab) return
        runOnUiThread {
            val seq = ++switchSeq
            if (tab == 2) {
                binding.webView.stopLoading()
                saveCookies(tab1Cookies)
                binding.swipeRefresh.visibility  = View.INVISIBLE
                binding.swipeRefresh2.visibility = View.VISIBLE
                if (!tab2Initialized) {
                    tab2Initialized = true
                    CookieManager.getInstance().removeAllCookies { _ ->
                        if (seq != switchSeq) return@removeAllCookies
                        CookieManager.getInstance().flush()
                        runOnUiThread {
                            if (seq != switchSeq) return@runOnUiThread
                            binding.webView2.postDelayed({
                                binding.webView2.loadUrl("https://replit.com/signup")
                            }, 50)
                        }
                    }
                    binding.etUrl.setText("https://replit.com/signup")
                } else {
                    restoreCookies(tab2Cookies, seq) {
                        binding.webView2.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                            ?.let { binding.etUrl.setText(it); binding.webView2.reload() }
                    }
                }
                setTabActive(binding.webView, active = false)
                setTabActive(binding.webView2, active = true)
                currentTab = 2; binding.btnTabCount.text = "2"
                if (autoEmail.isNotEmpty()) {
                    val url = binding.webView2.url ?: ""
                    if (url.isSignupPage()) {
                        binding.webView2.postDelayed({ injectAutoFill(binding.webView2) }, 500)
                        binding.webView2.postDelayed({ injectAutoFill(binding.webView2) }, 1500)
                    }
                }
            } else {
                binding.webView2.stopLoading()
                saveCookies(tab2Cookies)
                binding.swipeRefresh2.visibility = View.INVISIBLE
                binding.swipeRefresh.visibility  = View.VISIBLE
                setTabActive(binding.webView2, active = false)
                setTabActive(binding.webView,  active = true)
                currentTab = 1; binding.btnTabCount.text = "1"
                // FIX: reload after restore so page picks up Tab 1's session cookies.
                // Tab 2 logout clears the shared CookieManager; restore alone isn't enough.
                restoreCookies(tab1Cookies, seq) {
                    binding.webView.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                        ?.let { binding.etUrl.setText(it); binding.webView.reload() }
                        ?: binding.etUrl.setText("")
                }
            }
        }
    }

    /** LAYER + onPause/onResume để tab ẩn không tốn CPU/GPU. */
    private fun setTabActive(wv: WebView, active: Boolean) {
        if (active) {
            wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            wv.onResume()
        } else {
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.onPause()
        }
    }

    private fun String.isSignupPage() =
        contains("signup") || contains("login") || contains("register")

    private fun navigateToUrl(input: String) {
        val s = input.trim(); if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://www.google.com/search?q=${Uri.encode(s)}"
        }
        activeWebView().loadUrl(url); binding.etUrl.setText(url)
    }

    // ─── Panel tabs (Log ↔ Xác thực) ─────────────────────────────────────────

    private fun switchPanelTab(toVerify: Boolean) {
        showingVerify = toVerify
        if (toVerify) {
            binding.logScroll.visibility     = View.GONE
            binding.verifyWebView.visibility = View.VISIBLE
            binding.tabLog.setBackgroundColor(0xFFF3F4F6.toInt()); binding.tabLog.setTextColor(0xFF6B7280.toInt())
            binding.tabVerify.setBackgroundColor(0xFFFFFFFF.toInt()); binding.tabVerify.setTextColor(0xFF1D4ED8.toInt())
        } else {
            binding.logScroll.visibility     = View.VISIBLE
            binding.verifyWebView.visibility = View.GONE
            binding.tabLog.setBackgroundColor(0xFFFFFFFF.toInt()); binding.tabLog.setTextColor(0xFF1D4ED8.toInt())
            binding.tabVerify.setBackgroundColor(0xFFF3F4F6.toInt()); binding.tabVerify.setTextColor(0xFF6B7280.toInt())
        }
    }

    // ─── Logging ──────────────────────────────────────────────────────────────

    private fun log(msg: String) = runOnUiThread {
        val cur = binding.tvLog.text.toString()
        binding.tvLog.text = if (cur.isEmpty()) msg else "$cur\n$msg"
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ─── Accounts ─────────────────────────────────────────────────────────────

    private fun loadAccounts() {
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        accounts.clear()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                accounts.add(AccountEntry(
                    id       = o.optString("id", UUID.randomUUID().toString()),
                    email    = o.getString("email"),
                    password = o.getString("password"),
                    username = o.optString("username", "")
                ))
            }
        } catch (_: Exception) {}
    }

    private fun saveAccounts() {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id); put("email", a.email)
                put("password", a.password); put("username", a.username)
            })
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    // ─── WebView setup (dùng chung cho Tab 1 & Tab 2) ────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, isTab1: Boolean) {
        val swipe = if (isTab1) binding.swipeRefresh else binding.swipeRefresh2
        swipe.isEnabled = false
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        wv.isScrollbarFadingEnabled = true
        wv.settings.apply {
            javaScriptEnabled                    = true
            domStorageEnabled                    = true
            databaseEnabled                      = true
            useWideViewPort                      = true
            loadWithOverviewMode                 = true
            setSupportZoom(true)
            builtInZoomControls                  = true
            displayZoomControls                  = false
            mixedContentMode                     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString                      = COCCOC_UA
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode                            = WebSettings.LOAD_DEFAULT
            textZoom                             = 100
            safeBrowsingEnabled                  = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture     = true  // tắt auto-play — tiết kiệm pin
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(ClickBridge(), "ClickBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                val isActive = (isTab1 && currentTab == 1) || (!isTab1 && currentTab == 2)
                if (isActive) runOnUiThread {
                    swipe.isRefreshing = false
                    if (!binding.etUrl.isFocused && url != "about:blank") binding.etUrl.setText(url)
                }
                // Cloudflare challenge → reload sau 4s
                v.evaluateJavascript("document.title") { raw ->
                    val t = raw?.trim('"') ?: ""
                    if (t.contains("Just a moment", true) ||
                        (t.contains("Cloudflare", true) && url.contains("cdn-cgi"))) {
                        if (isActive) log("⚡ Cloudflare — tu tai lai sau 4s...")
                        v.postDelayed({ v.reload() }, 4000)
                    }
                }
                // Auto-fill signup
                if (url.isSignupPage() && autoEmail.isNotEmpty()) {
                    injectAutoFill(v)
                    v.postDelayed({ injectAutoFill(v) }, 1200)
                    v.postDelayed({ injectAutoFill(v) }, 3000)
                }
                // Detect dashboard sau xác thực
                if (flowRunning && url.contains("replit.com") && !url.isSignupPage() &&
                    !url.contains("verify") && !url.contains("confirm") && url != "about:blank") {
                    val label = if (isTab1) "" else " [Tab 2]"
                    log("✓$label Xac thuc thanh cong! Da vao dashboard.")
                    CookieManager.getInstance().flush()
                    injectAutoContinue(v)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.contains("verify") || url.contains("confirm-email") || url.contains("oobCode")) {
                    openVerifyTab(url); return true
                }
                return false
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                val isActive = (isTab1 && currentTab == 1) || (!isTab1 && currentTab == 2)
                if (isActive) runOnUiThread {
                    binding.progressBar.visibility = if (p < 100) View.VISIBLE else View.GONE
                    binding.progressBar.progress   = p
                }
            }

            // Chỉ Tab 1 cần file chooser (Replit Agent upload)
            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                if (!isTab1) { callback?.onReceiveValue(null); return false }
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val mimes = params?.acceptTypes
                        ?.flatMap { it.split(",") }?.map { it.trim() }
                        ?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: listOf("*/*")
                    fileChooserLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = if (mimes.size == 1) mimes[0] else "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                        if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    })
                    true
                } catch (e: Exception) {
                    filePathCallback = null; callback?.onReceiveValue(null)
                    log("Loi file chooser: ${e.message}"); false
                }
            }
        }

        wv.loadUrl("about:blank")

        // Tap trên signup → re-inject fill (debounced 1s)
        val lastTouch = longArrayOf(0L)
        wv.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && autoEmail.isNotEmpty()) {
                val url = wv.url ?: ""
                if (url.isSignupPage()) {
                    val now = System.currentTimeMillis()
                    if (now - lastTouch[0] > 1000L) {
                        lastTouch[0] = now
                        wv.postDelayed({ injectAutoFill(wv) }, 150)
                    }
                }
            }
            false
        }
    }

    // ─── Swipe to refresh ────────────────────────────────────────────────────

    private fun setupSwipeAndGestures() {
        val blue = intArrayOf(0xFF1D4ED8.toInt(), 0xFF60A5FA.toInt())
        binding.swipeRefresh.setColorSchemeColors(*blue)
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.swipeRefresh.isEnabled = false
        binding.swipeRefresh2.setColorSchemeColors(*blue)
        binding.swipeRefresh2.setOnRefreshListener { binding.webView2.reload() }
        binding.swipeRefresh2.isEnabled = false
    }

    // ─── Account flow ─────────────────────────────────────────────────────────

    private fun startCreateAccountFlow() {
        if (flowRunning) { Toast.makeText(this, "Dang xu ly...", Toast.LENGTH_SHORT).show(); return }
        flowRunning = true
        binding.btnCreateAccount.isEnabled = false
        binding.btnCreateAccount.text = "⏳ Dang tao tai khoan..."
        binding.tvLog.text = ""
        switchPanelTab(false)
        clearWebSession()
        binding.webView.postDelayed({ binding.webView.loadUrl("https://replit.com/signup") }, 300)
        lifecycleScope.launch {
            ensureAccount()
            flowRunning = false
            withContext(Dispatchers.Main) {
                binding.btnCreateAccount.isEnabled = true
                binding.btnCreateAccount.text = "🔄 Tao tai khoan moi"
            }
        }
    }

    private fun clearWebSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        tab1Cookies.clear(); tab2Cookies.clear()
        listOf(binding.webView).also { if (tab2Initialized) it + binding.webView2 }.forEach { wv ->
            wv.clearCache(true); wv.clearHistory(); wv.clearFormData()
            wv.evaluateJavascript("(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}})();", null)
            wv.loadUrl("about:blank")
        }
        if (tab2Initialized) {
            binding.webView2.clearCache(true); binding.webView2.clearHistory()
            binding.webView2.clearFormData()
            binding.webView2.evaluateJavascript("(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}})();", null)
            binding.webView2.loadUrl("about:blank")
            if (currentTab != 2) binding.webView2.onPause()
            tab2Initialized = false
        }
        WebStorage.getInstance().deleteAllData()
        autoEmail = ""; autoUsername = ""
        log("Da xoa session cu — chuan bi tao tai khoan moi...")
    }

    // ─── Cloud polling (server làm nặng, app chỉ poll + hiển thị) ────────────

    private suspend fun ensureAccount() = withContext(Dispatchers.IO) {
        log("Ket noi server...")
        val jobId = try {
            val body = "".toRequestBody(null)
            val res  = http.newCall(Request.Builder().url("$SERVER_URL/api/rem2/create").post(body).build()).execute()
            JSONObject(res.body?.string() ?: "{}").getString("jobId")
        } catch (e: Exception) { log("Loi ket noi: ${e.message}"); return@withContext }
        log("Server dang xu ly (5-10 phut)... ID: ${jobId.take(8)}")

        var lastLogIdx = 0
        repeat(120) { attempt ->
            delay(5000)
            try {
                val json   = JSONObject(http.newCall(Request.Builder().url("$SERVER_URL/api/rem2/status/$jobId").build()).execute().body?.string() ?: "{}")
                val status = json.optString("status", "pending")

                // Stream logs từ server
                val logs = json.optJSONArray("log")
                if (logs != null) {
                    for (i in lastLogIdx until logs.length()) log("[Cloud] ${logs.getString(i)}")
                    lastLogIdx = logs.length()
                }

                // Nhận email/username sớm (server tạo trước khi xác thực xong)
                if (autoEmail.isEmpty()) {
                    val e = json.optString("email", "")
                    val u = json.optString("username", "")
                    if (e.isNotEmpty()) {
                        autoEmail = e; autoUsername = u
                        withContext(Dispatchers.Main) {
                            fillIfOnSignup(binding.webView)
                            if (tab2Initialized) fillIfOnSignup(binding.webView2)
                        }
                    }
                }

                when (status) {
                    "done" -> {
                        autoEmail    = json.optString("email", "")
                        autoUsername = json.optString("username", "")
                        val link     = json.optString("verifyLink", "")
                        accounts.add(AccountEntry(email = autoEmail, password = MAIL_PASS, username = autoUsername))
                        saveAccounts()
                        log("✅ Xong! Email: $autoEmail | User: $autoUsername")
                        withContext(Dispatchers.Main) {
                            ensureOnSignup(binding.webView)
                            if (tab2Initialized) ensureOnSignup(binding.webView2)
                            if (link.isNotEmpty()) openVerifyTab(link)
                        }
                        return@withContext
                    }
                    "error" -> { log("❌ Server loi tao tai khoan"); return@withContext }
                    else -> if (attempt > 0 && attempt % 6 == 0) log("Dang cho... ${attempt * 5}s")
                }
            } catch (e: Exception) { if (attempt % 12 == 0) log("Poll loi: ${e.message}") }
        }
        log("Het thoi gian cho server")
    }

    private fun fillIfOnSignup(wv: WebView) {
        if ((wv.url ?: "").isSignupPage()) injectAutoFill(wv)
    }

    private fun ensureOnSignup(wv: WebView) {
        val url = wv.url ?: ""
        if (url.isSignupPage()) injectAutoFill(wv) else wv.loadUrl("https://replit.com/signup")
    }

    // ─── Verify tab ───────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        binding.verifyWebView.settings.javaScriptEnabled = false  // chỉ hiện HTML tĩnh
        binding.verifyWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
            override fun shouldOverrideUrlLoading(v: WebView, u: String) = true
        }
        renderVerifyPanel()
    }

    private fun renderVerifyPanel() = runOnUiThread {
        val html = buildString {
            append("<html><body style='font-family:sans-serif;margin:10px;color:#111827'>")
            append("<h3 style='color:#1D4ED8;margin:0 0 6px 0;font-size:15px'>📬 Hop thu Mail.tm</h3>")
            if (autoEmail.isNotEmpty())
                append("<p style='color:#6B7280;font-size:11px;margin:0 0 10px 0'>$autoEmail</p>")
            append("<p style='color:#9CA3AF;font-size:12px'>Server tu dong xu ly email xac thuc.</p>")
            append("</body></html>")
        }
        binding.verifyWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun openVerifyTab(url: String) = runOnUiThread {
        log("Tim thay link xac thuc — dang mo...")
        activeWebView().loadUrl(url)
        renderVerifyPanel()
        panelOpen = true; binding.logPanel.visibility = View.VISIBLE
        switchPanelTab(true)
    }

    // ─── Auto-Fill (JS, không cần accessibility) ──────────────────────────────
    // Tự dừng sau khi fill xong để không tốn pin.
    private fun injectAutoFill(wv: WebView) {
        if (autoEmail.isEmpty()) return
        val e = autoEmail.replace("'", "\\'")
        val p = MAIL_PASS.replace("'", "\\'")
        val u = autoUsername.replace("'", "\\'")
        wv.evaluateJavascript("""
            (function(){
              var sid = Date.now() + '-' + Math.random();
              window.__rem2FillSid = sid;
              if (window.__rem2FillMo) { try{window.__rem2FillMo.disconnect();}catch(x){} }
              if (window.__rem2FillIv) clearInterval(window.__rem2FillIv);
              var EMAIL='$e', PASS='$p', USER='$u', lastFill=0, emptyTicks=0;

              function fillReact(el, val) {
                if (!el || el.value === val) return;
                var s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
                if (s&&s.set) s.set.call(el,val); else el.value=val;
                ['input','change'].forEach(function(t){el.dispatchEvent(new Event(t,{bubbles:true}));});
                el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'a'}));
              }
              function findEmail(){return document.querySelector('input[type=email]')||document.querySelector('input[name*=email i]')||document.querySelector('input[placeholder*=email i]');}
              function findPass() {return document.querySelector('input[type=password]')||document.querySelector('input[name*=pass i]');}
              function findUser() {return document.querySelector('input[autocomplete=username]')||document.querySelector('input[name*=user i]')||document.querySelector('input[placeholder*=username i]');}

              function syntheticClick(el) {
                if (!el) return;
                try{el.focus();}catch(e){}
                ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function(t){
                  try{el.dispatchEvent(new (t.startsWith('pointer')?PointerEvent:MouseEvent)(t,{bubbles:true,cancelable:true,isPrimary:true,button:0}));}catch(e){}
                });
                try{el.click();}catch(e){}
                try{
                  var r=el.getBoundingClientRect();
                  if(r.width>0&&r.height>0&&window.ClickBridge)
                    window.ClickBridge.tapAt(r.left+r.width/2, r.top+r.height/2, window.devicePixelRatio||1);
                }catch(e){}
              }

              function tick() {
                if (window.__rem2FillSid!==sid) return;
                var now=Date.now(); if(now-lastFill<300) return; lastFill=now;
                try {
                  var eEl=findEmail(), pEl=findPass(), uEl=findUser();
                  if (!eEl && !pEl) { emptyTicks++; if(emptyTicks>8){clearInterval(window.__rem2FillIv);window.__rem2FillMo&&window.__rem2FillMo.disconnect();} return; }
                  emptyTicks=0;
                  if(eEl) fillReact(eEl,EMAIL);
                  if(pEl) fillReact(pEl,PASS);
                  if(uEl) fillReact(uEl,USER);
                  if(eEl&&!pEl) setTimeout(function(){
                    var kws=['continue','next','sign up','create account','get started','submit'];
                    var els=document.querySelectorAll('button:not([disabled]),input[type=submit]:not([disabled])');
                    for(var i=0;i<els.length;i++){var txt=(els[i].innerText||els[i].value||'').trim().toLowerCase();for(var k=0;k<kws.length;k++)if(txt.indexOf(kws[k])!==-1){syntheticClick(els[i]);return;}}
                  },400);
                }catch(err){}
              }

              var mo=new MutationObserver(function(ms){
                for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type==='childList'||['style','class','hidden','disabled'].indexOf(m.attributeName)!==-1){tick();break;}}
              });
              mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','hidden','disabled','type']});
              window.__rem2FillMo=mo;
              window.__rem2FillIv=setInterval(function(){if(window.__rem2FillSid!==sid){clearInterval(window.__rem2FillIv);return;}tick();},600);
              tick();
            })();
        """.trimIndent(), null)
    }

    // ─── Auto-Continue onboarding (không cần accessibility) ──────────────────
    private fun injectAutoContinue(wv: WebView) {
        wv.evaluateJavascript("""
            (function(){
              if (window.__rem2AutoContinueActive) return;
              window.__rem2AutoContinueActive=true;
              var CONTINUE_KW=['continue','next','skip','get started',"let's go",'done','finish','i agree','agree','ok','got it','submit'];
              var STOP_HEADS =['what do you want to make','what should we build','what are we building'];
              var EXCLUDE_KW =['back','log in','login','create account','upgrade','sign in','sign up','close','cancel','upload'];

              function syntheticClick(el) {
                if (!el) return;
                try{el.focus();}catch(e){}
                ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function(t){
                  try{el.dispatchEvent(new (t.startsWith('pointer')?PointerEvent:MouseEvent)(t,{bubbles:true,cancelable:true,isPrimary:true,button:0}));}catch(e){}
                });
                try{el.click();}catch(e){}
                try{
                  var r=el.getBoundingClientRect();
                  if(r.width>0&&r.height>0&&window.ClickBridge)
                    window.ClickBridge.tapAt(r.left+r.width/2,r.top+r.height/2,window.devicePixelRatio||1);
                }catch(e){}
              }

              function isDone(){
                return !!document.querySelector('textarea[placeholder*="Make anything" i]')
                    || !!document.querySelector('[placeholder*="Try an example" i]')
                    || !!document.querySelector('textarea[placeholder*="Ask Replit" i]');
              }
              function headingText(){var t='';document.querySelectorAll('h1,h2,h3').forEach(function(h){t+=' '+(h.innerText||'').toLowerCase();});return t;}
              function findContinue(){
                var els=document.querySelectorAll('button,a[role="button"],[role="button"],input[type=submit]');
                for(var i=0;i<els.length;i++){var txt=(els[i].innerText||els[i].value||'').trim().toLowerCase();for(var k=0;k<CONTINUE_KW.length;k++)if(txt===CONTINUE_KW[k]||txt.indexOf(CONTINUE_KW[k])!==-1)return els[i];}
                return null;
              }
              function findChoices(excl){
                var out=[];
                document.querySelectorAll('button,[role="button"]').forEach(function(el){
                  if(el===excl||el.disabled)return;
                  var txt=(el.innerText||'').trim().toLowerCase();
                  if(!txt||txt.length>40)return;
                  if(EXCLUDE_KW.some(function(k){return txt.indexOf(k)!==-1;}))return;
                  out.push(el);
                });
                return out;
              }

              function tick(){
                try{
                  if(isDone())return true;
                  var h=headingText();
                  if(STOP_HEADS.some(function(s){return h.indexOf(s)!==-1;}))return true;
                  var btn=findContinue();
                  if(btn&&!btn.disabled){syntheticClick(btn);return false;}
                  var cs=findChoices(btn);
                  if(cs.length){syntheticClick(cs[0]);setTimeout(function(){var b=findContinue();if(b&&!b.disabled)syntheticClick(b);},400);}
                }catch(e){}
                return false;
              }

              var count=0, mo=new MutationObserver(function(){tick();});
              mo.observe(document.body,{childList:true,subtree:true});
              var iv=setInterval(function(){
                count++;
                if(tick()||count>60){clearInterval(iv);mo.disconnect();window.__rem2AutoContinueActive=false;}
              },1200);
              tick();
            })();
        """.trimIndent(), null)
    }
}
