@file:Suppress("DEPRECATION")
package com.rem2.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
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

// --- Models -----------------------------------------------------------------

data class AccountEntry(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val password: String,
    val username: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// --- MainActivity -----------------------------------------------------------

@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS        = "rem2_prefs"
        private const val KEY_ACCOUNTS = "accounts_v3"
        private const val MAILTM       = "https://api.mail.tm"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
        private const val COCCOC_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) coc_coc_browser/109.0.0.0 Chrome/123.0.0.0 Mobile Safari/537.36"

        fun randEmail(domain: String): String {
            val adj  = listOf("bright","swift","calm","bold","wild","keen","cool","dark")
            val noun = listOf("fox","star","byte","hawk","wolf","ace","jet","sky")
            return "${adj.random()}${noun.random()}${(1000..9999).random()}@${domain}"
        }
        fun randPassword() = (1..10).map {
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP0123456789".random()
        }.joinToString("") + "!9"
        fun randFullName(): String {
            val first = listOf("Alex","Sam","Jordan","Taylor","Morgan","Casey")
            val last  = listOf("Smith","Jones","Brown","Davis","Wilson","Lee")
            return "${first.random()} ${last.random()}"
        }
        fun randUsername(): String {
            val adj  = listOf("cool","fast","dark","blue","wild","swift")
            val noun = listOf("fox","hawk","wolf","bear","lion","ace")
            return "${adj.random()}${noun.random()}${(1000..9999).random()}"
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private val accounts = mutableListOf<AccountEntry>()
    private var logOpen  = false
    private var autoEmail    = ""
    private var autoPassword = ""
    private var autoUsername = ""
    private var autoName     = ""

    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CookieManager.getInstance().setAcceptCookie(true)

        loadAccounts()
        setupHeader()
        setupWebView()

        lifecycleScope.launch { ensureAccount() }
    }

    // --- Header --------------------------------------------------------------

    private fun setupHeader() {
        binding.btnToggleLog.setOnClickListener {
            logOpen = !logOpen
            binding.logPanel.visibility = if (logOpen) View.VISIBLE else View.GONE
        }
        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
        }
    }

    // --- Log -----------------------------------------------------------------

    private fun log(msg: String) = runOnUiThread {
        val cur = binding.tvLog.text.toString()
        binding.tvLog.text = if (cur.isEmpty()) msg else "$cur\n$msg"
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // --- Accounts ------------------------------------------------------------

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

    // --- WebView -------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
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
            allowFileAccess          = true
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(object : Any() {
            @JavascriptInterface fun onPageReady(url: String) {
                runOnUiThread {
                    if (binding.verifyOverlay.visibility == View.VISIBLE &&
                        (url.contains("replit.com") || url.contains("replit.app")) &&
                        !url.contains("verify") && url != "about:blank") {
                        binding.verifyOverlay.visibility = View.GONE
                        log("Xac thuc thanh cong!")
                    }
                }
            }
        }, "RemBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                injectAutoFill(v, url)
            }
            override fun onPageFinished(v: WebView, url: String) {
                v.evaluateJavascript("RemBridge.onPageReady(window.location.href);", null)
                injectAutoFill(v, url)
                // If onboarding page, auto-fill name
                if (url.contains("onboarding") || url.contains("wizard")) {
                    v.postDelayed({ injectNameFill(v) }, 1500)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.contains("verify") || url.contains("confirm-email")) {
                    showVerifyOverlay(url)
                    return true
                }
                return false
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                binding.progressBar.progress   = newProgress
            }
        }

        wv.loadUrl("https://replit.com/signup")
    }

    // --- Verify overlay ------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun showVerifyOverlay(url: String) = runOnUiThread {
        binding.verifyOverlay.visibility = View.VISIBLE
        binding.verifyWebView.settings.javaScriptEnabled = true
        binding.verifyWebView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.verifyWebView, true)
        binding.verifyWebView.loadUrl(url)
        binding.btnCloseVerify.setOnClickListener {
            binding.verifyOverlay.visibility = View.GONE
        }
        log("Mo trang xac thuc email...")
    }

    // --- Auto-fill JS --------------------------------------------------------

    private fun injectAutoFill(wv: WebView, url: String) {
        if (autoEmail.isEmpty() || autoPassword.isEmpty()) return
        if (!url.contains("signup") && !url.contains("register") && !url.contains("login")) return
        val e = autoEmail.replace("\\", "\\\\").replace("'", "\\'")
        val p = autoPassword.replace("\\", "\\\\").replace("'", "\\'")
        val u = autoUsername.replace("\\", "\\\\").replace("'", "\\'")
        wv.evaluateJavascript(
            "(function(){" +
            "function fill(sel,val){var el=document.querySelector(sel);if(!el||el.value===val)return;" +
            "var nv=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
            "if(nv&&nv.set)nv.set.call(el,val);else el.value=val;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "fill('input[type=email],input[name*=email]','" + e + "');" +
            "fill('input[type=password],input[name*=pass]','" + p + "');" +
            "fill('input[name*=user],input[placeholder*=user]','" + u + "');" +
            "})()", null)
    }

    private fun injectNameFill(wv: WebView) {
        if (autoName.isEmpty()) return
        val n = autoName.replace("'", "\\'")
        wv.evaluateJavascript(
            "(function(){" +
            "function fill(sel,val){var el=document.querySelector(sel);if(!el)return;" +
            "var nv=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
            "if(nv&&nv.set)nv.set.call(el,val);else el.value=val;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "fill('input[placeholder*=name],input[name*=name]','" + n + "');" +
            "var btns=Array.from(document.querySelectorAll('button'));" +
            "var next=btns.find(function(b){return /next|continue/i.test(b.textContent);});" +
            "if(next)next.click();" +
            "})()", null)
    }

    // --- Main account flow ---------------------------------------------------

    private suspend fun ensureAccount() = withContext(Dispatchers.IO) {
        log("Bat dau xu ly tai khoan...")

        // Get mail.tm domain
        log("Lay domain Mail.tm...")
        val domain = try {
            val req = Request.Builder().url("$MAILTM/domains").build()
            val res = http.newCall(req).execute()
            val body = res.body?.string() ?: "[]"
            JSONArray(body).getJSONObject(0).getString("domain")
        } catch (e: Exception) { "mail.tm" }

        // Generate credentials
        autoEmail    = randEmail(domain)
        autoPassword = randPassword()
        autoUsername = randUsername()
        autoName     = randFullName()

        log("Email: $autoEmail")
        log("Pass:  $autoPassword")
        log("User:  $autoUsername")

        // Create mail.tm account
        log("Tao hop thu Mail.tm...")
        try {
            val reqBody = JSONObject().apply {
                put("address", autoEmail); put("password", autoPassword)
            }.toString().toRequestBody(JSON_MT)
            val req = Request.Builder().url("$MAILTM/accounts").post(reqBody).build()
            val res = http.newCall(req).execute()
            if (res.code == 201) log("Tao hop thu OK!")
            else log("Mail.tm code: ${res.code}")
        } catch (e: Exception) { log("Mail.tm loi: ${e.message}") }

        // Save account
        accounts.add(AccountEntry(email = autoEmail, password = autoPassword, username = autoUsername))
        saveAccounts()

        // Load Replit signup
        withContext(Dispatchers.Main) {
            log("Mo trang dang ky Replit...")
            binding.webView.loadUrl("https://replit.com/signup")
        }

        // Wait then poll for verification
        delay(10000)
        pollVerification(autoEmail, autoPassword)
    }

    private suspend fun pollVerification(email: String, password: String) = withContext(Dispatchers.IO) {
        log("Cho email xac thuc tu Replit...")

        val token = try {
            val reqBody = JSONObject().apply {
                put("address", email); put("password", password)
            }.toString().toRequestBody(JSON_MT)
            val req = Request.Builder().url("$MAILTM/token").post(reqBody).build()
            val res = http.newCall(req).execute()
            JSONObject(res.body?.string() ?: "{}").optString("token", "")
        } catch (e: Exception) { "" }

        if (token.isEmpty()) { log("Khong dang nhap duoc Mail.tm"); return@withContext }

        repeat(60) { attempt ->
            delay(5000)
            try {
                val req = Request.Builder().url("$MAILTM/messages")
                    .header("Authorization", "Bearer $token").build()
                val res = http.newCall(req).execute()
                val msgs = JSONObject(res.body?.string() ?: "{}").optJSONArray("hydra:member") ?: JSONArray()
                if (msgs.length() > 0) {
                    for (i in 0 until msgs.length()) {
                        val msg = msgs.getJSONObject(i)
                        val subject = msg.optString("subject", "")
                        if (subject.contains("verify", ignoreCase = true) ||
                            subject.contains("confirm", ignoreCase = true) ||
                            subject.contains("Replit", ignoreCase = true)) {
                            log("Email xac thuc: $subject")
                            val msgId = msg.getString("id")
                            val req2 = Request.Builder().url("$MAILTM/messages/$msgId")
                                .header("Authorization", "Bearer $token").build()
                            val res2 = http.newCall(req2).execute()
                            val fullBody = res2.body?.string() ?: "{}"
                            val html = JSONObject(fullBody).optString("html", "")
                            // Extract verify link using simple string parsing
                            val verifyUrl = extractVerifyLink(html)
                            if (verifyUrl != null) {
                                log("Link xac thuc tim thay!")
                                withContext(Dispatchers.Main) { showVerifyOverlay(verifyUrl) }
                                return@withContext
                            } else {
                                log("Khong tim thay link xac thuc trong email")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (attempt % 6 == 0) log("Dang cho... ${(attempt + 1) * 5}s")
            }
        }
        log("Het thoi gian cho email xac thuc")
    }

    // Extract replit verify link using simple substring matching (no Regex escaping issues)
    private fun extractVerifyLink(html: String): String? {
        val markers = listOf("confirm-email", "verify-email", "email-verification", "verifyEmail")
        for (marker in markers) {
            val idx = html.indexOf(marker)
            if (idx < 0) continue
            // Walk backwards to find start of URL (https://)
            var start = idx
            while (start > 0 && html[start - 1] != '"' && html[start - 1] != '\''
                   && html[start - 1] != '<' && html[start - 1] != ' ' && html[start - 1] != '\n') {
                start--
            }
            // Walk forward to find end of URL
            var end = idx
            while (end < html.length && html[end] != '"' && html[end] != '\''
                   && html[end] != '>' && html[end] != ' ' && html[end] != '\n') {
                end++
            }
            val url = html.substring(start, end)
            if (url.startsWith("http")) return url
        }
        return null
    }

    override fun onBackPressed() {
        when {
            binding.verifyOverlay.visibility == View.VISIBLE ->
                binding.verifyOverlay.visibility = View.GONE
            logOpen -> { binding.logPanel.visibility = View.GONE; logOpen = false }
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> super.onBackPressed()
        }
    }
}