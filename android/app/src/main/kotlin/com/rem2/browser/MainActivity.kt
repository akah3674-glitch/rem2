package com.rem2.browser

  import android.annotation.SuppressLint
  import android.os.Bundle
  import android.view.View
  import android.webkit.WebResourceRequest
  import android.webkit.WebSettings
  import android.webkit.WebView
  import android.webkit.WebViewClient
  import android.widget.LinearLayout
  import android.widget.TextView
  import android.widget.Toast
  import androidx.appcompat.app.AppCompatActivity
  import androidx.lifecycle.lifecycleScope
  import kotlinx.coroutines.*
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import org.json.JSONObject
  import com.rem2.browser.databinding.ActivityMainBinding
  import java.util.concurrent.TimeUnit

  class MainActivity : AppCompatActivity() {

      private lateinit var binding: ActivityMainBinding
      private val client = OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(10, TimeUnit.SECONDS)
          .build()
      private var mailToken: String = ""
      private var pollJob: Job? = null
      private val seenIds = mutableSetOf<String>()

      @SuppressLint("SetJavaScriptEnabled")
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          binding = ActivityMainBinding.inflate(layoutInflater)
          setContentView(binding.root)

          binding.mainWebView.apply {
              settings.apply {
                  javaScriptEnabled = true
                  domStorageEnabled = true
                  mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                  userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
              }
              webViewClient = object : WebViewClient() {
                  override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
              }
              loadUrl("https://replit.com")
          }

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

          binding.btnSetToken.setOnClickListener {
              val tok = binding.etToken.text.toString().trim()
              if (tok.isNotEmpty()) {
                  mailToken = tok
                  binding.etToken.text.clear()
                  Toast.makeText(this, "Token set", Toast.LENGTH_SHORT).show()
                  startPolling()
              } else {
                  Toast.makeText(this, "Enter token first", Toast.LENGTH_SHORT).show()
              }
          }

          binding.btnRefresh.setOnClickListener {
              if (mailToken.isEmpty()) {
                  Toast.makeText(this, "Set token first", Toast.LENGTH_SHORT).show()
              } else {
                  lifecycleScope.launch { fetchMail(forceAll = true) }
              }
          }
      }

      private fun startPolling() {
          pollJob?.cancel()
          pollJob = lifecycleScope.launch {
              while (isActive) {
                  fetchMail()
                  delay(5000L)
              }
          }
      }

      private fun stopPolling() { pollJob?.cancel(); pollJob = null }

      private suspend fun fetchMail(forceAll: Boolean = false) {
          if (mailToken.isEmpty()) return
          try {
              val req = Request.Builder()
                  .url("https://api.mail.tm/messages?page=1")
                  .addHeader("Authorization", "Bearer " + mailToken)
                  .build()
              val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
              if (!resp.isSuccessful) return
              val body = resp.body?.string() ?: return
              val json = JSONObject(body)
              val members = json.optJSONArray("hydra:member") ?: return
              val newMsgs = mutableListOf<Pair<String, String>>()
              for (i in 0 until members.length()) {
                  val msg = members.getJSONObject(i)
                  val id = msg.getString("id")
                  val subject = msg.optString("subject", "(no subject)")
                  val intro = msg.optString("intro", "")
                  if (forceAll || !seenIds.contains(id)) {
                      seenIds.add(id)
                      newMsgs.add(id to subject)
                      val subjectLower = subject.lowercase()
                      val introLower = intro.lowercase()
                      if (subjectLower.contains("verify") || subjectLower.contains("confirm") ||
                          introLower.contains("replit.com/auth")) {
                          fetchAndOpenVerifyLink(id)
                      }
                  }
              }
              if (newMsgs.isNotEmpty()) {
                  withContext(Dispatchers.Main) { addMailItems(newMsgs, forceAll) }
              }
          } catch (e: Exception) { /* ignore */ }
      }

      private suspend fun fetchAndOpenVerifyLink(msgId: String) {
          try {
              val req = Request.Builder()
                  .url("https://api.mail.tm/messages/" + msgId)
                  .addHeader("Authorization", "Bearer " + mailToken)
                  .build()
              val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
              val bodyText = resp.body?.string() ?: return
              val json = JSONObject(bodyText)
              val html = json.optString("html", "")
              val text = json.optString("text", "")
              val combined = html + " " + text
              val pattern = "https://replit\.com/\S+verify\S+"
              val regex = Regex(pattern)
              val url = regex.find(combined)?.value ?: return
              withContext(Dispatchers.Main) {
                  binding.mainWebView.loadUrl(url)
                  Toast.makeText(this@MainActivity, "Opening Replit verify link", Toast.LENGTH_SHORT).show()
                  binding.mailPanel.visibility = View.GONE
                  stopPolling()
              }
          } catch (e: Exception) { /* ignore */ }
      }

      private fun addMailItems(msgs: List<Pair<String, String>>, clear: Boolean) {
          if (clear) binding.mailList.removeAllViews()
          msgs.forEach { (id, subject) ->
              val tv = TextView(this).apply {
                  text = subject
                  textSize = 12f
                  setTextColor(0xFFFFFFFF.toInt())
                  setPadding(8, 6, 8, 6)
                  setOnClickListener { lifecycleScope.launch { fetchAndOpenVerifyLink(id) } }
              }
              binding.mailList.addView(tv, 0)
          }
      }

      @Suppress("DEPRECATION")
      override fun onBackPressed() {
          if (binding.mainWebView.canGoBack()) binding.mainWebView.goBack()
          else super.onBackPressed()
      }

      override fun onDestroy() { super.onDestroy(); stopPolling() }
  }