@file:Suppress("DEPRECATION")
package com.rem2.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.webkit.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
        private const val MAILTM       = "https://api.mail.tm"
        // Fixed password như Python tool — đơn giản, không random
        private const val MAIL_PASS    = "Mailtm2025Tool"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
        private const val COCCOC_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) coc_coc_browser/109.0.0.0 Chrome/123.0.0.0 Mobile Safari/537.36"

        // Lấy hydra:member hoặc array thẳng — giống _members() trong Python
        fun jsonMembers(raw: String): JSONArray {
            return try {
                // Thử parse như object có hydra:member
                val obj = JSONObject(raw)
                obj.optJSONArray("hydra:member") ?: JSONArray()
            } catch (_: Exception) {
                try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            }
        }

        fun randUser(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..10).map { chars.random() }.joinToString("")
        }

        fun randUsername(): String {
            val adj  = listOf("cool","fast","dark","blue","wild","swift","calm","bright")
            val noun = listOf("fox","hawk","wolf","bear","lion","ace","star","byte")
            return adj.random() + noun.random() + (1000..9999).random()
        }

        // Skip URLs — không phải verify link
        private val SKIP_KW = listOf(
            "unsubscribe","opt-out","privacy","policy","terms",
            "facebook.com","twitter.com","instagram.com","linkedin.com",
            "youtube.com","apple.com","google.com","play.google"
        )
        // Verify URL keywords
        private val VERIFY_URL_KW = listOf(
            "verify","confirm","activate","validation","oobCode","action-code",
            "token=","code=","activate=","email_token","verify_email","email-confirm"
        )
        // Verify label keywords
        private val VERIFY_LABEL_KW = listOf(
            "verify","confirm","activate","click here","complete","valid",
            "xac nhan","kich hoat"
        )

        fun isVerifyLink(url: String, label: String): Boolean {
            if (SKIP_KW.any { url.contains(it, ignoreCase = true) }) return false
            if (VERIFY_LABEL_KW.any { label.contains(it, ignoreCase = true) }) return true
            if (VERIFY_URL_KW.any { url.contains(it, ignoreCase = true) }) return true
            return false
        }

        // Extract all <a href> tags from HTML then pick best verify link
        fun extractVerifyLink(html: String): String? {
            // Parse all href values from <a href="..."> tags
            val links = mutableListOf<Pair<String, String>>()
            val hrefRegex = Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            hrefRegex.findAll(html).forEach { m ->
                val url   = m.groupValues[1].trim()
                val label = m.groupValues[2].replace(Regex("<[^>]+>"), " ").trim()
                if (url.startsWith("http")) links.add(Pair(url, label))
            }
            // Also catch bare https links not in <a> tags
            if (links.isEmpty()) {
                Regex("""https://[^\s"'<>]+""").findAll(html).forEach {
                    links.add(Pair(it.value, it.value))
                }
            }

            // Find best verify link: prefer label match, then URL match
            val candidates = links.filter { (u, l) -> isVerifyLink(u, l) }
            // Prefer label-matched ones first
            val labelMatch = candidates.firstOrNull { (_, l) ->
                VERIFY_LABEL_KW.any { kw -> l.contains(kw, ignoreCase = true) }
            }
            val raw = labelMatch?.first ?: candidates.firstOrNull()?.first
            return raw?.let { decodeHtmlEntities(it) }
        }

        // Email HTML thuong encode & thanh &amp; trong href — neu khong decode,
        // link se bi sai (vd: ...?token=xxx&amp;uid=yyy) va Replit tra ve "Page not found"
        fun decodeHtmlEntities(url: String): String {
            return url
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&#x26;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&#61;", "=")
                .trim()
        }

        // Danh sách URL để save/restore cookie khi chuyển tab
        private val COOKIE_URLS = listOf(
            "https://replit.com",
            "https://replit.com/",
            "https://replit.com/~",
            "https://replit.com/repls",
            "https://api.mail.tm"
        )
    }

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val accounts = mutableListOf<AccountEntry>()
    private var panelOpen     = true
    private var showingVerify = false

    private var autoEmail    = ""
    private var autoUsername = ""
    private var mailtmToken  = ""
    private var flowRunning  = false

    // ── Tab management ────────────────────────────────────────────────────────
    // currentTab = tab đang hiển thị (1 hoặc 2)
    // Tab 1 = webView (session Replit / tạo tài khoản)
    // Tab 2 = webView2 (session hoàn toàn độc lập, bắt đầu từ signup)
    private var currentTab       = 1
    private var tab2Initialized  = false
    private val tab1Cookies      = mutableMapOf<String, String>()
    private val tab2Cookies      = mutableMapOf<String, String>()

    private val inboxMessages = mutableListOf<Pair<String, String>>()

    // Ho tro upload file trong WebView (vd: nut "Upload a file" trong Replit Agent chat)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
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

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CookieManager.getInstance().setAcceptCookie(true)
        loadAccounts()
        setupHeader()
        setupWebView()
        setupWebView2()
        setupSwipeAndGestures()
        setupVerifyWebView()
        log("San sang. Nhan 'Tao tai khoan Replit tu dong' de bat dau.")
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun setupHeader() {
        // Thu gon / mo rong panel Log + Xac thuc (giu nguyen vi tri o TREN)
        binding.btnToggleLog.setOnClickListener {
            panelOpen = !panelOpen
            binding.logPanel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        }
        // Nut Tai lai trang — reload tab hiện tại
        binding.btnRefresh.setOnClickListener {
            if (currentTab == 1) {
                binding.swipeRefresh.isRefreshing = true
                val curUrl = binding.webView.url
                if (curUrl.isNullOrBlank() || curUrl == "about:blank") {
                    binding.webView.loadUrl("https://replit.com/signup")
                } else {
                    binding.webView.reload()
                }
            } else {
                binding.swipeRefresh2.isRefreshing = true
                val curUrl = binding.webView2.url
                if (curUrl.isNullOrBlank() || curUrl == "about:blank") {
                    binding.webView2.loadUrl("https://replit.com/signup")
                } else {
                    binding.webView2.reload()
                }
            }
        }
        binding.tabLog.setOnClickListener    { switchPanelTab(false) }
        binding.tabVerify.setOnClickListener { switchPanelTab(true)  }
        binding.btnCreateAccount.setOnClickListener { startCreateAccountFlow() }

        // ── URL bar: nhấn Go trên bàn phím → load trang ──────────────────
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToUrl(binding.etUrl.text.toString())
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
                binding.etUrl.clearFocus()
                true
            } else false
        }
        // Chọn toàn bộ text khi focus vào URL bar (dễ xoá/gõ đè)
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.etUrl.selectAll()
        }

        // ── Tab count button: nhấn để chuyển qua lại Tab 1 ↔ Tab 2 ──────
        // Số hiện = tab đang xem; nhấn → chuyển sang tab kia
        binding.btnTabCount.setOnClickListener {
            switchBrowserTab(if (currentTab == 1) 2 else 1)
        }
    }

    // ─── Browser Tab Switching (Tab 1 ↔ Tab 2 với session độc lập) ───────────

    private fun saveCookies(destination: MutableMap<String, String>) {
        destination.clear()
        val cm = CookieManager.getInstance()
        for (url in COOKIE_URLS) {
            cm.getCookie(url)?.takeIf { it.isNotEmpty() }?.let { destination[url] = it }
        }
    }

    private fun restoreCookies(source: Map<String, String>) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        for ((url, cookies) in source) {
            // getCookie trả về "a=1; b=2; c=3" — set từng cookie một
            cookies.split(";").forEach { c ->
                val trimmed = c.trim()
                if (trimmed.isNotEmpty()) cm.setCookie(url, trimmed)
            }
        }
        cm.flush()
    }

    private fun switchBrowserTab(tab: Int) {
        if (tab == currentTab) return
        runOnUiThread {
            if (tab == 2) {
                // Chuyển sang Tab 2
                saveCookies(tab1Cookies)
                if (!tab2Initialized) {
                    // Lần đầu mở Tab 2: xóa cookie → Tab 2 bắt đầu fresh (chưa đăng nhập)
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    tab2Initialized = true
                    binding.webView2.loadUrl("https://replit.com/signup")
                    binding.etUrl.setText("https://replit.com/signup")
                } else {
                    // Restore cookies của Tab 2
                    restoreCookies(tab2Cookies)
                    val url = binding.webView2.url
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        binding.etUrl.setText(url)
                    }
                }
                binding.swipeRefresh.visibility  = View.GONE
                binding.swipeRefresh2.visibility = View.VISIBLE
                currentTab = 2
                binding.btnTabCount.text = "2"
            } else {
                // Chuyển về Tab 1
                saveCookies(tab2Cookies)
                restoreCookies(tab1Cookies)
                binding.swipeRefresh2.visibility = View.GONE
                binding.swipeRefresh.visibility  = View.VISIBLE
                val url = binding.webView.url
                if (!url.isNullOrBlank() && url != "about:blank") {
                    binding.etUrl.setText(url)
                } else {
                    binding.etUrl.setText("")
                }
                currentTab = 1
                binding.btnTabCount.text = "1"
            }
        }
    }

    // Chuẩn hóa URL: tự thêm https://, hoặc tìm Google nếu là từ khoá
    private fun navigateToUrl(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://www.google.com/search?q=${Uri.encode(s)}"
        }
        val wv = if (currentTab == 1) binding.webView else binding.webView2
        wv.loadUrl(url)
        binding.etUrl.setText(url)
    }

    // Log co the copy bang cach nhan giu (long-press) va chon van ban — tvLog da bat textIsSelectable

    private fun startCreateAccountFlow() {
        if (flowRunning) { Toast.makeText(this, "Dang xu ly, vui long doi...", Toast.LENGTH_SHORT).show(); return }
        flowRunning = true
        inboxMessages.clear()
        renderVerifyPanel()
        binding.btnCreateAccount.isEnabled = false
        binding.btnCreateAccount.text = "\u23F3 Dang tao tai khoan..."
        binding.tvLog.text = ""
        switchPanelTab(false)
        // Dang xuat / xoa het session cu (cookie, cache, localStorage) TRUOC khi tao acc moi,
        // neu khong WebView se con dang nhap acc cu -> tuong nham la "xac thuc thanh cong" lien tuc
        clearWebSession()
        lifecycleScope.launch {
            ensureAccount()
            flowRunning = false
            withContext(Dispatchers.Main) {
                binding.btnCreateAccount.isEnabled = true
                binding.btnCreateAccount.text = "\uD83D\uDD04 Tao tai khoan moi"
            }
        }
    }

    // Xoa toan bo cookie/cache/localStorage cua WebView chinh — dam bao "dang xuat" that su
    // truoc khi bat dau tao tai khoan moi, tranh dinh session Replit cu.
    private fun clearWebSession() {
        val wv = binding.webView
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        // Xóa cả cookies đã lưu cho tab 1
        tab1Cookies.clear()
        wv.clearCache(true)
        wv.clearHistory()
        wv.clearFormData()
        WebStorage.getInstance().deleteAllData()
        wv.evaluateJavascript("(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}})();", null)
        wv.loadUrl("about:blank")
        log("Da dang xuat / xoa session cu, chuan bi tao tai khoan moi...")
    }

    // switchPanelTab: chuyển giữa 2 tab trong PANEL LOG (Log ↔ Xác thực Mail.tm)
    // Khác với switchBrowserTab (Tab 1 ↔ Tab 2 browser)
    private fun switchPanelTab(toVerify: Boolean) {
        showingVerify = toVerify
        if (toVerify) {
            binding.logScroll.visibility     = View.GONE
            binding.verifyWebView.visibility = View.VISIBLE
            binding.tabLog.setBackgroundColor(0xFFF3F4F6.toInt())
            binding.tabLog.setTextColor(0xFF6B7280.toInt())
            binding.tabVerify.setBackgroundColor(0xFFFFFFFF.toInt())
            binding.tabVerify.setTextColor(0xFF1D4ED8.toInt())
        } else {
            binding.logScroll.visibility     = View.VISIBLE
            binding.verifyWebView.visibility = View.GONE
            binding.tabLog.setBackgroundColor(0xFFFFFFFF.toInt())
            binding.tabLog.setTextColor(0xFF1D4ED8.toInt())
            binding.tabVerify.setBackgroundColor(0xFFF3F4F6.toInt())
            binding.tabVerify.setTextColor(0xFF6B7280.toInt())
        }
    }

    // ─── Logging ──────────────────────────────────────────────────────────────

    private fun log(msg: String) = runOnUiThread {
        val cur = binding.tvLog.text.toString()
        binding.tvLog.text = if (cur.isEmpty()) msg else (cur + "\n" + msg)
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

    // ─── Main WebView (Tab 1) ─────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        binding.swipeRefresh.isEnabled = false
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        wv.isScrollbarFadingEnabled = true
        wv.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            useWideViewPort          = true
            loadWithOverviewMode     = true
            setSupportZoom(true)
            builtInZoomControls      = true
            displayZoomControls      = false
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString          = COCCOC_UA
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode                = WebSettings.LOAD_DEFAULT
            textZoom                 = 100
            safeBrowsingEnabled      = false
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (currentTab == 1) {
                    runOnUiThread {
                        binding.swipeRefresh.isRefreshing = false
                        // Cập nhật URL bar theo trang hiện tại
                        if (!binding.etUrl.isFocused && url != null && url != "about:blank") {
                            binding.etUrl.setText(url)
                        }
                    }
                }
                // Sau khi verify link chay trong main WebView → detect thanh cong
                if (url.contains("replit.com") && !url.contains("verify") &&
                    !url.contains("confirm") && !url.contains("signup") &&
                    !url.contains("login") && url != "about:blank") {
                    log("✓ Xac thuc thanh cong! Da vao dashboard Replit.")
                    CookieManager.getInstance().flush()
                    // Tu dong bam Next/Continue/Skip qua cac cau hoi onboarding sau khi xac thuc
                    injectAutoContinue(v)
                }
                val isSignup = url.contains("signup") || url.contains("login") || url.contains("register")
                if (isSignup && autoEmail.isNotEmpty()) {
                    // Inject immediately and again after 1s, 2s, 4s
                    injectAutoFill(v)
                    v.postDelayed({ injectAutoFill(v) }, 1000)
                    v.postDelayed({ injectAutoFill(v) }, 2500)
                    v.postDelayed({ injectAutoFill(v) }, 5000)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.contains("verify") || url.contains("confirm-email") || url.contains("oobCode")) {
                    openVerifyTab(url)
                    return true
                }
                return false
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (currentTab == 1) {
                    runOnUiThread {
                        binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                        binding.progressBar.progress   = newProgress
                    }
                }
            }

            // Ho tro nut "Upload a file" / <input type=file> trong Replit web (vd: Agent chat)
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    log("Loi mo file chooser: ${e.message}")
                    false
                }
            }
        }

        // KHONG tu dong load signup luc khoi dong app nua.
        wv.loadUrl("about:blank")
    }

    // ─── Secondary WebView (Tab 2 — session độc lập) ─────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView2() {
        val wv = binding.webView2
        binding.swipeRefresh2.isEnabled = false
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        wv.isScrollbarFadingEnabled = true
        wv.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            useWideViewPort          = true
            loadWithOverviewMode     = true
            setSupportZoom(true)
            builtInZoomControls      = true
            displayZoomControls      = false
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString          = COCCOC_UA
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode                = WebSettings.LOAD_DEFAULT
            textZoom                 = 100
            safeBrowsingEnabled      = false
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (currentTab == 2) {
                    runOnUiThread {
                        binding.swipeRefresh2.isRefreshing = false
                        if (!binding.etUrl.isFocused && url != null && url != "about:blank") {
                            binding.etUrl.setText(url)
                        }
                    }
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (currentTab == 2) {
                    runOnUiThread {
                        binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                        binding.progressBar.progress   = newProgress
                    }
                }
            }
        }

        // Tab 2 bắt đầu blank; nội dung được load khi user lần đầu switch sang tab 2
        wv.loadUrl("about:blank")
    }

    // React-compatible fill: use native setter + bubble input/change events
    private fun injectAutoFill(wv: WebView) {
        if (autoEmail.isEmpty()) return
        val e = autoEmail.replace("'", "\\'")
        val p = MAIL_PASS.replace("'", "\\'")
        val u = autoUsername.replace("'", "\\'")

        val js = StringBuilder()
        js.append("(function(){")
        // Native setter để bypass React's synthetic value tracking
        js.append("function fillReact(el,val){")
        js.append("  if(!el)return;")
        js.append("  var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');")
        js.append("  if(setter&&setter.set){setter.set.call(el,val);}else{el.value=val;}")
        js.append("  el.dispatchEvent(new Event('input',{bubbles:true,cancelable:true}));")
        js.append("  el.dispatchEvent(new Event('change',{bubbles:true,cancelable:true}));")
        js.append("  el.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true}));")
        js.append("  el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));")
        js.append("}")
        // Find email: try multiple selectors
        js.append("var emailEl=document.querySelector('input[type=email]')")
        js.append("  ||document.querySelector('input[name*=email]')")
        js.append("  ||document.querySelector('input[placeholder*=mail]');")
        js.append("fillReact(emailEl,'").append(e).append("');")
        // Find password
        js.append("var passEl=document.querySelector('input[type=password]')")
        js.append("  ||document.querySelector('input[name*=pass]');")
        js.append("fillReact(passEl,'").append(p).append("');")
        // Find username (if present)
        js.append("var userEl=document.querySelector('input[name*=user]')")
        js.append("  ||document.querySelector('input[placeholder*=user]')")
        js.append("  ||document.querySelector('input[placeholder*=User]');")
        js.append("fillReact(userEl,'").append(u).append("');")
        js.append("})()")

        wv.evaluateJavascript(js.toString(), null)
    }

    // ─── Pull-to-refresh + swipe gestures ────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeAndGestures() {
        binding.swipeRefresh.setColorSchemeColors(0xFF1D4ED8.toInt(), 0xFF60A5FA.toInt())
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.swipeRefresh.isEnabled = false

        binding.swipeRefresh2.setColorSchemeColors(0xFF1D4ED8.toInt(), 0xFF60A5FA.toInt())
        binding.swipeRefresh2.setOnRefreshListener { binding.webView2.reload() }
        binding.swipeRefresh2.isEnabled = false
    }

    // Tu dong bam qua cac man hinh "cau hoi" onboarding (Welcome / Continue / Next / Skip...)
    private fun injectAutoContinue(wv: WebView) {
            val js = """
                (function(){
                  if (window.__rem2AutoContinue) return;
                  window.__rem2AutoContinue = true;
                  var KEYWORDS = ['continue','next','skip','get started','let\'s go','done','finish','i agree','agree','ok','got it'];
                  var ONBOARDING_HEADINGS = [
                    'how did you hear about replit',
                    'what best describes you',
                    'what is your role',
                    'what\'s your role',
                    'select all that apply',
                    'tell us about yourself',
                    'what brings you to replit'
                  ];
                  var STOP_HEADINGS = ['what do you want to make', 'what should we build', 'what are we building'];
                  var EXCLUDE_KW = ['back','log in','login','create account','upgrade','sign in','sign up','close','cancel','upload'];

                  function headingText(){
                    var hs = document.querySelectorAll('h1,h2,h3');
                    var txt = '';
                    for (var i=0;i<hs.length;i++) txt += ' ' + (hs[i].innerText||'').toLowerCase();
                    return txt;
                  }

                  function tryClickContinue(){
                    try {
                      var els = document.querySelectorAll('button, a[role="button"], [role="button"], input[type=submit]');
                      for (var i=0;i<els.length;i++){
                        var el = els[i];
                        if (el.disabled) continue;
                        var txt = (el.innerText || el.value || '').trim().toLowerCase();
                        if (!txt) continue;
                        for (var k=0;k<KEYWORDS.length;k++){
                          if (txt === KEYWORDS[k] || txt.indexOf(KEYWORDS[k]) !== -1) {
                            el.click();
                            return true;
                          }
                        }
                      }
                    } catch(e) {}
                    return false;
                  }

                  function tryClickOnboardingChoice(){
                    try {
                      var h = headingText();
                      for (var s=0;s<STOP_HEADINGS.length;s++){
                        if (h.indexOf(STOP_HEADINGS[s]) !== -1) return 'stop';
                      }
                      var isKnownQuestion = false;
                      for (var q=0;q<ONBOARDING_HEADINGS.length;q++){
                        if (h.indexOf(ONBOARDING_HEADINGS[q]) !== -1) { isKnownQuestion = true; break; }
                      }
                      if (!isKnownQuestion) return false;
                      var els = document.querySelectorAll('button, [role="button"]');
                      for (var i=0;i<els.length;i++){
                        var el = els[i];
                        if (el.disabled) continue;
                        var txt = (el.innerText || '').trim().toLowerCase();
                        if (!txt || txt.length > 40) continue;
                        var bad = false;
                        for (var e2=0;e2<EXCLUDE_KW.length;e2++){
                          if (txt.indexOf(EXCLUDE_KW[e2]) !== -1) { bad = true; break; }
                        }
                        if (bad) continue;
                        el.click();
                        return true;
                      }
                    } catch(e) {}
                    return false;
                  }

                  var count = 0;
                  var iv = setInterval(function(){
                    count++;
                    var stop = tryClickOnboardingChoice();
                    if (stop === 'stop') { clearInterval(iv); return; }
                    tryClickContinue();
                    if (count > 50) clearInterval(iv);
                  }, 1200);
                })();
            """.trimIndent()
            wv.evaluateJavascript(js, null)
        }

    // ─── Verify WebView (mini browser inside panel) ───────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        val vwv = binding.verifyWebView
        vwv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
        }
        vwv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = true
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
        }
        renderVerifyPanel()
    }

    private fun renderVerifyPanel() = runOnUiThread {
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;margin:10px;color:#111827'>")
        sb.append("<h3 style='color:#1D4ED8;margin:0 0 6px 0;font-size:15px'>\uD83D\uDCEC Hop thu Mail.tm</h3>")
        if (autoEmail.isNotEmpty()) {
            sb.append("<p style='color:#6B7280;font-size:11px;margin:0 0 10px 0'>").append(autoEmail).append("</p>")
        }
        if (inboxMessages.isEmpty()) {
            sb.append("<p style='color:#9CA3AF;font-size:12px'>Chua co email nao...</p>")
        } else {
            inboxMessages.forEach { (from, subject) ->
                sb.append("<div style='border-bottom:1px solid #E5E7EB;padding:8px 0'>")
                sb.append("<div style='color:#6B7280;font-size:11px'>").append(from).append("</div>")
                sb.append("<div style='font-weight:600;font-size:13px'>").append(subject).append("</div>")
                sb.append("</div>")
            }
        }
        sb.append("</body></html>")
        binding.verifyWebView.loadDataWithBaseURL(null, sb.toString(), "text/html", "UTF-8", null)
    }

    private fun openVerifyTab(url: String) = runOnUiThread {
        log("Tim thay link xac thuc — tu dong xac minh trong man hinh Replit...")
        // Man hinh TO (webView chinh) tu dong load link xac thuc
        binding.webView.loadUrl(url)
        // Man hinh nho chi cap nhat lai danh sach email
        renderVerifyPanel()
        panelOpen = true
        binding.logPanel.visibility = View.VISIBLE
        switchPanelTab(true)
    }

    // ─── Account flow ─────────────────────────────────────────────────────────

    private suspend fun ensureAccount() = withContext(Dispatchers.IO) {
        log("Bat dau...")

        // 1. Lấy domain từ mail.tm
        log("Lay domain Mail.tm...")
        val domain = try {
            val req = Request.Builder().url("$MAILTM/domains?page=1").build()
            val res = http.newCall(req).execute()
            val raw = res.body?.string() ?: "[]"
            val members = jsonMembers(raw)
            var found = ""
            for (i in 0 until members.length()) {
                val d = members.getJSONObject(i)
                if (d.optBoolean("isActive", true)) {
                    found = d.optString("domain", "")
                    if (found.isNotEmpty()) break
                }
            }
            if (found.isEmpty()) "mail.tm" else found
        } catch (e: Exception) {
            log("Domain loi: ${e.message}")
            "mail.tm"
        }
        log("Domain: $domain")

        // 2. Tạo tài khoản mail.tm
        var loginEmail = ""
        for (attempt in 1..5) {
            val user = randUser()
            val addr = "$user@$domain"
            try {
                val body = JSONObject().apply {
                    put("address", addr)
                    put("password", MAIL_PASS)
                }.toString().toRequestBody(JSON_MT)
                val req = Request.Builder().url("$MAILTM/accounts").post(body).build()
                val res = http.newCall(req).execute()
                when (res.code) {
                    201 -> { loginEmail = addr; log("Tao hop thu: $addr"); break }
                    429 -> { log("Rate limit, cho 5s..."); delay(5000) }
                    422 -> { log("422 dia chi $addr, thu lai..."); delay(1000) }
                    else -> { log("HTTP ${res.code}, thu lai..."); delay(2000) }
                }
            } catch (e: Exception) {
                log("Loi tao acc: ${e.message}")
                delay(2000)
            }
        }

        if (loginEmail.isEmpty()) {
            log("Khong tao duoc hop thu — dung")
            return@withContext
        }

        autoEmail    = loginEmail
        autoUsername = randUsername()
        log("Email: $autoEmail | Pass: $MAIL_PASS")
        log("User Replit: $autoUsername")

        withContext(Dispatchers.Main) {
            binding.webView.loadUrl("https://replit.com/signup")
        }

        // 3. Đăng nhập mail.tm lấy token
        log("Dang nhap Mail.tm...")
        for (attempt in 1..5) {
            try {
                delay(if (attempt == 1) 1500L else 3000L)
                val body = JSONObject().apply {
                    put("address", loginEmail)
                    put("password", MAIL_PASS)
                }.toString().toRequestBody(JSON_MT)
                val req = Request.Builder().url("$MAILTM/token").post(body).build()
                val res = http.newCall(req).execute()
                val tok = JSONObject(res.body?.string() ?: "{}").optString("token", "")
                if (tok.isNotEmpty()) {
                    mailtmToken = tok
                    log("Dang nhap OK")
                    break
                } else {
                    log("Token trong ($attempt/5), thu lai...")
                }
            } catch (e: Exception) {
                log("Login loi: ${e.message}")
            }
        }

        if (mailtmToken.isEmpty()) {
            log("Khong dang nhap duoc Mail.tm")
        }

        // 4. Lưu account
        accounts.add(AccountEntry(email = autoEmail, password = MAIL_PASS, username = autoUsername))
        saveAccounts()

        // 5. Mở Replit signup + auto-fill
        withContext(Dispatchers.Main) {
            log("Mo Replit signup...")
            binding.webView.loadUrl("https://replit.com/signup")
        }

        // 6. Poll email xác thực
        delay(15000)
        if (mailtmToken.isNotEmpty()) {
            pollVerification()
        } else {
            log("Bo qua poll — khong co token")
        }
    }

    private suspend fun pollVerification() = withContext(Dispatchers.IO) {
        log("Cho email xac thuc tu Replit...")
        repeat(72) { attempt ->
            delay(5000)
            try {
                val req = Request.Builder()
                    .url("$MAILTM/messages")
                    .header("Authorization", "Bearer $mailtmToken")
                    .build()
                val res  = http.newCall(req).execute()
                val raw  = res.body?.string() ?: "{}"
                val msgs = jsonMembers(raw)

                if (msgs.length() > 0) {
                    log("${msgs.length()} email trong hop thu")
                    inboxMessages.clear()
                    for (i in 0 until msgs.length()) {
                        val m2 = msgs.getJSONObject(i)
                        val fromAddr = m2.optJSONObject("from")?.optString("address", "") ?: ""
                        inboxMessages.add(Pair(fromAddr, m2.optString("subject", "(khong tieu de)")))
                    }
                    withContext(Dispatchers.Main) { renderVerifyPanel() }
                    for (i in 0 until msgs.length()) {
                        val msg  = msgs.getJSONObject(i)
                        val subj = msg.optString("subject", "")
                        val isVerify = subj.contains("verify", ignoreCase = true) ||
                            subj.contains("confirm", ignoreCase = true) ||
                            subj.contains("Replit", ignoreCase = true)
                        if (isVerify) {
                            log("Email: $subj")
                            val req2 = Request.Builder()
                                .url("$MAILTM/messages/${msg.getString("id")}")
                                .header("Authorization", "Bearer $mailtmToken")
                                .build()
                            val full = JSONObject(http.newCall(req2).execute().body?.string() ?: "{}")
                            val htmlRaw = full.opt("html")
                            val html = when (htmlRaw) {
                                is JSONArray -> (0 until htmlRaw.length()).joinToString("\n") { htmlRaw.getString(it) }
                                is String   -> htmlRaw
                                else        -> ""
                            }
                            val link = extractVerifyLink(html)
                            if (link != null) {
                                log("Tim thay link xac thuc!")
                                withContext(Dispatchers.Main) { openVerifyTab(link) }
                                return@withContext
                            } else {
                                log("Khong tim thay link")
                            }
                        }
                    }
                }
                if (attempt > 0 && attempt % 6 == 0) log("Dang cho... ${(attempt + 1) * 5}s")
            } catch (e: Exception) {
                if (attempt % 12 == 0) log("Poll loi: ${e.message}")
            }
        }
        log("Het thoi gian cho email")
    }

    // ─── Back press ───────────────────────────────────────────────────────────

    override fun onBackPressed() {
        val activeWv = if (currentTab == 1) binding.webView else binding.webView2
        when {
            panelOpen && showingVerify && binding.verifyWebView.canGoBack() ->
                binding.verifyWebView.goBack()
            panelOpen -> { binding.logPanel.visibility = View.GONE; panelOpen = false }
            activeWv.canGoBack() -> activeWv.goBack()
            else -> super.onBackPressed()
        }
    }
}
