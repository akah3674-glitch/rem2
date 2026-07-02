package com.rem2.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.rem2.browser.databinding.ActivityMainBinding
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS            = "rem2"
        private const val KEY_MAIL_EMAIL   = "mail_email"
        private const val KEY_MAIL_PASS    = "mail_pass"
        private const val KEY_MAIL_TOKEN   = "mail_token"
        private const val KEY_REPLIT_REG   = "replit_registered"
        private const val KEY_REPLIT_NAME  = "replit_name"
        private const val KEY_INVITE_EMAIL = "invite_email"
        private const val KEY_INVITE_PASS  = "invite_pass"
        private const val KEY_INVITE_TOKEN = "invite_token"
        private const val KEY_INVITE_NAME  = "invite_name"
        private const val MAILTM           = "https://api.mail.tm"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()

        private val FIRST_NAMES = listOf(
            "Alex", "Sam", "Jordan", "Taylor", "Morgan", "Casey", "Riley",
            "Avery", "Blake", "Cameron", "Drew", "Elliot", "Finley", "Harper",
            "Jamie", "Kai", "Logan", "Mika", "Noah", "Quinn"
        )
        private val LAST_NAMES = listOf(
            "Smith", "Lee", "Chen", "Park", "Johnson", "Brown", "Davis",
            "Wilson", "Moore", "Taylor", "Anderson", "Thomas", "Jackson",
            "White", "Harris", "Martin", "Garcia", "Martinez", "Robinson", "Clark"
        )
        fun randomFullName() = "${FIRST_NAMES.random()} ${LAST_NAMES.random()}"
    }

    private lateinit var binding: ActivityMainBinding
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var mailToken         = ""
    private var mailEmail         = ""
    private var pollJob: Job?     = null
    private val seenIds           = mutableSetOf<String>()
    private var autoRegInProgress = false

    private var inviteMailToken    = ""
    private var inviteMailEmail    = ""
    private var inviteInProgress   = false
    private var invitePollJob: Job? = null
    private val inviteSeenIds      = mutableSetOf<String>()

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupMainWebView()
        setupVerifyWebView()
        setupPanel()
        lifecycleScope.launch { ensureMailAccount() }
    }

    // ─── WebView setup ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMainWebView() {
        binding.mainWebView.apply {
            settings.javaScriptEnabled  = true
            settings.domStorageEnabled  = true
            settings.mixedContentMode   = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString    =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            addJavascriptInterface(WebBridge(), "REM2")
            webViewClient = buildMainClient()
            loadUrl("https://replit.com")
        }
    }

    private fun buildMainClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
        override fun onPageFinished(v: WebView, url: String) {
            val u = url.trimEnd('/')
            when {
                url.contains("/onboarding") || url.contains("/plans") -> {
                    val name = prefs.getString(KEY_REPLIT_NAME, "").let {
                        if (it.isNullOrEmpty()) randomFullName() else it
                    }
                    v.postDelayed({ injectOnboardingStep(v, name) }, 1500)
                }
                (u == "https://replit.com" || u == "https://www.replit.com") &&
                !prefs.getBoolean(KEY_REPLIT_REG, false) && !autoRegInProgress -> {
                    detectAndAutoRegister(v)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        binding.verifyWebView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString   =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            webViewClient = buildVerifyClient()
        }
    }

    private fun buildVerifyClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
        override fun onPageFinished(v: WebView, url: String) {
            when {
                url.contains("replit.com") &&
                (url.contains("invite") || url.contains("join") || url.contains("signup")) &&
                inviteMailEmail.isNotEmpty() -> {
                    val iName = prefs.getString(KEY_INVITE_NAME, randomFullName()) ?: randomFullName()
                    val iUser = "user" + (10000..99999).random()
                    val iPass = "Rem2x" + (100000..999999).random()
                    v.postDelayed({ expandEmailForm(v, inviteMailEmail, iUser, iPass, iName) }, 2000)
                }
                url.contains("/onboarding") || url.contains("/plans") -> {
                    val name = prefs.getString(KEY_INVITE_NAME, randomFullName()) ?: randomFullName()
                    v.postDelayed({ injectOnboardingStep(v, name) }, 1500)
                }
                url.contains("replit.com") &&
                !url.contains("verify") && !url.contains("confirm") &&
                !url.contains("invite") && !url.contains("join") &&
                url != "about:blank" -> {
                    if (inviteInProgress) onInviteSignupComplete()
                    else onVerifyComplete()
                }
            }
        }
    }

    // ─── Panel UI ─────────────────────────────────────────────────────────────

    private fun setupPanel() {
        binding.fabMail.setOnClickListener {
            if (binding.mailPanel.visibility == View.GONE) {
                binding.mailPanel.visibility = View.VISIBLE
                // Chỉ bắt đầu polling nếu KHÔNG đang auto-register
                if (mailToken.isNotEmpty() && !autoRegInProgress) startPolling()
            } else {
                binding.mailPanel.visibility = View.GONE
                // Không stopPolling nếu đang auto-register — vẫn cần nhận email verify
                if (!autoRegInProgress) stopPolling()
            }
        }
        binding.btnClose.setOnClickListener {
            binding.mailPanel.visibility = View.GONE
            if (!autoRegInProgress) stopPolling()
        }
        binding.btnRefresh.setOnClickListener {
            lifecycleScope.launch { fetchMail(forceAll = true) }
        }
        binding.btnAutoSetup.setOnClickListener {
            if (prefs.getBoolean(KEY_REPLIT_REG, false) || mailEmail.isNotEmpty()) {
                // Đã đăng ký hoặc có email cũ → Reset toàn bộ, tạo email mới
                resetAndCreateNewEmail()
            } else {
                lifecycleScope.launch { ensureMailAccount(force = true) }
            }
        }
        binding.btnSkipAutoReg.setOnClickListener {
            autoRegInProgress = false
            binding.mainWebView.webViewClient = buildMainClient()
            hideAutoOverlay()
        }
        binding.tabMailList.setOnClickListener { showInboxTab() }
        binding.tabVerify.setOnClickListener   { showVerifyTab() }
        binding.btnInviteNew.setOnClickListener { startInviteFlow() }
    }

    private fun showInboxTab() {
        binding.mailListContainer.visibility = View.VISIBLE
        binding.verifyWebView.visibility     = View.GONE
        binding.tabMailList.setBackgroundColor(0xFF1565C0.toInt())
        binding.tabMailList.setTextColor(0xFFFFFFFF.toInt())
        binding.tabVerify.setBackgroundColor(0x00000000)
        binding.tabVerify.setTextColor(0xFF90CAF9.toInt())
    }

    private fun showVerifyTab() {
        binding.mailListContainer.visibility = View.GONE
        binding.verifyWebView.visibility     = View.VISIBLE
        binding.tabVerify.setBackgroundColor(0xFF1565C0.toInt())
        binding.tabVerify.setTextColor(0xFFFFFFFF.toInt())
        binding.tabMailList.setBackgroundColor(0x00000000)
        binding.tabMailList.setTextColor(0xFF90CAF9.toInt())
    }

    /**
     * Đặt lại toàn bộ — xóa email cũ, tạo email Mail.tm mới, bắt đầu đăng ký Replit lại.
     * Gọi khi email cũ đã dùng đăng ký xong hoặc bị lỗi.
     */
    private fun resetAndCreateNewEmail() {
        // Dừng mọi luồng đang chạy
        autoRegInProgress = false
        pollJob?.cancel(); pollJob = null
        hideAutoOverlay()

        // Xóa thông tin email và tài khoản cũ
        prefs.edit()
            .remove(KEY_MAIL_EMAIL)
            .remove(KEY_MAIL_PASS)
            .remove(KEY_MAIL_TOKEN)
            .remove(KEY_REPLIT_NAME)
            .putBoolean(KEY_REPLIT_REG, false)
            .apply()

        // Reset biến trạng thái trong bộ nhớ
        mailToken = ""; mailEmail = ""
        seenIds.clear()

        // Xóa danh sách email cũ trong UI
        binding.mailList.removeAllViews()
        binding.tvMailStatus.text = "Đặt lại xong — đang tạo email mới\u2026"
        android.widget.Toast.makeText(this, "Đang tạo email mới, vui lòng chờ\u2026", android.widget.Toast.LENGTH_SHORT).show()

        // Tạo tài khoản Mail.tm mới, sau đó tự đăng ký Replit
        lifecycleScope.launch {
            ensureMailAccount(force = true)
            // Sau khi có email mới, đặt lại WebViewClient và vào trang signup Replit
            kotlinx.coroutines.delay(600)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (mailEmail.isNotEmpty() && !prefs.getBoolean(KEY_REPLIT_REG, false)) {
                    startAutoRegisterFlow()
                }
            }
        }
    }

    // ─── Mail.tm account management ──────────────────────────────────────────

    private suspend fun ensureMailAccount(force: Boolean = false) {
        val savedEmail = prefs.getString(KEY_MAIL_EMAIL, "") ?: ""
        val savedPass  = prefs.getString(KEY_MAIL_PASS,  "") ?: ""

        if (savedEmail.isNotEmpty() && !force) {
            val tok = mailLogin(savedEmail, savedPass)
            if (tok.isNotEmpty()) {
                mailToken = tok
                mailEmail = savedEmail
                prefs.edit().putString(KEY_MAIL_TOKEN, tok).apply()
                withContext(Dispatchers.Main) {
                    binding.tvMailStatus.text = "\u2713 $savedEmail"
                    if (binding.mailPanel.visibility == View.VISIBLE) startPolling()
                }
                return
            }
        }

        withContext(Dispatchers.Main) { binding.tvMailStatus.text = "Đang tạo Mail.tm\u2026" }

        val domains = getMailDomains()
        if (domains.isEmpty()) {
            withContext(Dispatchers.Main) { binding.tvMailStatus.text = "\u2717 Mail.tm không khả dụng" }
            return
        }
        val user  = "rem2" + (1000..9999).random()
        val email = "$user@${domains[0]}"
        val pass  = UUID.randomUUID().toString().replace("-", "").take(16)

        if (!mailCreate(email, pass)) {
            withContext(Dispatchers.Main) { binding.tvMailStatus.text = "\u2717 Tạo tài khoản thất bại" }
            return
        }
        val tok = mailLogin(email, pass)
        if (tok.isEmpty()) {
            withContext(Dispatchers.Main) { binding.tvMailStatus.text = "\u2717 Đăng nhập thất bại" }
            return
        }
        mailToken = tok
        mailEmail = email
        prefs.edit()
            .putString(KEY_MAIL_EMAIL, email)
            .putString(KEY_MAIL_PASS,  pass)
            .putString(KEY_MAIL_TOKEN, tok)
            .apply()
        withContext(Dispatchers.Main) {
            binding.tvMailStatus.text = "\u2713 $email"
            toast("Mail.tm sẵn sàng: $email")
            if (binding.mailPanel.visibility == View.VISIBLE) startPolling()
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
            val resp = http.newCall(
                Request.Builder().url("$MAILTM/accounts").post(body).build()
            ).execute()
            resp.code in listOf(200, 201) &&
                JSONObject(resp.body?.string() ?: "{}").has("id")
        } catch (e: Exception) { false }
    }

    private suspend fun mailLogin(email: String, pass: String): String = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("address", email).put("password", pass)
                .toString().toRequestBody(JSON_MT)
            val resp = http.newCall(
                Request.Builder().url("$MAILTM/token").post(body).build()
            ).execute()
            if (!resp.isSuccessful) return@withContext ""
            JSONObject(resp.body?.string() ?: "{}").optString("token", "")
        } catch (e: Exception) { "" }
    }

    // ─── Mail polling ─────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) { fetchMail(); delay(5000L) }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    private suspend fun fetchMail(forceAll: Boolean = false) {
        if (mailToken.isEmpty()) return
        try {
            val req = Request.Builder()
                .url("$MAILTM/messages?page=1")
                .addHeader("Authorization", "Bearer $mailToken")
                .build()
            val resp = withContext(Dispatchers.IO) { http.newCall(req).execute() }
            if (!resp.isSuccessful) {
                val e = prefs.getString(KEY_MAIL_EMAIL, "") ?: ""
                val p = prefs.getString(KEY_MAIL_PASS,  "") ?: ""
                if (e.isNotEmpty()) { val t = mailLogin(e, p); if (t.isNotEmpty()) mailToken = t }
                return
            }
            val members = JSONObject(resp.body?.string() ?: "{}")
                .optJSONArray("hydra:member") ?: return
            val newMsgs = mutableListOf<Pair<String, String>>()
            for (i in 0 until members.length()) {
                val msg     = members.getJSONObject(i)
                val id      = msg.getString("id")
                val subject = msg.optString("subject", "(không có tiêu đề)")
                val intro   = msg.optString("intro", "")
                if (forceAll || !seenIds.contains(id)) {
                    seenIds.add(id)
                    newMsgs.add(id to subject)
                    val sl = subject.lowercase()
                    val il = intro.lowercase()
                    if (sl.contains("verify") || sl.contains("confirm") ||
                        sl.contains("replit") || il.contains("replit.com")) {
                        fetchAndLoadVerifyLink(id)
                    }
                }
            }
            if (newMsgs.isNotEmpty()) {
                withContext(Dispatchers.Main) { addMailItems(newMsgs, forceAll) }
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchAndLoadVerifyLink(msgId: String) {
        try {
            val req = Request.Builder()
                .url("$MAILTM/messages/$msgId")
                .addHeader("Authorization", "Bearer $mailToken")
                .build()
            val resp     = withContext(Dispatchers.IO) { http.newCall(req).execute() }
            val combined = extractCombined(resp.body?.string() ?: return)
            val finalUrl = findReplitLink(combined, listOf("verify", "confirm")) ?: return
            withContext(Dispatchers.Main) {
                binding.verifyWebView.loadUrl(finalUrl)
                showVerifyTab()
                if (binding.mailPanel.visibility == View.GONE)
                    binding.mailPanel.visibility = View.VISIBLE
                toast("Đang mở link xác nhận\u2026")
                if (autoRegInProgress) updateAutoStatus("Đang xác nhận email\u2026")
            }
        } catch (_: Exception) {}
    }

    private fun onVerifyComplete() {
        prefs.edit().putBoolean(KEY_REPLIT_REG, true).apply()
        autoRegInProgress = false
        hideAutoOverlay()
        showInboxTab()
        toast("Email đã xác nhận! Tài khoản Replit sẵn sàng \u2713")
        binding.mainWebView.webViewClient = buildMainClient()
        binding.mainWebView.loadUrl("https://replit.com")
        // Tự động reset email cũ → tạo email mới cho chu kỳ đăng ký tiếp theo
        lifecycleScope.launch {
            delay(3000L)
            refreshMailForNextCycle()
        }
    }

    /**
     * Sau khi đăng ký xong: xóa email cũ (đã dùng), tạo email Mail.tm mới,
     * reset cờ replit_registered = false → sẵn sàng cho lần đăng ký tiếp theo.
     */
    private suspend fun refreshMailForNextCycle() {
        // Dừng polling email cũ
        pollJob?.cancel(); pollJob = null

        // Xóa thông tin email và cờ đã đăng ký
        prefs.edit()
            .remove(KEY_MAIL_EMAIL)
            .remove(KEY_MAIL_PASS)
            .remove(KEY_MAIL_TOKEN)
            .remove(KEY_REPLIT_NAME)
            .putBoolean(KEY_REPLIT_REG, false)
            .apply()

        mailToken = ""; mailEmail = ""
        seenIds.clear()

        withContext(Dispatchers.Main) {
            binding.mailList.removeAllViews()
            binding.tvMailStatus.text = "Email cũ đã xóa \u2014 đang tạo email mới\u2026"
            toast("Đang tạo email mới cho chu kỳ tiếp theo\u2026")
        }

        // Tạo email Mail.tm mới (không tự đăng ký Replit ngay)
        ensureMailAccount(force = true)
    }

    // ─── Auto-register ────────────────────────────────────────────────────────

    private fun detectAndAutoRegister(webView: WebView) {
        val js = listOf(
            "(function(){",
            "  var els = document.querySelectorAll('a[href]');",
            "  for (var i = 0; i < els.length; i++) {",
            "    var h = els[i].getAttribute('href') || '';",
            "    if (h.indexOf('/signup') >= 0 || h.indexOf('/login') >= 0) return 'yes';",
            "  }",
            "  return 'no';",
            "})()"
        ).joinToString("\n")
        webView.evaluateJavascript(js) { result ->
            if (result?.trim('"') == "yes" && !autoRegInProgress)
                startAutoRegisterFlow()
        }
    }

    private fun startAutoRegisterFlow() {
        if (autoRegInProgress) return
        if (mailToken.isEmpty() || mailEmail.isEmpty()) {
            lifecycleScope.launch {
                ensureMailAccount()
                delay(500)
                withContext(Dispatchers.Main) { startAutoRegisterFlow() }
            }
            return
        }
        autoRegInProgress = true
        showAutoOverlay("Đang dọn dẹp session cũ\u2026")

        // Xóa cookie + cache WebView để tránh auto-login OAuth cũ
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        binding.mainWebView.clearCache(true)
        binding.mainWebView.clearHistory()

        val email    = mailEmail
        val username = "user" + (10000..99999).random()
        val password = "Rem2x" + (100000..999999).random()
        val fullName = randomFullName()
        prefs.edit().putString(KEY_REPLIT_NAME, fullName).apply()

        binding.mainWebView.webViewClient = object : WebViewClient() {
            private fun isExternal(url: String) =
                url.isNotEmpty() && url != "about:blank" &&
                !url.startsWith("data:") && !url.startsWith("javascript:") &&
                !url.contains("replit.com")

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (isExternal(u)) {
                    updateAutoStatus("\u2715 Chặn: ${r.url.host} \u2192 quay lại signup\u2026")
                    v.stopLoading()
                    v.postDelayed({ v.loadUrl("https://replit.com/signup") }, 400)
                    return true
                }
                return false
            }
            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (isExternal(url)) {
                    updateAutoStatus("\u2715 Dừng tải: ${url.take(30)} \u2192 quay lại signup\u2026")
                    v.stopLoading()
                    v.postDelayed({ v.loadUrl("https://replit.com/signup") }, 400)
                }
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (isExternal(url)) {
                    updateAutoStatus("\u2715 Trang ngoài đã tải, quay lại signup\u2026")
                    v.postDelayed({ v.loadUrl("https://replit.com/signup") }, 400)
                    return
                }
                when {
                    url.contains("/signup") -> {
                        val d = (2500L..5000L).random()
                        updateAutoStatus("Chờ trang sẵn sàng (${d/1000}s)\u2026")
                        v.postDelayed({ expandEmailForm(v, email, username, password, fullName) }, d)
                    }
                    url.contains("/onboarding") || url.contains("/plans") -> {
                        val d = (1500L..3000L).random()
                        updateAutoStatus("Đang điền thông tin tài khoản\u2026")
                        v.postDelayed({ injectOnboardingStep(v, fullName) }, d)
                    }
                    url.contains("replit.com") && !url.contains("signup") &&
                    !url.contains("onboarding") && !url.contains("plans") &&
                    url != "https://replit.com/" && url != "https://replit.com" -> {
                        updateAutoStatus("Đăng ký xong, chờ email xác nhận\u2026")
                        waitForVerifyEmail()
                    }
                    (url == "https://replit.com/" || url == "https://replit.com") && autoRegInProgress -> {
                        updateAutoStatus("Đang chờ email xác nhận\u2026")
                        waitForVerifyEmail()
                    }
                }
            }
        }
        binding.mainWebView.loadUrl("https://replit.com/signup")
    }

    // ─── Form injection ───────────────────────────────────────────────────────

    /**
     * Bước 1: Tìm nút "Tiếp tục bằng email" và bấm để hiện form.
     * Nếu form email đã hiện → điền ngay.
     */
    private fun expandEmailForm(
        webView: WebView,
        email: String, username: String, password: String, fullName: String
    ) {
        val js = listOf(
            "(function(){",
            // Bước 0: form email đã hiện → điền ngay
            "  var inputs = document.querySelectorAll('input[type=\"email\"],input[name=\"email\"]');",
            "  if (inputs.length > 0) return 'visible';",
            // Bước 1: ẩn/vô hiệu hoá TẤT CẢ nút OAuth trong DOM
            "  var PROVIDERS = ['google','github',' x','with x','twitter','apple','facebook','microsoft'];",
            "  document.querySelectorAll('button,a,[role=\"button\"],div[tabindex]').forEach(function(el){",
            "    var t = ' ' + (el.textContent||el.innerText||'').toLowerCase() + ' ';",
            "    if (PROVIDERS.some(function(p){ return t.indexOf(p) >= 0; })){",
            "      el.style.display='none'; el.style.pointerEvents='none';",
            "    }",
            "  });",
            // Bước 2: tìm và click nút có chữ "email" (còn hiển thị)
            "  var els = document.querySelectorAll('button,a,[role=\"button\"]');",
            "  for (var i=0; i<els.length; i++){",
            "    if (els[i].style.display === 'none') continue;",
            "    var raw = (els[i].textContent||els[i].innerText||'').trim();",
            "    if (raw.toLowerCase().indexOf('email') >= 0 && raw.length < 50){",
            "      els[i].click(); return 'clicked:' + raw.substring(0,35);",
            "    }",
            "  }",
            "  return 'not-found';",
            "})()"
        ).joinToString("\n")

        webView.evaluateJavascript(js) { result ->
            val r = result?.trim('"') ?: ""
            when {
                r == "visible" -> {
                    val d = (800L..2000L).random()
                    updateAutoStatus("Điền form đăng ký (${d}ms)\u2026")
                    webView.postDelayed({ injectSignupForm(webView, email, username, password, fullName) }, d)
                }
                r.startsWith("clicked:") -> {
                    val d = (2000L..4000L).random()
                    updateAutoStatus("Mở form email, chờ ${d/1000}s\u2026")
                    webView.postDelayed({ injectSignupForm(webView, email, username, password, fullName) }, d)
                }
                else -> {
                    val d = (2000L..3500L).random()
                    updateAutoStatus("Trang chưa sẵn sàng, thử lại sau ${d/1000}s\u2026")
                    webView.postDelayed({ expandEmailForm(webView, email, username, password, fullName) }, d)
                }
            }
        }
    }

    /**
     * Bước 2: Điền form email / username / password và bấm Đăng ký.
     */
    private fun injectSignupForm(
        webView: WebView,
        email: String, username: String, password: String,
        fullName: String = ""
    ) {
        val firstName = fullName.substringBefore(" ").ifEmpty { "User" }
        val lastName  = fullName.substringAfter(" ", "").ifEmpty { "Dev" }
        val js = listOf(
            "(function(){",
            "  function setVal(el, val) {",
            "    try {",
            "      var s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;",
            "      s.call(el, val);",
            "      el.dispatchEvent(new Event('input',  {bubbles: true}));",
            "      el.dispatchEvent(new Event('change', {bubbles: true}));",
            "    } catch(e) { el.value = val; }",
            "  }",
            // ── ẩn nút OAuth ngay khi vào form ──
            "  var OAUTH = ['google','github','facebook','apple','microsoft','twitter',' x ','with x','continue with google','continue with github'];",
            "  document.querySelectorAll('button,a,[role=\"button\"]').forEach(function(el){",
            "    var t = ' '+(el.textContent||el.innerText||'').toLowerCase()+' ';",
            "    if (OAUTH.some(function(k){ return t.indexOf(k)>=0; })){ el.style.display='none'; el.style.pointerEvents='none'; }",
            "  });",
            // ── điền form ──
            "  var filled = 0;",
            "  document.querySelectorAll('input[type=\"email\"],input[name=\"email\"]').forEach(function(el){ setVal(el,'${email}'); filled++; });",
            "  document.querySelectorAll('input[name=\"username\"]').forEach(function(el){ setVal(el,'${username}'); filled++; });",
            "  document.querySelectorAll('input[type=\"password\"]').forEach(function(el){ setVal(el,'${password}'); filled++; });",
            "  document.querySelectorAll('input[name=\"first_name\"],input[placeholder*=\"first\" i]').forEach(function(el){ setVal(el,'${firstName}'); filled++; });",
            "  document.querySelectorAll('input[name=\"last_name\"],input[placeholder*=\"last\" i]').forEach(function(el){ setVal(el,'${lastName}'); filled++; });",
            "  document.querySelectorAll('input[name=\"full_name\"],input[placeholder*=\"name\" i]').forEach(function(el){ setVal(el,'${fullName}'); filled++; });",
            "  if (filled === 0) return 'no-fields';",
            // ── click submit với delay ngẫu nhiên (tránh bot detection) ──
            "  var submitDelay = 900 + Math.floor(Math.random()*1200);",
            "  setTimeout(function(){",
            "    function isVisible(el){ return el.offsetParent!==null && el.style.display!=='none' && el.style.visibility!=='hidden'; }",
            "    function isOAuthBtn(t){ return OAUTH.some(function(k){ return (' '+t+' ').indexOf(k)>=0; }); }",
            // Ưu tiên button[type=submit] visible
            "    var btns = Array.from(document.querySelectorAll('button[type=\"submit\"]')).filter(isVisible);",
            "    if (btns.length === 0) btns = Array.from(document.querySelectorAll('button')).filter(isVisible);",
            "    for (var i=0; i<btns.length; i++){",
            "      var t = (btns[i].textContent||'').toLowerCase().trim();",
            "      if (isOAuthBtn(t)) continue;",
            // 'continue' OK chỉ khi KHÔNG có 'with' — loại 'Continue with X/Google'
            "      var ok = t.indexOf('sign')>=0 || t.indexOf('create')>=0 || t.indexOf('register')>=0",
            "             || (t.indexOf('continue')>=0 && t.indexOf('with')<0 && t.length<20)",
            "             || t.indexOf('next')>=0 || t.indexOf('submit')>=0;",
            "      if (ok){ btns[i].click(); break; }",
            "    }",
            "  }, submitDelay);",
            "  return 'ok:' + filled;",
            "})()"
        ).joinToString("\n")

        webView.evaluateJavascript(js) { result ->
            if (result?.contains("no-fields") == true) {
                updateAutoStatus("Form chưa sẵn sàng, thử lại\u2026")
                webView.postDelayed({ expandEmailForm(webView, email, username, password, fullName) }, 2000)
            } else {
                updateAutoStatus("Đã gửi, chờ email xác nhận\u2026")
                waitForVerifyEmail()
            }
        }
    }

    /**
     * Xử lý toàn bộ luồng Onboarding của Replit:
     * - Bấm Next trên trang "Let's set up your account"
     * - Chọn ngẫu nhiên các tùy chọn trong câu hỏi
     * - Điền tên / bio nếu có
     * - Chọn "Free / Continue with Starter" khi chọn gói
     */
    private fun injectOnboardingStep(webView: WebView, fullName: String) {
        val firstName = fullName.substringBefore(" ").ifEmpty { "User" }
        val lastName  = fullName.substringAfter(" ", "").ifEmpty { "Dev" }
        val js = listOf(
            "(function(){",
            "  function setVal(el,val){",
            "    try{ var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;",
            "    s.call(el,val); el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){el.value=val;}",
            "  }",
            "  function setArea(el,val){",
            "    try{ var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;",
            "    s.call(el,val); el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){el.value=val;}",
            "  }",
            // 1) Chọn gói miễn phí trước (ưu tiên cao nhất)
            "  var planClicked = false;",
            "  document.querySelectorAll('button,a,[role=\"button\"]').forEach(function(el){",
            "    if(planClicked) return;",
            "    var t=(el.textContent||'').toLowerCase();",
            "    if(t.indexOf('starter')>=0||t.indexOf('free')>=0||t.indexOf('continue with free')>=0){",
            "      el.click(); planClicked=true;",
            "    }",
            "  });",
            "  if(planClicked) return 'plan-selected';",
            // 2) Điền tên nếu có input
            "  document.querySelectorAll('input[name=\"full_name\"],input[name=\"fullName\"],input[name=\"name\"]').forEach(function(el){ setVal(el,'${fullName}'); });",
            "  document.querySelectorAll('input[name=\"first_name\"],input[name=\"firstName\"],input[placeholder*=\"first\" i]').forEach(function(el){ setVal(el,'${firstName}'); });",
            "  document.querySelectorAll('input[name=\"last_name\"],input[name=\"lastName\"],input[placeholder*=\"last\" i]').forEach(function(el){ setVal(el,'${lastName}'); });",
            "  document.querySelectorAll('textarea').forEach(function(el){ if(!el.value) setArea(el,'Thích lập trình và xây dựng những thứ hay ho.'); });",
            // 3) Chọn ngẫu nhiên các tùy chọn (role cards, checkboxes, radio)
            "  var opts = Array.from(document.querySelectorAll('[data-testid*=\"option\"],[data-cy*=\"option\"],[class*=\"SelectableCard\"],[class*=\"selectable\"],[class*=\"choice\"],[role=\"checkbox\"],[role=\"radio\"]'));",
            "  if(opts.length > 0){",
            "    var pick = Math.min(opts.length, Math.floor(Math.random()*2)+1);",
            "    var chosen = [];",
            "    while(chosen.length < pick){ var idx=Math.floor(Math.random()*opts.length); if(!chosen.includes(idx)) chosen.push(idx); }",
            "    chosen.forEach(function(i){ opts[i].click(); });",
            "  }",
            "  var radios = document.querySelectorAll('input[type=\"radio\"]');",
            "  if(radios.length>0) radios[Math.floor(Math.random()*radios.length)].click();",
            // 4) Bấm Next / Continue — lọc ketat OAuth
            "  var OB=['google','github','facebook','apple','microsoft','twitter',' x ','with x'];",
            "  function noOb(t){ return !OB.some(function(k){ return (' '+t+' ').indexOf(k)>=0; }); }",
            "  function vis(el){ return el.offsetParent!==null&&el.style.display!=='none'&&el.style.visibility!=='hidden'; }",
            "  var clicked = false;",
            "  setTimeout(function(){",
            "    var btns=Array.from(document.querySelectorAll('button[type=\"submit\"],button')).filter(vis);",
            "    for(var i=0;i<btns.length;i++){",
            "      var t=(btns[i].textContent||'').toLowerCase().trim();",
            "      if(!noOb(t)) continue;",
            // 'continue' OK chỉ nếu KHÔNG có 'with' (loại 'Continue with X/Google')
            "      var ok=t.indexOf('next')>=0||t.indexOf('get started')>=0||t.indexOf('finish')>=0||t.indexOf('done')>=0",
            "            ||(t.indexOf('continue')>=0&&t.indexOf('with')<0&&t.length<18)",
            "            ||(t.indexOf('start')>=0&&t.indexOf('starter')<0);",
            "      if(ok){ btns[i].click(); clicked=true; break; }",
            "    }",
            "  }, 500);",
            "  return clicked ? 'next-clicked' : 'filled';",
            "})()"
        ).joinToString("\n")

        webView.evaluateJavascript(js) { result ->
            val r = result?.trim('"') ?: ""
            if (r == "plan-selected") {
                updateAutoStatus("Đã chọn gói miễn phí \u2713")
            }
            // Sau 4 giây thử lại nếu vẫn còn ở trang onboarding (có thể có nhiều bước)
            webView.postDelayed({
                webView.evaluateJavascript("window.location.pathname") { path ->
                    val p = path?.trim('"') ?: ""
                    if (p.contains("onboarding") || p.contains("plans")) {
                        injectOnboardingStep(webView, fullName)
                    }
                }
            }, 4000)
        }
    }

    // ─── Wait for verify email ────────────────────────────────────────────────

    private fun waitForVerifyEmail() {
        lifecycleScope.launch {
            repeat(36) {
                if (!autoRegInProgress) return@launch
                delay(5000)
                fetchMail()
                if (prefs.getBoolean(KEY_REPLIT_REG, false)) {
                    autoRegInProgress = false
                    withContext(Dispatchers.Main) { hideAutoOverlay() }
                    return@launch
                }
            }
            if (autoRegInProgress) {
                autoRegInProgress = false
                withContext(Dispatchers.Main) {
                    hideAutoOverlay()
                    toast("Hết thời gian - vui lòng xác nhận thủ công trong panel Mail")
                    binding.mainWebView.webViewClient = buildMainClient()
                }
            }
        }
    }

    // ─── Invite flow ──────────────────────────────────────────────────────────

    private fun startInviteFlow() {
        if (inviteInProgress) {
            toast("Đang có lời mời đang xử lý\u2026")
            return
        }
        if (mailToken.isEmpty()) {
            toast("Hãy thiết lập Mail.tm trước (nhấn Tự động)")
            return
        }
        inviteInProgress = true
        updateInviteStatus("Đang tạo email mời\u2026")

        lifecycleScope.launch {
            val domains = getMailDomains()
            if (domains.isEmpty()) {
                withContext(Dispatchers.Main) { updateInviteStatus("Mail.tm không khả dụng"); inviteInProgress = false }
                return@launch
            }
            val domain = if (domains.size > 1) domains[1] else domains[0]
            val user   = "inv" + (1000..9999).random()
            val email  = "$user@$domain"
            val pass   = UUID.randomUUID().toString().replace("-", "").take(16)
            val name   = randomFullName()

            if (!mailCreate(email, pass)) {
                withContext(Dispatchers.Main) { updateInviteStatus("Tạo tài khoản thất bại"); inviteInProgress = false }
                return@launch
            }
            val tok = mailLogin(email, pass)
            if (tok.isEmpty()) {
                withContext(Dispatchers.Main) { updateInviteStatus("Đăng nhập thất bại"); inviteInProgress = false }
                return@launch
            }

            inviteMailToken = tok
            inviteMailEmail = email
            inviteSeenIds.clear()
            prefs.edit()
                .putString(KEY_INVITE_EMAIL, email)
                .putString(KEY_INVITE_PASS,  pass)
                .putString(KEY_INVITE_TOKEN, tok)
                .putString(KEY_INVITE_NAME,  name)
                .apply()

            withContext(Dispatchers.Main) {
                updateInviteStatus("Đang gửi lời mời đến $email\u2026")
                binding.mainWebView.webViewClient = buildInviteWebViewClient(email)
                binding.mainWebView.loadUrl("https://replit.com/refer")
            }
        }
    }

    private fun buildInviteWebViewClient(inviteEmail: String) = object : WebViewClient() {
        private var injected = false
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
        override fun onPageFinished(v: WebView, url: String) {
            when {
                (url.contains("refer") || url.contains("invite")) && !injected -> {
                    injected = true
                    v.postDelayed({ injectInviteEmailField(v, inviteEmail) }, 2000)
                }
                url.contains("replit.com") && !url.contains("refer") && !url.contains("invite") -> {
                    binding.mainWebView.webViewClient = buildMainClient()
                    updateInviteStatus("Đang theo dõi hộp thư lời mời\u2026")
                    startInviteMailPolling()
                }
            }
        }
    }

    private fun injectInviteEmailField(webView: WebView, email: String) {
        val js = listOf(
            "(function(){",
            "  function setVal(el,val){",
            "    try{ var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;",
            "    s.call(el,val); el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){el.value=val;}",
            "  }",
            "  var inputs=document.querySelectorAll('input[type=\"email\"],input[name=\"email\"],input[placeholder*=\"email\" i]');",
            "  if(inputs.length===0) return 'no-input';",
            "  setVal(inputs[0],'${email}');",
            "  setTimeout(function(){",
            "    var btns=document.querySelectorAll('button[type=\"submit\"],button');",
            "    for(var i=0;i<btns.length;i++){",
            "      var t=(btns[i].textContent||'').toLowerCase();",
            "      if(t.indexOf('invite')>=0||t.indexOf('send')>=0||t.indexOf('refer')>=0||t.indexOf('submit')>=0||t.indexOf('continue')>=0){btns[i].click();break;}",
            "    }",
            "  }, 600);",
            "  return 'invited';",
            "})()"
        ).joinToString("\n")

        webView.evaluateJavascript(js) { result ->
            if (result?.contains("no-input") == true) {
                webView.postDelayed({ injectInviteEmailField(webView, email) }, 2000)
            } else {
                updateInviteStatus("Đã gửi lời mời, đang theo dõi hộp thư\u2026")
                startInviteMailPolling()
            }
        }
    }

    private fun startInviteMailPolling() {
        invitePollJob?.cancel()
        invitePollJob = lifecycleScope.launch {
            repeat(36) {
                if (!inviteInProgress) return@launch
                delay(5000)
                fetchInviteMail()
            }
            if (inviteInProgress) {
                inviteInProgress = false
                withContext(Dispatchers.Main) {
                    updateInviteStatus("Hết thời gian - kiểm tra thủ công")
                    binding.mainWebView.webViewClient = buildMainClient()
                }
            }
        }
    }

    private suspend fun fetchInviteMail() {
        if (inviteMailToken.isEmpty()) return
        try {
            val req = Request.Builder()
                .url("$MAILTM/messages?page=1")
                .addHeader("Authorization", "Bearer $inviteMailToken")
                .build()
            val resp = withContext(Dispatchers.IO) { http.newCall(req).execute() }
            if (!resp.isSuccessful) {
                val e = prefs.getString(KEY_INVITE_EMAIL, "") ?: ""
                val p = prefs.getString(KEY_INVITE_PASS,  "") ?: ""
                if (e.isNotEmpty()) { val t = mailLogin(e, p); if (t.isNotEmpty()) inviteMailToken = t }
                return
            }
            val members = JSONObject(resp.body?.string() ?: "{}").optJSONArray("hydra:member") ?: return
            for (i in 0 until members.length()) {
                val msg = members.getJSONObject(i)
                val id  = msg.getString("id")
                if (!inviteSeenIds.contains(id)) {
                    inviteSeenIds.add(id)
                    val sl = msg.optString("subject", "").lowercase()
                    if (sl.contains("invite") || sl.contains("replit") || sl.contains("join")) {
                        fetchAndHandleInviteEmail(id)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchAndHandleInviteEmail(msgId: String) {
        try {
            val req = Request.Builder()
                .url("$MAILTM/messages/$msgId")
                .addHeader("Authorization", "Bearer $inviteMailToken")
                .build()
            val resp     = withContext(Dispatchers.IO) { http.newCall(req).execute() }
            val combined = extractCombined(resp.body?.string() ?: return)
            val finalUrl = findReplitLink(combined, listOf("invite", "join", "signup", "refer")) ?: return
            withContext(Dispatchers.Main) {
                invitePollJob?.cancel()
                updateInviteStatus("Đã nhận lời mời! Đang hoàn thành đăng ký\u2026")
                binding.verifyWebView.loadUrl(finalUrl)
                showVerifyTab()
                if (binding.mailPanel.visibility == View.GONE)
                    binding.mailPanel.visibility = View.VISIBLE
                toast("Tìm thấy link mời \u2013 đang tự đăng ký\u2026")
            }
        } catch (_: Exception) {}
    }

    private fun onInviteSignupComplete() {
        inviteInProgress = false
        invitePollJob?.cancel()
        val invEmail = prefs.getString(KEY_INVITE_EMAIL, "") ?: ""
        updateInviteStatus("\u2713 $invEmail đã đăng ký")
        toast("Tài khoản mời đã đăng ký! \u2713")
        showInboxTab()
    }

    // ─── Link helpers ─────────────────────────────────────────────────────────

    private fun extractCombined(bodyText: String): String {
        return try {
            val json = JSONObject(bodyText)
            "${json.optString("html", "")} ${json.optString("text", "")}"
        } catch (e: Exception) { bodyText }
    }

    private fun findReplitLink(combined: String, keywords: List<String>): String? {
        val prefix = "https://replit.com/"
        val terms  = charArrayOf('"', ' ', '<', '>', '\n', '\r', '\t', '\'')
        var from   = 0
        while (true) {
            val idx = combined.indexOf(prefix, from)
            if (idx < 0) break
            val end       = combined.indexOfAny(terms, idx)
            val candidate = combined.substring(idx, if (end < 0) combined.length else end)
            if (keywords.any { candidate.contains(it) }) return candidate
            from = idx + 1
        }
        return null
    }

    // ─── Overlay helpers ──────────────────────────────────────────────────────

    private fun showAutoOverlay(msg: String) {
        binding.autoRegisterOverlay.visibility = View.VISIBLE
        binding.tvAutoStatus.text = msg
    }

    private fun hideAutoOverlay() {
        binding.autoRegisterOverlay.visibility = View.GONE
    }

    private fun updateAutoStatus(msg: String) {
        runOnUiThread { binding.tvAutoStatus.text = msg }
    }

    private fun updateInviteStatus(msg: String) {
        runOnUiThread { binding.tvInviteStatus.text = msg }
    }

    // ─── Mail list UI ─────────────────────────────────────────────────────────

    private fun addMailItems(msgs: List<Pair<String, String>>, clear: Boolean) {
        if (clear) binding.mailList.removeAllViews()
        msgs.forEach { (id, subject) ->
            val tv = TextView(this).apply {
                text = subject
                textSize = 12f
                setTextColor(0xFFE0E0E0.toInt())
                setPadding(12, 10, 12, 10)
                setOnClickListener { lifecycleScope.launch { fetchAndLoadVerifyLink(id) } }
            }
            binding.mailList.addView(tv, 0)
            val divider = View(this).apply {
                setBackgroundColor(0xFF1F2937.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1)
            }
            binding.mailList.addView(divider, 1)
        }
    }

    // ─── JS Bridge ────────────────────────────────────────────────────────────

    inner class WebBridge {
        @JavascriptInterface
        fun notifyNotLoggedIn() = runOnUiThread { startAutoRegisterFlow() }
    }

    // ─── Overrides ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            binding.verifyWebView.canGoBack() && binding.verifyWebView.visibility == View.VISIBLE ->
                binding.verifyWebView.goBack()
            binding.mainWebView.canGoBack() -> binding.mainWebView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        invitePollJob?.cancel()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
