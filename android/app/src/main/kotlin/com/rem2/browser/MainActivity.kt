@file:Suppress("DEPRECATION")
  package com.rem2.browser

  import android.annotation.SuppressLint
  import android.content.Context
  import android.graphics.Color
  import android.net.Uri
  import android.os.Bundle
  import android.view.KeyEvent
  import android.view.View
  import android.view.inputmethod.EditorInfo
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

  // ─── Models ──────────────────────────────────────────────────────────────────

  data class AccountEntry(
      val id: String = UUID.randomUUID().toString(),
      val email: String,
      val password: String,
      val username: String = "",
      val createdAt: Long = System.currentTimeMillis()
  )

  // ─── MainActivity ─────────────────────────────────────────────────────────────

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
              val adj  = listOf("bright","swift","calm","bold","wild","keen","cool","dark","nice","easy")
              val noun = listOf("fox","star","byte","hawk","wolf","ace","jet","sky","ray","ion")
              return "${adj.random()}${noun.random()}${(1000..9999).random()}@$domain"
          }
          fun randPassword(): String {
              val s = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP0123456789"
              return (1..12).map { s.random() }.joinToString("") + "!9"
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

      private lateinit var binding: ActivityMainBinding
      private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
      private val http = OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

      private val accounts = mutableListOf<AccountEntry>()
      private var logOpen  = false

      // Current auto-fill values
      private var autoEmail    = ""
      private var autoPassword = ""
      private var autoUsername = ""
      private var autoName     = ""

      // ─────────────────────────────────────────────────────────────────────────

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
          binding = ActivityMainBinding.inflate(layoutInflater)
          setContentView(binding.root)

          loadAccounts()
          setupHeader()
          setupWebView()

          lifecycleScope.launch { ensureAccount() }
      }

      // ─── Header ───────────────────────────────────────────────────────────────

      private fun setupHeader() {
          // Paste/clipboard button → toggle log panel
          binding.btnToggleLog.setOnClickListener {
              logOpen = !logOpen
              binding.logPanel.visibility = if (logOpen) View.VISIBLE else View.GONE
          }
          // Trash → clear log
          binding.btnClearLog.setOnClickListener {
              binding.tvLog.text = ""
          }
      }

      // ─── Log helpers ─────────────────────────────────────────────────────────

      private fun log(msg: String) = runOnUiThread {
          val cur = binding.tvLog.text.toString()
          binding.tvLog.text = if (cur.isEmpty()) msg else "$cur\n$msg"
          // Auto-scroll to bottom
          binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
      }

      // ─── Account persistence ─────────────────────────────────────────────────

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

      // ─── WebView setup ────────────────────────────────────────────────────────

      @SuppressLint("SetJavaScriptEnabled")
      private fun setupWebView() {
          val wv = binding.webView
          wv.settings.apply {
              javaScriptEnabled   = true
              domStorageEnabled   = true
              databaseEnabled     = true
              useWideViewPort     = true
              loadWithOverviewMode = true
              setSupportZoom(true)
              builtInZoomControls  = true
              displayZoomControls  = false
              mixedContentMode    = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
              userAgentString     = COCCOC_UA
              allowFileAccess     = true
              javaScriptCanOpenWindowsAutomatically = true
          }
          CookieManager.getInstance().apply {
              setAcceptCookie(true)
              setAcceptThirdPartyCookies(wv, true)
          }

          wv.addJavascriptInterface(object : Any() {
              @JavascriptInterface fun onPageReady(url: String) {
                  runOnUiThread {
                      // Auto-hide verify overlay if on verified page
                      if ((url.contains("replit.com") || url.contains("replit.app")) &&
                          !url.contains("verify") && url != "about:blank") {
                          if (binding.verifyOverlay.visibility == View.VISIBLE) {
                              binding.verifyOverlay.visibility = View.GONE
                              log("✅ Xác thực thành công!")
                          }
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
              }
              override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                  val url = req.url.toString()
                  // If Replit sends to verify email → open in overlay webview
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

      // ─── Verify email overlay ─────────────────────────────────────────────────

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
          log("📧 Mở trang xác thực email...")
      }

      // ─── Auto-fill JS injection ───────────────────────────────────────────────

      private fun injectAutoFill(wv: WebView, url: String) {
          if (autoEmail.isEmpty() || autoPassword.isEmpty()) return
          if (!url.contains("signup") && !url.contains("register") && !url.contains("login")) return

          val eEsc = autoEmail.replace("'", "\\'")
          val pEsc = autoPassword.replace("'", "\\'")
          val uEsc = autoUsername.replace("'", "\\'")

          wv.evaluateJavascript("""
  (function(){
    function fill(sel,val){
      var el=document.querySelector(sel); if(!el||el.value===val) return;
      var nv=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
      if(nv&&nv.set) nv.set.call(el,val); else el.value=val;
      el.dispatchEvent(new Event('input',{bubbles:true}));
      el.dispatchEvent(new Event('change',{bubbles:true}));
    }
    fill('input[type="email"],input[name*="email"],input[name*="mail"]','$eEsc');
    fill('input[type="password"],input[name*="pass"]','$pEsc');
    fill('input[name*="user"],input[placeholder*="username"]','$uEsc');
  })();""", null)
      }

      // ─── Onboarding auto-next ────────────────────────────────────────────────

      private fun injectOnboardingStep(wv: WebView) {
          if (autoName.isEmpty()) return
          val nEsc = autoName.replace("'", "\\'")
          wv.evaluateJavascript("""
  (function(){
    function fill(sel,val){
      var el=document.querySelector(sel); if(!el) return;
      var nv=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
      if(nv&&nv.set) nv.set.call(el,val); else el.value=val;
      el.dispatchEvent(new Event('input',{bubbles:true}));
    }
    fill('input[placeholder*="name"],input[name*="name"]','$nEsc');
    var radios=document.querySelectorAll('input[type="radio"]:not(:checked)');
    if(radios.length>0) radios[0].click();
    var btns=Array.from(document.querySelectorAll('button'));
    var next=btns.find(function(b){return /next|continue|tiep/i.test(b.textContent);});
    if(next) next.click();
  })();""", null)
      }

      // ─── Ensure Mail.tm account + register on Replit ─────────────────────────

      private suspend fun ensureAccount() = withContext(Dispatchers.IO) {
          log("🚀 Bắt đầu xử lý tài khoản...")

          // Step 1: Get available domain from mail.tm
          log("📬 Lấy domain Mail.tm...")
          val domain = try {
              val req = Request.Builder().url("$MAILTM/domains").build()
              val res = http.newCall(req).execute()
              val body = res.body?.string() ?: "[]"
              JSONObject(JSONArray(body).getJSONObject(0).toString()).getString("domain")
          } catch (e: Exception) {
              log("⚠️ Không lấy được domain: ${e.message}")
              "mail.tm"
          }

          // Step 2: Prepare new account credentials
          autoEmail    = randEmail(domain)
          autoPassword = randPassword()
          autoUsername = randUsername()
          autoName     = randFullName()

          log("📧 Email: $autoEmail")
          log("🔑 Pass: $autoPassword")
          log("👤 Username: $autoUsername")

          // Step 3: Register Mail.tm account
          log("📬 Tạo hộp thư Mail.tm...")
          try {
              val reqBody = JSONObject().apply {
                  put("address", autoEmail); put("password", autoPassword)
              }.toString().toRequestBody(JSON_MT)
              val req = Request.Builder().url("$MAILTM/accounts")
                  .post(reqBody).build()
              val res = http.newCall(req).execute()
              val code = res.code
              if (code == 201) log("✅ Tạo hộp thư thành công!")
              else log("ℹ️ Mail.tm: code $code (có thể đã tồn tại)")
          } catch (e: Exception) {
              log("⚠️ Mail.tm lỗi: ${e.message}")
          }

          // Step 4: Save to accounts list
          val acc = AccountEntry(email = autoEmail, password = autoPassword, username = autoUsername)
          accounts.add(acc)
          saveAccounts()

          // Step 5: Navigate WebView to Replit signup and auto-fill
          withContext(Dispatchers.Main) {
              log("🌐 Mở trang đăng ký Replit...")
              binding.webView.loadUrl("https://replit.com/signup")
          }

          // Step 6: Poll Mail.tm for verification email
          delay(8000)
          pollVerification(autoEmail, autoPassword)
      }

      private suspend fun pollVerification(email: String, password: String) = withContext(Dispatchers.IO) {
          log("⏳ Chờ email xác thực từ Replit...")

          // Login to mail.tm
          val token = try {
              val reqBody = JSONObject().apply {
                  put("address", email); put("password", password)
              }.toString().toRequestBody(JSON_MT)
              val req = Request.Builder().url("$MAILTM/token").post(reqBody).build()
              val res = http.newCall(req).execute()
              val body = res.body?.string() ?: "{}"
              JSONObject(body).optString("token", "")
          } catch (e: Exception) { "" }

          if (token.isEmpty()) { log("⚠️ Không đăng nhập được Mail.tm"); return@withContext }

          // Poll for messages (max 60 attempts x 5s = 5 minutes)
          repeat(60) { attempt ->
              delay(5000)
              try {
                  val req = Request.Builder().url("$MAILTM/messages")
                      .header("Authorization", "Bearer $token").build()
                  val res = http.newCall(req).execute()
                  val body = res.body?.string() ?: "{}"
                  val msgs = JSONObject(body).optJSONArray("hydra:member") ?: JSONArray()
                  if (msgs.length() > 0) {
                      log("📨 Nhận được ${msgs.length()} email!")
                      // Find Replit verification email
                      for (i in 0 until msgs.length()) {
                          val msg = msgs.getJSONObject(i)
                          val subject = msg.optString("subject", "")
                          if (subject.contains("verify", ignoreCase = true) ||
                              subject.contains("confirm", ignoreCase = true) ||
                              subject.contains("Replit", ignoreCase = true)) {
                              log("📧 Email xác thực: $subject")
                              // Get full message
                              val msgId = msg.getString("id")
                              val req2 = Request.Builder().url("$MAILTM/messages/$msgId")
                                  .header("Authorization", "Bearer $token").build()
                              val res2 = http.newCall(req2).execute()
                              val fullBody = res2.body?.string() ?: "{}"
                              val html = JSONObject(fullBody).optString("html", "")
                              // Extract verify link
                              val linkRegex = Regex("https://replit\.com[^"'\s<>]+confirm[^"'\s<>]+")
                              val verifyUrl = linkRegex.find(html)?.value
                                  ?: Regex("https://[^"'\s<>]*replit[^"'\s<>]*verify[^"'\s<>]+").find(html)?.value
                              if (verifyUrl != null) {
                                  log("🔗 Link xác thực tìm thấy!")
                                  withContext(Dispatchers.Main) { showVerifyOverlay(verifyUrl) }
                                  return@withContext
                              } else {
                                  log("⚠️ Không tìm thấy link xác thực trong email")
                              }
                          }
                      }
                  }
              } catch (e: Exception) {
                  if (attempt % 6 == 0) log("⏳ Vẫn đang chờ... (${(attempt + 1) * 5}s)")
              }
          }
          log("⏱ Hết thời gian chờ email xác thực")
      }

      override fun onBackPressed() {
          if (binding.verifyOverlay.visibility == View.VISIBLE) {
              binding.verifyOverlay.visibility = View.GONE
          } else if (binding.logPanel.visibility == View.VISIBLE) {
              binding.logPanel.visibility = View.GONE
              logOpen = false
          } else if (binding.webView.canGoBack()) {
              binding.webView.goBack()
          } else {
              super.onBackPressed()
          }
      }
  }