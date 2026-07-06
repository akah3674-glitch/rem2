package com.golike.ops


import com.rem2.browser.R
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WebLoginActivity v1.0 — ghép "nội tạng" từ app gốc GoLike Flutter.
 *
 * App gốc GoLike (base.apk) dùng flutter_inappwebview + MyCookieManager để
 * wrap app.golike.net — không dùng API username/password riêng.
 * Activity này làm y chang: mở app.golike.net trong WebView, sau khi user
 * đăng nhập → tự động lấy Bearer token từ localStorage + cookie web thật
 * từ android.webkit.CookieManager.
 *
 * Mục tiêu: gửi /api/withdraw kèm Cookie thật → GoLike server ghi vào
 * "Lịch sử đổi thưởng" như rút thủ công (không chỉ Nhật ký hoạt động).
 */
class WebLoginActivity : AppCompatActivity() {

    companion object {
        const val RESULT_TOKEN    = "web_token"
        const val RESULT_COOKIE   = "web_cookie"
        const val RESULT_USERNAME = "web_username"
        const val RESULT_EMAIL    = "web_email"
        const val RESULT_COIN     = "web_coin"

        // Tất cả key localStorage GoLike có thể dùng
        private val LS_KEYS = listOf(
            "token","access_token","auth_token","user_token",
            "golike_token","jwt","userToken","authToken",
            "accessToken","gl_token","golike_access","Authorization"
        )
    }

    private lateinit var webView : WebView
    private lateinit var tvStatus: TextView
    private lateinit var progress : ProgressBar

    @Volatile private var tokenFound = false
    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_login)
        webView  = findViewById(R.id.webViewLogin)
        tvStatus = findViewById(R.id.tvWebStatus)
        progress = findViewById(R.id.progressWeb)
        setupWebView()
        webView.loadUrl("https://app.golike.net")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            databaseEnabled        = true
            allowFileAccess        = true
            mixedContentMode       = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode              = WebSettings.LOAD_DEFAULT
            // UA giống browser thật để GoLike không redirect sang app-only flow
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // JS Interface: nhận token khi JS phát hiện trong localStorage
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onToken(key: String, value: String) {
                if (!tokenFound && value.length > 20) {
                    tokenFound = true
                    handler.post { handleTokenFound(value) }
                }
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectTokenDetector()
                if (!tokenFound && !url.contains("/login") && !url.contains("/register")) {
                    startPolling()
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }
    }

    /** Inject JS quét localStorage + sessionStorage tìm Bearer token */
    private fun injectTokenDetector() {
        val keysJs = LS_KEYS.joinToString(",") { "\"$it\"" }
        webView.evaluateJavascript("""
            (function(){
                var keys=[$keysJs];
                var stores=[localStorage,sessionStorage];
                for(var s=0;s<stores.length;s++){
                    for(var i=0;i<keys.length;i++){
                        try{
                            var v=stores[s].getItem(keys[i]);
                            if(v&&v.length>20){
                                if(typeof Android!=='undefined') Android.onToken(keys[i],v);
                                return;
                            }
                        }catch(ex){}
                    }
                }
            })();
        """.trimIndent(), null)
    }

    private fun startPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (!tokenFound) {
                    injectTokenDetector()
                    handler.postDelayed(this, 1500)
                }
            }
        }
        pollRunnable = tick
        handler.postDelayed(tick, 1500)
    }

    private fun handleTokenFound(token: String) {
        pollRunnable?.let { handler.removeCallbacks(it) }
        tvStatus.text = "✅ Token lấy được — đang lấy thông tin tài khoản..."

        // Lấy cookie từ CookieManager (giống MyCookieManager trong app gốc)
        val cm = CookieManager.getInstance()
        val rawCookies = listOf(
            cm.getCookie("app.golike.net")     ?: "",
            cm.getCookie("gateway.golike.net") ?: "",
            cm.getCookie(".golike.net")        ?: ""
        ).filter { it.isNotEmpty() }.joinToString("; ")

        lifecycleScope.launch {
            try {
                val info     = GoLikeApi.getUserInfo(token)
                val username = info.optString("username","").ifEmpty { info.optString("name","webuser") }
                val email    = info.optString("email","")
                val coin     = info.optInt("coin", 0)

                tvStatus.text = "✅ @$username  •  ${coin}xu"
                delay(600)

                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(RESULT_TOKEN,    token)
                    putExtra(RESULT_COOKIE,   rawCookies)
                    putExtra(RESULT_USERNAME, username)
                    putExtra(RESULT_EMAIL,    email)
                    putExtra(RESULT_COIN,     coin)
                })
                finish()
            } catch (ex: Exception) {
                tvStatus.text = "⚠️ ${ex.message} — hãy thử lại"
                tokenFound = false
                startPolling()
            }
        }
    }

    override fun onDestroy() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        webView.destroy()
        super.onDestroy()
    }
}
