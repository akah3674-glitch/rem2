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
        private const val PREFS          = "rem2"
        private const val KEY_MAIL_EMAIL = "mail_email"
        private const val KEY_MAIL_PASS  = "mail_pass"
        private const val KEY_MAIL_TOKEN = "mail_token"
        private const val KEY_REPLIT_REG = "replit_registered"
        private const val MAILTM         = "https://api.mail.tm"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
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
            if ((u == "https://replit.com" || u == "https://www.replit.com") &&
                !prefs.getBoolean(KEY_REPLIT_REG, false) && !autoRegInProgress) {
                detectAndAutoRegister(v)
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
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
                override fun onPageFinished(v: WebView, url: String) {
                    if (url.contains("replit.com") &&
                        !url.contains("verify") && !url.contains("confirm") &&
                        url != "about:blank") {
                        onVerifyComplete()
                    }
                }
            }
        }
    }

    // ─── Panel UI ─────────────────────────────────────────────────────────────

    private fun setupPanel() {
        binding.fabMail.setOnClickListener {
            if (binding.mailPanel.visibility == View.GONE) {
                binding.mailPanel.visibility = View.VISIBLE
                if (mailToken.isNotEmpty()) startPolling()
            } else {
                binding.mailPanel.visibility = View.GONE
                stopPolling()
            }
        }
        binding.btnClose.setOnClickListener {
            binding.mailPanel.visibility = View.GONE
            stopPolling()
        }
        binding.btnRefresh.setOnClickListener {
            lifecycleScope.launch { fetchMail(forceAll = true) }
        }
        binding.btnAutoSetup.setOnClickListener {
            lifecycleScope.launch { ensureMailAccount(force = true) }
        }
        binding.btnSkipAutoReg.setOnClickListener {
            autoRegInProgress = false
            binding.mainWebView.webViewClient = buildMainClient()
            hideAutoOverlay()
        }
        binding.tabMailList.setOnClickListener { showInboxTab() }
        binding.tabVerify.setOnClickListener   { showVerifyTab() }
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

        withContext(Dispatchers.Main) { binding.tvMailStatus.text = "Creating Mail.tm\u2026" }

        val domains = getMailDomains()
        if (domains.isEmpty()) {
            withContext(Dispatchers.Main) { binding.tvMailStatus.text = "\u2717 Mail.tm unavailable" }
            return
        }
        val domain = domains[0]
        val user   = "rem2" + (1000..9999).random()
        val email  = "$user@$domain"
        val pass   = UUID.randomUUID().toString().replace("-", "").take(16)

        if (!mailCreate(email, pass)) {
            withContext(Dispatchers.Main) { binding.tvMailStatus.text = "\u2717 Create failed" }
            return
        }
        val tok = mailLogin(email, pass)
        if (tok.isEmpty()) {
            withContext(Dispatchers.Main) { binding.tvMailStatus.text = "\u2717 Login failed" }
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
            toast("Mail.tm ready: $email")
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
                val subject = msg.optString("subject", "(no subject)")
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
            val bodyText = resp.body?.string() ?: return
            val json     = JSONObject(bodyText)
            val html     = json.optString("html", "")
            val text     = json.optString("text", "")
            val combined = "$html $text"
            val prefix   = "https://replit.com/"
            val terms    = charArrayOf('"', ' ', '<', '>', '\n', '\r', '\t', '\'')
            var url: String? = null
            var from = 0
            while (true) {
                val idx = combined.indexOf(prefix, from)
                if (idx < 0) break
                val end       = combined.indexOfAny(terms, idx)
                val candidate = combined.substring(idx, if (end < 0) combined.length else end)
                if (candidate.contains("verify") || candidate.contains("confirm")) {
                    url = candidate; break
                }
                from = idx + 1
            }
            val finalUrl = url ?: return
            withContext(Dispatchers.Main) {
                binding.verifyWebView.loadUrl(finalUrl)
                showVerifyTab()
                if (binding.mailPanel.visibility == View.GONE)
                    binding.mailPanel.visibility = View.VISIBLE
                toast("Opening verify link\u2026")
                if (autoRegInProgress) updateAutoStatus("Verifying email in panel\u2026")
            }
        } catch (_: Exception) {}
    }

    private fun onVerifyComplete() {
        prefs.edit().putBoolean(KEY_REPLIT_REG, true).apply()
        autoRegInProgress = false
        hideAutoOverlay()
        showInboxTab()
        toast("Email verified! Replit account ready \u2713")
        binding.mainWebView.webViewClient = buildMainClient()
        binding.mainWebView.loadUrl("https://replit.com")
    }

    // ─── Auto-register Replit ─────────────────────────────────────────────────

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
        showAutoOverlay("Opening signup page\u2026")

        val email    = mailEmail
        val username = "user" + (10000..99999).random()
        val password = "Rem2x" + (100000..999999).random()

        binding.mainWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
            override fun onPageFinished(v: WebView, url: String) {
                when {
                    url.contains("/signup") -> {
                        updateAutoStatus("Filling signup form\u2026")
                        v.postDelayed({ injectSignupForm(v, email, username, password) }, 2500)
                    }
                    url.contains("replit.com") && !url.contains("signup") &&
                    url != "https://replit.com/" && url != "https://replit.com" -> {
                        updateAutoStatus("Signup done, waiting for verify email\u2026")
                        waitForVerifyEmail()
                    }
                    (url == "https://replit.com/" || url == "https://replit.com") && autoRegInProgress -> {
                        updateAutoStatus("Waiting for verification email\u2026")
                        waitForVerifyEmail()
                    }
                }
            }
        }
        binding.mainWebView.loadUrl("https://replit.com/signup")
    }

    private fun injectSignupForm(webView: WebView, email: String, username: String, password: String) {
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
            "  var filled = 0;",
            "  document.querySelectorAll('input[type=\"email\"],input[name=\"email\"]').forEach(function(el){ setVal(el, '${email}'); filled++; });",
            "  document.querySelectorAll('input[name=\"username\"]').forEach(function(el){ setVal(el, '${username}'); filled++; });",
            "  document.querySelectorAll('input[type=\"password\"]').forEach(function(el){ setVal(el, '${password}'); filled++; });",
            "  if (filled === 0) { return 'no-fields'; }",
            "  setTimeout(function(){",
            "    var btns = document.querySelectorAll('button[type=\"submit\"], button');",
            "    for (var i = 0; i < btns.length; i++) {",
            "      var t = (btns[i].textContent || '').toLowerCase();",
            "      if (t.indexOf('sign')>=0||t.indexOf('create')>=0||t.indexOf('continue')>=0) { btns[i].click(); break; }",
            "    }",
            "  }, 800);",
            "  return 'ok:' + filled;",
            "})()"
        ).joinToString("\n")

        webView.evaluateJavascript(js) { result ->
            if (result?.contains("no-fields") == true) {
                updateAutoStatus("Form not ready, retrying\u2026")
                webView.postDelayed({ injectSignupForm(webView, email, username, password) }, 2000)
            } else {
                updateAutoStatus("Submitted, waiting for verify email\u2026")
                waitForVerifyEmail()
            }
        }
    }

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
                    toast("Timeout - please verify manually in Mail panel")
                    binding.mainWebView.webViewClient = buildMainClient()
                }
            }
        }
    }

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

    override fun onDestroy() { super.onDestroy(); stopPolling() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
