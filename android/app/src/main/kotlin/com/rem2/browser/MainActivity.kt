@file:Suppress("DEPRECATION")
package com.rem2.browser

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import com.rem2.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

// ─── Data classes ──────────────────────────────────────────────────────────────

data class TabEntry(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Replit",
    var url: String = "https://replit.com",
    var webView: WebView? = null,
)

data class AccountEntry(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val password: String,
    val username: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Main Activity ─────────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS           = "rem2"
        private const val KEY_ACCOUNTS    = "accounts_json"
        private const val KEY_MAIL_EMAIL  = "mail_email"
        private const val KEY_MAIL_PASS   = "mail_pass"
        private const val KEY_MAIL_TOKEN  = "mail_token"
        private const val KEY_REPLIT_NAME = "replit_name"
        private const val MAILTM          = "https://api.mail.tm"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        data class DeviceProfile(
            val name: String, val ua: String,
            val screenW: Int, val screenH: Int,
            val deviceMemory: Int, val hardwareConcurrency: Int,
            val timezone: String
        )
        val DEVICE_PROFILES = listOf(
            DeviceProfile("Samsung S23",
                "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
                393, 851, 8, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Samsung A54",
                "Mozilla/5.0 (Linux; Android 13; SM-A546E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.118 Mobile Safari/537.36",
                360, 780, 6, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Xiaomi 13",
                "Mozilla/5.0 (Linux; Android 13; 2211133C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
                393, 851, 12, 8, "Asia/Bangkok"),
            DeviceProfile("Redmi Note 12",
                "Mozilla/5.0 (Linux; Android 13; 23021RAAEG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36",
                393, 873, 6, 8, "Asia/Jakarta"),
            DeviceProfile("OPPO Reno10",
                "Mozilla/5.0 (Linux; Android 13; CPH2531) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.80 Mobile Safari/537.36",
                412, 915, 8, 8, "Asia/Singapore"),
            DeviceProfile("Pixel 7",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
                412, 915, 8, 8, "America/New_York"),
        )

        fun randomFullName(): String {
            val first = listOf("Alex","Sam","Jordan","Taylor","Morgan","Casey","Riley","Blake","Kai","Logan")
            val last  = listOf("Smith","Lee","Chen","Park","Johnson","Brown","Davis","Wilson","Moore","Taylor")
            return "${first.random()} ${last.random()}"
        }
        fun randomUsername(): String {
            val adjs  = listOf("cool","fast","dark","blue","wild","swift","bright","smart","keen","top")
            val nouns = listOf("fox","hawk","wolf","ace","star","byte","code","dev","pro","net")
            return adjs.random() + nouns.random() + (1000..9999).random()
        }
        fun randomPassword(): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP0123456789!@#"
            return (1..14).map { chars.random() }.joinToString("")
        }
    }

    // ── Binding ──────────────────────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // ── HTTP ─────────────────────────────────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Tabs ─────────────────────────────────────────────────────────────────
    private val tabs = mutableListOf<TabEntry>()
    private var activeTabIndex = 0

    // ── Accounts ─────────────────────────────────────────────────────────────
    private val accounts = mutableListOf<AccountEntry>()
    private var focusedFieldType = "" // "email" | "password" | "username" | ""

    // ── Mail FAB ─────────────────────────────────────────────────────────────
    private var unreadCount = 0
    private var mailFab: TextView? = null

    // ── Device & UA ──────────────────────────────────────────────────────────
    private var currentDevice: DeviceProfile = DEVICE_PROFILES.random()
    private var isDesktopMode = false

    // ── Mail state ────────────────────────────────────────────────────────────
    private var mailToken    = ""
    private var mailEmail    = ""
    private var mailPassword = ""
    private var pollJob: Job? = null
    private val seenIds = mutableSetOf<String>()

    // ── Auto-fill credentials ─────────────────────────────────────────────────
    private var autoEmail    = ""
    private var autoUsername = ""
    private var autoPassword = ""
    private var autoFullName = ""

    // ── Panel section ─────────────────────────────────────────────────────────
    private var panelSection = 0 // 0=accounts, 1=verify

    // ── File upload ───────────────────────────────────────────────────────────
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val clip = result.data?.clipData
            val single = result.data?.data
            when {
                clip   != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                single != null -> arrayOf(single)
                else           -> null
            }
        } else null
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable cookies globally (needed for hCaptcha/Cloudflare)
        CookieManager.getInstance().setAcceptCookie(true)

        requestStoragePermissionIfNeeded()
        loadAccounts()
        setupPanel()
        setupTabActions()
        setupMailFab()
        setupVerifyWebView()

        addNewTab("https://replit.com", select = true)
        lifecycleScope.launch { ensureMailAccount() }
    }

    // ─── Tab management ────────────────────────────────────────────────────────

    private fun addNewTab(url: String? = null, select: Boolean = true): TabEntry {
        val targetUrl = url ?: "https://replit.com/signup"
        val entry = TabEntry(url = targetUrl)
        tabs.add(entry)
        val wv = createWebView()
        entry.webView = wv
        binding.webContainer.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.loadUrl(targetUrl)
        if (select) selectTab(tabs.size - 1) else wv.visibility = View.GONE
        renderTabBar()
        renderPanelTabList()
        return entry
    }

    private fun selectTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        tabs.forEachIndexed { i, t ->
            t.webView?.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        activeTabIndex = index
        renderTabBar()
        renderPanelTabList()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) { toast("Không thể đóng tab cuối"); return }
        tabs[index].webView?.let { binding.webContainer.removeView(it); it.destroy() }
        tabs.removeAt(index)
        selectTab(if (index >= tabs.size) tabs.size - 1 else index)
        renderTabBar()
        renderPanelTabList()
    }

    private fun renderTabBar() {
        val bar = binding.tabBar
        bar.removeAllViews()
        val dp = resources.displayMetrics.density

        tabs.forEachIndexed { i, tab ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = if (i == activeTabIndex) GradientDrawable().apply {
                    setColor(0xFF1565C0.toInt()); cornerRadius = 6 * dp } else null
                setPadding((6*dp).toInt(), 0, (2*dp).toInt(), 0)
                setOnClickListener { selectTab(i); closePanel() }
            }
            val title = TextView(this).apply {
                text = tab.title.take(14).ifEmpty { "Replit" }
                textSize = 11.5f
                setTextColor(if (i == activeTabIndex) Color.WHITE else 0xFF444444.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setSingleLine(true)
                maxWidth = (110 * dp).toInt()
            }
            val closeBtn = TextView(this).apply {
                text = " ✕"
                textSize = 10f
                setTextColor(if (i == activeTabIndex) 0xFFCCCCCC.toInt() else 0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((2*dp).toInt(), 0, (4*dp).toInt(), 0)
                setOnClickListener { closeTab(i) }
            }
            chip.addView(title)
            chip.addView(closeBtn)
            bar.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { setMargins((2*dp).toInt(), (3*dp).toInt(), (2*dp).toInt(), (3*dp).toInt()) })
        }
        binding.tabScrollView.post {
            bar.getChildAt(activeTabIndex)?.let { binding.tabScrollView.smoothScrollTo(it.left, 0) }
        }
    }

    /** Panel's tab list (inside mail panel) */
    private fun renderPanelTabList() {
        val container = binding.panelTabList
        container.removeAllViews()
        val dp = resources.displayMetrics.density

        tabs.forEachIndexed { i, tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = if (i == activeTabIndex) GradientDrawable().apply {
                    setColor(0xFFE3F2FD.toInt()); cornerRadius = 6 * dp } else null
                setPadding((10*dp).toInt(), (5*dp).toInt(), (6*dp).toInt(), (5*dp).toInt())
                setOnClickListener { selectTab(i); closePanel() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((4*dp).toInt(), (2*dp).toInt(), (4*dp).toInt(), (2*dp).toInt()) }
            }
            val indicator = TextView(this).apply {
                text = if (i == activeTabIndex) "●" else "○"
                textSize = 8f
                setTextColor(if (i == activeTabIndex) 0xFF1565C0.toInt() else 0xFF999999.toInt())
                setPadding(0, 0, (6*dp).toInt(), 0)
            }
            val title = TextView(this).apply {
                text = tab.title.take(24).ifEmpty { "Replit" }
                textSize = 12f
                setTextColor(if (i == activeTabIndex) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val closeBtn = TextView(this).apply {
                text = "✕"
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                setPadding((8*dp).toInt(), 0, (4*dp).toInt(), 0)
                setOnClickListener { closeTab(i) }
            }
            row.addView(indicator)
            row.addView(title)
            row.addView(closeBtn)
            container.addView(row)
        }
    }

    private fun setupTabActions() {
        binding.btnReload.setOnClickListener {
            tabs.getOrNull(activeTabIndex)?.webView?.reload()
            ObjectAnimator.ofFloat(binding.btnReload, "rotation", 0f, 360f).apply {
                duration = 600; start()
            }
        }
        binding.btnNewTab.setOnClickListener {
            addNewTab(); closePanel()
        }
    }

    // ─── WebView factory ───────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        applyWebViewSettings(wv)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun onFieldFocus(fieldType: String) { focusedFieldType = fieldType }
            @JavascriptInterface
            fun onPageReady(info: String) {}
        }, "REM2")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(buildAntiDetectJs(currentDevice), null)
                // Inject captcha fix early
                if (url.contains("replit.com")) {
                    view.evaluateJavascript(buildCaptchaFixJs(), null)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                val idx = tabs.indexOfFirst { it.webView == view }
                if (idx >= 0) {
                    tabs[idx].url = url
                    tabs[idx].title = view.title?.take(18)?.ifEmpty { "Replit" } ?: "Replit"
                    if (idx == activeTabIndex) { renderTabBar(); renderPanelTabList() }
                }
                view.evaluateJavascript(buildFieldFocusJs(), null)
                if (url.contains("replit.com")) {
                    view.evaluateJavascript(buildCaptchaFixJs(), null)
                    // Auto-fill ONLY on signup pages
                    val isSignup = (url.contains("/signup") || url.contains("/join"))
                        && !url.contains("/login") && !url.contains("/signin")
                    if (isSignup && autoEmail.isNotEmpty()) {
                        view.postDelayed({
                            injectSignupForm(view, autoEmail, autoUsername, autoPassword, autoFullName)
                        }, 1800)
                    }
                    // Auto-onboarding (wizard questions)
                    val isOnboarding = url.contains("onboarding") || url.contains("plans")
                        || url.contains("wizard") || url.contains("get-started")
                    if (isOnboarding) {
                        view.postDelayed({ injectOnboardingStep(view, autoFullName) }, 1500)
                    }
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                val idx = tabs.indexOfFirst { it.webView == view }
                if (idx >= 0) {
                    tabs[idx].title = title.take(18).ifEmpty { "Replit" }
                    if (idx == activeTabIndex) { renderTabBar(); renderPanelTabList() }
                }
            }
            override fun onShowFileChooser(
                webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                             Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(pick, "Chọn file")
                return try { filePickerLauncher.launch(chooser); true }
                catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null); fileUploadCallback = null; false
                }
            }
        }

        val gd = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (Math.abs(dx) < Math.abs(dy) || Math.abs(vX) < 250 || Math.abs(dx) < 60) return false
                return if (wv.canGoBack()) {
                    wv.goBack()
                    wv.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                } else false
            }
        })
        wv.setOnTouchListener { _, event -> gd.onTouchEvent(event); false }
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyWebViewSettings(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled          = true
            domStorageEnabled          = true
            databaseEnabled            = true
            allowFileAccess            = true
            allowContentAccess         = true
            setSupportZoom(true)
            builtInZoomControls        = true
            displayZoomControls        = false
            mixedContentMode           = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString            = if (isDesktopMode) DESKTOP_UA else currentDevice.ua
            cacheMode                  = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode       = true
            useWideViewPort            = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            // Important for Replit: loosen sandbox
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
        WebView.setWebContentsDebuggingEnabled(false)
    }

    // ─── Verify WebView (inside panel) ────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        val vwv = binding.verifyWebView
        applyWebViewSettings(vwv)
        CookieManager.getInstance().setAcceptThirdPartyCookies(vwv, true)
        vwv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                v.evaluateJavascript(buildAntiDetectJs(currentDevice), null)
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (url.contains("onboarding") || url.contains("plans") || url.contains("wizard")) {
                    v.postDelayed({ injectOnboardingStep(v, autoFullName) }, 1500)
                } else if (url.contains("replit.com") && !url.contains("verify") &&
                           !url.contains("confirm") && url != "about:blank") {
                    runOnUiThread {
                        binding.verifyWebView.visibility    = View.GONE
                        binding.mailListScroll.visibility   = View.VISIBLE
                        toast("✅ Xác thực thành công!")
                    }
                }
            }
        }
    }

    // ─── Panel setup ───────────────────────────────────────────────────────────

    private fun setupPanel() {
        binding.tabAccounts.setOnClickListener { switchPanelSection(0) }
        binding.tabMailList.setOnClickListener  { switchPanelSection(1) }
        binding.tabPanelTabs.setOnClickListener { switchPanelSection(2) }
        binding.btnClose.setOnClickListener     { closePanel() }

        // Search bar submit
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val q = binding.etSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    val url = if (q.startsWith("http")) q
                              else "https://www.google.com/search?q=${Uri.encode(q)}"
                    tabs.getOrNull(activeTabIndex)?.webView?.loadUrl(url)
                    closePanel()
                }
                true
            } else false
        }

        // Search icon toggle
        binding.btnToggleSearch.setOnClickListener {
            val isOpen = binding.etSearch.visibility == View.VISIBLE
            binding.etSearch.visibility = if (isOpen) View.GONE else View.VISIBLE
            binding.tvPanelTitle.visibility = if (isOpen) View.VISIBLE else View.GONE
            if (!isOpen) { binding.etSearch.requestFocus(); showKeyboard(binding.etSearch) }
            else hideKeyboard()
        }

        // Desktop mode toggle
        binding.btnDesktop.setOnClickListener {
            isDesktopMode = !isDesktopMode
            val ua = if (isDesktopMode) DESKTOP_UA else currentDevice.ua
            tabs.forEach { it.webView?.settings?.userAgentString = ua }
            tabs.getOrNull(activeTabIndex)?.webView?.reload()
            binding.btnDesktop.setTextColor(
                if (isDesktopMode) 0xFF1565C0.toInt() else 0xFF888888.toInt()
            )
            toast(if (isDesktopMode) "Chế độ máy tính bật" else "Chế độ di động")
        }

        // + button: delete old mail, create new mail
        binding.btnNewMail.setOnClickListener {
            lifecycleScope.launch {
                stopPolling()
                prefs.edit()
                    .remove(KEY_MAIL_EMAIL).remove(KEY_MAIL_PASS).remove(KEY_MAIL_TOKEN).apply()
                mailToken = ""; mailEmail = ""; mailPassword = ""
                autoEmail = ""; autoUsername = ""; autoPassword = ""; autoFullName = ""
                seenIds.clear()
                withContext(Dispatchers.Main) {
                    binding.mailList.removeAllViews()
                    binding.verifyWebView.visibility  = View.GONE
                    binding.mailListScroll.visibility = View.VISIBLE
                    binding.tvMailStatus.text = "⏳ Đang tạo email mới..."
                    toast("Đang tạo email mới...")
                }
                ensureMailAccount(force = true)
            }
        }

        binding.tvMailStatus.setOnClickListener {
            if (mailEmail.isNotEmpty()) copyToClipboard(mailEmail, "Email đã copy")
        }

        // Add tab from panel
        binding.btnPanelNewTab.setOnClickListener {
            addNewTab(); closePanel()
        }

        refreshAccountList()
    }

    private fun switchPanelSection(section: Int) {
        panelSection = section
        val sections = listOf(binding.accountsContainer, binding.verifyContainer, binding.tabsContainer)
        val tabBtns  = listOf(binding.tabAccounts, binding.tabMailList, binding.tabPanelTabs)
        sections.forEachIndexed { i, v -> v.visibility = if (i == section) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { i, tv ->
            if (i == section) {
                tv.setBackgroundColor(0xFF1565C0.toInt()); tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT); tv.setTextColor(0xFF1565C0.toInt())
            }
        }
        if (section == 1 && mailToken.isNotEmpty()) startPolling()
        if (section == 2) renderPanelTabList()
    }

    private fun openPanel() {
        binding.mailPanel.visibility = View.VISIBLE
        refreshAccountList()
        if (mailToken.isNotEmpty() && panelSection == 1) startPolling()
    }

    private fun closePanel() {
        binding.mailPanel.visibility    = View.GONE
        binding.etSearch.visibility     = View.GONE
        binding.tvPanelTitle.visibility = View.VISIBLE
        stopPolling()
        hideKeyboard()
    }

    // ─── Accounts ─────────────────────────────────────────────────────────────

    private fun loadAccounts() {
        val json = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            accounts.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                accounts.add(AccountEntry(
                    id       = o.optString("id", UUID.randomUUID().toString()),
                    email    = o.getString("email"),
                    password = o.optString("password", ""),
                    username = o.optString("username", ""),
                    createdAt= o.optLong("createdAt", System.currentTimeMillis())
                ))
            }
        } catch (_: Exception) {}
    }

    private fun saveAccounts() {
        val arr = JSONArray()
        accounts.forEach { acc ->
            arr.put(JSONObject().apply {
                put("id", acc.id); put("email", acc.email)
                put("password", acc.password); put("username", acc.username)
                put("createdAt", acc.createdAt)
            })
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun addOrUpdateAccount(acc: AccountEntry) {
        if (accounts.none { it.email == acc.email }) {
            accounts.add(0, acc); saveAccounts()
            runOnUiThread { refreshAccountList() }
        }
    }

    private fun refreshAccountList() {
        val list = binding.accountList
        list.removeAllViews()
        val dp = resources.displayMetrics.density

        if (accounts.isEmpty()) {
            binding.tvAccountsEmpty.visibility = View.VISIBLE; return
        }
        binding.tvAccountsEmpty.visibility = View.GONE

        accounts.forEach { acc ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFF0F4FF.toInt()); cornerRadius = 8 * dp
                    setStroke(1, 0xFFD0D9FF.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((8*dp).toInt(), (3*dp).toInt(), (8*dp).toInt(), (3*dp).toInt()) }
            }

            // Email row
            val emailRow = makeCredRow("📧", acc.email, true) {
                // Smart paste: if email field focused → paste email
                smartPaste(acc.email, acc.password, "email")
            }
            // Password row
            val passRow = makeCredRow("🔑", "•".repeat(minOf(acc.password.length, 10)), false) {
                smartPaste(acc.email, acc.password, "password")
            }

            // Auto-fill row
            val fillRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4*dp).toInt(), 0, 0)
            }
            val fillBtn = TextView(this).apply {
                text = "⚡ Tự điền tài khoản này"
                textSize = 11f
                setTextColor(0xFF1565C0.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFE3EFFF.toInt()); cornerRadius = 4 * dp
                }
                setPadding((8*dp).toInt(), (3*dp).toInt(), (8*dp).toInt(), (3*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    autoEmail = acc.email; autoPassword = acc.password
                    tabs.getOrNull(activeTabIndex)?.webView?.let { wv ->
                        injectLoginForm(wv, acc.email, acc.password)
                    }
                    toast("Đang điền: ${acc.email.take(20)}")
                }
            }
            val copyBtn = TextView(this).apply {
                text = "  📋"
                textSize = 14f
                setOnClickListener { copyToClipboard(acc.email + "\n" + acc.password, "Đã copy thông tin") }
            }
            val delBtn = TextView(this).apply {
                text = "  🗑"
                textSize = 14f
                setOnClickListener {
                    accounts.remove(acc); saveAccounts(); refreshAccountList()
                }
            }
            fillRow.addView(fillBtn); fillRow.addView(copyBtn); fillRow.addView(delBtn)

            card.addView(emailRow); card.addView(passRow); card.addView(fillRow)
            list.addView(card)
        }
    }

    private fun makeCredRow(icon: String, text: String, isEmail: Boolean, onTap: () -> Unit): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (2*dp).toInt(), 0, (2*dp).toInt())
            val label = TextView(this@MainActivity).apply {
                this.text = "$icon $text"
                textSize = 12f
                setTextColor(if (isEmail) 0xFF1565C0.toInt() else 0xFF555555.toInt())
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btn = TextView(this@MainActivity).apply {
                this.text = "📋"
                textSize = 14f
                setPadding((8*dp).toInt(), 0, 0, 0)
                setOnClickListener { onTap() }
            }
            addView(label); addView(btn)
        }
    }

    /** Smart paste: injects the right value based on focused field type */
    private fun smartPaste(email: String, password: String, prefer: String) {
        val activeWv = tabs.getOrNull(activeTabIndex)?.webView ?: return
        val value = when {
            focusedFieldType == "email" || (prefer == "email" && focusedFieldType.isEmpty()) -> email
            focusedFieldType == "password" || (prefer == "password" && focusedFieldType.isEmpty()) -> password
            else -> if (prefer == "email") email else password
        }
        val js = buildPasteJs(value)
        activeWv.evaluateJavascript(js, null)
        copyToClipboard(value, "Đã dán: ${value.take(20)}")
    }

    private fun buildPasteJs(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function(){
  var el=document.activeElement;
  if(!el||!['INPUT','TEXTAREA'].includes(el.tagName)){
    el=document.querySelector('input:focus,textarea:focus')||
       document.querySelector('input[type="email"]:not([disabled]),input[type="password"]:not([disabled])');
  }
  if(el){
    try{
      var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
      if(s&&s.set){s.set.call(el,'$escaped');}else{el.value='$escaped';}
      ['input','change','blur'].forEach(function(ev){el.dispatchEvent(new Event(ev,{bubbles:true}));});
    }catch(e){el.value='$escaped';}
  }
})();
        """.trimIndent()
    }

    private fun injectLoginForm(wv: WebView, email: String, password: String) {
        val escapedEmail = email.replace("'", "\\'")
        val escapedPass  = password.replace("'", "\\'")
        val js = """
