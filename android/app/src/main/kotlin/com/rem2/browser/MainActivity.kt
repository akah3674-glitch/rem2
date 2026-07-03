@file:Suppress("DEPRECATION")
package com.rem2.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
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

// ─── Data classes ──────────────────────────────────────────────────────────────

data class TabEntry(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Replit",
    var url: String = "https://replit.com",
    var webView: WebView? = null
)

data class AccountEntry(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val password: String,
    val username: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Main Activity ─────────────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS          = "rem2_prefs"
        private const val KEY_ACCOUNTS   = "accounts_v3"
        private const val KEY_MAIL_EMAIL = "mail_email"
        private const val KEY_MAIL_PASS  = "mail_pass"
        private const val KEY_MAIL_TOKEN = "mail_token"
        private const val MAILTM         = "https://api.mail.tm"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()

        // Color palette
        val CLR_SURFACE  = Color.WHITE
        val CLR_PRIMARY  = Color.parseColor("#1D4ED8")
        val CLR_ACCENT   = Color.parseColor("#2563EB")
        val CLR_GREEN    = Color.parseColor("#16A34A")
        val CLR_RED      = Color.parseColor("#DC2626")
        val CLR_TEXT_PRI = Color.parseColor("#111827")
        val CLR_TEXT_SEC = Color.parseColor("#6B7280")
        val CLR_BORDER   = Color.parseColor("#E5E7EB")
        val CLR_TAG_BG   = Color.parseColor("#EFF6FF")

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun dp(ctx: Context, n: Float) =
            (n * ctx.resources.displayMetrics.density + 0.5f).toInt()

        fun randEmail(domain: String): String {
            val adj  = listOf("bright","swift","calm","bold","wild","keen","cool","dark","nice","easy")
            val noun = listOf("fox","star","byte","hawk","wolf","ace","jet","sky","ray","ion")
            return "${adj.random()}${noun.random()}${(1000..9999).random()}@$domain"
        }
        fun randPassword(): String {
            val s = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP0123456789!@#"
            return (1..12).map { s.random() }.joinToString("")
        }
        fun randFullName(): String {
            val first = listOf("Alex","Sam","Jordan","Taylor","Morgan","Casey","Riley","Blake","Kai","Logan")
            val last  = listOf("Smith","Jones","Brown","Davis","Wilson","Lee","Clark","Hall","Moore","White")
            return "${first.random()} ${last.random()}"
        }
        fun randUsername(): String {
            val adj  = listOf("cool","fast","dark","blue","wild","swift","calm","bright")
            val noun = listOf("fox","hawk","wolf","bear","lion","ace","star","byte")
            return "${adj.random()}${noun.random()}${(1000..9999).random()}"
        }
    }

    // ── Binding & prefs ───────────────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    // ── HTTP ──────────────────────────────────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    // ── Tab state ─────────────────────────────────────────────────────────────
    private val tabs = mutableListOf<TabEntry>()
    private var activeTabIndex = 0

    // ── Account state ─────────────────────────────────────────────────────────
    private val accounts = mutableListOf<AccountEntry>()

    // ── Mail state ────────────────────────────────────────────────────────────
    private var mailEmail    = ""
    private var mailPassword = ""
    private var mailToken    = ""
    private var pollJob: Job? = null
    private val seenIds      = mutableSetOf<String>()

    // ── UI / panel state ──────────────────────────────────────────────────────
    private var panelSection  = 0
    private var panelOpen     = false
    private var isDesktopMode = false

    // ── Smart-paste ───────────────────────────────────────────────────────────
    private var focusedFieldType = ""   // "email" | "password" | ""

    // ── Auto-fill (used during Replit sign-up flow) ───────────────────────────
    private var autoEmail    = ""
    private var autoPassword = ""
    private var autoUsername = ""
    private var autoFullName = ""

    // ── FAB drag ──────────────────────────────────────────────────────────────
    private var fabInitX = 0f
    private var fabInitY = 0f
    private var fabMoved = false

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CookieManager.getInstance().setAcceptCookie(true)

        loadAccounts()
        setupToolbar()
        setupTabBar()
        setupPanel()
        setupFab()
        setupVerifyArea()

        addNewTab("https://replit.com", select = true)
        lifecycleScope.launch { ensureMailAccount() }
    }

    // ─── Toolbar ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            tabs.getOrNull(activeTabIndex)?.webView?.goBack()
        }
        binding.btnForward.setOnClickListener {
            tabs.getOrNull(activeTabIndex)?.webView?.goForward()
        }
        binding.btnRefresh.setOnClickListener {
            tabs.getOrNull(activeTabIndex)?.webView?.reload()
        }
        binding.btnDesktop.setOnClickListener {
            isDesktopMode = !isDesktopMode
            val ua = if (isDesktopMode) DESKTOP_UA else MOBILE_UA
            tabs.forEach { it.webView?.settings?.userAgentString = ua }
            tabs.getOrNull(activeTabIndex)?.webView?.reload()
            binding.btnDesktop.setTextColor(if (isDesktopMode) CLR_ACCENT else CLR_TEXT_SEC)
            toast(if (isDesktopMode) "🖥 Chế độ máy tính" else "📱 Mobile")
        }
        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val q = binding.etUrl.text.toString().trim()
                if (q.isNotEmpty()) {
                    val url = when {
                        q.startsWith("http") -> q
                        q.contains(".") && !q.contains(" ") -> "https://$q"
                        else -> "https://www.google.com/search?q=${Uri.encode(q)}"
                    }
                    tabs.getOrNull(activeTabIndex)?.webView?.loadUrl(url)
                }
                hideKeyboard(); true
            } else false
        }
        binding.etUrl.setOnFocusChangeListener { _, f -> if (f) binding.etUrl.selectAll() }
    }

    // ─── Tabs ────────────────────────────────────────────────────────────────

    private fun setupTabBar() {
        binding.btnAddTab.setOnClickListener { addNewTab("https://replit.com/signup") }
    }

    private fun addNewTab(url: String = "https://replit.com", select: Boolean = true): TabEntry {
        val entry = TabEntry(url = url)
        val wv = createWebView()
        entry.webView = wv
        binding.webContainer.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.loadUrl(url)
        tabs.add(entry)
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
        binding.etUrl.setText(tabs[index].url)
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
        val bar = binding.tabChipsContainer
        bar.removeAllViews()
        val d = resources.displayMetrics.density

        tabs.forEachIndexed { i, tab ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(this@MainActivity, 8f), 0, dp(this@MainActivity, 4f), 0)
                background  = GradientDrawable().apply {
                    setColor(if (i == activeTabIndex) CLR_ACCENT else Color.parseColor("#E0E7FF"))
                    cornerRadius = 6 * d
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (28 * d).toInt()
                ).apply { setMargins(0, 0, (4 * d).toInt(), 0) }
                setOnClickListener { selectTab(i); if (panelOpen) closePanel() }
            }
            chip.addView(TextView(this).apply {
                text = tab.title.take(14).ifEmpty { "Replit" }
                textSize = 11f
                setTextColor(if (i == activeTabIndex) Color.WHITE else CLR_TEXT_SEC)
                setSingleLine(true)
                maxWidth = (100 * d).toInt()
            })
            chip.addView(TextView(this).apply {
                text = " ✕"; textSize = 9f
                setTextColor(if (i == activeTabIndex) Color.WHITE else CLR_TEXT_SEC)
                setPadding(0, 0, dp(this@MainActivity, 2f), 0)
                setOnClickListener { closeTab(i) }
            })
            bar.addView(chip)
        }
    }

    // ─── WebView factory ────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this).apply { visibility = View.GONE }
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
            userAgentString          = if (isDesktopMode) DESKTOP_UA else MOBILE_UA
            allowFileAccess          = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture      = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(object : Any() {
            @JavascriptInterface fun onFieldFocus(type: String) { focusedFieldType = type }

            @JavascriptInterface fun onPageReady(url: String) {
                runOnUiThread {
                    val idx = tabs.indexOfFirst { it.webView == wv }
                    if (idx >= 0) { tabs[idx].url = url; if (idx == activeTabIndex) binding.etUrl.setText(url) }
                    if (url.contains("onboarding") || url.contains("plans") || url.contains("wizard"))
                        wv.postDelayed({ injectOnboardingStep(wv, autoFullName) }, 1500)
                    if ((url.contains("replit.com") || url.contains("replit.app")) &&
                        !url.contains("verify") && !url.contains("confirm") && url != "about:blank") {
                        if (binding.verifyOverlay.visibility == View.VISIBLE) {
                            binding.verifyOverlay.visibility = View.GONE
                            toast("✅ Xác thực thành công!")
                        }
                    }
                }
            }

            @JavascriptInterface fun onTitleChange(title: String, url: String) {
                runOnUiThread {
                    val idx = tabs.indexOfFirst { it.webView == wv }
                    if (idx >= 0) {
                        tabs[idx].title = title; tabs[idx].url = url
                        if (idx == activeTabIndex) binding.etUrl.setText(url)
                        renderTabBar(); renderPanelTabList()
                    }
                }
            }
        }, "RemBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                injectFocusTracker(v)
                injectAutoFill(v, url)
            }
            override fun onPageFinished(v: WebView, url: String) {
                injectFocusTracker(v)
                v.evaluateJavascript("RemBridge.onPageReady(window.location.href);", null)
                v.evaluateJavascript("RemBridge.onTitleChange(document.title,window.location.href);", null)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                view.evaluateJavascript("RemBridge.onTitleChange(document.title,window.location.href);", null)
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                binding.progressBar.progress   = newProgress
            }
        }
        return wv
    }

    // ─── Focus tracker JS ────────────────────────────────────────────────────

    private fun injectFocusTracker(wv: WebView) {
        wv.evaluateJavascript("""
(function(){
  if(window._remFocusReady) return; window._remFocusReady=true;
  document.addEventListener('focusin',function(e){
    var el=e.target;
    if(!el||!['INPUT','TEXTAREA'].includes(el.tagName)) return;
    var t=(el.type||'').toLowerCase(),n=(el.name||'').toLowerCase(),id=(el.id||'').toLowerCase();
    var type='text';
    if(t==='password'||n.includes('pass')||id.includes('pass')) type='password';
    else if(t==='email'||n.includes('email')||n.includes('mail')||id.includes('email')) type='email';
    RemBridge.onFieldFocus(type);
  },true);
})();""", null)
    }

    // ─── Auto-fill JS ────────────────────────────────────────────────────────

    private fun injectAutoFill(wv: WebView, url: String) {
        if (autoEmail.isEmpty() || autoPassword.isEmpty()) return
        if (!url.contains("signup") && !url.contains("register") && !url.contains("login")) return
        val eEsc = autoEmail.replace("'", "\\'")
        val pEsc = autoPassword.replace("'", "\\'")
        val uEsc = autoUsername.replace("'", "\\'")
        wv.evaluateJavascript("""
(function(){
  function fill(sel,val){
    var el=document.querySelector(sel); if(!el) return false;
    var nv=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
    if(nv&&nv.set) nv.set.call(el,val); else el.value=val;
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true})); return true;
  }
  fill('input[type="email"],input[name*="email"],input[name*="mail"]','$eEsc');
  fill('input[type="password"],input[name*="pass"]','$pEsc');
  fill('input[name*="user"],input[name*="name"],input[placeholder*="name"]','$uEsc');
})();""", null)
    }

    // ─── Onboarding auto-next ────────────────────────────────────────────────

    private fun injectOnboardingStep(wv: WebView, name: String) {
        val nEsc = name.replace("'", "\\'")
        wv.evaluateJavascript("""
(function(){
  var nameInp=document.querySelector('input[placeholder*="name"],input[name*="name"]');
  if(nameInp&&nameInp.value===''){
    var nv=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
    if(nv&&nv.set) nv.set.call(nameInp,'$nEsc'); else nameInp.value='$nEsc';
    nameInp.dispatchEvent(new Event('input',{bubbles:true}));
  }
  var radios=document.querySelectorAll('input[type="radio"]:not(:checked)');
  if(radios.length>0) radios[0].click();
  var checks=document.querySelectorAll('input[type="checkbox"]:not(:checked)');
  if(checks.length>0&&checks.length<5) checks[0].click();
  var btns=Array.from(document.querySelectorAll('button,a[role="button"]'));
  var next=btns.find(function(b){return /next|continue|proceed|tiếp/i.test(b.textContent);});
  if(next){next.click();return;}
  var sub=btns.find(function(b){return /submit|start|get started|begin/i.test(b.textContent);});
  if(sub) sub.click();
})();""", null)
    }

    // ─── Login form injection ────────────────────────────────────────────────

    private fun injectLoginForm(wv: WebView, email: String, pass: String) {
        val eEsc = email.replace("'", "\\'"); val pEsc = pass.replace("'", "\\'")
        wv.evaluateJavascript("""
(function(){
  function sv(el,v){
    var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
    if(d&&d.set) d.set.call(el,v); else el.value=v;
    ['input','change'].forEach(function(ev){el.dispatchEvent(new Event(ev,{bubbles:true}));});
  }
  var em=document.querySelector('input[type="email"],input[name*="email"],input[autocomplete*="email"]');
  var pw=document.querySelector('input[type="password"]');
  if(em) sv(em,'$eEsc'); if(pw) sv(pw,'$pEsc');
})();""", null)
    }

    // ─── Smart paste ─────────────────────────────────────────────────────────

    private fun pasteToWebView(value: String) {
        val wv = tabs.getOrNull(activeTabIndex)?.webView ?: return
        val esc = value.replace("\\", "\\\\").replace("'", "\\'")
        wv.evaluateJavascript("""
(function(){
  var el=document.activeElement;
  if(!el||!['INPUT','TEXTAREA'].includes(el.tagName))
    el=document.querySelector('input:focus,textarea:focus')
      ||document.querySelector('input[type="email"]')
      ||document.querySelector('input[type="text"]');
  if(!el) return;
  var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
  if(d&&d.set) d.set.call(el,'$esc'); else el.value='$esc';
  ['input','change'].forEach(function(ev){el.dispatchEvent(new Event(ev,{bubbles:true}));});
})();""", null)
        copyClipboard(value)
    }

    private fun smartPaste(acc: AccountEntry, preferType: String) {
        val value = when {
            focusedFieldType == "email"    -> acc.email
            focusedFieldType == "password" -> acc.password
            preferType == "email"          -> acc.email
            else                           -> acc.password
        }
        pasteToWebView(value)
        toast("📋 Đã dán: ${value.take(24)}")
    }

    // ─── FAB ─────────────────────────────────────────────────────────────────

    private fun setupFab() {
        val fab = binding.fabMail
        // Start position: top-right like Cốc Cốc
        binding.root.post {
            val sw = resources.displayMetrics.widthPixels
            fab.x = sw - dp(this, 68f).toFloat()
            fab.y = dp(this, 110f).toFloat()
        }
        fab.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    fabInitX = event.rawX - v.x; fabInitY = event.rawY - v.y; fabMoved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = event.rawX - fabInitX; val ny = event.rawY - fabInitY
                    if (Math.abs(nx - v.x) + Math.abs(ny - v.y) > 8f) fabMoved = true
                    v.x = nx; v.y = ny; true
                }
                MotionEvent.ACTION_UP -> { if (!fabMoved) togglePanel(); true }
                else -> false
            }
        }
    }

    private fun post(fn: () -> Unit) = binding.root.post(fn)

    // ─── Panel ────────────────────────────────────────────────────────────────

    private fun setupPanel() {
        binding.btnPanelClose.setOnClickListener { closePanel() }
        binding.tabPanelAccounts.setOnClickListener { switchPanelSection(0) }
        binding.tabPanelMail.setOnClickListener     { switchPanelSection(1) }
        binding.tabPanelTabs.setOnClickListener     { switchPanelSection(2) }

        // Search toggle
        binding.btnPanelSearch.setOnClickListener {
            val open = binding.etPanelSearch.visibility == View.VISIBLE
            binding.etPanelSearch.visibility = if (open) View.GONE else View.VISIBLE
            binding.tvPanelTitle.visibility  = if (open) View.VISIBLE else View.GONE
            if (!open) { binding.etPanelSearch.requestFocus(); showKeyboard(binding.etPanelSearch) }
            else hideKeyboard()
        }
        binding.etPanelSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val q = binding.etPanelSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    val url = when {
                        q.startsWith("http") -> q
                        q.contains(".") && !q.contains(" ") -> "https://$q"
                        else -> "https://www.google.com/search?q=${Uri.encode(q)}"
                    }
                    tabs.getOrNull(activeTabIndex)?.webView?.loadUrl(url)
                    closePanel()
                }; true
            } else false
        }

        binding.btnNewMail.setOnClickListener {
            lifecycleScope.launch { createAndSetNewMailAccount() }
        }

        switchPanelSection(0)
    }

    private fun togglePanel() { if (panelOpen) closePanel() else openPanel() }

    private fun openPanel() {
        panelOpen = true
        binding.mailPanel.visibility = View.VISIBLE
        refreshAccountList(); renderPanelTabList(); updateMailBadge()
    }

    private fun closePanel() {
        panelOpen = false
        binding.mailPanel.visibility     = View.GONE
        binding.etPanelSearch.visibility = View.GONE
        binding.tvPanelTitle.visibility  = View.VISIBLE
        hideKeyboard()
    }

    private fun switchPanelSection(s: Int) {
        panelSection = s
        listOf(binding.tabPanelAccounts, binding.tabPanelMail, binding.tabPanelTabs)
            .forEachIndexed { i, tv ->
                tv.setBackgroundColor(if (i == s) CLR_ACCENT else Color.TRANSPARENT)
                tv.setTextColor(if (i == s) Color.WHITE else CLR_TEXT_SEC)
            }
        binding.panelAccounts.visibility = if (s == 0) View.VISIBLE else View.GONE
        binding.panelMail.visibility     = if (s == 1) View.VISIBLE else View.GONE
        binding.panelTabsView.visibility = if (s == 2) View.VISIBLE else View.GONE
        when (s) { 0 -> refreshAccountList(); 1 -> refreshMailPanel(); 2 -> renderPanelTabList() }
    }

    // ─── Accounts ─────────────────────────────────────────────────────────────

    private fun loadAccounts() {
        accounts.clear()
        val arr = JSONArray(prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            accounts.add(AccountEntry(
                id       = o.optString("id", UUID.randomUUID().toString()),
                email    = o.optString("email"),
                password = o.optString("password"),
                username = o.optString("username"),
                createdAt= o.optLong("createdAt", System.currentTimeMillis())
            ))
        }
    }

    private fun saveAccounts() {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("id",a.id); put("email",a.email); put("password",a.password)
                put("username",a.username); put("createdAt",a.createdAt)
            })
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun addOrUpdateAccount(acc: AccountEntry) {
        if (accounts.none { it.email == acc.email }) {
            accounts.add(0, acc); saveAccounts()
            runOnUiThread { if (panelSection == 0 && panelOpen) refreshAccountList() }
        }
    }

    private fun refreshAccountList() {
        val list = binding.accountList
        list.removeAllViews()
        val d = resources.displayMetrics.density

        if (accounts.isEmpty()) { binding.tvAccountsEmpty.visibility = View.VISIBLE; return }
        binding.tvAccountsEmpty.visibility = View.GONE

        accounts.forEach { acc ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(this@MainActivity, 10f), dp(this@MainActivity, 8f),
                           dp(this@MainActivity, 10f), dp(this@MainActivity, 8f))
                background = GradientDrawable().apply {
                    setColor(CLR_SURFACE); cornerRadius = 10 * d; setStroke((1 * d).toInt(), CLR_BORDER)
                }
                elevation = 2 * d
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(this@MainActivity, 8f), dp(this@MainActivity, 4f),
                                     dp(this@MainActivity, 8f), dp(this@MainActivity, 4f)) }
            }
            card.addView(makeCredRow("📧", acc.email, true)  { smartPaste(acc, "email") })
            card.addView(View(this).apply {
                setBackgroundColor(CLR_BORDER)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    .apply { setMargins(0, dp(this@MainActivity, 3f), 0, dp(this@MainActivity, 3f)) }
            })
            card.addView(makeCredRow("🔑", "•".repeat(minOf(acc.password.length, 12)), false) {
                smartPaste(acc, "password")
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(this@MainActivity, 6f), 0, 0)
            }
            row.addView(makeSmallBtn("⚡ Điền", CLR_TAG_BG, CLR_ACCENT) {
                autoEmail = acc.email; autoPassword = acc.password
                injectLoginForm(tabs.getOrNull(activeTabIndex)?.webView ?: return@makeSmallBtn, acc.email, acc.password)
                toast("Đang điền: ${acc.email.take(20)}")
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
            row.addView(makeSmallBtn("📋 Copy", Color.parseColor("#F3F4F6"), CLR_TEXT_SEC) {
                copyClipboard("${acc.email}\n${acc.password}"); toast("Đã copy tài khoản")
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
                .apply { setMargins(dp(this@MainActivity, 4f), 0, dp(this@MainActivity, 4f), 0) })
            row.addView(makeSmallBtn("🗑", Color.parseColor("#FEF2F2"), CLR_RED) {
                accounts.remove(acc); saveAccounts(); refreshAccountList()
            }, LinearLayout.LayoutParams(dp(this@MainActivity, 36f), dp(this@MainActivity, 28f)))
            card.addView(row)
            list.addView(card)
        }
    }

    private fun makeCredRow(icon: String, txt: String, isEmail: Boolean, onTap: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(this@MainActivity, 2f), 0, dp(this@MainActivity, 2f))
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 12f; setPadding(0, 0, dp(this@MainActivity, 6f), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = txt; textSize = 12.5f
                setTextColor(if (isEmail) CLR_ACCENT else CLR_TEXT_PRI); setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "📋"; textSize = 14f
                setPadding(dp(this@MainActivity, 8f), 0, 0, 0)
                setOnClickListener { onTap() }
            })
        }

    private fun makeSmallBtn(label: String, bg: Int, fg: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label; textSize = 11f; setTextColor(fg); gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 6 * resources.displayMetrics.density }
            setPadding(dp(this@MainActivity, 6f), dp(this@MainActivity, 5f),
                       dp(this@MainActivity, 6f), dp(this@MainActivity, 5f))
            setOnClickListener { onClick() }
        }

    // ─── Panel tab list ───────────────────────────────────────────────────────

    private fun renderPanelTabList() {
        val list = binding.panelTabList; list.removeAllViews()
        val d = resources.displayMetrics.density

        // + New tab row
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(this@MainActivity, 12f), dp(this@MainActivity, 10f),
                       dp(this@MainActivity, 12f), dp(this@MainActivity, 10f))
            background = GradientDrawable().apply {
                setColor(CLR_TAG_BG); cornerRadius = 10 * d; setStroke((1 * d).toInt(), CLR_BORDER)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(this@MainActivity, 8f), dp(this@MainActivity, 4f),
                                 dp(this@MainActivity, 8f), dp(this@MainActivity, 4f)) }
            setOnClickListener { addNewTab("https://replit.com/signup"); closePanel() }
        }
        addRow.addView(TextView(this).apply {
            text = "+ Tab mới (Replit Signup)"; textSize = 13f; setTextColor(CLR_ACCENT)
        })
        list.addView(addRow)

        tabs.forEachIndexed { i, tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(this@MainActivity, 12f), dp(this@MainActivity, 8f),
                           dp(this@MainActivity, 8f), dp(this@MainActivity, 8f))
                background = GradientDrawable().apply {
                    setColor(if (i == activeTabIndex) CLR_ACCENT else CLR_SURFACE)
                    cornerRadius = 10 * d; setStroke((1 * d).toInt(), CLR_BORDER)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(this@MainActivity, 8f), dp(this@MainActivity, 3f),
                                     dp(this@MainActivity, 8f), dp(this@MainActivity, 3f)) }
                setOnClickListener { selectTab(i); closePanel() }
            }
            row.addView(TextView(this).apply {
                text = "🌐 ${tab.title.take(22).ifEmpty { tab.url.take(30) }}"
                textSize = 12.5f; setTextColor(if (i == activeTabIndex) Color.WHITE else CLR_TEXT_PRI)
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = " ✕"; textSize = 13f
                setTextColor(if (i == activeTabIndex) Color.WHITE else CLR_TEXT_SEC)
                setPadding(dp(this@MainActivity, 10f), dp(this@MainActivity, 4f),
                           dp(this@MainActivity, 4f), dp(this@MainActivity, 4f))
                setOnClickListener { closeTab(i) }
            })
            list.addView(row)
        }
    }

    // ─── Verify overlay ───────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyArea() {
        binding.btnCloseVerify.setOnClickListener {
            binding.verifyOverlay.visibility = View.GONE
        }
        binding.verifyWebView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.verifyWebView, true)
    }

    private fun openVerifyLink(url: String) {
        toast("📧 Tìm thấy link xác thực — đang mở...")
        binding.verifyOverlay.visibility = View.VISIBLE
        binding.verifyWebView.loadUrl(url)
        if (panelOpen) closePanel()
    }

    // ─── Mail panel refresh ───────────────────────────────────────────────────

    private fun refreshMailPanel() {
        binding.tvMailEmail.text = if (mailEmail.isEmpty()) "Chưa có tài khoản" else mailEmail
        binding.tvMailStatus.text = when {
            mailToken.isNotEmpty() -> "✅ Đang theo dõi hộp thư"
            mailEmail.isNotEmpty() -> "⏳ Chưa kết nối"
            else                   -> "❌ Chưa có mail"
        }
    }

    private fun updateMailBadge() {
        binding.tvMailBadge.visibility = if (mailEmail.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ─── Mail.tm API ──────────────────────────────────────────────────────────

    private suspend fun ensureMailAccount() = withContext(Dispatchers.IO) {
        mailEmail    = prefs.getString(KEY_MAIL_EMAIL, "") ?: ""
        mailPassword = prefs.getString(KEY_MAIL_PASS,  "") ?: ""
        mailToken    = prefs.getString(KEY_MAIL_TOKEN, "") ?: ""

        if (mailEmail.isEmpty()) {
            createAndSetNewMailAccount()
        } else if (mailToken.isEmpty()) {
            val tok = getMailToken(mailEmail, mailPassword)
            if (tok != null) {
                mailToken = tok
                prefs.edit().putString(KEY_MAIL_TOKEN, tok).apply()
                startPolling(tok)
            }
        } else {
            startPolling(mailToken)
        }
        if (mailEmail.isNotEmpty() && mailPassword.isNotEmpty())
            addOrUpdateAccount(AccountEntry(email = mailEmail, password = mailPassword))
        runOnUiThread { updateMailBadge() }
    }

    private suspend fun createAndSetNewMailAccount() = withContext(Dispatchers.IO) {
        runOnUiThread { toast("⏳ Đang tạo mail.tm mới...") }
        stopPolling()
        val domain = getMailDomains()?.firstOrNull() ?: run {
            runOnUiThread { toast("❌ Không lấy được domain") }; return@withContext
        }
        val email = randEmail(domain); val pass = randPassword()
        if (!createMailAccount(email, pass)) { runOnUiThread { toast("❌ Tạo mail thất bại") }; return@withContext }
        val tok = getMailToken(email, pass) ?: run {
            runOnUiThread { toast("❌ Lấy token thất bại") }; return@withContext
        }
        mailEmail = email; mailPassword = pass; mailToken = tok
        autoEmail = email; autoPassword = pass
        autoUsername = randUsername(); autoFullName = randFullName()
        seenIds.clear()
        prefs.edit().putString(KEY_MAIL_EMAIL, email).putString(KEY_MAIL_PASS, pass)
            .putString(KEY_MAIL_TOKEN, tok).apply()
        addOrUpdateAccount(AccountEntry(email = email, password = pass, username = autoUsername))
        runOnUiThread {
            toast("✅ Mail mới: $email")
            updateMailBadge()
            if (panelSection == 0 && panelOpen) refreshAccountList()
            if (panelSection == 1 && panelOpen) refreshMailPanel()
        }
        startPolling(tok)
    }

    private fun getMailDomains(): List<String>? = try {
        val res = http.newCall(Request.Builder().url("$MAILTM/domains").build()).execute()
        val arr = JSONObject(res.body!!.string()).getJSONArray("hydra:member")
        (0 until arr.length()).map { arr.getJSONObject(it).getString("domain") }
    } catch (e: Exception) { null }

    private fun createMailAccount(email: String, pass: String): Boolean = try {
        val body = JSONObject().put("address", email).put("password", pass)
            .toString().toRequestBody(JSON_MT)
        http.newCall(Request.Builder().url("$MAILTM/accounts").post(body).build())
            .execute().code in 200..201
    } catch (e: Exception) { false }

    private fun getMailToken(email: String, pass: String): String? = try {
        val body = JSONObject().put("address", email).put("password", pass)
            .toString().toRequestBody(JSON_MT)
        val res  = http.newCall(Request.Builder().url("$MAILTM/token").post(body).build()).execute()
        JSONObject(res.body!!.string()).optString("token").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun getMessages(token: String): List<JSONObject> = try {
        val res = http.newCall(
            Request.Builder().url("$MAILTM/messages?page=1")
                .header("Authorization", "Bearer $token").build()
        ).execute()
        val arr = JSONObject(res.body!!.string()).getJSONArray("hydra:member")
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    private fun getMessage(token: String, id: String): JSONObject? = try {
        val res = http.newCall(
            Request.Builder().url("$MAILTM/messages/$id")
                .header("Authorization", "Bearer $token").build()
        ).execute()
        JSONObject(res.body!!.string())
    } catch (e: Exception) { null }

    private fun extractVerifyLink(msg: JSONObject): String? {
        val html = msg.optJSONArray("html")?.let { a -> (0 until a.length()).joinToString("") { a.getString(it) } } ?: ""
        val text = msg.optString("text", "")
        val combined = html + text
        return listOf(
            Regex("https://replit\\.com/[^\"'\\s<>]*confirm[^\"'\\s<>]*", RegexOption.IGNORE_CASE),
            Regex("https://replit\\.com/[^\"'\\s<>]*verif[^\"'\\s<>]*", RegexOption.IGNORE_CASE),
            Regex("https://[^\"'\\s<>]*replit[^\"'\\s<>]*token[^\"'\\s<>]*", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { it.find(combined)?.value }
    }

    // ─── Polling ──────────────────────────────────────────────────────────────

    private fun startPolling(token: String) {
        stopPolling()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    for (msg in getMessages(token)) {
                        val id = msg.optString("id"); if (id.isEmpty() || seenIds.contains(id)) continue
                        seenIds.add(id)
                        val from = msg.optJSONObject("from")?.optString("address", "") ?: ""
                        val subj = msg.optString("subject", "")
                        if (subj.lowercase().contains("replit") || from.contains("replit")) {
                            getMessage(token, id)?.let { detail ->
                                extractVerifyLink(detail)?.let { link ->
                                    runOnUiThread { openVerifyLink(link) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(3500)
            }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private fun copyClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("rem2", text))
    }

    private fun showKeyboard(v: View) =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)

    private fun hideKeyboard() =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(currentFocus?.windowToken, 0)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            panelOpen -> closePanel()
            binding.verifyWebView.canGoBack() && binding.verifyOverlay.visibility == View.VISIBLE ->
                binding.verifyWebView.goBack()
            tabs.getOrNull(activeTabIndex)?.webView?.canGoBack() == true ->
                tabs[activeTabIndex].webView?.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() { super.onDestroy(); stopPolling(); tabs.forEach { it.webView?.destroy() } }
    override fun onPause()   { super.onPause();   tabs.forEach { it.webView?.onPause() } }
    override fun onResume()  { super.onResume();  tabs.forEach { it.webView?.onResume() } }
}
