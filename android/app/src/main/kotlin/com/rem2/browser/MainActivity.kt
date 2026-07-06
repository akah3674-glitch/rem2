@file:Suppress("DEPRECATION")
package com.rem2.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.app.Activity
import android.webkit.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.rem2.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.*
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
        private const val SERVER_URL      = "https://zkdjjc--hemv5x7n7p.replit.app"
        private const val DEFAULT_TAB2_URL  = "https://f0b31e09-2ec2-40f2-bfd8-c81e4a04bcb2-00-3vvr3lienriez.pike.replit.dev/api/terminal"
        private const val KEY_DATA_SAVING  = "data_saving"
        private const val DEFAULT_URL      = "https://replit.com/signup"
        private const val MAIL_PASS    = "Mailtm2025Tool"
        private const val COCCOC_UA    =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        private const val NOTIF_CHANNEL = "rem2_done"
        private const val NOTIF_PERM_RC = 1001

        private val COOKIE_URLS = listOf(
            "https://replit.com",
            "https://replit.com/",
            "https://replit.com/~",
            "https://replit.com/repls"
        )
        private const val KEY_TAB_CURRENT  = "tab_current"
        private const val KEY_TAB1_URL     = "tab1_url"
        private const val KEY_TAB2_URL     = "tab2_url"
        private const val KEY_TAB2_INIT    = "tab2_initialized"
        private const val KEY_TAB1_CK_JSON = "tab1_cookies_j"
        private const val KEY_TAB2_CK_JSON = "tab2_cookies_j"
        private const val KEY_TAB1_LS_JSON = "tab1_localstorage_j"
        private const val KEY_TAB2_LS_JSON = "tab2_localstorage_j"
    }

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val accounts     = mutableListOf<AccountEntry>()
    private var panelOpen    = false
    private var showingVerify = false
    private var autoEmail    = ""
    private var autoUsername = ""
    private var flowRunning  = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var dataSaving   = false

    // Batch mode
    private var batchTotal   = 1
    private var batchCurrent = 0

    // Tab management
    private var currentTab      = 1
    private var tab2Initialized = false
    private val tab1Cookies     = mutableMapOf<String, String>()
    private val tab2Cookies     = mutableMapOf<String, String>()
    private var tab1LocalStorageJson = "{}"
    private var tab2LocalStorageJson = "{}"
    private var switchSeq       = 0

    // File chooser
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
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

    // ── ClickBridge: JS → native MotionEvent tap, không cần trợ năng ──────────
    inner class ClickBridge {
        @JavascriptInterface
        fun tapAt(cssX: Float, cssY: Float, dpr: Float) {
            val wv = activeWebView()
            runOnUiThread { simulateTap(wv, cssX * wv.scale.coerceAtLeast(0.01f), cssY * wv.scale.coerceAtLeast(0.01f)) }
        }
        @JavascriptInterface
        fun tapAtPercent(xPct: Float, yPct: Float) {
            val wv = activeWebView()
            runOnUiThread { wv.post { simulateTap(wv, wv.width * xPct.coerceIn(0f,1f), wv.height * yPct.coerceIn(0f,1f)) } }
        }
        @JavascriptInterface
        fun cloudflareChallengeDetected(tabTag: String) {
            runOnUiThread {
                val wv = if (tabTag == "1") binding.webView else binding.webView2
                log("⚡ Cloudflare (xac minh bao mat) — tu tai lai de loai bo trang nay...")
                wv.postDelayed({ wv.reload() }, 1500)
            }
        }
    }

    private fun activeWebView() = if (currentTab == 1) binding.webView else binding.webView2

    private fun simulateTap(wv: WebView, x: Float, y: Float) {
        val t = System.currentTimeMillis()
        val dn = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        wv.dispatchTouchEvent(dn); dn.recycle()
        val up = MotionEvent.obtain(t, t+80L, MotionEvent.ACTION_UP, x, y, 0)
        wv.postDelayed({ wv.dispatchTouchEvent(up); up.recycle() }, 80)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor     = android.graphics.Color.parseColor("#1565C0")
        window.navigationBarColor = android.graphics.Color.parseColor("#1565C0")
        CookieManager.getInstance().setAcceptCookie(true)
        binding.swipeRefresh2.visibility = View.INVISIBLE
        binding.logPanel.visibility  = View.GONE  // ẩn panel khi mở app
        binding.webView2.onPause()
        createNotificationChannel()
        loadAccounts()
        dataSaving = prefs.getBoolean(KEY_DATA_SAVING, false)
        setupHeader()
        setupWebView(binding.webView,  isTab1 = true)
        setupWebView(binding.webView2, isTab1 = false)
        setupSwipeAndGestures()
        setupVerifyWebView()
        log("San sang. Nhan nut tao tai khoan de bat dau.")
        restoreSessionState()
    }

    override fun onPause()  { super.onPause();  saveSessionState(); binding.webView.onPause(); binding.webView2.onPause(); binding.webView.pauseTimers() }
    override fun onResume() { super.onResume(); binding.webView.resumeTimers(); if (currentTab==1) binding.webView.onResume() else binding.webView2.onResume() }
    override fun onStop()   { super.onStop();   binding.webView.onPause(); binding.webView2.onPause() }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        // Notification permission granted — nothing extra needed, next notify() will work
    }

    override fun onBackPressed() {
        val wv = activeWebView()
        when {
            panelOpen && showingVerify && binding.verifyWebView.canGoBack() -> binding.verifyWebView.goBack()
            panelOpen -> { binding.logPanel.visibility = View.GONE; panelOpen = false }
            wv.canGoBack() -> wv.goBack()
            else -> super.onBackPressed()
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "Tao tai khoan xong", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Thong bao khi tao tai khoan Replit thanh cong"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun showDoneNotification(email: String, username: String, batchInfo: String = "") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM_RC)
                return
            }
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        val title = if (batchInfo.isEmpty()) "✅ Tao xong tai khoan" else "✅ $batchInfo"
        val text  = "Email: $email  |  User: $username"
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt() and 0xFFFF, notif) }
        catch (_: SecurityException) {}
    }

    // ─── Account list dialog ──────────────────────────────────────────────────

    private fun showAccountList() {
        if (accounts.isEmpty()) {
            Toast.makeText(this, "Chua co tai khoan nao", Toast.LENGTH_SHORT).show(); return
        }
        val items = accounts.mapIndexed { i, a ->
            "${i+1}. ${a.username.ifEmpty { "?" }}\n${a.email}  |  ${a.password}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📋 Danh sach tai khoan (${accounts.size})")
            .setItems(items) { _, idx ->
                val a = accounts[idx]
                val text = "Email: ${a.email}\nMat khau: ${a.password}\nUsername: ${a.username}"
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("account", text))
                Toast.makeText(this, "✅ Da copy tai khoan ${idx+1}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Xoa tat ca") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Xac nhan xoa")
                    .setMessage("Xoa toan bo ${accounts.size} tai khoan da luu?")
                    .setPositiveButton("Xoa") { _, _ ->
                        accounts.clear(); saveAccounts()
                        Toast.makeText(this, "Da xoa tat ca", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Huy", null).show()
            }
            .setPositiveButton("Dong", null)
            .show()
    }

    // ─── Batch creation ───────────────────────────────────────────────────────

    // ─── Lam mat toi da ───────────────────────────────────────────────────────
    // Kiem tra nhiet do may (Android Thermal API, API 29+) va tu nghi neu may dang nong,
    // giup giam nguy co qua nhiet khi chay batch nhieu tai khoan lien tuc.
    private suspend fun coolDownIfHot() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var waited = 0
        while (waited < 5 * 60_000) {
            val status = try { pm.currentThermalStatus } catch (e: Exception) { return }
            // THERMAL_STATUS_MODERATE = 2 tro len la bat dau nong, nen cho nguoi bot
            if (status < PowerManager.THERMAL_STATUS_MODERATE) return
            withContext(Dispatchers.Main) {
                log("🌡️ May dang nong (muc $status) — tam nghi 20s de lam mat...")
            }
            delay(20_000)
            waited += 20_000
        }
    }

    private fun startBatchFlow(total: Int) {
          batchTotal   = total
          batchCurrent = 0
          val targetTab = currentTab
          val targetWv  = if (targetTab == 1) binding.webView else binding.webView2
          // Giữ CPU awake suốt batch — tránh bị Android ngắt khi màn hình tắt
          wakeLock?.release()
          wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rem2:batch")
          @Suppress("WakelockTimeout")
          wakeLock?.acquire(batchTotal * 18 * 60 * 1000L)  // 18 phút / acc tối đa
          lifecycleScope.launch {
              try {
                  for (i in 1..total) {
                      batchCurrent = i
                      flowRunning  = true
                      autoEmail    = ""; autoUsername = ""
                      coolDownIfHot()
                      withContext(Dispatchers.Main) {
                          val label = if (total == 1) "⏳ Dang tao tai khoan..." else "⏳ Dang tao $i/$total..."
                          binding.btnCreateAccount.isEnabled = false
                          binding.btnCreateAccount.text      = label
                          binding.tvLog.text = ""
                          switchPanelTab(false)
                          clearWebSession(targetTab)
                          targetWv.postDelayed({ targetWv.loadUrl("https://replit.com/signup") }, 300)
                      }
                      try {
                          ensureAccount(targetTab)
                      } catch (e: Exception) {
                          log("❌ Loi tai khoan $i/$total: ${e.message} — bo qua, tao tiep")
                      } finally {
                          flowRunning = false
                      }
                      if (i < total) {
                          // Cu moi 5 tai khoan thi nghi dai hon de may nguoi han
                          val restMs = if (i % 5 == 0) 45_000L else 15_000L
                          log("--- Xong $i/$total — cho ${restMs/1000}s roi tao tiep ---")
                          delay(restMs)
                          coolDownIfHot()
                      }
                  }
                  withContext(Dispatchers.Main) {
                      if (total > 1) {
                          log("✅ Da tao xong $total tai khoan! Nhan giu nut de xem danh sach.")
                          showDoneNotification("Batch $total tai khoan xong", "$total tai khoan da duoc tao")
                      }
                  }
              } finally {
                  // Đảm bảo button luôn được re-enable dù có exception hay CancellationException
                  flowRunning = false
                  wakeLock?.release(); wakeLock = null
                  withContext(Dispatchers.Main) {
                      binding.btnCreateAccount.isEnabled = true
                      binding.btnCreateAccount.text      = "🔄 Tao tai khoan moi"
                  }
              }
          }
      }
  
    // ─── Session persistence ──────────────────────────────────────────────────

    private fun saveSessionState() {
        if (currentTab == 1) saveCookies(tab1Cookies) else saveCookies(tab2Cookies)
        killAutoScripts(binding.webView); killAutoScripts(binding.webView2)
        CookieManager.getInstance().flush()
        fun m2j(m: Map<String,String>) = JSONObject().also { o -> m.forEach { (k,v)->o.put(k,v) } }.toString()
        val snapSeq = ++switchSeq
        val activeWv = if (currentTab == 1) binding.webView else binding.webView2
        snapshotLocalStorage(activeWv) { js ->
            if (snapSeq != switchSeq) return@snapshotLocalStorage
            if (currentTab == 1) tab1LocalStorageJson = js else tab2LocalStorageJson = js
            prefs.edit().putString(KEY_TAB1_LS_JSON, tab1LocalStorageJson)
                .putString(KEY_TAB2_LS_JSON, tab2LocalStorageJson).apply()
        }
        prefs.edit()
            .putInt    (KEY_TAB_CURRENT,  currentTab)
            .putString (KEY_TAB1_URL,     binding.webView.url?.takeIf  { it!="about:blank" } ?: "")
            .putString (KEY_TAB2_URL,     binding.webView2.url?.takeIf { it!="about:blank" } ?: "")
            .putBoolean(KEY_TAB2_INIT,    tab2Initialized)
            .putString (KEY_TAB1_CK_JSON, m2j(tab1Cookies))
            .putString (KEY_TAB2_CK_JSON, m2j(tab2Cookies))
            .putString (KEY_TAB1_LS_JSON, tab1LocalStorageJson)
            .putString (KEY_TAB2_LS_JSON, tab2LocalStorageJson)
            .commit()
    }

    private fun restoreSessionState() {
        val savedTab = prefs.getInt    (KEY_TAB_CURRENT, 1)
        val tab1Url  = prefs.getString (KEY_TAB1_URL, "") ?: ""
        val tab2Url  = prefs.getString (KEY_TAB2_URL, "") ?: ""
        val tab2Init = prefs.getBoolean(KEY_TAB2_INIT, false)
        fun loadMap(json: String, target: MutableMap<String,String>) {
            try { val o=JSONObject(json); o.keys().forEach { target[it]=o.getString(it) } } catch (_: Exception) {}
        }
        loadMap(prefs.getString(KEY_TAB1_CK_JSON,"{}") ?: "{}", tab1Cookies)
        loadMap(prefs.getString(KEY_TAB2_CK_JSON,"{}") ?: "{}", tab2Cookies)
        tab1LocalStorageJson = prefs.getString(KEY_TAB1_LS_JSON, "{}") ?: "{}"
        tab2LocalStorageJson = prefs.getString(KEY_TAB2_LS_JSON, "{}") ?: "{}"
        tab2Initialized = tab2Init
        if (tab1Url.isEmpty() && tab2Url.isEmpty()) {
            // Lần đầu mở app — trang đăng ký mặc định
            binding.webView.loadUrl(DEFAULT_URL); binding.etUrl.setText(DEFAULT_URL)
            return
        }
        if (savedTab == 2 && tab2Init) {
            binding.swipeRefresh.visibility=View.INVISIBLE; binding.swipeRefresh2.visibility=View.VISIBLE
            currentTab=2; binding.btnTabCount.text="2"
            // Header: browser mode
            binding.etUrl.visibility = android.view.View.VISIBLE
            binding.tvTerminalTitle.visibility = android.view.View.GONE
            val u = tab2Url.takeIf { it.isNotEmpty() } ?: DEFAULT_TAB2_URL
            binding.webView2.loadUrl(u); binding.etUrl.setText(u)
        } else if (tab1Url.isNotEmpty()) {
            binding.webView.loadUrl(tab1Url); binding.etUrl.setText(tab1Url)
        }
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun setupHeader() {
        // Nút quay lại — Tab 1: điều hướng trang; Tab 2: về Tab 1
        binding.btnBack.setOnClickListener {
            if (currentTab == 2) { switchBrowserTab(1) }
            else { val wv = activeWebView(); if (wv.canGoBack()) wv.goBack() }
        }

        // Ba chấm ⋮ — menu thích nghi: Tab 1 = automation; Tab 2 = terminal controls
        binding.btnMenu.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            val m = popup.menu
            if (currentTab == 1) {
                m.add(0, 1, 0, if (flowRunning) "⏳ Đang xử lý..." else "1. Tạo tài khoản mới")
                m.add(0, 2, 1, "2. Danh sách tài khoản (${accounts.size})")
                m.add(0, 3, 2, "3. Nhật ký")
                m.add(0, 4, 3, "4. Trang đăng ký")
                m.add(0, 5, 4, if (dataSaving) "5. Tắt tiết kiệm data" else "5. Bật tiết kiệm data")
            } else {
                m.add(0, 10, 0, "1. Trang chủ Google")
                m.add(0, 11, 1, "2. Sao chép địa chỉ")
                m.add(0, 12, 2, if (dataSaving) "3. Tắt tiết kiệm data" else "3. Bật tiết kiệm data")
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1  -> { if (!flowRunning) startBatchFlow(1) else Toast.makeText(this, "Đang xử lý, vui lòng đợi...", Toast.LENGTH_SHORT).show(); true }
                    2  -> { showAccountList(); true }
                    3  -> { panelOpen = !panelOpen; binding.logPanel.visibility = if (panelOpen) View.VISIBLE else View.GONE; true }
                    4  -> { activeWebView().loadUrl(DEFAULT_URL); true }
                    5  -> { applyDataSaving(!dataSaving); true }
                    10 -> { binding.webView2.loadUrl(DEFAULT_TAB2_URL); binding.etUrl.setText(DEFAULT_TAB2_URL); true }
                    11 -> { val u = binding.webView2.url ?: ""; if (u.isNotEmpty()) { android.content.ClipboardManager::class.java.let { cm -> (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("url", u)) }; Toast.makeText(this, "Đã sao chép: $u", Toast.LENGTH_SHORT).show() }; true }
                    12 -> { applyDataSaving(!dataSaving); true }
                    else -> false
                }
            }
            popup.show()
        }

        // btnToggleLog giờ không còn trong header (ẩn trong XML) — giữ click rỗng tránh crash cũ
        binding.btnToggleLog.setOnClickListener { /* no-op — replaced by btnMenu */ }
        binding.btnRefresh.setOnClickListener {
            val wv = activeWebView()
            val sw = if (currentTab==1) binding.swipeRefresh else binding.swipeRefresh2
            sw.isRefreshing = true
            if (currentTab == 2) {
                val cur2 = wv.url
                if (cur2.isNullOrBlank() || cur2 == "about:blank") wv.loadUrl(DEFAULT_TAB2_URL) else wv.reload()
            } else {
                val cur = wv.url
                if (cur.isNullOrBlank() || cur=="about:blank") wv.loadUrl("https://replit.com/signup") else wv.reload()
            }
        }
        binding.tabLog.setOnClickListener    { switchPanelTab(false) }
        binding.tabVerify.setOnClickListener { switchPanelTab(true)  }

        // Nhấn thường → tạo ngay 1 tài khoản (không hỏi); Nhấn giữ → xem danh sách đã tạo
        binding.btnCreateAccount.setOnClickListener {
            if (flowRunning) { Toast.makeText(this, "Dang xu ly, vui long doi...", Toast.LENGTH_SHORT).show() }
            else startBatchFlow(1)
        }
        binding.btnCreateAccount.setOnLongClickListener  { showAccountList(); true }

        binding.etUrl.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                navigateToUrl(binding.etUrl.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
                binding.etUrl.clearFocus(); true
            } else false
        }
        binding.etUrl.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) binding.etUrl.selectAll() }
        binding.btnTabCount.setOnClickListener { switchBrowserTab(if (currentTab==1) 2 else 1) }
    }

    // ─── Cookie helpers ───────────────────────────────────────────────────────

    private fun saveCookies(dst: MutableMap<String,String>) {
        dst.clear()
        val cm = CookieManager.getInstance()
        COOKIE_URLS.forEach { url -> cm.getCookie(url)?.takeIf { it.isNotEmpty() }?.let { dst[url]=it } }
    }

    private fun restoreCookies(src: Map<String,String>, seq: Int, onDone: (()->Unit)? = null) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { _ ->
            if (seq != switchSeq) return@removeAllCookies
            src.forEach { (url, cookies) ->
                cookies.split(";").forEach { kv -> val t=kv.trim(); if (t.isNotEmpty()) cm.setCookie(url, t) }
            }
            cm.flush()
            runOnUiThread { if (seq == switchSeq) onDone?.invoke() }
        }
    }

    // ─── Storage isolation helpers (Android WebView dùng CookieManager/localStorage
    // chung cho toàn app — phải tự cách li từng tab bằng snapshot/restore) ─────────

    private fun jsStringUnquote(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        return try { JSONObject("{\"v\":$raw}").getString("v") } catch (_: Exception) { "{}" }
    }

    /** Dừng hẳn các JS interval/observer tự-điền và tự-bấm đang chạy trên webview này,
     *  để nó không tiếp tục thao túng cookie/localStorage khi tab bị chuyển sang nền. */
    private fun killAutoScripts(wv: WebView) {
        wv.evaluateJavascript(
            "(function(){try{window.__rem2FillSid=null;if(window.__rem2FillIv)clearInterval(window.__rem2FillIv);" +
            "if(window.__rem2FillMo)window.__rem2FillMo.disconnect();}catch(e){}" +
            "try{window.__rem2AutoContinueActive=false;" +
            "if(window.__rem2AcIv)clearInterval(window.__rem2AcIv);" +
            "if(window.__rem2AcMo)window.__rem2AcMo.disconnect();}catch(e){}})();",
            null
        )
    }

    private fun snapshotLocalStorage(wv: WebView, onDone: (String) -> Unit) {
        wv.evaluateJavascript(
            "(function(){try{var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o[k]=localStorage.getItem(k);}return JSON.stringify(o);}catch(e){return '{}';}})();"
        ) { raw -> onDone(jsStringUnquote(raw)) }
    }

    private fun restoreLocalStorage(wv: WebView, json: String, onDone: () -> Unit) {
        val safe = json.replace("\\", "\\\\").replace("'", "\\'")
        wv.evaluateJavascript(
            "(function(){try{localStorage.clear();sessionStorage.clear();var o=JSON.parse('$safe');for(var k in o){localStorage.setItem(k,o[k]);}}catch(e){}})();"
        ) { onDone() }
    }

    // ─── Tab switching ────────────────────────────────────────────────────────

    private fun switchBrowserTab(tab: Int) {
        if (tab == currentTab) return
        runOnUiThread {
            val seq = ++switchSeq
            if (tab == 2) {
                binding.webView.stopLoading(); killAutoScripts(binding.webView); saveCookies(tab1Cookies)
                snapshotLocalStorage(binding.webView) { js -> if (seq == switchSeq) tab1LocalStorageJson = js }
                binding.swipeRefresh.visibility=View.INVISIBLE; binding.swipeRefresh2.visibility=View.VISIBLE
                if (!tab2Initialized) {
                    tab2Initialized = true
                    // tab1Cookies vua duoc chup o tren (saveCookies) — xoa kho chung xong phai
                    // khoi phuc lai ngay cho Tab 1, khong thi Tab 1 se bi mat dang nhap oan.
                    CookieManager.getInstance().removeAllCookies { _ ->
                        if (seq != switchSeq) return@removeAllCookies
                        CookieManager.getInstance().flush()
                        restoreCookies(tab1Cookies, seq) {
                            if (seq != switchSeq) return@restoreCookies
                            tab2LocalStorageJson = "{}"
                            binding.webView2.postDelayed({ binding.webView2.loadUrl(DEFAULT_TAB2_URL) }, 50)
                        }
                    }
                    binding.etUrl.setText(DEFAULT_TAB2_URL)
                } else {
                    restoreCookies(tab2Cookies, seq) {
                        restoreLocalStorage(binding.webView2, tab2LocalStorageJson) {
                            if (seq != switchSeq) return@restoreLocalStorage
                            // KHONG ep reload — webview2 dang tam dung (onPause) van giu nguyen
                            // DOM/scroll trong bo nho, chi can cookie dung la du de cac request
                            // moi (neu co) dung dung session, khong can tai lai tu dau.
                            binding.webView2.url?.takeIf { it.isNotBlank() && it!="about:blank" }
                                ?.let { binding.etUrl.setText(it) }
                        }
                    }
                }
                setTabActive(binding.webView, false); setTabActive(binding.webView2, true)
                currentTab=2; binding.btnTabCount.text="2"
                // Header: browser mode
                binding.etUrl.visibility = android.view.View.VISIBLE
                binding.tvTerminalTitle.visibility = android.view.View.GONE
                if (autoEmail.isNotEmpty() && (binding.webView2.url ?: "").isSignupPage()) {
                    binding.webView2.postDelayed({ injectAutoFill(binding.webView2) }, 500)
                    binding.webView2.postDelayed({ injectAutoFill(binding.webView2) }, 1500)
                }
            } else {
                binding.webView2.stopLoading(); killAutoScripts(binding.webView2); saveCookies(tab2Cookies)
                snapshotLocalStorage(binding.webView2) { js -> if (seq == switchSeq) tab2LocalStorageJson = js }
                binding.swipeRefresh2.visibility=View.INVISIBLE; binding.swipeRefresh.visibility=View.VISIBLE
                setTabActive(binding.webView2, false); setTabActive(binding.webView, true)
                currentTab=1; binding.btnTabCount.text="1"
                // Header: browser mode
                binding.etUrl.visibility = android.view.View.VISIBLE
                binding.tvTerminalTitle.visibility = android.view.View.GONE
                restoreCookies(tab1Cookies, seq) {
                    restoreLocalStorage(binding.webView, tab1LocalStorageJson) {
                        if (seq != switchSeq) return@restoreLocalStorage
                        // KHONG ep reload — giu nguyen DOM/scroll dang co, chi can cookie dung
                        // la du cho cac request moi, tranh cam giac "tai lai tu dau" moi lan chuyen tab.
                        binding.webView.url?.takeIf { it.isNotBlank() && it!="about:blank" }
                            ?.let { binding.etUrl.setText(it) }
                            ?: binding.etUrl.setText("")
                    }
                }
            }
        }
    }

    private fun applyDataSaving(enable: Boolean) {
        dataSaving = enable
        binding.webView.settings.blockNetworkImage  = enable
        binding.webView2.settings.blockNetworkImage = enable
        prefs.edit().putBoolean(KEY_DATA_SAVING, enable).apply()
        Toast.makeText(this,
            if (enable) "🔋 Tiết kiệm data BẬT — ảnh bị ẩn để tiết kiệm 3G/4G"
            else "📶 Tiết kiệm data TẮT — ảnh hiển thị bình thường",
            Toast.LENGTH_SHORT).show()
    }

    private fun setTabActive(wv: WebView, active: Boolean) {
        if (active) { wv.setLayerType(View.LAYER_TYPE_HARDWARE, null); wv.onResume() }
        else        { wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null); wv.onPause()  }
    }

    private fun String.isSignupPage() = contains("signup") || contains("login") || contains("register")

    private fun navigateToUrl(input: String) {
        val s = input.trim(); if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://www.google.com/search?q=${Uri.encode(s)}"
        }
        activeWebView().loadUrl(url); binding.etUrl.setText(url)
    }

    // ─── Panel tabs (Log ↔ Xác thực) ─────────────────────────────────────────

    private fun switchPanelTab(toVerify: Boolean) {
        showingVerify = toVerify
        if (toVerify) {
            binding.logScroll.visibility=View.GONE; binding.verifyWebView.visibility=View.VISIBLE
            binding.tabLog.setBackgroundColor(0xFFF3F4F6.toInt()); binding.tabLog.setTextColor(0xFF6B7280.toInt())
            binding.tabVerify.setBackgroundColor(0xFFFFFFFF.toInt()); binding.tabVerify.setTextColor(0xFF1D4ED8.toInt())
        } else {
            binding.logScroll.visibility=View.VISIBLE; binding.verifyWebView.visibility=View.GONE
            binding.tabLog.setBackgroundColor(0xFFFFFFFF.toInt()); binding.tabLog.setTextColor(0xFF1D4ED8.toInt())
            binding.tabVerify.setBackgroundColor(0xFFF3F4F6.toInt()); binding.tabVerify.setTextColor(0xFF6B7280.toInt())
        }
    }

    // ─── Logging ──────────────────────────────────────────────────────────────

    private fun log(msg: String) = runOnUiThread {
        val cur = binding.tvLog.text.toString()
        binding.tvLog.text = if (cur.isEmpty()) msg else "$cur\n$msg"
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
            arr.put(JSONObject().apply { put("id",a.id); put("email",a.email); put("password",a.password); put("username",a.username) })
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    // ─── WebView setup (dùng chung cho cả 2 tab) ─────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, isTab1: Boolean) {
        val swipe = if (isTab1) binding.swipeRefresh else binding.swipeRefresh2
        swipe.isEnabled = false
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        wv.isScrollbarFadingEnabled = true
        wv.settings.apply {
            blockNetworkImage                     = dataSaving
            javaScriptEnabled                     = true
            domStorageEnabled                     = true
            databaseEnabled                       = true
            useWideViewPort                       = true
            loadWithOverviewMode                  = true
            setSupportZoom(true)
            builtInZoomControls                   = true
            displayZoomControls                   = false
            mixedContentMode                      = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString                       = COCCOC_UA
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode                             = WebSettings.LOAD_DEFAULT
            textZoom                              = 100
            safeBrowsingEnabled                   = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture      = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(ClickBridge(), "ClickBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                val isActive = (isTab1 && currentTab==1) || (!isTab1 && currentTab==2)
                if (isActive) runOnUiThread {
                    swipe.isRefreshing = false
                    if (!binding.etUrl.isFocused && url != "about:blank") binding.etUrl.setText(url)
                }
                // Cloudflare (kể cả trang xác minh bảo mật đa ngôn ngữ) → auto-reload.
                // Dùng watcher lặp lại (khong chi check 1 lan luc onPageFinished) vi noi dung
                // trang Cloudflare co the render/doi ngon ngu muon hon thoi diem nay.
                val tabTag = if (isTab1) "1" else "2"
                v.evaluateJavascript(
                    """
                    (function(){
                      if(window.__rem2CfWatchActive)return;
                      window.__rem2CfWatchActive=true;
                      var sid=Date.now()+'-'+Math.random();window.__rem2CfSid=sid;
                      var KW=['just a moment','checking your browser','ray id','xác minh bảo mật','đang xác minh','chống bot'];
                      function textOf(){try{return ((document.title||'')+'|'+(document.body?document.body.innerText||'':'')).toLowerCase();}catch(e){return '';}}
                      function isCf(){
                        var t=textOf();
                        for(var i=0;i<KW.length;i++)if(t.indexOf(KW[i])!==-1)return true;
                        if(t.indexOf('cloudflare')!==-1&&(location.href.indexOf('cdn-cgi')!==-1||t.indexOf('ray id')!==-1))return true;
                        return false;
                      }
                      var ticks=0;
                      var iv=setInterval(function(){
                        if(window.__rem2CfSid!==sid){clearInterval(iv);return;}
                        ticks++;
                        if(isCf()){
                          clearInterval(iv);window.__rem2CfWatchActive=false;
                          if(window.ClickBridge)window.ClickBridge.cloudflareChallengeDetected('$tabTag');
                          return;
                        }
                        if(ticks>15){clearInterval(iv);window.__rem2CfWatchActive=false;}
                      },1000);
                    })();
                    """.trimIndent(), null
                )
                // Auto-fill
                if (url.isSignupPage() && autoEmail.isNotEmpty()) {
                    injectAutoFill(v)
                    v.postDelayed({ injectAutoFill(v) }, 1200)
                    v.postDelayed({ injectAutoFill(v) }, 3000)
                }
                // Dashboard detect — dùng autoEmail (còn hiệu lực suốt phiên tài khoản,
                // không tắt sớm như flowRunning khi server báo "done" trước khi user
                // xác thực email và các màn onboarding mới thực sự load)
                if (autoEmail.isNotEmpty() && url.contains("replit.com") &&
                    !url.isSignupPage() && !url.contains("verify") &&
                    !url.contains("confirm") && url != "about:blank") {
                    val lbl = if (isTab1) "" else " [Tab 2]"
                    log("✓$lbl Xac thuc thanh cong! Da vao dashboard.")
                    CookieManager.getInstance().flush()
                    injectAutoContinue(v)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.contains("verify") || url.contains("confirm-email") || url.contains("oobCode")) {
                    openVerifyTab(url, view); return true
                }
                return false
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                val isActive = (isTab1 && currentTab==1) || (!isTab1 && currentTab==2)
                if (isActive) runOnUiThread {
                    binding.progressBar.visibility = if (p < 100) View.VISIBLE else View.GONE
                    binding.progressBar.progress   = p
                }
            }
            override fun onShowFileChooser(view: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                if (!isTab1) { cb?.onReceiveValue(null); return false }
                filePathCallback?.onReceiveValue(null); filePathCallback = cb
                return try {
                    val mimes = params?.acceptTypes?.flatMap { it.split(",") }?.map { it.trim() }
                        ?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: listOf("*/*")
                    fileChooserLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = if (mimes.size==1) mimes[0] else "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                        if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }); true
                } catch (e: Exception) { filePathCallback=null; cb?.onReceiveValue(null); log("Loi file chooser: ${e.message}"); false }
            }
        }

        wv.loadUrl("about:blank")

        val lastTouch = longArrayOf(0L)
        wv.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && autoEmail.isNotEmpty()) {
                val url = wv.url ?: ""
                if (url.isSignupPage()) {
                    val now = System.currentTimeMillis()
                    if (now - lastTouch[0] > 1000L) { lastTouch[0]=now; wv.postDelayed({ injectAutoFill(wv) }, 150) }
                }
            }
            false
        }
    }

    // ─── Swipe refresh ────────────────────────────────────────────────────────

    private fun setupSwipeAndGestures() {
        val blue = intArrayOf(0xFF1D4ED8.toInt(), 0xFF60A5FA.toInt())
        binding.swipeRefresh.setColorSchemeColors(*blue); binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }; binding.swipeRefresh.isEnabled=false
        binding.swipeRefresh2.setColorSchemeColors(*blue); binding.swipeRefresh2.setOnRefreshListener { binding.webView2.reload() }; binding.swipeRefresh2.isEnabled=false
    }

    // ─── clearWebSession ──────────────────────────────────────────────────────


      // Chỉ xoá session của MỘT tab (tab đang được dùng để tạo tài khoản),
      // không đụng tới tab còn lại — tránh vạ lây khi 2 tab dùng song song.
      //
      // LƯU Ý QUAN TRỌNG: CookieManager và WebStorage của Android WebView là KHO DÙNG CHUNG
      // cho toàn app (không phải riêng từng WebView) — gọi removeAllCookies()/deleteAllData()
      // sẽ xoá LUÔN session (đăng nhập, localStorage) của tab CÒN LẠI dù không hề đụng tới nó.
      // Đây từng là nguyên nhân bấm "Tạo tài khoản" ở Tab 2 lại làm Tab 1 bị lỗi/văng theo.
      // Fix: chụp lại session hiện tại của tab CÒN LẠI ngay trước khi xoá, rồi khôi phục lại
      // NGAY sau khi xoá xong — chỉ tab đang target mới thực sự mất session.
      private fun clearWebSession(tab: Int = currentTab) {
          val wv          = if (tab == 1) binding.webView  else binding.webView2
          val otherWv     = if (tab == 1) binding.webView2 else binding.webView
          val otherIsTab1 = tab != 1
          killAutoScripts(wv)
          val seq = ++switchSeq

          // Chụp session sống hiện tại của tab kia trước khi xoá toàn bộ kho dùng chung.
          if (otherIsTab1) saveCookies(tab1Cookies) else saveCookies(tab2Cookies)
          snapshotLocalStorage(otherWv) { js ->
              if (seq != switchSeq) return@snapshotLocalStorage
              if (otherIsTab1) tab1LocalStorageJson = js else tab2LocalStorageJson = js

              CookieManager.getInstance().removeAllCookies { _ ->
                  if (seq != switchSeq) return@removeAllCookies
                  CookieManager.getInstance().flush()
                  // Khôi phục NGAY session của tab kia — không để nó bị mất đăng nhập oan.
                  val otherCookies = if (otherIsTab1) tab1Cookies else tab2Cookies
                  restoreCookies(otherCookies, seq) {
                      val otherJson = if (otherIsTab1) tab1LocalStorageJson else tab2LocalStorageJson
                      restoreLocalStorage(otherWv, otherJson) {}
                  }
              }
          }

          if (tab == 1) { tab1Cookies.clear(); tab1LocalStorageJson = "{}" }
          else          { tab2Cookies.clear(); tab2LocalStorageJson = "{}" }
          autoEmail=""; autoUsername=""
          wv.clearCache(true); wv.clearHistory(); wv.clearFormData()
          wv.evaluateJavascript("(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}})();", null)
          wv.loadUrl("about:blank")
          log("Da xoa session cu (Tab $tab) — chuan bi tao tai khoan moi...")
      }

  
    // ─── Verify tab ───────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        binding.verifyWebView.settings.javaScriptEnabled = false
        binding.verifyWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
            override fun shouldOverrideUrlLoading(v: WebView, u: String) = true
        }
        renderVerifyPanel()
    }

    private fun renderVerifyPanel() = runOnUiThread {
        val html = buildString {
            append("<html><body style='font-family:sans-serif;margin:10px;color:#111827'>")
            append("<h3 style='color:#1D4ED8;margin:0 0 6px 0;font-size:15px'>📬 Hop thu Mail.tm</h3>")
            if (autoEmail.isNotEmpty()) append("<p style='color:#6B7280;font-size:11px;margin:0 0 10px 0'>$autoEmail</p>")
            append("<p style='color:#9CA3AF;font-size:12px'>Server tu dong xu ly email xac thuc.</p>")
            append("</body></html>")
        }
        binding.verifyWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    // Mở vào wv được truyền vào (tab đúng chủ của link), không phải luôn "tab đang xem"
      // — tránh mở nhầm link xác thực của tab kia vào tab đang active nếu user đã chuyển tab.
      private fun openVerifyTab(url: String, wv: WebView = activeWebView()) = runOnUiThread {
          log("Tim thay link xac thuc — dang mo...")
          wv.loadUrl(url)
          renderVerifyPanel(); panelOpen=true; binding.logPanel.visibility=View.VISIBLE; switchPanelTab(true)
      }
  
    // ─── Cloud polling ────────────────────────────────────────────────────────

    private suspend fun ensureAccount(targetTab: Int) = withContext(Dispatchers.IO) {
          val targetWv = if (targetTab == 1) binding.webView else binding.webView2
          log("Ket noi server...")
          val jobId = try {
              val res = http.newCall(Request.Builder().url("$SERVER_URL/api/rem2/create")
                  .post("".toRequestBody(null)).build()).execute()
              JSONObject(res.body?.string() ?: "{}").getString("jobId")
          } catch (e: Exception) { log("Loi ket noi: ${e.message}"); return@withContext }
          val batchLabel = if (batchTotal > 1) " ($batchCurrent/$batchTotal)" else ""
          log("Server dang xu ly$batchLabel... ID: ${jobId.take(8)}")

          var lastLogIdx = 0
            var notFoundStreak = 0
            var pollMs = 5_000L
            repeat(150) { attempt ->
                delay(pollMs)
                try {
                    val json   = JSONObject(http.newCall(Request.Builder().url("$SERVER_URL/api/rem2/status/$jobId").build()).execute().body?.string() ?: "{}")
                    if (json.has("error")) {
                        // Server tam thoi khong tim thay job (autoscale doi instance / lanh khoi dong) —
                        // KHONG coi day la loi vinh vien, chi cho lau hon thay vi bat nguoi dung lam lai tu dau.
                        notFoundStreak++
                        if (notFoundStreak == 1 || notFoundStreak % 6 == 0) log("Server dang khoi dong lai, tiep tuc cho$batchLabel...")
                        return@repeat
                    }
                    notFoundStreak = 0
                    val status = json.optString("status", "pending")
                    val logs   = json.optJSONArray("log")
                    if (logs != null) {
                      for (i in lastLogIdx until logs.length()) log("[Cloud] ${logs.getString(i)}")
                      lastLogIdx = logs.length()
                  }
                  // Nhận email sớm để fill form kịp — CHỈ điền vào tab đang tạo tài khoản này,
                  // không đụng tab còn lại (tránh dính nhầm email/pass sang tab kia).
                  if (autoEmail.isEmpty()) {
                      val e = json.optString("email", ""); val u = json.optString("username", "")
                      if (e.isNotEmpty()) {
                          autoEmail=e; autoUsername=u
                          // Đã có email → server cần 30-120s nữa để nhận verify mail
                          // Giảm tần suất poll từ 5s → 12s để giảm nhiệt máy
                          pollMs = 12_000L
                          withContext(Dispatchers.Main) { fillIfOnSignup(targetWv) }
                      }
                  }
                  when (status) {
                      "done" -> {
                          autoEmail    = json.optString("email", "")
                          autoUsername = json.optString("username", "")
                          val link     = json.optString("verifyLink", "")
                          accounts.add(AccountEntry(email=autoEmail, password=MAIL_PASS, username=autoUsername))
                          saveAccounts()
                          val bInfo = if (batchTotal>1) "Tai khoan $batchCurrent/$batchTotal" else ""
                          log("✅ Xong${ if (bInfo.isEmpty()) "" else " ($bInfo)" }! Email: $autoEmail | User: $autoUsername")
                          withContext(Dispatchers.Main) {
                              ensureOnSignup(targetWv)
                              if (link.isNotEmpty()) openVerifyTab(link, targetWv)
                              showDoneNotification(autoEmail, autoUsername, bInfo)
                          }
                          return@withContext
                      }
                      "error" -> { log("❌ Server loi tao tai khoan"); return@withContext }
                      else    -> if (attempt > 0 && attempt % 6 == 0) log("Dang cho... ${attempt*5}s$batchLabel")
                  }
              } catch (e: Exception) { if (attempt % 12 == 0) log("Poll loi: ${e.message}") }
          }
          log("Het thoi gian cho server")
      }
  
    private fun fillIfOnSignup(wv: WebView) { if ((wv.url ?: "").isSignupPage()) injectAutoFill(wv) }
    private fun ensureOnSignup(wv: WebView) {
        val url = wv.url ?: ""
        when {
            url.isSignupPage() ->
                // Vẫn còn trên signup → điền form
                injectAutoFill(wv)
            url.contains("replit.com") && !url.contains("verify") &&
            !url.contains("confirm") && url != "about:blank" ->
                // Đã qua signup, đang ở onboarding/dashboard → bấm Continue
                injectAutoContinue(wv)
            else ->
                // Chưa xác định trang → điều hướng về signup
                wv.loadUrl("https://replit.com/signup")
        }
    }

    // ─── Auto-Fill ────────────────────────────────────────────────────────────

    private fun injectAutoFill(wv: WebView) {
        if (autoEmail.isEmpty()) return
        val e = autoEmail.replace("'", "\\'")
        val p = MAIL_PASS.replace("'", "\\'")
        val u = autoUsername.replace("'", "\\'")
        wv.evaluateJavascript("""
            (function(){
              var sid=Date.now()+'-'+Math.random();
              window.__rem2FillSid=sid;
              if(window.__rem2FillMo){try{window.__rem2FillMo.disconnect();}catch(x){}}
              if(window.__rem2FillIv) clearInterval(window.__rem2FillIv);
              var EMAIL='$e',PASS='$p',USER='$u',lastFill=0,emptyTicks=0;
              function fillReact(el,val){
                if(!el||el.value===val)return;
                var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
                if(s&&s.set)s.set.call(el,val);else el.value=val;
                ['input','change'].forEach(function(t){el.dispatchEvent(new Event(t,{bubbles:true}));});
                el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'a'}));
              }
              function findEmail(){return document.querySelector('input[type=email]')||document.querySelector('input[name*=email i]')||document.querySelector('input[placeholder*=email i]');}
              function findPass(){return document.querySelector('input[type=password]')||document.querySelector('input[name*=pass i]');}
              function findUser(){return document.querySelector('input[autocomplete=username]')||document.querySelector('input[name*=user i]')||document.querySelector('input[placeholder*=username i]');}
              function syntheticClick(el){
                if(!el)return;try{el.focus();}catch(e){}
                ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function(t){try{el.dispatchEvent(new(t.startsWith('pointer')?PointerEvent:MouseEvent)(t,{bubbles:true,cancelable:true,isPrimary:true,button:0}));}catch(e){}});
                try{el.click();}catch(e){}
                try{var r=el.getBoundingClientRect();if(r.width>0&&r.height>0&&window.ClickBridge)window.ClickBridge.tapAt(r.left+r.width/2,r.top+r.height/2,window.devicePixelRatio||1);}catch(e){}
              }
              var pendingTick=null;
              function tick(){
                if(window.__rem2FillSid!==sid)return;
                var now=Date.now();if(now-lastFill<500)return;lastFill=now;
                try{
                  var eEl=findEmail(),pEl=findPass(),uEl=findUser();
                  if(!eEl&&!pEl){emptyTicks++;if(emptyTicks>8){clearInterval(window.__rem2FillIv);if(window.__rem2FillMo)window.__rem2FillMo.disconnect();}return;}
                  emptyTicks=0;
                  if(eEl)fillReact(eEl,EMAIL);if(pEl)fillReact(pEl,PASS);if(uEl)fillReact(uEl,USER);
                  if(eEl&&!pEl)setTimeout(function(){var kws=['continue','next','sign up','create account','get started','submit'];var els=document.querySelectorAll('button:not([disabled]),input[type=submit]:not([disabled])');for(var i=0;i<els.length;i++){var txt=(els[i].innerText||els[i].value||'').trim().toLowerCase();for(var k=0;k<kws.length;k++)if(txt.indexOf(kws[k])!==-1){syntheticClick(els[i]);return;}}},400);
                }catch(err){}
              }
              // Gộp nhiều mutation liên tiếp thành 1 lần tick (debounce) — giảm tải CPU/nhiet do MutationObserver ban ren
              function scheduleTick(){if(pendingTick)return;pendingTick=setTimeout(function(){pendingTick=null;tick();},250);}
              var mo=new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type==='childList'||['style','class','hidden','disabled'].indexOf(m.attributeName)!==-1){scheduleTick();break;}}});
              mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','hidden','disabled','type']});
              window.__rem2FillMo=mo;
              window.__rem2FillIv=setInterval(function(){if(window.__rem2FillSid!==sid){clearInterval(window.__rem2FillIv);return;}tick();},900);
              tick();
              // Re-trigger khi SPA thay đổi route (onboarding dùng pushState/replaceState)
              (function(){var lastUrl=location.href;function checkUrl(){var u=location.href;if(u!==lastUrl){lastUrl=u;if(!isDone()){window.__rem2AutoContinueActive=false;try{if(window.__rem2AcIv)clearInterval(window.__rem2AcIv);}catch(e){}try{if(window.__rem2AcMo)window.__rem2AcMo.disconnect();}catch(e){}setTimeout(function(){if(window.ClickBridge){}},50);}}}setInterval(checkUrl,800);window.addEventListener('popstate',function(){setTimeout(checkUrl,300)});})();
            })();
        """.trimIndent(), null)
    }

    // ─── Auto-Continue onboarding ─────────────────────────────────────────────

    private fun injectAutoContinue(wv: WebView) {
        wv.evaluateJavascript("""
            (function(){
              // Dừng instance cũ (nếu có) trước khi khởi động instance mới
              try{if(window.__rem2AcIv)clearInterval(window.__rem2AcIv);}catch(e){}
              try{if(window.__rem2AcMo)window.__rem2AcMo.disconnect();}catch(e){}
              window.__rem2AutoContinueActive=true;
              var CONTINUE_KW=['continue','next','skip','get started',"let's go",'done','finish','i agree','agree','ok','got it','submit'];
              // STOP_HEADS đã bị loại bỏ — không dừng theo heading text vì Replit A/B test copy bất kỳ lúc nào
              var EXCLUDE_KW=['back','log in','login','create account','upgrade','sign in','sign up','close','cancel','upload'];
              var PAID_PLAN_KW=['core','pro','teams','enterprise','business','$'];
              var PREFER_KW=['starter','free'];
              function syntheticClick(el){
                if(!el)return;try{el.focus();}catch(e){}
                ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function(t){try{el.dispatchEvent(new(t.startsWith('pointer')?PointerEvent:MouseEvent)(t,{bubbles:true,cancelable:true,isPrimary:true,button:0}));}catch(e){}});
                try{el.click();}catch(e){}
                try{var r=el.getBoundingClientRect();if(r.width>0&&r.height>0&&window.ClickBridge)window.ClickBridge.tapAt(r.left+r.width/2,r.top+r.height/2,window.devicePixelRatio||1);}catch(e){}
              }
              function isDone(){return!!document.querySelector('textarea[placeholder*="Make anything" i]')||!!document.querySelector('[placeholder*="Try an example" i]')||!!document.querySelector('textarea[placeholder*="Ask Replit" i]');}
              // headingText/STOP_HEADS đã bỏ — dùng isDone() làm điều kiện dừng duy nhất
              function findContinue(){
                var els=document.querySelectorAll('button,a[role="button"],[role="button"],input[type=submit]');
                var candidates=[];
                for(var i=0;i<els.length;i++){
                  var txt=(els[i].innerText||els[i].value||'').trim().toLowerCase();
                  for(var k=0;k<CONTINUE_KW.length;k++){
                    if(txt===CONTINUE_KW[k]||txt.indexOf(CONTINUE_KW[k])!==-1){candidates.push({el:els[i],txt:txt});break;}
                  }
                }
                if(!candidates.length)return null;
                // Nếu có nhiều nút "continue" (trang chọn plan) — ưu tiên Starter/Free,
                // tuyệt đối không tự bấm nút của plan trả phí (Core/Pro/Teams...)
                for(var p=0;p<candidates.length;p++){
                  var c=candidates[p];
                  if(PREFER_KW.some(function(k){return c.txt.indexOf(k)!==-1;})) return c.el;
                }
                var safe=candidates.filter(function(c){return !PAID_PLAN_KW.some(function(k){return c.txt.indexOf(k)!==-1;});});
                if(safe.length) return safe[0].el;
                if(candidates.length>1) return null; // nhiều nút nhưng toàn plan trả phí — không đoán, chờ vòng sau
                return candidates[0].el;
              }
              function findChoices(excl){var out=[];document.querySelectorAll('button,[role="button"],[role="radio"],[role="option"],[role="checkbox"],input[type="radio"],input[type="checkbox"]').forEach(function(el){if(el===excl||el.disabled)return;var lbl=el.closest('label');var txt=((el.innerText||el.value||(lbl?lbl.innerText:'')||'')+'').trim().toLowerCase();if(!txt||txt.length>120)return;if(EXCLUDE_KW.some(function(k){return txt.indexOf(k)!==-1;}))return;out.push(lbl&&(el.type==='radio'||el.type==='checkbox')?lbl:el);});return out;}
              var lastTick=0;
              function tick(){
                var now=Date.now();if(now-lastTick<500)return false;lastTick=now;
                try{if(isDone())return true;var btn=findContinue();if(btn&&!btn.disabled){syntheticClick(btn);return false;}var cs=findChoices(btn);if(cs.length){var pick=cs[0];syntheticClick(pick);setTimeout(function(){var b=findContinue();if(b&&!b.disabled)syntheticClick(b);},400);}}catch(e){}return false;
              }
              var count=0,pendingTick2=null;
              // Gộp nhiều mutation lien tiep thanh 1 lan tick (debounce) — giam CPU/nhiet
              function scheduleTick2(){if(pendingTick2)return;pendingTick2=setTimeout(function(){pendingTick2=null;if(tick()){clearInterval(iv);mo.disconnect();window.__rem2AutoContinueActive=false;}},250);}
              var mo=new MutationObserver(function(){scheduleTick2();});
              mo.observe(document.body,{childList:true,subtree:true});
              window.__rem2AcMo=mo;
              var iv=setInterval(function(){count++;if(tick()||count>60){clearInterval(iv);mo.disconnect();window.__rem2AutoContinueActive=false;window.__rem2AcIv=null;window.__rem2AcMo=null;}},1200);
              window.__rem2AcIv=iv;
              tick();
            })();
        """.trimIndent(), null)
    }
}


