package com.golike.ops


import com.rem2.browser.R
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * Rem2BrowserActivity — trình duyệt WebView với URL bar + multi-tab độc lập.
 *
 * Header: ⚡ Rem2  [📋 paste] [URL input] [← back] [🔄 refresh] [📑 new tab] [🗑 clear]
 *
 * Restore behavior:
 *  - Root tab lưu URL hiện tại vào SharedPreferences trong onPause()
 *    (khi user rời khỏi Activity theo bất kỳ cách nào: home, back, v.v.)
 *  - Lần mở tiếp theo tự động load lại URL đó — user vẫn logged-in nhờ WebView cookie persistent
 *
 * Android Back = đóng Activity (không navigate ngược trong WebView).
 * Dùng nút ← trong header để navigate ngược trong WebView history.
 *
 * Multi-tab: 📑 mở Activity instance mới hoàn toàn độc lập (FLAG_ACTIVITY_MULTIPLE_TASK).
 */
@SuppressLint("SetJavaScriptEnabled")
class Rem2BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL       = "rem2_start_url"
        private const val PREF_NAME     = "rem2_browser"
        private const val PREF_LAST_URL = "last_url_solo"
        private const val DEFAULT_URL   = "about:blank"
    }

    private lateinit var webView    : WebView
    private lateinit var etUrl      : EditText
    private lateinit var btnPaste   : TextView
    private lateinit var btnBack    : TextView   // ← navigate trong WebView history
    private lateinit var btnRefresh : TextView
    private lateinit var btnNewTab  : TextView
    private lateinit var btnClear   : TextView
    private lateinit var progress   : ProgressBar

    private var currentUrl: String  = DEFAULT_URL
    private var isRootTab: Boolean  = true   // root tab mới lưu/đọc SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rem2_browser)

        webView    = findViewById(R.id.webViewRem2)
        etUrl      = findViewById(R.id.etUrlRem2)
        btnPaste   = findViewById(R.id.btnPasteRem2)
        btnBack    = findViewById(R.id.btnBackRem2)
        btnRefresh = findViewById(R.id.btnRefreshRem2)
        btnNewTab  = findViewById(R.id.btnNewTabRem2)
        btnClear   = findViewById(R.id.btnClearRem2)
        progress   = findViewById(R.id.progressRem2)

        isRootTab = !intent.getBooleanExtra("is_child_tab", false)

        setupWebView()
        setupButtons()

        // Xác định URL ban đầu
        val startUrl = when {
            intent.hasExtra(EXTRA_URL) ->
                intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
            isRootTab ->
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .getString(PREF_LAST_URL, DEFAULT_URL) ?: DEFAULT_URL
            else -> DEFAULT_URL
        }
        loadUrl(startUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            databaseEnabled        = true
            allowFileAccess        = false
            mixedContentMode       = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode              = WebSettings.LOAD_DEFAULT
            userAgentString        = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String,
                                       favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                etUrl.setText(url)
                // Update nút ← theo trạng thái history
                btnBack.alpha = if (view.canGoBack()) 1f else 0.4f
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                currentUrl = url
                etUrl.setText(url)
                btnBack.alpha = if (view.canGoBack()) 1f else 0.4f
            }
            override fun shouldOverrideUrlLoading(view: WebView,
                                                   request: WebResourceRequest): Boolean {
                currentUrl = request.url.toString()
                etUrl.setText(currentUrl)
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100)
                    android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun setupButtons() {
        // 📋 Paste URL từ clipboard
        btnPaste.setOnClickListener {
            val cm   = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                etUrl.setText(text); loadUrl(text)
            } else {
                Toast.makeText(this, "Clipboard trống", Toast.LENGTH_SHORT).show()
            }
        }

        // ← Navigate ngược trong WebView history (không đóng Activity)
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
            else Toast.makeText(this, "Không còn trang trước", Toast.LENGTH_SHORT).show()
        }

        // 🔄 Reload
        btnRefresh.setOnClickListener {
            if (webView.url != null) webView.reload() else loadUrl(currentUrl)
        }

        // 📑 Mở tab mới độc lập (new Activity instance, không chia sẻ state)
        btnNewTab.setOnClickListener {
            val urlForNewTab = currentUrl.takeIf { it != DEFAULT_URL } ?: DEFAULT_URL
            startActivity(Intent(this, Rem2BrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, urlForNewTab)
                putExtra("is_child_tab", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            })
        }

        // 🗑 Xóa cache + cookie + about:blank
        btnClear.setOnClickListener {
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies { flushed ->
                if (flushed) CookieManager.getInstance().flush()
            }
            if (isRootTab) {
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit().remove(PREF_LAST_URL).apply()
            }
            currentUrl = DEFAULT_URL
            loadUrl(DEFAULT_URL)
            Toast.makeText(this, "Đã xóa cache & cookie", Toast.LENGTH_SHORT).show()
        }

        // URL input → navigate
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = etUrl.text.toString().trim()
                if (input.isNotBlank()) { loadUrl(input); hideKeyboard() }
                true
            } else false
        }
    }

    private fun loadUrl(raw: String) {
        val url = when {
            raw.isBlank() || raw == "about:blank" -> DEFAULT_URL
            raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("about:") -> raw
            raw.contains(" ") -> "https://www.google.com/search?q=${android.net.Uri.encode(raw)}"
            raw.contains(".") -> "https://$raw"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(raw)}"
        }
        currentUrl = url
        etUrl.setText(url)
        webView.loadUrl(url)
    }

    /** Lưu URL hiện tại vào SharedPreferences khi user rời Activity
     *  (back, home, incoming call, v.v.) — đây là URL thực sự user đang xem */
    override fun onPause() {
        super.onPause()
        val urlToSave = webView.url ?: currentUrl
        if (isRootTab && urlToSave.isNotBlank() && urlToSave != DEFAULT_URL) {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putString(PREF_LAST_URL, urlToSave).apply()
        }
    }

    /** Android back = đóng Activity (không navigate WebView ngược).
     *  Dùng nút ← trong header để điều hướng ngược trong trang.
     *  Nhờ vậy khi mở lại browser, URL cũ được restore đúng (không bị ghi đè bởi trang trước). */
    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.webViewClient  = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.clearHistory()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
