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
        private const val SERVER_URL   = "https://zkdjjc--hemv5x7n7p.replit.app"
        private const val KEY_DATA_SAVING  = "data_saving"
        private const val DEFAULT_URL  = "https://replit.com/signup"
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
        private const val KEY_TAB1_URL     = "tab1_url"
        private const val KEY_TAB1_CK_JSON = "tab1_cookies_j"
        private const val KEY_TAB1_LS_JSON = "tab1_localstorage_j"
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
    private var batchJob: kotlinx.coroutines.Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dataSaving   = false

    // Batch mode
    private var batchTotal   = 1
    private var batchCurrent = 0

    private val tab1Cookies     = mutableMapOf<String, String>()
    private var tab1LocalStorageJson = "{}"


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

    // ── ClickBridge: JS → native ──────────────────────────────────────────────
    inner class ClickBridge {
        @JavascriptInterface
        fun cloudflareChallengeDetected(tabTag: String) {
            runOnUiThread {
                log("Cloudflare phat hien — tu tai lai...")
                binding.webView.postDelayed({ binding.webView.reload() }, 1500)
            }
        }
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
        binding.logPanel.visibility = View.GONE
        createNotificationChannel()
        loadAccounts()
        dataSaving     = prefs.getBoolean(KEY_DATA_SAVING, false)
        setupHeader()
        setupWebView(binding.webView)
        setupSwipeAndGestures()
        setupVerifyWebView()
        log("San sang. Nhan nut tao tai khoan de bat dau.")
        restoreSessionState()
    }

    override fun onPause()  { super.onPause();  saveSessionState(); binding.webView.onPause(); binding.webView.pauseTimers() }
    override fun onResume() { super.onResume(); binding.webView.resumeTimers(); binding.webView.onResume() }
    override fun onStop()   { super.onStop();   binding.webView.onPause() }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
    }

    override fun onBackPressed() {
        when {
            panelOpen && showingVerify && binding.verifyWebView.canGoBack() -> binding.verifyWebView.goBack()
            panelOpen -> { binding.logPanel.visibility = View.GONE; panelOpen = false }
            binding.webView.canGoBack() -> binding.webView.goBack()
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
        val title = if (batchInfo.isEmpty()) "Tao xong tai khoan" else batchInfo
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
            .setTitle("Danh sach tai khoan (${accounts.size})")
            .setItems(items) { _, idx ->
                val a = accounts[idx]
                val text = "Email: ${a.email}\nMat khau: ${a.password}\nUsername: ${a.username}"
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("account", text))
                Toast.makeText(this, "Da copy tai khoan ${idx+1}", Toast.LENGTH_SHORT).show()
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

    // ─── Lam mat toi da ───────────────────────────────────────────────────────
    private suspend fun coolDownIfHot() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var waited = 0
        while (waited < 5 * 60_000) {
            val status = try { pm.currentThermalStatus } catch (e: Exception) { return }
            if (status < PowerManager.THERMAL_STATUS_MODERATE) return
            withContext(Dispatchers.Main) {
                log("May dang nong (muc $status) — tam nghi 20s de lam mat...")
            }
            delay(20_000)
            waited += 20_000
        }
    }

    private fun startBatchFlow(total: Int) {
        batchTotal   = total
        batchCurrent = 0
        val wv = binding.webView
        wakeLock?.release()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rem2:batch")
        @Suppress("WakelockTimeout")
        wakeLock?.acquire(batchTotal * 18 * 60 * 1000L)
        batchJob = lifecycleScope.launch {
            try {
                for (i in 1..total) {
                    batchCurrent = i
                    flowRunning  = true
                    autoEmail    = ""; autoUsername = ""
                    coolDownIfHot()
                    withContext(Dispatchers.Main) {
                        binding.btnCreateAccount.isEnabled = true
                        binding.btnCreateAccount.text = if (total == 1) "[DNG] Dung" else "[DNG] Dung ($i/$total)"
                        binding.tvLog.text = ""
                        switchPanelTab(false)
                        clearWebSession()
                        wv.postDelayed({ wv.loadUrl("https://replit.com/signup") }, 300)
                    }
                    try {
                        ensureAccount(wv)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Loi tai khoan $i/$total: ${e.message} — bo qua, tao tiep")
                    } finally {
                        flowRunning = false
                    }
                    if (i < total) {
                        val restMs = if (i % 5 == 0) 45_000L else 15_000L
                        log("--- Xong $i/$total — cho ${restMs/1000}s roi tao tiep ---")
                        delay(restMs)
                        coolDownIfHot()
                    }
                }
                withContext(Dispatchers.Main) {
                    if (total > 1) {
                        log("Da tao xong $total tai khoan! Nhan giu nut de xem danh sach.")
                        showDoneNotification("Batch $total tai khoan xong", "$total tai khoan da duoc tao")
                    }
                }
            } finally {
                flowRunning = false
                wakeLock?.release(); wakeLock = null
                withContext(Dispatchers.Main) {
                    binding.btnCreateAccount.isEnabled = true
                    binding.btnCreateAccount.text = "Tao tai khoan moi"
                }
            }
        }
    }

    private fun stopBatchFlow() {
        log("Dang dung...")
        batchJob?.cancel()
        batchJob = null
    }

    // ─── Session persistence ──────────────────────────────────────────────────

    private fun saveSessionState() {
        killAutoScripts(binding.webView)
        CookieManager.getInstance().flush()
        saveCookies(tab1Cookies)
        fun m2j(m: Map<String,String>) = JSONObject().also { o -> m.forEach { (k,v)->o.put(k,v) } }.toString()
        snapshotLocalStorage(binding.webView) { js ->
            tab1LocalStorageJson = js
            prefs.edit().putString(KEY_TAB1_LS_JSON, js).apply()
        }
        prefs.edit()
            .putString(KEY_TAB1_URL,     binding.webView.url?.takeIf { it != "about:blank" } ?: "")
            .putString(KEY_TAB1_CK_JSON, m2j(tab1Cookies))
            .commit()
    }

    private fun restoreSessionState() {
        fun loadMap(json: String, target: MutableMap<String,String>) {
            try { val o=JSONObject(json); o.keys().forEach { target[it]=o.getString(it) } } catch (_: Exception) {}
        }
        loadMap(prefs.getString(KEY_TAB1_CK_JSON,"{}") ?: "{}", tab1Cookies)
        tab1LocalStorageJson = prefs.getString(KEY_TAB1_LS_JSON, "{}") ?: "{}"
        val savedUrl = prefs.getString(KEY_TAB1_URL, "") ?: ""
        if (savedUrl.isNotEmpty()) {
            binding.webView.loadUrl(savedUrl); binding.etUrl.setText(savedUrl)
        } else {
            binding.webView.loadUrl(DEFAULT_URL); binding.etUrl.setText(DEFAULT_URL)
        }
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun setupHeader() {
        binding.tvTitle.setOnClickListener { showAccountList() }

        binding.btnToggleLog.setOnClickListener {
            panelOpen = !panelOpen
            binding.logPanel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        }
        binding.btnToggleLog.setOnLongClickListener { showAccountList(); true }

        binding.btnRefresh.setOnClickListener {
            binding.swipeRefresh.isRefreshing = true
            val cur = binding.webView.url
            if (cur.isNullOrBlank() || cur == "about:blank") binding.webView.loadUrl(DEFAULT_URL)
            else binding.webView.reload()
        }

        binding.tabLog.setOnClickListener    { switchPanelTab(false) }
        binding.tabVerify.setOnClickListener { switchPanelTab(true)  }

        binding.btnCreateAccount.setOnClickListener {
            if (flowRunning) {
                AlertDialog.Builder(this)
                    .setTitle("Dung qua trinh?")
                    .setMessage("Huy bo viec tao tai khoan dang chay?")
                    .setPositiveButton("Dung lai") { _, _ -> stopBatchFlow() }
                    .setNegativeButton("Tiep tuc", null)
                    .show()
            } else {
                startBatchFlow(1)
            }
        }
        binding.btnCreateAccount.setOnLongClickListener { showAccountList(); true }

        binding.etUrl.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                navigateToUrl(binding.etUrl.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
                binding.etUrl.clearFocus(); true
            } else false
        }
        binding.etUrl.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) binding.etUrl.selectAll() }

        updateDataSavingBtn()
        binding.btnDataSaving.setOnClickListener {
            dataSaving = !dataSaving
            prefs.edit().putBoolean(KEY_DATA_SAVING, dataSaving).apply()
            applyDataSaving()
            updateDataSavingBtn()
            val msg = if (dataSaving) "Tiet kiem data: BAT (anh an)" else "Tiet kiem data: TAT (hien anh)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

    }

    // ─── Data saving helpers ──────────────────────────────────────────────────

    private fun updateDataSavingBtn() {
        binding.btnDataSaving.text  = if (dataSaving) "OFF" else "NET"
        binding.btnDataSaving.alpha = if (dataSaving) 0.6f else 1.0f
    }

    private fun applyDataSaving() {
        binding.webView.settings.blockNetworkImage = dataSaving
    }

    // ─── Cookie helpers ───────────────────────────────────────────────────────

    private fun saveCookies(dst: MutableMap<String,String>) {
        dst.clear()
        val cm = CookieManager.getInstance()
        COOKIE_URLS.forEach { url -> cm.getCookie(url)?.takeIf { it.isNotEmpty() }?.let { dst[url]=it } }
    }

    private fun restoreCookies(src: Map<String,String>, onDone: (()->Unit)? = null) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { _ ->
            src.forEach { (url, cookies) ->
                cookies.split(";").forEach { kv -> val t=kv.trim(); if (t.isNotEmpty()) cm.setCookie(url, t) }
            }
            cm.flush()
            runOnUiThread { onDone?.invoke() }
        }
    }

    // ─── Storage helpers ──────────────────────────────────────────────────────

    private fun jsStringUnquote(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        return try { JSONObject("{\"v\":$raw}").getString("v") } catch (_: Exception) { "{}" }
    }

    private fun killAutoScripts(wv: WebView) {
        wv.evaluateJavascript(
            "(function(){try{window.__rem2FillSid=null;if(window.__rem2FillIv)clearInterval(window.__rem2FillIv);" +
            "if(window.__rem2FillMo)window.__rem2FillMo.disconnect();}catch(e){}" +
            "try{window.__rem2RecordActive=false;}catch(e){}" +
            "})();",
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

    private fun navigateToUrl(input: String) {
        val s = input.trim(); if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://www.google.com/search?q=${Uri.encode(s)}"
        }
        binding.webView.loadUrl(url); binding.etUrl.setText(url)
    }

    // ─── Panel tabs (Log ↔ Mail) ──────────────────────────────────────────────

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

    // ─── WebView setup ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        binding.swipeRefresh.isEnabled = false
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
                runOnUiThread {
                    binding.swipeRefresh.isRefreshing = false
                    if (!binding.etUrl.isFocused && url != "about:blank") binding.etUrl.setText(url)
                }

                // Browser fingerprint patch — giong trinh duyet that hon
                v.evaluateJavascript("""
                    (function(){
                      try{Object.defineProperty(navigator,'webdriver',{get:()=>false});}catch(e){}
                      if(!window.chrome){window.chrome={runtime:{},loadTimes:function(){},csi:function(){},app:{}};}
                    })();
                """.trimIndent(), null)

                // Cloudflare watcher
                v.evaluateJavascript("""
                    (function(){
                      if(window.__rem2CfWatchActive)return;
                      window.__rem2CfWatchActive=true;
                      var sid=Date.now()+'-'+Math.random();window.__rem2CfSid=sid;
                      var KW=['just a moment','checking your browser','ray id','xac minh bao mat','dang xac minh','chong bot'];
                      function textOf(){try{return((document.title||'')+'|'+(document.body?document.body.innerText||'':'')).toLowerCase();}catch(e){return '';}}
                      function isCf(){var t=textOf();for(var i=0;i<KW.length;i++)if(t.indexOf(KW[i])!==-1)return true;if(t.indexOf('cloudflare')!==-1&&(location.href.indexOf('cdn-cgi')!==-1||t.indexOf('ray id')!==-1))return true;return false;}
                      var ticks=0;var iv=setInterval(function(){if(window.__rem2CfSid!==sid){clearInterval(iv);return;}ticks++;if(isCf()){clearInterval(iv);window.__rem2CfWatchActive=false;if(window.ClickBridge)window.ClickBridge.cloudflareChallengeDetected('1');return;}if(ticks>15){clearInterval(iv);window.__rem2CfWatchActive=false;}},1000);
                    })();
                """.trimIndent(), null)

                // Auto-fill khi co email va dang tren trang signup
                if (url.isSignupPage() && autoEmail.isNotEmpty()) {
                    injectAutoFill(v)
                    v.postDelayed({ injectAutoFill(v) }, 1200)
                    v.postDelayed({ injectAutoFill(v) }, 3000)
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
                runOnUiThread {
                    binding.progressBar.visibility = if (p < 100) View.VISIBLE else View.GONE
                    binding.progressBar.progress   = p
                }
            }
            override fun onShowFileChooser(view: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
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
        binding.swipeRefresh.setColorSchemeColors(*blue)
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.swipeRefresh.isEnabled = false
    }

    // ─── clearWebSession ──────────────────────────────────────────────────────

    private fun clearWebSession() {
        killAutoScripts(binding.webView)
        tab1Cookies.clear()
        tab1LocalStorageJson = "{}"
        autoEmail = ""; autoUsername = ""
        val wv = binding.webView
        wv.clearCache(true); wv.clearHistory(); wv.clearFormData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }
        wv.evaluateJavascript("(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}})();", null)
        wv.loadUrl("about:blank")
        log("Da xoa session cu — chuan bi tao tai khoan moi...")
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
            append("<h3 style='color:#1D4ED8;margin:0 0 6px 0;font-size:15px'>Hop thu Mail.tm</h3>")
            if (autoEmail.isNotEmpty()) append("<p style='color:#6B7280;font-size:11px;margin:0 0 10px 0'>$autoEmail</p>")
            append("<p style='color:#9CA3AF;font-size:12px'>Server tu dong xu ly email xac thuc.</p>")
            append("</body></html>")
        }
        binding.verifyWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun openVerifyTab(url: String, wv: WebView = binding.webView) = runOnUiThread {
        log("Tim thay link xac thuc — dang mo...")
        wv.loadUrl(url)
        renderVerifyPanel(); panelOpen=true; binding.logPanel.visibility=View.VISIBLE; switchPanelTab(true)
    }

    // ─── Cloud polling ────────────────────────────────────────────────────────

    private suspend fun ensureAccount(targetWv: WebView) = withContext(Dispatchers.IO) {
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
                val json = JSONObject(http.newCall(Request.Builder().url("$SERVER_URL/api/rem2/status/$jobId").build()).execute().body?.string() ?: "{}")
                if (json.has("error")) {
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
                if (autoEmail.isEmpty()) {
                    val e = json.optString("email", ""); val u = json.optString("username", "")
                    if (e.isNotEmpty()) {
                        autoEmail=e; autoUsername=u
                        pollMs = 12_000L
                        withContext(Dispatchers.Main) {
                            if ((targetWv.url ?: "").isSignupPage()) injectAutoFill(targetWv)
                            else targetWv.loadUrl("https://replit.com/signup")
                        }
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
                        log("Xong${ if (bInfo.isEmpty()) "" else " ($bInfo)" }! Email: $autoEmail | User: $autoUsername")
                        withContext(Dispatchers.Main) {
                            val url = targetWv.url ?: ""
                            if (!url.isSignupPage() && url.contains("replit.com") && url != "about:blank") {
                                injectAutoFill(targetWv)
                            } else if (!url.isSignupPage()) {
                                targetWv.loadUrl("https://replit.com/signup")
                            }
                            if (link.isNotEmpty()) openVerifyTab(link, targetWv)
                            showDoneNotification(autoEmail, autoUsername, bInfo)
                        }
                        return@withContext
                    }
                    "error" -> { log("Server loi tao tai khoan"); return@withContext }
                    else    -> if (attempt > 0 && attempt % 6 == 0) log("Dang cho... ${attempt*5}s$batchLabel")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { if (attempt % 12 == 0) log("Poll loi: ${e.message}") }
        }
        log("Het thoi gian cho server")
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
              function scheduleTick(){if(pendingTick)return;pendingTick=setTimeout(function(){pendingTick=null;tick();},250);}
              var mo=new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type==='childList'||['style','class','hidden','disabled'].indexOf(m.attributeName)!==-1){scheduleTick();break;}}});
              mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','hidden','disabled','type']});
              window.__rem2FillMo=mo;
              window.__rem2FillIv=setInterval(function(){if(window.__rem2FillSid!==sid){clearInterval(window.__rem2FillIv);return;}tick();},900);
              tick();
              (function(){var lastUrl=location.href;function checkUrl(){var u=location.href;if(u!==lastUrl){lastUrl=u;window.__rem2FillSid=null;if(window.__rem2FillIv){clearInterval(window.__rem2FillIv);window.__rem2FillIv=null;}if(window.__rem2FillMo){try{window.__rem2FillMo.disconnect();}catch(e){}}}}setInterval(checkUrl,800);window.addEventListener('popstate',function(){setTimeout(checkUrl,300)});})();
            })();
        """.trimIndent(), null)
    }

    // ─── String ext ───────────────────────────────────────────────────────────
    private fun String.isSignupPage() = contains("signup") || contains("login") || contains("register")
}

