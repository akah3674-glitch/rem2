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
    var cookieSnapshot: String = ""  // cookies saved when tab is backgrounded
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

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) coc_coc_browser/117.0.0 Chrome/111.0.5563.116 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.6099.210 Safari/537.36"

        private val FIRST_NAMES = listOf(
            "Alex","Sam","Jordan","Taylor","Morgan","Casey","Riley","Avery",
            "Blake","Cameron","Drew","Elliot","Finley","Harper","Jamie","Kai",
            "Logan","Mika","Noah","Quinn","Robin","Sage","Skyler","Toby"
        )
        private val LAST_NAMES = listOf(
            "Smith","Lee","Chen","Park","Johnson","Brown","Davis","Wilson",
            "Moore","Taylor","Anderson","Thomas","Jackson","White","Harris",
            "Martin","Garcia","Martinez","Robinson","Clark"
        )
        fun randomFullName() = "${FIRST_NAMES.random()} ${LAST_NAMES.random()}"
        fun randomUsername(): String {
            val adjs  = listOf("cool","fast","dark","blue","wild","swift","calm","bright","smart","keen")
            val nouns = listOf("fox","hawk","wolf","bear","lion","ace","star","byte","code","dev")
            return adjs.random() + nouns.random() + (1000..9999).random()
        }
        fun randomPassword(): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP0123456789!@#"
            return (1..14).map { chars.random() }.joinToString("")
        }

        // ── Device profiles ──────────────────────────────────────────────────
        data class DeviceProfile(
            val name: String, val ua: String,
            val screenW: Int, val screenH: Int,
            val deviceMemory: Int, val hardwareConcurrency: Int,
            val timezone: String
        )
        val DEVICE_PROFILES = listOf(
            DeviceProfile("Samsung S23",
                "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36",
                393, 851, 8, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Samsung A54",
                "Mozilla/5.0 (Linux; Android 13; SM-A546E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.193 Mobile Safari/537.36",
                360, 780, 6, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Xiaomi 13",
                "Mozilla/5.0 (Linux; Android 13; 2211133C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
                393, 851, 12, 8, "Asia/Bangkok"),
            DeviceProfile("Redmi Note 12",
                "Mozilla/5.0 (Linux; Android 13; 23021RAAEG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.111 Mobile Safari/537.36",
                393, 873, 6, 8, "Asia/Jakarta"),
            DeviceProfile("OPPO Reno10",
                "Mozilla/5.0 (Linux; Android 13; CPH2531) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.163 Mobile Safari/537.36",
                412, 915, 8, 8, "Asia/Singapore"),
            DeviceProfile("Vivo Y36",
                "Mozilla/5.0 (Linux; Android 13; V2247) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.140 Mobile Safari/537.36",
                393, 873, 8, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Pixel 7",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36",
                412, 915, 8, 8, "America/New_York"),
            DeviceProfile("OnePlus 11",
                "Mozilla/5.0 (Linux; Android 13; CPH2449) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36",
                412, 919, 16, 8, "Europe/London")
        )
    }

    // ── Binding ──────────────────────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // ── HTTP ─────────────────────────────────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
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
    private var mailToken   = ""
    private var mailEmail   = ""
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
        // Tab đầu tiên → replit.com; tab mới → trang login để đăng ký tài khoản riêng
        val targetUrl = url ?: if (tabs.isEmpty()) "https://replit.com"
                                else "https://replit.com/login"
        val entry = TabEntry(url = targetUrl)
        tabs.add(entry)
        val wv = createWebView()
        entry.webView = wv
        binding.webContainer.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.loadUrl(targetUrl)
        if (select) selectTab(tabs.size - 1) else wv.visibility = View.GONE
        renderTabBar()
        return entry
    }

    // Cookie domains cần lưu/khôi phục cho mỗi tab
    private val SESSION_DOMAINS = listOf(
        "https://replit.com",
        "https://replit.com/api",
        "https://cdn.replit.com"
    )

    /** Lưu cookie của tab hiện tại vào TabEntry, xoá sạch CookieManager,
     *  rồi khôi phục cookie của tab mới → mỗi tab là một session độc lập. */
    private fun selectTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val cm = CookieManager.getInstance()

        // 1. Lưu cookie tab đang active
        tabs.getOrNull(activeTabIndex)?.let { current ->
            val snapshot = StringBuilder()
            SESSION_DOMAINS.forEach { domain ->
                val c = cm.getCookie(domain)
                if (!c.isNullOrEmpty()) snapshot.append("$domain\t$c\n")
            }
            current.cookieSnapshot = snapshot.toString()
        }

        // 2. Xoá toàn bộ cookie
        cm.removeAllCookies(null)

        // 3. Khôi phục cookie của tab mới (nếu có)
        val newTab = tabs[index]
        if (newTab.cookieSnapshot.isNotEmpty()) {
            newTab.cookieSnapshot.trim().lines().forEach { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    val (domain, cookieStr) = parts
                    cookieStr.split(";").forEach { cookie ->
                        val trimmed = cookie.trim()
                        if (trimmed.isNotEmpty()) cm.setCookie(domain, trimmed)
                    }
                }
            }
            cm.flush()
        }

        // 4. Chuyển visibility
        tabs.forEachIndexed { i, t ->
            t.webView?.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        activeTabIndex = index
        renderTabBar()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        tabs[index].webView?.let { binding.webContainer.removeView(it); it.destroy() }
        tabs.removeAt(index)
        selectTab(if (index >= tabs.size) tabs.size - 1 else index)
        renderTabBar()
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
                    setColor(0xFFEEEEEE.toInt()); cornerRadius = 6 * dp } else null
                setPadding((6*dp).toInt(), 0, (2*dp).toInt(), 0)
                setOnClickListener { selectTab(i); closePanel() }
            }
            val title = TextView(this).apply {
                text = (if (i == activeTabIndex) "● " else "") + tab.title.take(12).ifEmpty { "Replit" }
                textSize = 12f
                setTextColor(if (i == activeTabIndex) Color.WHITE else 0xFF555555.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setSingleLine(true)
                maxWidth = (120 * dp).toInt()
            }
            val closeBtn = TextView(this).apply {
                text = " ✕"
                textSize = 11f
                setTextColor(if (i == activeTabIndex) 0xFFAAAAAA.toInt() else 0xFF555555.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((2*dp).toInt(), 0, (4*dp).toInt(), 0)
                setOnClickListener {
                    if (tabs.size > 1) closeTab(i) else toast("Không thể đóng tab cuối")
                }
            }
            chip.addView(title)
            chip.addView(closeBtn)
            bar.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { setMargins((2*dp).toInt(), (4*dp).toInt(), (2*dp).toInt(), (4*dp).toInt()) })
        }
        binding.tabScrollView.post {
            bar.getChildAt(activeTabIndex)?.let { binding.tabScrollView.smoothScrollTo(it.left, 0) }
        }
    }

    private fun setupTabActions() {
        binding.btnReload.setOnClickListener {
            tabs.getOrNull(activeTabIndex)?.webView?.reload()
            // Spin animation 360°
            ObjectAnimator.ofFloat(binding.btnReload, "rotation", 0f, 360f).apply {
                duration = 600
                start()
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
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString = currentDevice.ua
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }

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
            }

            override fun onPageFinished(view: WebView, url: String) {
                val idx = tabs.indexOfFirst { it.webView == view }
                if (idx >= 0) {
                    tabs[idx].url = url
                    tabs[idx].title = view.title?.take(18)?.ifEmpty { "Replit" } ?: "Replit"
                    if (idx == activeTabIndex) renderTabBar()
                }
                // Inject field focus detection
                view.evaluateJavascript(buildFieldFocusJs(), null)
                // CAPTCHA fix on signup pages
                if (url.contains("replit.com")) {
                    view.evaluateJavascript(buildCaptchaFixJs(), null)
                    // Auto-fill ONLY on registration page (skip login)
                    val isSignup = (url.contains("/signup") || url.contains("/join"))
                        && !url.contains("/login") && !url.contains("/signin")
                    if (isSignup && autoEmail.isNotEmpty()) {
                        view.postDelayed({
                            injectSignupForm(view, autoEmail, autoUsername, autoPassword, autoFullName)
                        }, 1500)
                    }
                    // Auto-onboarding
                    if (url.contains("onboarding") || url.contains("plans") || url.contains("wizard")) {
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
                    if (idx == activeTabIndex) renderTabBar()
                }
            }
            override fun onShowFileChooser(
                webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                // Use ACTION_OPEN_DOCUMENT for persistent URI access across process boundaries
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                             Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(pick, "Chọn file đính kèm")
                return try { filePickerLauncher.launch(chooser); true }
                catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null); fileUploadCallback = null; false
                }
            }
        }

        // Swipe left/right to navigate back/forward
        val gd = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // Easier trigger: |dx| > |dy| (not 1.5x), velocity > 250
                if (Math.abs(dx) < Math.abs(dy) || Math.abs(vX) < 250) return false
                // Both left and right swipe → go back
                return if (Math.abs(dx) > 60) {
                    if (wv.canGoBack()) {
                        wv.goBack()
                        wv.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        runOnUiThread { toast("◀ Quay lại") }
                        true
                    } else false
                } else false
            }
        })
        wv.setOnTouchListener { _, event -> gd.onTouchEvent(event); false }
        return wv
    }

    // ─── Verify WebView (inside panel) ────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        binding.verifyWebView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = currentDevice.ua
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
                override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    v.evaluateJavascript(buildAntiDetectJs(currentDevice), null)
                }
                override fun onPageFinished(v: WebView, url: String) {
                    if (url.contains("onboarding") || url.contains("plans")) {
                        v.postDelayed({ injectOnboardingStep(v, autoFullName) }, 1500)
                    } else if (url.contains("replit.com") && !url.contains("verify") &&
                               !url.contains("confirm") && url != "about:blank") {
                        // Verify complete — hide verifyWebView, show mail list
                        runOnUiThread {
                            binding.verifyWebView.visibility = View.GONE
                            binding.mailListScroll.visibility = View.VISIBLE
                            toast("✅ Xác thực thành công!")
                        }
                    }
                }
            }
        }
    }

    // ─── Panel setup ───────────────────────────────────────────────────────────

    private fun setupPanel() {
        binding.tabAccounts.setOnClickListener { switchPanelSection(0) }
        binding.tabMailList.setOnClickListener { switchPanelSection(1) }
        binding.btnDesktop.setOnClickListener {
            isDesktopMode = !isDesktopMode
            val ua = if (isDesktopMode) DESKTOP_UA else currentDevice.ua
            tabs.forEach { t ->
                t.webView?.settings?.userAgentString = ua
                t.webView?.reload()
            }
            binding.btnDesktop.alpha = if (isDesktopMode) 1.0f else 0.5f
            toast(if (isDesktopMode) "🖥 Chế độ máy tính" else "📱 Chế độ mobile")
        }

        binding.btnRefresh.setOnClickListener {
            lifecycleScope.launch {
                stopPolling()
                prefs.edit()
                    .remove(KEY_MAIL_EMAIL).remove(KEY_MAIL_PASS).remove(KEY_MAIL_TOKEN).apply()
                mailToken = ""; mailEmail = ""; mailPassword = ""
                autoEmail = ""; autoUsername = ""; autoPassword = ""; autoFullName = ""
                seenIds.clear()
                withContext(Dispatchers.Main) {
                    binding.mailList.removeAllViews()
                    binding.tvMailStatus.text = "Đang tạo email mới..."
                    binding.tvBottomStatus.text = ""
                    toast("Đang tạo email mới...")
                }
                ensureMailAccount(force = true)
            }
        }

        binding.tvMailStatus.setOnClickListener {
            if (mailEmail.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("email", mailEmail))
                toast("Đã copy: $mailEmail")
            }
        }

        refreshAccountList()
    }

    private fun switchPanelSection(section: Int) {
        panelSection = section
        if (section == 0) {
            binding.accountsContainer.visibility = View.VISIBLE
            binding.verifyContainer.visibility = View.GONE
            binding.tabAccounts.setBackgroundColor(0xFF1565C0.toInt())
            binding.tabAccounts.setTextColor(Color.WHITE)
            binding.tabMailList.setBackgroundColor(Color.TRANSPARENT)
            binding.tabMailList.setTextColor(0xFF1565C0.toInt())
        } else {
            binding.accountsContainer.visibility = View.GONE
            binding.verifyContainer.visibility = View.VISIBLE
            binding.tabMailList.setBackgroundColor(0xFF1565C0.toInt())
            binding.tabMailList.setTextColor(Color.WHITE)
            binding.tabAccounts.setBackgroundColor(Color.TRANSPARENT)
            binding.tabAccounts.setTextColor(0xFF1565C0.toInt())
            if (mailToken.isNotEmpty()) startPolling()
        }
    }

    private fun openPanel() {
        binding.mailPanel.visibility = View.VISIBLE
        refreshAccountList()
        if (mailToken.isNotEmpty() && panelSection == 1) startPolling()
    }

    private fun closePanel() {
        binding.mailPanel.visibility = View.GONE
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
                    id = o.optString("id", UUID.randomUUID().toString()),
                    email = o.getString("email"),
                    password = o.optString("password", ""),
                    username = o.optString("username", ""),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
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
            accounts.add(0, acc)
            saveAccounts()
            runOnUiThread { refreshAccountList() }
        }
    }

    private fun refreshAccountList() {
        val list = binding.accountList
        list.removeAllViews()
        val dp = resources.displayMetrics.density

        if (accounts.isEmpty()) {
            binding.tvAccountsEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvAccountsEmpty.visibility = View.GONE

        accounts.forEach { acc ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12*dp).toInt(), (8*dp).toInt(), (12*dp).toInt(), (8*dp).toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFEEEEEE.toInt()); cornerRadius = 8 * dp }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((8*dp).toInt(),(4*dp).toInt(),(8*dp).toInt(),(4*dp).toInt()) }
            }

            // Email row
            val emailRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val emailTv = TextView(this).apply {
                text = "📧 " + acc.email
                textSize = 12f
                setTextColor(0xFF1565C0.toInt())
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val pasteEmail = TextView(this).apply {
                text = "📋"
                textSize = 14f
                setPadding((8*dp).toInt(), 0, 0, 0)
                setOnClickListener { pasteIntoWebView(acc.email) }
            }
            emailRow.addView(emailTv); emailRow.addView(pasteEmail)

            // Password row
            val passRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val maskedPass = "🔑 " + "•".repeat(minOf(acc.password.length, 8))
            val passTv = TextView(this).apply {
                text = maskedPass
                textSize = 12f
                setTextColor(0xFF555555.toInt())
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val pastePass = TextView(this).apply {
                text = "📋"
                textSize = 14f
                setPadding((8*dp).toInt(), 0, 0, 0)
                setOnClickListener { pasteIntoWebView(acc.password) }
            }
            passRow.addView(passTv); passRow.addView(pastePass)

            // Delete button
            val delBtn = TextView(this).apply {
                text = "🗑 Xoá"
                textSize = 10f
                setTextColor(0xFF888888.toInt())
                setPadding(0, (4*dp).toInt(), 0, 0)
                setOnClickListener {
                    accounts.remove(acc)
                    saveAccounts()
                    refreshAccountList()
                }
            }

            card.addView(emailRow); card.addView(passRow); card.addView(delBtn)
            list.addView(card)
        }
    }

    private fun pasteIntoWebView(value: String) {
        val activeWv = tabs.getOrNull(activeTabIndex)?.webView ?: return
        val js = """
(function(){
  var el = document.activeElement;
  if(!el || !['INPUT','TEXTAREA'].includes(el.tagName)) {
    // find last focused visible input
    el = document.querySelector('input:focus,textarea:focus');
  }
  if(el) {
    try {
      var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
      if(s&&s.set){s.set.call(el,'${value.replace("'","\\'")}');}
      else{el.value='${value.replace("'","\\'")}'}
      el.dispatchEvent(new Event('input',{bubbles:true}));
      el.dispatchEvent(new Event('change',{bubbles:true}));
    } catch(e) { el.value='${value.replace("'","\\'")}'; }
  }
})();
        """.trimIndent()
        activeWv.evaluateJavascript(js, null)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("rem2", value))
        toast("Đã dán: ${value.take(20)}")
    }


    // ─── Draggable Mail FAB ────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMailFab() {
        val dp = resources.displayMetrics.density
        val size = (48 * dp).toInt()

        val fab = TextView(this).apply {
            text = "📧"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1565C0.toInt())
            }
            elevation = 12 * dp
            layoutParams = android.view.WindowManager.LayoutParams(size, size)
        }
        mailFab = fab

        // Add to root view
        val root = binding.root
        root.addView(fab, ViewGroup.LayoutParams(size, size))

        // Restore saved position or place bottom-right near main FAB
        fab.post {
            val sx = prefs.getFloat("mailfab_x", -1f)
            val sy = prefs.getFloat("mailfab_y", -1f)
            val maxX = (resources.displayMetrics.widthPixels - size).toFloat()
            val maxY = (resources.displayMetrics.heightPixels - size).toFloat()
            if (sx >= 0 && sy >= 0) {
                fab.x = sx.coerceIn(0f, maxX)
                fab.y = sy.coerceIn(0f, maxY)
            } else {
                fab.x = maxX - (16 * dp)
                fab.y = maxY - (90 * dp)
            }
        }

        var dX = 0f; var dY = 0f; var isDragging = false
        val slop = 10f

        fab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX; dY = v.y - event.rawY; isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX = Math.abs(event.rawX + dX - v.x)
                    val movedY = Math.abs(event.rawY + dY - v.y)
                    if (movedX > slop || movedY > slop) isDragging = true
                    if (isDragging) {
                        val maxX = (resources.displayMetrics.widthPixels - v.width).toFloat()
                        val maxY = (resources.displayMetrics.heightPixels - v.height).toFloat()
                        v.x = (event.rawX + dX).coerceIn(0f, maxX)
                        v.y = (event.rawY + dY).coerceIn(0f, maxY)
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        prefs.edit().putFloat("mailfab_x", v.x).putFloat("mailfab_y", v.y).apply()
                    } else {
                        // Short tap → auto-inject email+password into current WebView
                        injectSavedCredentials()
                        unreadCount = 0
                        updateMailBadge()
                    }
                    isDragging = false; true
                }
                else -> false
            }
        }

        // Long-press → open/close mail panel
        fab.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (binding.mailPanel.visibility == View.GONE) {
                openPanel(); switchPanelSection(1)
            } else {
                closePanel()
            }
            true
        }
    }

    private fun injectSavedCredentials() {
        val email = mailEmail
        val pass  = mailPassword
        if (email.isEmpty()) { toast("⚠️ Chưa có email được tạo"); return }
        val activeWv = tabs.getOrNull(activeTabIndex)?.webView ?: run {
            toast("⚠️ Không có trang nào đang mở"); return
        }
        val emailEsc = email.replace("\\", "\\\\").replace("'", "\\'")
        val passEsc  = pass.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
(function(){
  function fill(el, val) {
    try {
      var s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
      if (s && s.set) s.set.call(el, val); else el.value = val;
    } catch(e) { el.value = val; }
    el.dispatchEvent(new Event('input',  {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
  }
  var inputs = Array.from(document.querySelectorAll('input'));
  var eF = false, pF = false;
  inputs.forEach(function(el) {
    var t  = el.type || '';
    var n  = (el.name        || '').toLowerCase();
    var p  = (el.placeholder || '').toLowerCase();
    var id = (el.id          || '').toLowerCase();
    if (!eF && (t==='email' || n.includes('email') || p.includes('email') || id.includes('email') || n.includes('username') || id.includes('username'))) {
      fill(el, '${'$'}{emailEsc}'); eF = true;
    }
    if (!pF && (t==='password' || n.includes('pass') || p.includes('pass') || id.includes('pass'))) {
      fill(el, '${'$'}{passEsc}'); pF = true;
    }
  });
  return (eF || pF) ? 'ok' : 'none';
})();
        """.trimIndent()
        activeWv.evaluateJavascript(js) { result ->
            runOnUiThread {
                if (result?.contains("ok") == true)
                    toast("✅ Đã điền email & mật khẩu vào trang")
                else
                    toast("⚠️ Không tìm thấy ô nhập liệu")
            }
        }
    }


    private fun updateMailBadge() {
        runOnUiThread {
            mailFab?.text = if (unreadCount > 0) "📧${unreadCount}" else "📧"
        }
    }

    // ─── JavaScript injections ─────────────────────────────────────────────────

    private fun buildFieldFocusJs(): String = """
(function(){
  function detectType(el){
    var t=el.type||'',n=(el.name||'').toLowerCase(),p=(el.placeholder||'').toLowerCase();
    if(t==='email'||n.includes('email')||p.includes('email')) return 'email';
    if(t==='password'||n.includes('pass')||p.includes('pass')) return 'password';
    if(n.includes('user')||p.includes('user')||n.includes('username')) return 'username';
    return 'text';
  }
  function attach(el){
    if(el._rem2)return; el._rem2=true;
    el.addEventListener('focus',function(){if(window.REM2)window.REM2.onFieldFocus(detectType(el));});
    el.addEventListener('blur',function(){if(window.REM2)window.REM2.onFieldFocus('');});
  }
  document.querySelectorAll('input').forEach(attach);
  new MutationObserver(function(){
    document.querySelectorAll('input:not([data-rem2])').forEach(function(el){
      el.setAttribute('data-rem2','1'); attach(el);
    });
  }).observe(document.body||document.documentElement,{childList:true,subtree:true});
})();
    """.trimIndent()

    private fun buildCaptchaFixJs(): String = """
(function(){
  // Patch fetch to detect captcha errors and auto-refresh
  var _fetch = window.fetch;
  window.fetch = function(url, opts) {
    return _fetch.apply(this, arguments).then(function(r) {
      var c = r.clone();
      c.text().then(function(t) {
        if(t.indexOf('captcha token is invalid') >= 0 || t.indexOf('code:1') >= 0) {
          // Try to click retry button
          setTimeout(function() {
            document.querySelectorAll('button').forEach(function(b) {
              var bt = (b.textContent||'').toLowerCase();
              if(bt.indexOf('refresh') >= 0 || bt.indexOf('retry') >= 0 || bt.indexOf('try again') >= 0) b.click();
            });
            // Reload captcha iframe if present
            document.querySelectorAll('iframe[src*="recaptcha"],iframe[src*="hcaptcha"]').forEach(function(f) {
              f.src = f.src;
            });
          }, 500);
        }
      }).catch(function(){});
      return r;
    });
  };

  // Patch XMLHttpRequest for same detection
  var _XHRopen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function() {
    this.addEventListener('load', function() {
      try {
        if(this.responseText && this.responseText.indexOf('captcha token is invalid') >= 0) {
          setTimeout(function() {
            var f = document.querySelector('form');
            if(f) f.dispatchEvent(new Event('submit',{bubbles:true}));
          }, 1000);
        }
      } catch(e){}
    });
    return _XHRopen.apply(this, arguments);
  };

  // Ensure grecaptcha timing
  var _grc = window.grecaptcha;
  Object.defineProperty(window, 'grecaptcha', {
    get: function() { return _grc; },
    set: function(v) {
      _grc = v;
      if(v && v.ready) {
        var _r = v.ready.bind(v);
        v.ready = function(cb) { setTimeout(function() { _r(cb); }, 150); };
      }
    }, configurable: true
  });
})();
    """.trimIndent()

    private fun buildAntiDetectJs(d: DeviceProfile): String {
        val noise = (1..8).random()
        val lang = if (d.timezone.startsWith("Asia/Ho_Chi_Minh")) "vi-VN" else "en-US"
        return """
(function(){
  try{Object.defineProperty(navigator,'webdriver',{get:()=>undefined,configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'platform',{get:()=>'Linux armv8l',configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'userAgent',{get:()=>'${d.ua}',configurable:true});}catch(e){}
  var lang='$lang';
  try{Object.defineProperty(navigator,'language',{get:()=>lang,configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'languages',{get:()=>[lang,'en-US','en'],configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'deviceMemory',{get:()=>${d.deviceMemory},configurable:true});}catch(e){}
  try{Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>${d.hardwareConcurrency},configurable:true});}catch(e){}
  try{
    var fp=[{name:'Chrome PDF Plugin',filename:'internal-pdf-viewer',description:'PDF'},{name:'Chrome PDF Viewer',filename:'mhjfbmdgcfjbbpaeojofohoefgiehjai',description:''},{name:'Native Client',filename:'internal-nacl-plugin',description:''}];
    Object.defineProperty(navigator,'plugins',{get:()=>Object.assign(fp,{item:function(i){return fp[i];},namedItem:function(n){return fp.find(function(p){return p.name===n;})||null;},length:fp.length}),configurable:true});
    Object.defineProperty(navigator,'mimeTypes',{get:()=>({length:2,item:function(){return null;}}),configurable:true});
  }catch(e){}
  try{Object.defineProperty(screen,'width',{get:()=>${d.screenW},configurable:true});}catch(e){}
  try{Object.defineProperty(screen,'height',{get:()=>${d.screenH},configurable:true});}catch(e){}
  try{Object.defineProperty(screen,'availWidth',{get:()=>${d.screenW},configurable:true});}catch(e){}
  try{Object.defineProperty(screen,'availHeight',{get:()=>${d.screenH - 48},configurable:true});}catch(e){}
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
    WebGLRenderingContext.prototype.getParameter=function(param){
      if(param===37445)return 'Qualcomm Technologies, Inc.';
      if(param===37446)return 'Adreno (TM) 740';
      return _wgl.call(this,param);
    };
  }catch(e){}
})();
        """.trimIndent()
    }

    // ─── Auto-fill & Onboarding ────────────────────────────────────────────────

    private fun injectSignupForm(webView: WebView, email: String, username: String, password: String, fullName: String) {
        if (email.isEmpty()) return
        val js = """
(function(){
  if(window._rem2AutoFill) return 'running';
  window._rem2AutoFill = true;
  var OAUTH=['google','github','facebook','apple','microsoft','twitter','x ','with x'];
  function fill(el,val){
    try{var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
    if(s&&s.set){s.set.call(el,val);}else{el.value=val;}
    ['input','change','blur'].forEach(function(ev){el.dispatchEvent(new Event(ev,{bubbles:true}));});}
    catch(e){el.value=val;}
  }
  function findVisible(selectors){
    for(var i=0;i<selectors.length;i++){
      var els=document.querySelectorAll(selectors[i]);
      for(var j=0;j<els.length;j++){
        var r=els[j].getBoundingClientRect();
        if(r.width>10&&r.height>10&&r.top>=0&&r.top<window.innerHeight)return els[j];
      }
    }
    return null;
  }
  function clickNext(){
    var btns=Array.from(document.querySelectorAll('button[type="submit"],button')).filter(function(b){
      if(!b.offsetParent||b.offsetWidth===0)return false;
      var t=(b.textContent||'').toLowerCase().trim();
      return !OAUTH.some(function(k){return t.indexOf(k)>=0;});
    });
    for(var i=0;i<btns.length;i++){
      var t=(btns[i].textContent||'').toLowerCase().replace(/[→>↪]/g,'').trim();
      if(t==='next'||t==='continue'||t.indexOf('next')>=0||t.indexOf('get started')>=0||(t.indexOf('continue')>=0&&t.indexOf('with')<0)){
        btns[i].click();return true;
      }
    }
    var form=document.querySelector('form');
    if(form){form.dispatchEvent(new Event('submit',{bubbles:true}));return true;}
    return false;
  }
  var phase=0,ticks=0,MAX=600;
  var tid=setInterval(function(){
    ticks++;
    if(ticks>MAX){clearInterval(tid);window._rem2AutoFill=false;return;}
    if(phase===0){
      var el=findVisible(['input[type="email"]','input[name="email"]','input[autocomplete="email"]','input[placeholder*="mail" i]']);
      if(el&&el.value!=='$email'){fill(el,'$email');phase=1;setTimeout(function(){clickNext();},800);}
    } else if(phase===1){
      var pe=findVisible(['input[type="password"]']);
      if(pe){
        fill(pe,'$password');
        var ue=findVisible(['input[name="username"]','input[autocomplete="username"]','input[placeholder*="username" i]']);
        if(ue&&ue.value!=='$username')fill(ue,'$username');
        phase=2;setTimeout(function(){clickNext();},800);
      } else if(ticks%8===0)clickNext();
    } else if(phase===2){
      if(!findVisible(['input[type="password"]'])){clearInterval(tid);window._rem2AutoFill=false;}
    }
  },400);
  return 'started';
})()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectOnboardingStep(webView: WebView, fullName: String) {
        val firstName = fullName.substringBefore(" ").ifEmpty { "User" }
        val lastName  = fullName.substringAfter(" ", "").ifEmpty { "Dev" }
        val js = """
(function(){
  function setVal(el,val){
    try{var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
    s.call(el,val);el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));el.dispatchEvent(new Event('blur',{bubbles:true}));}
    catch(e){el.value=val;}
  }
  function vis(el){return el.offsetParent!==null&&el.style.display!=='none'&&el.offsetWidth>0;}
  var OB=['google','github','facebook','apple','microsoft','twitter',' x '];
  function noOb(t){return !OB.some(function(k){return(' '+t+' ').indexOf(k)>=0;});}

  // Select free plan if visible
  var planClicked=false;
  document.querySelectorAll('button,a,[role="button"]').forEach(function(el){
    if(planClicked)return;
    var t=(el.textContent||'').toLowerCase();
    if(t.indexOf('starter')>=0||t.indexOf('free')>=0||t.indexOf('continue with free')>=0){el.click();planClicked=true;}
  });
  if(planClicked)return 'plan-selected';

  // Fill name fields
  document.querySelectorAll('input[name="full_name"],input[name="fullName"],input[name="name"]').forEach(function(el){if(vis(el))setVal(el,'$fullName');});
  document.querySelectorAll('input[name="first_name"],input[name="firstName"],input[placeholder*="first" i]').forEach(function(el){if(vis(el))setVal(el,'$firstName');});
  document.querySelectorAll('input[name="last_name"],input[name="lastName"],input[placeholder*="last" i]').forEach(function(el){if(vis(el))setVal(el,'$lastName');});

  // Click random radio/checkbox options
  var opts=Array.from(document.querySelectorAll('[data-testid*="option"],[class*="SelectableCard"],[class*="selectable"],[class*="choice"],[role="checkbox"],[role="radio"]'));
  if(opts.length>0){var pick=Math.min(opts.length,Math.floor(Math.random()*2)+1);for(var ci=0;ci<pick;ci++)opts[Math.floor(Math.random()*opts.length)].click();}
  var radios=document.querySelectorAll('input[type="radio"]');
  if(radios.length>0)radios[Math.floor(Math.random()*radios.length)].click();

  // Click Next/Continue
  var clicked=false;
  function tryClick(){
    var candidates=[];
    Array.from(document.querySelectorAll('button[type="submit"]')).filter(vis).forEach(function(b){candidates.push(b);});
    Array.from(document.querySelectorAll('button')).filter(vis).forEach(function(b){if(!candidates.includes(b))candidates.push(b);});
    for(var i=0;i<candidates.length;i++){
      var t=(candidates[i].textContent||'').toLowerCase().replace(/[→>]/g,'').trim();
      if(!noOb(t))continue;
      var ok=t==='next'||t==='continue'||t.indexOf('next')>=0||t.indexOf('get started')>=0
             ||t.indexOf('finish')>=0||t.indexOf('done')>=0
             ||(t.indexOf('continue')>=0&&t.indexOf('with')<0&&t.length<22)
             ||(t.indexOf('start')>=0&&t.indexOf('starter')<0);
      if(ok){candidates[i].click();clicked=true;break;}
    }
    return clicked;
  }
  tryClick();
  if(!clicked)setTimeout(function(){tryClick();},600);
  setTimeout(function(){if(!clicked)tryClick();},1500);
  return clicked?'next-clicked':'filled';
})()
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val r = result?.trim('"') ?: ""
            if (r == "plan-selected") toast("Đã chọn gói miễn phí ✓")
            webView.postDelayed({
                webView.evaluateJavascript("window.location.pathname") { path ->
                    val p = path?.trim('"') ?: ""
                    if (p.contains("onboarding") || p.contains("plans")) {
                        injectOnboardingStep(webView, fullName)
                    }
                }
            }, 5000)
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
                binding.tvMailStatus.text = "✅ $mailEmail  (nhấn để copy)"
                binding.tvBottomStatus.text = mailEmail
            }
            return
        }

        withContext(Dispatchers.Main) {
            binding.pbMail.visibility = View.VISIBLE
            binding.tvMailStatus.text = "🔄 Đang tạo Mail.tm..."
        }

        try {
            val domains = getMailDomains()
            if (domains.isEmpty()) {
                withContext(Dispatchers.Main) { binding.tvMailStatus.text = "❌ Mail.tm không khả dụng" }
                return
            }
            val user  = "rem${(1000..9999).random()}"
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
                binding.tvMailStatus.text = "✅ $email  (nhấn để copy)"
                binding.tvBottomStatus.text = email
                toast("Mail.tm sẵn sàng: $email")
            }
            addOrUpdateAccount(AccountEntry(email = email, password = pass, username = autoUsername))

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                binding.pbMail.visibility = View.GONE
                binding.tvMailStatus.text = "❌ Lỗi: ${e.message?.take(40)}"
            }
        }
    }

    private fun setupAutoCredentials(email: String) {
        autoEmail    = email
        autoUsername = "user${(10000..99999).random()}"
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
            (0 until arr.length()).map { arr.getJSONObject(it) }
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
            while (isActive) {
                fetchMail()
                delay(5000)
            }
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
                    val sub = msg.optString("subject","(không có tiêu đề)")
                    if (id.isNotEmpty() && !seenIds.contains(id)) {
                        seenIds.add(id); newMsgs.add(id to sub)
                    }
                }
                if (newMsgs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        addMailItems(newMsgs)
                        // Auto-open verify section when new mail arrives
                        if (binding.mailPanel.visibility == View.VISIBLE && panelSection != 1) {
                            switchPanelSection(1)
                            toast("📧 Email xác nhận mới!")
                        } else if (binding.mailPanel.visibility == View.GONE) {
                            toast("📧 Email xác nhận mới — mở panel để xem")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun addMailItems(msgs: List<Pair<String,String>>) {
        val dp = resources.displayMetrics.density
        unreadCount += msgs.size
        updateMailBadge()
        msgs.forEach { (id, subject) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())
                setOnClickListener { lifecycleScope.launch { loadVerifyLink(id) } }
            }
            val tv = TextView(this).apply {
                text = "📨 $subject"
                textSize = 12f
                setTextColor(0xFFE0E0E0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            binding.mailList.addView(row, 0)
            val div = View(this).apply {
                setBackgroundColor(0xFF1F2937.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            }
            binding.mailList.addView(div, 1)
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
        val keywords = listOf("verify","confirm","activate","click here","account")
        val urlRegex = Regex("""https?://[^\s<>"']+""")
        val link = urlRegex.findAll(body)
            .map { it.value.trimEnd('.',')',']','"','\'') }
            .firstOrNull { url -> keywords.any { kw -> url.lowercase().contains(kw) } }
            ?: urlRegex.findAll(body).firstOrNull { it.value.contains("replit.com") }?.value

        if (link != null) {
            withContext(Dispatchers.Main) {
                binding.verifyWebView.visibility = View.VISIBLE
                binding.mailListScroll.visibility = View.GONE
                binding.verifyWebView.loadUrl(link)
                switchPanelSection(1)
                toast("Đang mở link xác nhận...")
            }
        } else {
            withContext(Dispatchers.Main) { toast("Không tìm thấy link xác nhận") }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun requestStoragePermissionIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO)
        } else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 200)
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
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
        super.onDestroy()
        stopPolling()
        tabs.forEach { it.webView?.destroy() }
    }

    override fun onPause() { super.onPause(); tabs.forEach { it.webView?.onPause() } }
    override fun onResume() { super.onResume(); tabs.forEach { it.webView?.onResume() } }
}