(function(){
  function fill(el,v){
    try{var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
    if(s&&s.set)s.set.call(el,v);else el.value=v;
    ['input','change','blur'].forEach(function(e){el.dispatchEvent(new Event(e,{bubbles:true}));});}
    catch(e){el.value=v;}
  }
  var em=document.querySelector('input[type="email"],input[name="email"],input[autocomplete="email"]');
  var pw=document.querySelector('input[type="password"]');
  if(em)fill(em,'$escapedEmail');
  if(pw)fill(pw,'$escapedPass');
})();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    // ─── Draggable Mail FAB ────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMailFab() {
        val dp = resources.displayMetrics.density
        val size = (52 * dp).toInt()

        val fab = TextView(this).apply {
            text = "📧"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1565C0.toInt())
                setStroke((2*dp).toInt(), 0xFF90CAF9.toInt())
            }
            elevation = 14 * dp
        }
        mailFab = fab

        val root = binding.root
        root.addView(fab, ViewGroup.LayoutParams(size, size))

        fab.post {
            val sx = prefs.getFloat("mailfab_x", -1f)
            val sy = prefs.getFloat("mailfab_y", -1f)
            val maxX = (resources.displayMetrics.widthPixels - size).toFloat()
            val maxY = (resources.displayMetrics.heightPixels - size).toFloat()
            fab.x = if (sx >= 0) sx.coerceIn(0f, maxX) else maxX - (16 * dp)
            fab.y = if (sy >= 0) sy.coerceIn(0f, maxY) else maxY - (100 * dp)
        }

        var dX = 0f; var dY = 0f; var isDragging = false; val slop = 12f

        fab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX; dY = v.y - event.rawY; isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = Math.abs(event.rawX + dX - v.x)
                    val my = Math.abs(event.rawY + dY - v.y)
                    if (mx > slop || my > slop) isDragging = true
                    if (isDragging) {
                        val maxX = (resources.displayMetrics.widthPixels - v.width).toFloat()
                        val maxY = (resources.displayMetrics.heightPixels - v.height).toFloat()
                        v.x = (event.rawX + dX).coerceIn(0f, maxX)
                        v.y = (event.rawY + dY).coerceIn(0f, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        prefs.edit().putFloat("mailfab_x", v.x).putFloat("mailfab_y", v.y).apply()
                    } else {
                        if (binding.mailPanel.visibility == View.GONE) {
                            openPanel(); switchPanelSection(0)
                        } else closePanel()
                        unreadCount = 0; updateMailBadge()
                    }
                    isDragging = false; true
                }
                else -> false
            }
        }
    }

    private fun updateMailBadge() = runOnUiThread {
        mailFab?.text = if (unreadCount > 0) "📧$unreadCount" else "📧"
    }

    // ─── JavaScript injections ─────────────────────────────────────────────────

    private fun buildFieldFocusJs(): String = """
(function(){
  function detectType(el){
    var t=el.type||'',n=(el.name||'').toLowerCase(),p=(el.placeholder||'').toLowerCase(),
        id=(el.id||'').toLowerCase(),ac=(el.autocomplete||'').toLowerCase();
    if(t==='email'||n.includes('email')||p.includes('email')||id.includes('email')||ac==='email')return'email';
    if(t==='password'||n.includes('pass')||p.includes('pass'))return'password';
    if(n.includes('user')||p.includes('user')||id.includes('user'))return'username';
    return'text';
  }
  function attach(el){
    if(el._rem2)return; el._rem2=true;
    el.addEventListener('focus',function(){if(window.REM2)window.REM2.onFieldFocus(detectType(el));});
    el.addEventListener('blur',function(){if(window.REM2)window.REM2.onFieldFocus('');});
  }
  document.querySelectorAll('input').forEach(attach);
  new MutationObserver(function(){
    document.querySelectorAll('input:not([data-rem2])').forEach(function(el){
      el.setAttribute('data-rem2','1');attach(el);
    });
  }).observe(document.body||document.documentElement,{childList:true,subtree:true});
})();
    """.trimIndent()

    /**
     * CAPTCHA fix v2: covers hCaptcha (used by Replit), reCAPTCHA, and Turnstile.
     * Main fix for "Your captcha token is invalid. Please refresh and try again. (code:1)":
     *   — Wait for hcaptcha widget to be ready before allowing form submit
     *   — Intercept errors and re-trigger widget
     *   — Remove requestAnimationFrame throttle that breaks hCaptcha in WebView
     */
    private fun buildCaptchaFixJs(): String = """
(function(){
  if(window._rem2CaptchaFixed)return; window._rem2CaptchaFixed=true;

  // Fix 1: Patch fetch to catch captcha errors and retry form
  var _origFetch=window.fetch;
  if(_origFetch){
    window.fetch=function(url,opts){
      return _origFetch.apply(this,arguments).then(function(r){
        r.clone().text().then(function(t){
          if(t&&(t.indexOf('captcha token is invalid')>=0||t.indexOf('"code":1')>=0||t.indexOf('code:1')>=0)){
            setTimeout(function(){
              // Reset hCaptcha
              if(window.hcaptcha){try{window.hcaptcha.reset();}catch(e){}}
              if(window.grecaptcha){try{window.grecaptcha.reset();}catch(e){}}
              // Click retry buttons
              document.querySelectorAll('button').forEach(function(b){
                var bt=(b.textContent||'').toLowerCase();
                if(bt.indexOf('retry')>=0||bt.indexOf('refresh')>=0||bt.indexOf('try again')>=0)b.click();
              });
            },300);
          }
        }).catch(function(){});
        return r;
      });
    };
  }

  // Fix 2: hCaptcha — watch for assignment and patch without breaking reads
  function patchHcaptcha(hc){
    if(!hc||hc._rem2Patched)return; hc._rem2Patched=true;
    // No-op patch; just mark as seen so reset() calls below work
  }
  (function watchGlobal(key,patchFn){
    var _val=window[key];
    if(_val){patchFn(_val);}
    try{
      Object.defineProperty(window,key,{
        get:function(){return _val;},
        set:function(v){_val=v; setTimeout(function(){patchFn(v);},50);},
        configurable:true
      });
    }catch(e){}
  })('hcaptcha',patchHcaptcha);

  // Fix 3: reCAPTCHA timing fix (v2 checkbox) — same safe getter+setter pattern
  function patchRecaptcha(rc){
    if(!rc||!rc.ready||rc._rem2Patched)return; rc._rem2Patched=true;
    var _ready=rc.ready.bind(rc);
    rc.ready=function(cb){return _ready(function(){setTimeout(cb,200);});};
  }
  (function watchGlobal2(key,patchFn){
    var _val=window[key];
    if(_val){patchFn(_val);}
    try{
      Object.defineProperty(window,key,{
        get:function(){return _val;},
        set:function(v){_val=v; setTimeout(function(){patchFn(v);},50);},
        configurable:true
      });
    }catch(e){}
  })('grecaptcha',patchRecaptcha);

  // Fix 4: Turnstile (Cloudflare) — safe getter+setter
  function patchTurnstile(ts){
    if(!ts||!ts.render||ts._rem2Patched)return; ts._rem2Patched=true;
    var _render=ts.render.bind(ts);
    ts.render=function(el,params){
      if(params&&params.callback){var _cb=params.callback;params.callback=function(tk){setTimeout(function(){_cb(tk);},100);};}
      return _render(el,params);
    };
  }
  (function watchGlobal3(key,patchFn){
    var _val=window[key];
    if(_val){patchFn(_val);}
    try{
      Object.defineProperty(window,key,{
        get:function(){return _val;},
        set:function(v){_val=v; setTimeout(function(){patchFn(v);},50);},
        configurable:true
      });
    }catch(e){}
  })('turnstile',patchTurnstile);

  // Fix 5: XMLHttpRequest captcha error detection
  var _XHRopen=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(){
    this.addEventListener('load',function(){
      try{
        if(this.responseText&&(this.responseText.indexOf('captcha token is invalid')>=0||
           this.responseText.indexOf('"code":1')>=0)){
          setTimeout(function(){
            if(window.hcaptcha)try{window.hcaptcha.reset();}catch(e){}
            if(window.grecaptcha)try{window.grecaptcha.reset();}catch(e){}
          },300);
        }
      }catch(e){}
    });
    return _XHRopen.apply(this,arguments);
  };
})();
    """.trimIndent()

    private fun buildAntiDetectJs(d: DeviceProfile): String {
        val noise = (1..8).random()
        val lang = if (d.timezone.contains("Ho_Chi_Minh")) "vi-VN" else "en-US"
        return """
(function(){
  try{Object.defineProperty(navigator,'webdriver',{get:()=>undefined,configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'platform',{get:()=>'Linux armv8l',configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'userAgent',{get:()=>'${d.ua}',configurable:true});}catch(e){}
  var _lang='$lang';
  try{Object.defineProperty(navigator,'language',{get:()=>_lang,configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'languages',{get:()=>[_lang,'en-US','en'],configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'deviceMemory',{get:()=>${d.deviceMemory},configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>${d.hardwareConcurrency},configurable:true});}catch(e){}
  try{
    var _fp=[{name:'Chrome PDF Plugin',filename:'internal-pdf-viewer',description:'PDF'},
      {name:'Chrome PDF Viewer',filename:'mhjfbmdgcfjbbpaeojofohoefgiehjai',description:''},
      {name:'Native Client',filename:'internal-nacl-plugin',description:''}];
    Object.defineProperty(navigator,'plugins',{get:()=>Object.assign(_fp,{item:function(i){return _fp[i];},namedItem:function(n){return _fp.find(function(p){return p.name===n;})||null;},length:_fp.length}),configurable:true});
    Object.defineProperty(navigator,'mimeTypes',{get:()=>({length:2,item:function(){return null;}}),configurable:true});
  }catch(e){}
  try{Object.defineProperty(screen,'width',{get:()=>${d.screenW},configurable:true});}catch(e){}
  try{Object.defineProperty(screen,'height',{get:()=>${d.screenH},configurable:true});}catch(e){}
  try{Object.defineProperty(screen,'availWidth',{get:()=>${d.screenW},configurable:true});}catch(e){}
  try{Object.defineProperty(screen,'availHeight',{get:()=>${d.screenH-48},configurable:true});}catch(e){}
  try{Object.defineProperty(window,'outerWidth',{get:()=>${d.screenW},configurable:true});}catch(e){}
  try{Object.defineProperty(window,'outerHeight',{get:()=>${d.screenH},configurable:true});}catch(e){}
  try{
    var _tdu=HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL=function(type){
      var ctx=this.getContext('2d');
      if(ctx){var img=ctx.getImageData(0,0,this.width,this.height);for(var i=0;i<img.data.length;i+=4){img.data[i]^=(${noise}&3);img.data[i+1]^=((${noise}>>1)&3);}ctx.putImageData(img,0,0);}
      return _tdu.apply(this,arguments);
    };
  }catch(e){}
  try{
    var _wgl=WebGLRenderingContext.prototype.getParameter;
    WebGLRenderingContext.prototype.getParameter=function(p){
      if(p===37445)return'Qualcomm Technologies, Inc.';
      if(p===37446)return'Adreno (TM) 740';
      return _wgl.call(this,p);
    };
  }catch(e){}
  // Remove automation indicators
  try{delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;}catch(e){}
  try{delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;}catch(e){}
  try{delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;}catch(e){}
})();
        """.trimIndent()
    }

    // ─── Auto-fill & Onboarding ────────────────────────────────────────────────

    private fun injectSignupForm(webView: WebView, email: String, username: String, password: String, fullName: String) {
        if (email.isEmpty()) return
        val eE = email.replace("'", "\\'")
        val eU = username.replace("'", "\\'")
        val eP = password.replace("'", "\\'")
        val js = """
(function(){
  if(window._rem2AutoFill)return'running';
  window._rem2AutoFill=true;
  var OAUTH=['google','github','facebook','apple','microsoft','twitter','with x','x login'];
  function fill(el,val){
    try{var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
    if(s&&s.set){s.set.call(el,val);}else{el.value=val;}
    ['input','change','blur'].forEach(function(ev){el.dispatchEvent(new Event(ev,{bubbles:true}));});}
    catch(e){el.value=val;}
  }
  function vis(el){var r=el.getBoundingClientRect();return r.width>8&&r.height>8&&r.top>=0&&r.top<window.innerHeight;}
  function findEl(sels){for(var i=0;i<sels.length;i++){var els=document.querySelectorAll(sels[i]);for(var j=0;j<els.length;j++){if(vis(els[j]))return els[j];}}return null;}
  function clickNext(){
    var btns=Array.from(document.querySelectorAll('button[type="submit"],button')).filter(function(b){
      if(!b.offsetParent||b.offsetWidth===0)return false;
      var t=(b.textContent||'').toLowerCase().trim();
      return !OAUTH.some(function(k){return t.indexOf(k)>=0;});
    });
    for(var i=0;i<btns.length;i++){
      var t=(btns[i].textContent||'').toLowerCase().replace(/[→>↪]/g,'').trim();
      if(t==='next'||t==='continue'||t.indexOf('next')>=0||t.indexOf('get started')>=0||(t.indexOf('continue')>=0&&t.indexOf('with')<0)){btns[i].click();return true;}
    }
    var f=document.querySelector('form');if(f){f.dispatchEvent(new Event('submit',{bubbles:true}));return true;}
    return false;
  }
  var phase=0,ticks=0;
  var tid=setInterval(function(){
    ticks++;if(ticks>500){clearInterval(tid);window._rem2AutoFill=false;return;}
    if(phase===0){
      var el=findEl(['input[type="email"]','input[name="email"]','input[autocomplete="email"]','input[placeholder*="mail" i]']);
      if(el&&el.value!=='$eE'){fill(el,'$eE');phase=1;setTimeout(function(){clickNext();},900);}
    }else if(phase===1){
      var pe=findEl(['input[type="password"]']);
      if(pe){
        fill(pe,'$eP');
        var ue=findEl(['input[name="username"]','input[autocomplete="username"]','input[placeholder*="username" i]']);
        if(ue&&ue.value!=='$eU')fill(ue,'$eU');
        phase=2;setTimeout(function(){clickNext();},900);
      }else if(ticks%10===0)clickNext();
    }else if(phase===2){
      if(!findEl(['input[type="password"]'])){clearInterval(tid);window._rem2AutoFill=false;}
    }
  },400);
  return'started';
})()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Auto-onboarding: automatically selects options (radio/checkbox/cards)
     * and clicks Next/Continue buttons until wizard is complete.
     */
    private fun injectOnboardingStep(webView: WebView, fullName: String) {
        val fn = fullName.substringBefore(" ").ifEmpty { "User" }.replace("'", "\\'")
        val ln = fullName.substringAfter(" ", "").ifEmpty { "Dev" }.replace("'", "\\'")
        val fq = fullName.replace("'", "\\'")
        val js = """
(function(){
  function setVal(el,v){
    try{var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
    if(s&&s.set){s.set.call(el,v);}else{el.value=v;}
    ['input','change','blur'].forEach(function(e){el.dispatchEvent(new Event(e,{bubbles:true}));});}
    catch(e){el.value=v;}
  }
  function vis(el){return el.offsetParent!==null&&el.offsetWidth>0&&el.style.display!=='none';}

  // Select free plan
  var planPicked=false;
  document.querySelectorAll('button,a,[role="button"],[data-testid]').forEach(function(el){
    if(planPicked)return;
    var t=(el.textContent||'').toLowerCase();
    if(t.indexOf('starter')>=0||t.indexOf('free')>=0||t.indexOf('continue with free')>=0||t.indexOf('continue for free')>=0){
      el.click();planPicked=true;
    }
  });
  if(planPicked)return'plan';

  // Fill name fields
  var nameFields=[
    ['input[name="full_name"],input[name="fullName"],input[name="name"]','$fq'],
    ['input[name="first_name"],input[name="firstName"],input[placeholder*="first" i]','$fn'],
    ['input[name="last_name"],input[name="lastName"],input[placeholder*="last" i]','$ln'],
  ];
  nameFields.forEach(function(pair){
    document.querySelectorAll(pair[0]).forEach(function(el){if(vis(el))setVal(el,pair[1]);});
  });

  // Select ALL visible radio/checkbox/card options (pick random subset)
  var opts=Array.from(document.querySelectorAll(
    '[data-testid*="option"],[class*="SelectableCard"],[class*="selectable"],[class*="choice"],' +
    '[role="checkbox"],[role="radio"],[class*="ChoiceCard"],[class*="option-card"]'
  )).filter(vis);
  if(opts.length>0){
    var pick=Math.min(opts.length,Math.floor(Math.random()*3)+1);
    var chosen=[];
    while(chosen.length<pick){
      var idx=Math.floor(Math.random()*opts.length);
      if(!chosen.includes(idx)){chosen.push(idx);opts[idx].click();}
    }
  }
  // Also handle radio inputs
  var radios=Array.from(document.querySelectorAll('input[type="radio"]')).filter(vis);
  if(radios.length>0)radios[Math.floor(Math.random()*radios.length)].click();
  // Checkboxes: click first visible
  var checks=Array.from(document.querySelectorAll('input[type="checkbox"]')).filter(vis);
  if(checks.length>0&&!checks[0].checked)checks[0].click();

  // Click Next / Continue / Get Started / Finish
  var SKIP=['google','github','facebook','apple','microsoft','twitter',' x ','login','sign in'];
  function noOauth(t){return !SKIP.some(function(k){return(' '+t+' ').indexOf(k)>=0;});}
  var clicked=false;
  function tryNext(){
    if(clicked)return;
    var btns=Array.from(document.querySelectorAll('button[type="submit"],button,[role="button"]')).filter(vis);
    // Sort submit buttons first
    btns.sort(function(a,b){return (a.type==='submit'?0:1)-(b.type==='submit'?0:1);});
    for(var i=0;i<btns.length;i++){
      var t=(btns[i].textContent||'').toLowerCase().replace(/[→>↪]/g,'').trim();
      if(!noOauth(t))continue;
      if(t==='next'||t==='continue'||t.indexOf('next')>=0||t.indexOf('get started')>=0||
         t.indexOf('finish')>=0||t.indexOf('done')>=0||t.indexOf('skip')>=0||
         (t.indexOf('continue')>=0&&t.indexOf('with')<0&&t.length<28)||
         (t.indexOf('start')>=0&&t.indexOf('starter')<0)){
        btns[i].click();clicked=true;break;
      }
    }
  }
  tryNext();
  if(!clicked)setTimeout(tryNext,700);
  setTimeout(tryNext,2000);
  return clicked?'next':'filled';
})()
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val r = result?.trim('"') ?: ""
            if (r == "plan") toast("Đã chọn gói miễn phí ✓")
            // Keep going if still on onboarding
            webView.postDelayed({
                webView.evaluateJavascript("window.location.pathname") { path ->
                    val p = path?.trim('"') ?: ""
                    if (p.contains("onboarding") || p.contains("plans") ||
                        p.contains("wizard") || p.contains("get-started")) {
                        injectOnboardingStep(webView, fullName)
                    }
                }
            }, 5500)
        }
    }

    // ─── Mail.tm ───────────────────────────────────────────────────────────────

    private suspend fun ensureMailAccount(force: Boolean = false) {
        val savedEmail = prefs.getString(KEY_MAIL_EMAIL, "") ?: ""
        val savedPass  = prefs.getString(KEY_MAIL_PASS,  "") ?: ""
        val savedToken = prefs.getString(KEY_MAIL_TOKEN, "") ?: ""

        if (!force && savedEmail.isNotEmpty() && savedToken.isNotEmpty()) {
            mailEmail = savedEmail; mailPassword = savedPass; mailToken = savedToken
            setupAutoCredentials(savedEmail)
            withContext(Dispatchers.Main) {
                binding.tvMailStatus.text = "✅ $mailEmail"
                binding.tvBottomStatus.text = mailEmail
                startPolling()
            }
            return
        }

        withContext(Dispatchers.Main) {
            binding.pbMail.visibility = View.VISIBLE
            binding.tvMailStatus.text = "⏳ Đang tạo Mail.tm..."
        }

        try {
            val domains = getMailDomains()
            if (domains.isEmpty()) {
                withContext(Dispatchers.Main) { binding.tvMailStatus.text = "❌ Mail.tm không khả dụng" }
                return
            }
            val user  = "r${(10000..99999).random()}"
            val email = "$user@${domains[0]}"
            val pass  = UUID.randomUUID().toString().replace("-","").take(16)

            if (!mailCreate(email, pass)) {
                withContext(Dispatchers.Main) { binding.tvMailStatus.text = "❌ Tạo tài khoản thất bại" }
                return
            }
            val tok = mailLogin(email, pass)
            if (tok.isEmpty()) {
                withContext(Dispatchers.Main) { binding.tvMailStatus.text = "❌ Đăng nhập thất bại" }
                return
            }
            mailToken = tok; mailEmail = email; mailPassword = pass
            prefs.edit().putString(KEY_MAIL_EMAIL, email).putString(KEY_MAIL_PASS, pass)
                .putString(KEY_MAIL_TOKEN, tok).apply()
            setupAutoCredentials(email)

            withContext(Dispatchers.Main) {
                binding.pbMail.visibility = View.GONE
                binding.tvMailStatus.text = "✅ $email"
                binding.tvBottomStatus.text = email
                toast("Mail.tm: $email")
                startPolling()
            }
            addOrUpdateAccount(AccountEntry(email = email, password = pass, username = autoUsername))

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                binding.pbMail.visibility = View.GONE
                binding.tvMailStatus.text = "❌ ${e.message?.take(40)}"
            }
        }
    }

    private fun setupAutoCredentials(email: String) {
        autoEmail    = email
        autoUsername = randomUsername()
        autoPassword = "Rem2@${(100000..999999).random()}"
        autoFullName = prefs.getString(KEY_REPLIT_NAME, "").let {
            if (it.isNullOrEmpty()) randomFullName()
                .also { n -> prefs.edit().putString(KEY_REPLIT_NAME, n).apply() }
            else it
        }
    }

    private suspend fun getMailDomains(): List<String> = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(Request.Builder().url("$MAILTM/domains").build()).execute()
            val arr  = JSONObject(resp.body?.string() ?: "{}").optJSONArray("hydra:member")
                ?: return@withContext emptyList()
            (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .filter { it.optBoolean("isActive", true) }
                .map { it.getString("domain") }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun mailCreate(email: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("address", email).put("password", pass)
                .toString().toRequestBody(JSON_MT)
            val resp = http.newCall(Request.Builder().url("$MAILTM/accounts").post(body).build()).execute()
            resp.code in listOf(200, 201)
        } catch (e: Exception) { false }
    }

    private suspend fun mailLogin(email: String, pass: String): String = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("address", email).put("password", pass)
                .toString().toRequestBody(JSON_MT)
            val resp = http.newCall(Request.Builder().url("$MAILTM/token").post(body).build()).execute()
            JSONObject(resp.body?.string() ?: "{}").optString("token", "")
        } catch (e: Exception) { "" }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (isActive) { fetchMail(); delay(5000) }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    private suspend fun fetchMail() {
        if (mailToken.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val resp = http.newCall(
                    Request.Builder().url("$MAILTM/messages")
                        .addHeader("Authorization", "Bearer $mailToken").build()
                ).execute()
                val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("hydra:member")
                    ?: return@withContext
                val newMsgs = mutableListOf<Pair<String,String>>()
                for (i in 0 until arr.length()) {
                    val msg = arr.getJSONObject(i)
                    val id  = msg.optString("id","")
                    val sub = msg.optString("subject","(không tiêu đề)")
                    if (id.isNotEmpty() && !seenIds.contains(id)) {
                        seenIds.add(id); newMsgs.add(id to sub)
                    }
                }
                if (newMsgs.isNotEmpty()) withContext(Dispatchers.Main) {
                    addMailItems(newMsgs)
                    if (binding.mailPanel.visibility == View.VISIBLE && panelSection != 1) {
                        switchPanelSection(1); toast("📧 Email mới!")
                    } else if (binding.mailPanel.visibility == View.GONE) {
                        unreadCount += newMsgs.size; updateMailBadge()
                        toast("📧 ${newMsgs.size} email mới — nhấn FAB để xem")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun addMailItems(msgs: List<Pair<String,String>>) {
        val dp = resources.displayMetrics.density
        unreadCount += msgs.size; updateMailBadge()
        msgs.forEach { (id, subject) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())
                background = GradientDrawable().apply { setColor(0xFFE8F5E9.toInt()); cornerRadius=6*dp }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((6*dp).toInt(),(3*dp).toInt(),(6*dp).toInt(),(3*dp).toInt()) }
                setOnClickListener { lifecycleScope.launch { loadVerifyLink(id) } }
            }
            val tv = TextView(this).apply {
                text = "📨 $subject"
                textSize = 12f
                setTextColor(0xFF1B5E20.toInt())
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrow = TextView(this).apply { text = " ›"; textSize = 16f; setTextColor(0xFF388E3C.toInt()) }
            row.addView(tv); row.addView(arrow)
            binding.mailList.addView(row, 0)
        }
    }

    private suspend fun loadVerifyLink(msgId: String) {
        val body = withContext(Dispatchers.IO) {
            try {
                val resp = http.newCall(
                    Request.Builder().url("$MAILTM/messages/$msgId")
                        .addHeader("Authorization", "Bearer $mailToken").build()
                ).execute()
                val msg = JSONObject(resp.body?.string() ?: "{}")
                msg.optString("text","") + " " + msg.optString("html","")
            } catch (e: Exception) { "" }
        }
        val keywords = listOf("verify","confirm","activate","click here","account","email")
        val urlRegex = Regex("""https?://[^\s<>"']+""")
        val link = urlRegex.findAll(body)
            .map { it.value.trimEnd('.',')',']','"','\'') }
            .firstOrNull { url -> keywords.any { kw -> url.lowercase().contains(kw) } }
            ?: urlRegex.findAll(body).firstOrNull { it.value.contains("replit.com") }?.value

        withContext(Dispatchers.Main) {
            if (link != null) {
                binding.verifyWebView.visibility  = View.VISIBLE
                binding.mailListScroll.visibility = View.GONE
                binding.verifyWebView.loadUrl(link)
                switchPanelSection(1)
                toast("Đang mở link xác nhận...")
            } else toast("Không tìm thấy link xác nhận")
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String, toast: String? = null) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("rem2", text))
        toast?.let { toast(it) }
    }

    private fun requestStoragePermissionIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 200)
    }

    private fun showKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }
    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            binding.mailPanel.visibility == View.VISIBLE -> closePanel()
            binding.verifyWebView.canGoBack() && binding.verifyWebView.visibility == View.VISIBLE ->
                binding.verifyWebView.goBack()
            tabs.getOrNull(activeTabIndex)?.webView?.canGoBack() == true ->
                tabs[activeTabIndex].webView?.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy(); stopPolling()
        tabs.forEach { it.webView?.destroy() }
    }
    override fun onPause()  { super.onPause();  tabs.forEach { it.webView?.onPause()  } }
    override fun onResume() { super.onResume(); tabs.forEach { it.webView?.onResume() } }
}
