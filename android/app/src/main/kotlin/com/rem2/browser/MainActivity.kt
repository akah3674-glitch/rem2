@file:Suppress("DEPRECATION")
package com.rem2.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.*
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
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
        private const val COCCOC_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) coc_coc_browser/109.0.0.0 Chrome/123.0.0.0 Mobile Safari/537.36"

        fun randEmail(domain: String): String {
            val adj  = listOf("bright","swift","calm","bold","wild","keen","cool","dark","nice","fast")
            val noun = listOf("fox","star","byte","hawk","wolf","ace","jet","sky","ray","ion")
            return adj.random() + noun.random() + (1000..9999).random() + "@" + domain
        }
        fun randPassword() = (1..10).map {
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP0123456789".random()
        }.joinToString("") + "!9"
        fun randFullName(): String {
            val first = listOf("Alex","Sam","Jordan","Taylor","Morgan","Casey","Riley","Blake")
            val last  = listOf("Smith","Jones","Brown","Davis","Wilson","Lee","Clark","Hall")
            return first.random() + " " + last.random()
        }
        fun randUsername(): String {
            val adj  = listOf("cool","fast","dark","blue","wild","swift","calm","bright")
            val noun = listOf("fox","hawk","wolf","bear","lion","ace","star","byte")
            return adj.random() + noun.random() + (1000..9999).random()
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val accounts = mutableListOf<AccountEntry>()
    private var panelOpen     = false
    private var showingVerify = false

    private var autoEmail    = ""
    private var autoPassword = ""
    private var autoUsername = ""
    private var mailtmToken  = ""

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
        setupVerifyWebView()
        lifecycleScope.launch { ensureAccount() }
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun setupHeader() {
        binding.btnToggleLog.setOnClickListener {
            panelOpen = !panelOpen
            binding.logPanel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        }
        binding.btnClearLog.setOnClickListener { binding.tvLog.text = "" }
        binding.tabLog.setOnClickListener    { switchTab(false) }
        binding.tabVerify.setOnClickListener { switchTab(true)  }
    }

    private fun switchTab(toVerify: Boolean) {
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

    // ─── Main WebView ─────────────────────────────────────────────────────────

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
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (url.contains("signup") || url.contains("login") || url.contains("register")) {
                    startAutoFillLoop(v)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.contains("verify") || url.contains("confirm-email")) {
                    openVerifyTab(url)
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

    // ─── Verify WebView (mini browser inside panel) ───────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        val vwv = binding.verifyWebView
        vwv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode  = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString   = COCCOC_UA
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(vwv, true)

        vwv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (url.contains("replit.com") &&
                    !url.contains("verify") &&
                    !url.contains("confirm") &&
                    url != "about:blank") {
                    log("Xac thuc email thanh cong!")
                    CookieManager.getInstance().flush()
                }
            }
        }
    }

    private fun openVerifyTab(url: String) = runOnUiThread {
        log("Mo link xac thuc trong panel...")
        binding.verifyWebView.loadUrl(url)
        panelOpen = true
        binding.logPanel.visibility = View.VISIBLE
        switchTab(true)
    }

    // ─── Auto-fill: self-retrying JS loop ─────────────────────────────────────

    private fun startAutoFillLoop(wv: WebView) {
        if (autoEmail.isEmpty()) return
        // Escape single quotes only (safe in JS string)
        val e = autoEmail.replace("'", "\\'")
        val p = autoPassword.replace("'", "\\'")
        val u = autoUsername.replace("'", "\\'")
        // Build JS string using concatenation to avoid any template/escape conflicts
        val js = StringBuilder()
        js.append("(function(){")
        js.append("var attempts=0;")
        js.append("var iv=setInterval(function(){")
        js.append("attempts++;")
        js.append("function fill(sel,val){")
        js.append("var el=document.querySelector(sel);")
        js.append("if(!el)return false;")
        js.append("var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');")
        js.append("if(d&&d.set)d.set.call(el,val);else el.value=val;")
        js.append("el.dispatchEvent(new Event('input',{bubbles:true}));")
        js.append("el.dispatchEvent(new Event('change',{bubbles:true}));")
        js.append("return el.value===val;")
        js.append("}")
        js.append("var ok=0;")
        js.append("ok+=fill('input[type=email]','").append(e).append("')?1:0;")
        js.append("ok+=fill('input[name*=email]','").append(e).append("')?1:0;")
        js.append("ok+=fill('input[type=password]','").append(p).append("')?1:0;")
        js.append("ok+=fill('input[name*=user]','").append(u).append("')?1:0;")
        js.append("ok+=fill('[placeholder*=user]','").append(u).append("')?1:0;")
        js.append("if(ok>=2&&attempts>=2)clearInterval(iv);")
        js.append("if(attempts>=30)clearInterval(iv);")
        js.append("},700);")
        js.append("})()")
        wv.evaluateJavascript(js.toString(), null)
    }

    // ─── Account flow ─────────────────────────────────────────────────────────

    private suspend fun ensureAccount() = withContext(Dispatchers.IO) {
        log("Bat dau...")

        // 1. Get mail.tm domain
        val domain = try {
            val req = Request.Builder().url("$MAILTM/domains").build()
            val res = http.newCall(req).execute()
            JSONArray(res.body?.string() ?: "[]").getJSONObject(0).getString("domain")
        } catch (e: Exception) {
            log("Mail.tm domain loi: ${e.message}")
            "mail.tm"
        }

        // 2. Generate credentials
        autoEmail    = randEmail(domain)
        autoPassword = randPassword()
        autoUsername = randUsername()
        log("Email: $autoEmail")
        log("Pass:  $autoPassword")
        log("User:  $autoUsername")

        // 3. Register mail.tm — retry up to 3 times with new email on conflict
        var loginEmail = autoEmail
        var loginPass  = autoPassword
        for (attempt in 1..3) {
            try {
                val body = JSONObject().apply {
                    put("address", loginEmail)
                    put("password", loginPass)
                }.toString().toRequestBody(JSON_MT)
                val req = Request.Builder().url("$MAILTM/accounts").post(body).build()
                val res = http.newCall(req).execute()
                when (res.code) {
                    201  -> { log("Tao hop thu OK: $loginEmail"); break }
                    422  -> {
                        loginEmail = randEmail(domain)
                        loginPass  = randPassword()
                        log("422 - Thu email moi: $loginEmail")
                    }
                    else -> log("Mail.tm HTTP ${res.code}")
                }
            } catch (e: Exception) {
                log("Mail.tm loi: ${e.message}")
            }
            delay(2000)
        }
        autoEmail    = loginEmail
        autoPassword = loginPass

        // 4. Login mail.tm — retry up to 5 times
        log("Dang nhap Mail.tm...")
        for (attempt in 1..5) {
            try {
                delay(if (attempt == 1) 1000L else 2500L)
                val body = JSONObject().apply {
                    put("address", loginEmail)
                    put("password", loginPass)
                }.toString().toRequestBody(JSON_MT)
                val req = Request.Builder().url("$MAILTM/token").post(body).build()
                val res = http.newCall(req).execute()
                val tok = JSONObject(res.body?.string() ?: "{}").optString("token", "")
                if (tok.isNotEmpty()) {
                    mailtmToken = tok
                    log("Dang nhap Mail.tm OK")
                    break
                } else {
                    log("Token trong ($attempt/5)...")
                }
            } catch (e: Exception) {
                log("Login loi: ${e.message}")
            }
        }

        if (mailtmToken.isEmpty()) log("Khong dang nhap duoc Mail.tm")

        // 5. Save account
        accounts.add(AccountEntry(email = autoEmail, password = autoPassword, username = autoUsername))
        saveAccounts()

        // 6. Navigate to Replit signup
        withContext(Dispatchers.Main) {
            log("Mo Replit signup...")
            binding.webView.loadUrl("https://replit.com/signup")
        }

        // 7. Poll for verify email
        delay(15000)
        if (mailtmToken.isNotEmpty()) {
            pollVerification()
        } else {
            log("Bo qua poll - khong co token")
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
                val msgs = JSONObject(res.body?.string() ?: "{}").optJSONArray("hydra:member") ?: JSONArray()
                if (msgs.length() > 0) {
                    log("${msgs.length()} email trong hop thu")
                    for (i in 0 until msgs.length()) {
                        val msg  = msgs.getJSONObject(i)
                        val subj = msg.optString("subject", "")
                        if (subj.contains("verify", ignoreCase = true) ||
                            subj.contains("confirm", ignoreCase = true) ||
                            subj.contains("Replit", ignoreCase = true)) {
                            log("Email xac thuc: $subj")
                            val req2 = Request.Builder()
                                .url("$MAILTM/messages/${msg.getString("id")}")
                                .header("Authorization", "Bearer $mailtmToken")
                                .build()
                            val html = JSONObject(http.newCall(req2).execute().body?.string() ?: "{}").optString("html", "")
                            val link = extractVerifyLink(html)
                            if (link != null) {
                                log("Tim thay link xac thuc!")
                                withContext(Dispatchers.Main) { openVerifyTab(link) }
                                return@withContext
                            } else {
                                log("Khong tim thay link trong email")
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

    // Use char codes to avoid quote escaping issues in the extractor
    private fun extractVerifyLink(html: String): String? {
        val DQUOTE = 34.toChar()
        val SQUOTE = 39.toChar()
        val SPACE  = 32.toChar()
        val NL     = 10.toChar()
        val LT     = 60.toChar()
        val GT     = 62.toChar()
        val markers = listOf("confirm-email", "verify-email", "email-verification", "verifyEmail")
        for (marker in markers) {
            val idx = html.indexOf(marker)
            if (idx < 0) continue
            var start = idx
            while (start > 0) {
                val ch = html[start - 1]
                if (ch == DQUOTE || ch == SQUOTE || ch == SPACE || ch == NL || ch == LT) break
                start--
            }
            var end = idx
            while (end < html.length) {
                val ch = html[end]
                if (ch == DQUOTE || ch == SQUOTE || ch == GT || ch == SPACE || ch == NL) break
                end++
            }
            val url = html.substring(start, end)
            if (url.startsWith("http")) return url
        }
        return null
    }

    // ─── Back press ───────────────────────────────────────────────────────────

    override fun onBackPressed() {
        when {
            panelOpen && showingVerify && binding.verifyWebView.canGoBack() ->
                binding.verifyWebView.goBack()
            panelOpen -> { binding.logPanel.visibility = View.GONE; panelOpen = false }
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> super.onBackPressed()
        }
    }
}
