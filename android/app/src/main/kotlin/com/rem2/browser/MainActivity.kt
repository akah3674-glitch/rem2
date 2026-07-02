package com.rem2.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlin.coroutines.resume
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

        // ── Device profiles (thiết bị thật, UA từ GSMArena / UA-Parser DB) ──
        data class DeviceProfile(
            val name: String,
            val ua: String,
            val screenW: Int, val screenH: Int,
            val deviceMemory: Int,      // GB
            val hardwareConcurrency: Int,
            val timezone: String
        )

        val DEVICE_PROFILES = listOf(
            DeviceProfile("Samsung S23",
                "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36",
                393, 851, 8, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Samsung A54",
                "Mozilla/5.0 (Linux; Android 13; SM-A546E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.193 Mobile Safari/537.36",
                360, 780, 6, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Xiaomi 13",
                "Mozilla/5.0 (Linux; Android 13; 2211133C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
                393, 851, 12, 8, "Asia/Bangkok"),
            DeviceProfile("Redmi Note 12",
                "Mozilla/5.0 (Linux; Android 13; 23021RAAEG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.111 Mobile Safari/537.36",
                393, 873, 6, 8, "Asia/Jakarta"),
            DeviceProfile("OPPO Reno10",
                "Mozilla/5.0 (Linux; Android 13; CPH2531) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.163 Mobile Safari/537.36",
                412, 915, 8, 8, "Asia/Singapore"),
            DeviceProfile("Vivo Y36",
                "Mozilla/5.0 (Linux; Android 13; V2247) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.140 Mobile Safari/537.36",
                393, 873, 8, 8, "Asia/Ho_Chi_Minh"),
            DeviceProfile("Pixel 7",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36",
                412, 915, 8, 8, "America/New_York"),
            DeviceProfile("OnePlus 11",
                "Mozilla/5.0 (Linux; Android 13; CPH2449) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36",
                412, 919, 16, 8, "Europe/London"),
            DeviceProfile("Samsung A34",
                "Mozilla/5.0 (Linux; Android 13; SM-A346E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.80 Mobile Safari/537.36",
                360, 800, 6, 8, "Asia/Tokyo"),
            DeviceProfile("Realme C55",
                "Mozilla/5.0 (Linux; Android 13; RMX3710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.163 Mobile Safari/537.36",
                393, 851, 8, 8, "Asia/Kuala_Lumpur")
        )
    }

    private lateinit var binding: ActivityMainBinding
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var mailToken     = ""
    private var mailEmail     = ""
    private var pollJob: Job? = null
    private val seenIds       = mutableSetOf<String>()

    // Thông tin đăng ký sẵn — được set sau khi ensureMailAccount() xong
    private var autoEmail    = ""
    private var autoUsername = ""
    private var autoPassword = ""
    private var autoFullName = ""

    private var verifyPollJob: Job? = null

    // Profile giả lập thiết bị — đổi mới mỗi chu kỳ đăng ký
    private var currentDevice: DeviceProfile = DEVICE_PROFILES.random()

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
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode  = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString   = currentDevice.ua
            addJavascriptInterface(WebBridge(), "REM2")
            webViewClient = buildMainClient()
            loadUrl("https://replit.com")
        }
        attachSwipeBack(binding.mainWebView)
    }

    /**
     * Vuốt từ cạnh trái/phải → goBack().
     * Vuốt xuống khi đã ở đầu trang → reload.
     * Không consume event nên WebView vẫn xử lý scroll/tap bình thường.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeBack(webView: WebView) {
        val density    = resources.displayMetrics.density
        val edgeWidth  = (40 * density).toInt()
        val minSwipeX  = (60 * density)
        val maxSwipeY  = (80 * density)
        val minSwipeY  = (80 * density)   // tối thiểu kéo xuống để reload
        val minVelY    = 800f              // vận tốc tối thiểu (dp/s)

        val gesture = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val x1 = e1?.x ?: return false
                val y1 = e1.y
                val dx = e2.x - x1
                val dy = e2.y - y1
                val adx = Math.abs(dx); val ady = Math.abs(dy)
                val screenW = resources.displayMetrics.widthPixels

                // ── Vuốt cạnh → back ──────────────────────────────────────────
                val fromLeft  = x1 < edgeWidth && dx >  minSwipeX && ady < maxSwipeY
                val fromRight = x1 > (screenW - edgeWidth) && dx < -minSwipeX && ady < maxSwipeY
                if (fromLeft || fromRight) {
                    if (webView.canGoBack()) { webView.goBack(); return true }
                }

                // ── Kéo xuống khi đầu trang → reload ─────────────────────────
                if (dy > minSwipeY && velocityY > minVelY && ady > adx * 1.5f && webView.scrollY == 0) {
                    toast("\u21bb Đang tải lại\u2026")
                    webView.reload()
                    return true
                }
                return false
            }
        })

        webView.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            false   // không consume → WebView vẫn nhận event
        }
    }

    private fun buildMainClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
        override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
            v.evaluateJavascript(buildAntiDetectJs(currentDevice), null)
        }
        override fun onPageFinished(v: WebView, url: String) {
            when {
                // Trang signup: inject watcher — khi user click email form, tự điền
                url.contains("/signup") -> {
                    v.postDelayed({ injectAutoFillWatcher(v) }, (800L..1500L).random())
                }
                // Trang onboarding/plans: tự chọn next/free
                url.contains("/onboarding") || url.contains("/plans") -> {
                    val name = autoFullName.ifEmpty { randomFullName() }
                    v.postDelayed({ injectOnboardingStep(v, name) }, (1500L..3000L).random())
                }
                // Trang replit khác và chưa verify → thử poll mail
                url.contains("replit.com") && !url.contains("signup") &&
                !url.contains("onboarding") && !url.contains("plans") &&
                !prefs.getBoolean(KEY_REPLIT_REG, false) && autoEmail.isNotEmpty() -> {
                    lifecycleScope.launch { fetchMail() }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVerifyWebView() {
        binding.verifyWebView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString   = currentDevice.ua
            webViewClient = buildVerifyClient()
        }
    }

    private fun buildVerifyClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
        override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
            v.evaluateJavascript(buildAntiDetectJs(currentDevice), null)
        }
        override fun onPageFinished(v: WebView, url: String) {
            when {
                url.contains("/onboarding") || url.contains("/plans") -> {
                    val name = autoFullName.ifEmpty { randomFullName() }
                    v.postDelayed({ injectOnboardingStep(v, name) }, 1500)
                }
                url.contains("replit.com") &&
                !url.contains("verify") && !url.contains("confirm") &&
                url != "about:blank" -> {
                    onVerifyComplete()
                }
            }
        }
    }

    // ─── Panel UI ─────────────────────────────────────────────────────────────

    private fun setupPanel() {
        // FAB toggle mail panel
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
        // Nút "Tự động / Reset" → tạo email mới
        binding.btnAutoSetup.setOnClickListener {
            resetAndCreateNewEmail()
        }
        binding.tabMailList.setOnClickListener { showInboxTab() }
        binding.tabVerify.setOnClickListener   { showVerifyTab() }

        // Tap vào email status → copy vào clipboard
        binding.tvMailStatus.setOnClickListener {
            if (autoEmail.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("rem2_email", autoEmail))
                toast("Đã copy: $autoEmail")
            }
        }

        // Ẩn các view không còn dùng (invite, auto-overlay, skip button)
        listOf("btnInviteNew", "tvInviteStatus", "btnSkipAutoReg", "autoRegisterOverlay")
            .forEach { name ->
                try {
                    val id = resources.getIdentifier(name, "id", packageName)
                    if (id != 0) findViewById<View>(id)?.visibility = View.GONE
                } catch (_: Exception) {}
            }
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

    private fun resetAndCreateNewEmail() {
        verifyPollJob?.cancel(); verifyPollJob = null
        pollJob?.cancel(); pollJob = null

        prefs.edit()
            .remove(KEY_MAIL_EMAIL).remove(KEY_MAIL_PASS).remove(KEY_MAIL_TOKEN)
            .remove(KEY_REPLIT_NAME).putBoolean(KEY_REPLIT_REG, false).apply()

        mailToken = ""; mailEmail = ""
        autoEmail = ""; autoUsername = ""; autoPassword = ""; autoFullName = ""
        seenIds.clear()

        binding.mailList.removeAllViews()
        binding.tvMailStatus.text = "Đang tạo email mới\u2026"
        toast("Đang tạo email mới\u2026")

        lifecycleScope.launch { ensureMailAccount(force = true) }
    }

    // ─── Anti-detection JS ───────────────────────────────────────────────────

    /**
     * Inject trước khi trang load (onPageStarted).
     * Giả lập navigator, screen, canvas, WebGL của thiết bị thật.
     */
    private fun buildAntiDetectJs(d: DeviceProfile): String {
        val noise = (1..8).random()          // canvas noise seed per session
        val chVer = d.ua.substringAfter("Chrome/").substringBefore(" ").substringBefore(".")
        return """
(function(){
  /* 1 ── Xóa navigator.webdriver hoàn toàn */
  try {
    Object.defineProperty(navigator,'webdriver',{get:()=>undefined,configurable:true});
  } catch(e){}

  /* 2 ── Platform nhất quán với UA */
  try {
    Object.defineProperty(navigator,'platform',{get:()=>'Linux armv8l',configurable:true});
  } catch(e){}

  /* 3 ── userAgent (backup) */
  try {
    Object.defineProperty(navigator,'userAgent',{get:()=>'${d.ua}',configurable:true});
  } catch(e){}

  /* 4 ── Ngôn ngữ nhất quán với timezone */
  var lang = '${if (d.timezone.startsWith("Asia/Ho_Chi_Minh")) "vi-VN" 
               else if (d.timezone.startsWith("Asia")) "en-US" 
               else "en-US"}';
  try {
    Object.defineProperty(navigator,'language',{get:()=>lang,configurable:true});
    Object.defineProperty(navigator,'languages',{get:()=>[lang,'en-US','en'],configurable:true});
  } catch(e){}

  /* 5 ── deviceMemory và hardwareConcurrency */
  try {
    Object.defineProperty(navigator,'deviceMemory',{get:()=>${d.deviceMemory},configurable:true});
  } catch(e){}
  try {
    Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>${d.hardwareConcurrency},configurable:true});
  } catch(e){}

  /* 6 ── Plugins: giả lập danh sách plugin Chrome Android */
  try {
    var fakePlugins = [
      {name:'Chrome PDF Plugin',filename:'internal-pdf-viewer',description:'Portable Document Format'},
      {name:'Chrome PDF Viewer',filename:'mhjfbmdgcfjbbpaeojofohoefgiehjai',description:''},
      {name:'Native Client',filename:'internal-nacl-plugin',description:''}
    ];
    Object.defineProperty(navigator,'plugins',{
      get:()=>Object.assign(fakePlugins,{item:function(i){return fakePlugins[i];},
        namedItem:function(n){return fakePlugins.find(function(p){return p.name===n;})||null;},
        length:fakePlugins.length}),
      configurable:true
    });
    Object.defineProperty(navigator,'mimeTypes',{
      get:()=>({length:2,item:function(){return null;}}),configurable:true
    });
  } catch(e){}

  /* 7 ── Screen resolution */
  try {
    Object.defineProperty(screen,'width',{get:()=>${d.screenW},configurable:true});
    Object.defineProperty(screen,'height',{get:()=>${d.screenH},configurable:true});
    Object.defineProperty(screen,'availWidth',{get:()=>${d.screenW},configurable:true});
    Object.defineProperty(screen,'availHeight',{get:()=>${d.screenH - 48},configurable:true});
    Object.defineProperty(screen,'colorDepth',{get:()=>24,configurable:true});
    Object.defineProperty(screen,'pixelDepth',{get:()=>24,configurable:true});
  } catch(e){}
  try {
    Object.defineProperty(window,'outerWidth',{get:()=>${d.screenW},configurable:true});
    Object.defineProperty(window,'outerHeight',{get:()=>${d.screenH},configurable:true});
  } catch(e){}

  /* 8 ── Canvas fingerprint noise (thêm nhiễu nhỏ) */
  try {
    var _toDataURL = HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL = function(type){
      var ctx = this.getContext('2d');
      if(ctx){
        var img = ctx.getImageData(0,0,this.width,this.height);
        for(var i=0;i<img.data.length;i+=4){
          img.data[i]   ^= (${noise} & 3);
          img.data[i+1] ^= ((${noise}>>1) & 3);
        }
        ctx.putImageData(img,0,0);
      }
      return _toDataURL.apply(this,arguments);
    };
    var _getImageData = CanvasRenderingContext2D.prototype.getImageData;
    CanvasRenderingContext2D.prototype.getImageData = function(sx,sy,sw,sh){
      var data = _getImageData.apply(this,arguments);
      for(var i=0;i<data.data.length;i+=4) data.data[i] ^= (${noise} & 1);
      return data;
    };
  } catch(e){}

  /* 9 ── WebGL fingerprint */
  try {
    var getParam = WebGLRenderingContext.prototype.getParameter;
    WebGLRenderingContext.prototype.getParameter = function(param){
      if(param===37445) return 'Google Inc. (Qualcomm)';
      if(param===37446) return 'ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)';
      return getParam.call(this,param);
    };
  } catch(e){}

  /* 10 ── chrome object (bot check) */
  try {
    if(!window.chrome){
      window.chrome = {
        app:{isInstalled:false},
        runtime:{id:undefined,connect:function(){},sendMessage:function(){}},
        loadTimes:function(){return {};},csi:function(){return {};}
      };
    }
  } catch(e){}

  /* 11 ── Permissions API giả */
  try {
    var _query = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = function(p){
      if(p&&p.name==='notifications') return Promise.resolve({state:'denied',onchange:null});
      return _query(p);
    };
  } catch(e){}
})();
        """.trimIndent()
    }

    // ─── Auto-fill watcher ────────────────────────────────────────────────────

    /**
     * Inject MutationObserver vào trang /signup.
     * Khi user click "Continue with email" và form xuất hiện,
     * tự động gọi REM2.readyToFill() để điền form.
     */
    private fun injectAutoFillWatcher(webView: WebView) {
        if (autoEmail.isEmpty()) return
        // Poll mỗi 400ms — đáng tin cậy hơn MutationObserver trên React SPA
        val js = """
            (function(){
              if (window._rem2WatcherActive) return;
              window._rem2WatcherActive = true;
              var attempts = 0;
              var maxAttempts = 450; // 3 phút
              var tid = setInterval(function(){
                attempts++;
                if (attempts > maxAttempts) {
                  clearInterval(tid);
                  window._rem2WatcherActive = false;
                  return;
                }
                // Tìm email input hiện trên màn hình
                var sel = [
                  'input[type="email"]',
                  'input[name="email"]',
                  'input[placeholder*="mail" i]',
                  'input[placeholder*="Email"]',
                  'input[autocomplete="email"]'
                ];
                var found = null;
                for (var i = 0; i < sel.length; i++) {
                  var els = document.querySelectorAll(sel[i]);
                  for (var j = 0; j < els.length; j++) {
                    var r = els[j].getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) { found = els[j]; break; }
                  }
                  if (found) break;
                }
                if (found) {
                  clearInterval(tid);
                  window._rem2WatcherActive = false;
                  window.REM2.readyToFill();
                }
              }, 400);
            })()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ─── Mail.tm account management ──────────────────────────────────────────

    private suspend fun ensureMailAccount(force: Boolean = false) {
        val savedEmail = prefs.getString(KEY_MAIL_EMAIL, "") ?: ""
        val savedPass  = prefs.getString(KEY_MAIL_PASS,  "") ?: ""

        if (savedEmail.isNotEmpty() && !force) {
            val tok = mailLogin(savedEmail, savedPass)
            if (tok.isNotEmpty()) {
                mailToken = tok; mailEmail = savedEmail
                prefs.edit().putString(KEY_MAIL_TOKEN, tok).apply()
                withContext(Dispatchers.Main) {
                    setupAutoCredentials(savedEmail)
                    binding.tvMailStatus.text = "\u2713 $savedEmail  \ud83d\udccb nhấn để copy"
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
        mailToken = tok; mailEmail = email
        prefs.edit()
            .putString(KEY_MAIL_EMAIL, email).putString(KEY_MAIL_PASS, pass)
            .putString(KEY_MAIL_TOKEN, tok).apply()
        withContext(Dispatchers.Main) {
            setupAutoCredentials(email)
            binding.tvMailStatus.text = "\u2713 $email  \ud83d\udccb nhấn để copy"
            toast("Mail.tm sẵn sàng: $email")
            if (binding.mailPanel.visibility == View.VISIBLE) startPolling()
        }
    }

    private fun setupAutoCredentials(email: String) {
        autoEmail    = email
        autoUsername = "user" + (10000..99999).random()
        autoPassword = "Rem2x" + (100000..999999).random()
        autoFullName = prefs.getString(KEY_REPLIT_NAME, "").let {
            if (it.isNullOrEmpty()) randomFullName()
                .also { n -> prefs.edit().putString(KEY_REPLIT_NAME, n).apply() }
            else it
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
                    val sl = subject.lowercase(); val il = intro.lowercase()
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
            }
        } catch (_: Exception) {}
    }

    private fun onVerifyComplete() {
        verifyPollJob?.cancel(); verifyPollJob = null
        prefs.edit().putBoolean(KEY_REPLIT_REG, true).apply()
        showInboxTab()
        toast("Email đã xác nhận! Tài khoản Replit sẵn sàng \u2713")
        binding.mainWebView.loadUrl("https://replit.com")
        lifecycleScope.launch {
            delay(3000L)
            refreshMailForNextCycle()
        }
    }

    private suspend fun refreshMailForNextCycle() {
        verifyPollJob?.cancel(); verifyPollJob = null
        pollJob?.cancel(); pollJob = null

        prefs.edit()
            .remove(KEY_MAIL_EMAIL).remove(KEY_MAIL_PASS).remove(KEY_MAIL_TOKEN)
            .remove(KEY_REPLIT_NAME).putBoolean(KEY_REPLIT_REG, false).apply()

        mailToken = ""; mailEmail = ""
        autoEmail = ""; autoUsername = ""; autoPassword = ""; autoFullName = ""
        seenIds.clear()

        // Đổi profile thiết bị mới cho chu kỳ tiếp
        currentDevice = DEVICE_PROFILES.filter { it != currentDevice }.random()
        withContext(Dispatchers.Main) {
            binding.mainWebView.settings.userAgentString  = currentDevice.ua
            binding.verifyWebView.settings.userAgentString = currentDevice.ua
            binding.mailList.removeAllViews()
            binding.tvMailStatus.text = "Email cũ đã xóa \u2014 đang tạo email mới\u2026"
            toast("Đang tạo email mới \u2014 thiết bị: ${currentDevice.name}")
        }
        ensureMailAccount(force = true)
    }

    // ─── Form injection ───────────────────────────────────────────────────────

    /**
     * Điền form đăng ký (email/username/password) và click nút submit.
     * Chỉ click nút KHÔNG phải OAuth.
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
            "  function setVal(el,val){",
            "    try{var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;",
            "    s.call(el,val);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){el.value=val;}",
            "  }",
            // Ẩn tất cả nút OAuth để tránh nhầm
            "  var OAUTH=['google','github','facebook','apple','microsoft','twitter',' x ','with x'];",
            "  document.querySelectorAll('button,a,[role=\"button\"]').forEach(function(el){",
            "    var t=' '+(el.textContent||el.innerText||'').toLowerCase()+' ';",
            "    if(OAUTH.some(function(k){return t.indexOf(k)>=0;})){el.style.display='none';el.style.pointerEvents='none';}",
            "  });",
            // Điền các trường
            "  var filled=0;",
            "  document.querySelectorAll('input[type=\"email\"],input[name=\"email\"]').forEach(function(el){setVal(el,'${email}');filled++;});",
            "  document.querySelectorAll('input[name=\"username\"]').forEach(function(el){setVal(el,'${username}');filled++;});",
            "  document.querySelectorAll('input[type=\"password\"]').forEach(function(el){setVal(el,'${password}');filled++;});",
            "  document.querySelectorAll('input[name=\"first_name\"],input[placeholder*=\"first\" i]').forEach(function(el){setVal(el,'${firstName}');filled++;});",
            "  document.querySelectorAll('input[name=\"last_name\"],input[placeholder*=\"last\" i]').forEach(function(el){setVal(el,'${lastName}');filled++;});",
            "  document.querySelectorAll('input[name=\"full_name\"],input[placeholder*=\"name\" i]').forEach(function(el){setVal(el,'${fullName}');filled++;});",
            "  if(filled===0) return 'no-fields';",
            // Click submit với delay ngẫu nhiên
            "  var d=900+Math.floor(Math.random()*1200);",
            "  setTimeout(function(){",
            "    function vis(el){return el.offsetParent!==null&&el.style.display!=='none'&&el.style.visibility!=='hidden';}",
            "    function isOA(t){return OAUTH.some(function(k){return(' '+t+' ').indexOf(k)>=0;});}",
            "    var btns=Array.from(document.querySelectorAll('button[type=\"submit\"]')).filter(vis);",
            "    if(btns.length===0) btns=Array.from(document.querySelectorAll('button')).filter(vis);",
            "    for(var i=0;i<btns.length;i++){",
            "      var t=(btns[i].textContent||'').toLowerCase().trim();",
            "      if(isOA(t)) continue;",
            "      var ok=t.indexOf('sign')>=0||t.indexOf('create')>=0||t.indexOf('register')>=0",
            "            ||(t.indexOf('continue')>=0&&t.indexOf('with')<0&&t.length<20)",
            "            ||t.indexOf('next')>=0||t.indexOf('submit')>=0;",
            "      if(ok){btns[i].click();break;}",
            "    }",
            "  },d);",
            "  return 'ok:'+filled;",
            "})()"
        ).joinToString("\n")

        webView.evaluateJavascript(js) { result ->
            if (result?.contains("no-fields") == true) {
                webView.postDelayed({ injectSignupForm(webView, email, username, password, fullName) }, (1500L..3000L).random())
            } else {
                toast("Đã điền form, chờ email xác nhận\u2026")
                waitForVerifyEmail()
            }
        }
    }

    /**
     * Xử lý trang Onboarding — chọn gói free và bấm Next.
     */
    private fun injectOnboardingStep(webView: WebView, fullName: String) {
        val firstName = fullName.substringBefore(" ").ifEmpty { "User" }
        val lastName  = fullName.substringAfter(" ", "").ifEmpty { "Dev" }
        val js = listOf(
            "(function(){",
            "  function setVal(el,val){try{var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(el,val);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){el.value=val;}}",
            "  function setArea(el,val){try{var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(el,val);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){el.value=val;}}",
            // Chọn gói miễn phí
            "  var planClicked=false;",
            "  document.querySelectorAll('button,a,[role=\"button\"]').forEach(function(el){",
            "    if(planClicked) return;",
            "    var t=(el.textContent||'').toLowerCase();",
            "    if(t.indexOf('starter')>=0||t.indexOf('free')>=0||t.indexOf('continue with free')>=0){el.click();planClicked=true;}",
            "  });",
            "  if(planClicked) return 'plan-selected';",
            // Điền tên
            "  document.querySelectorAll('input[name=\"full_name\"],input[name=\"fullName\"],input[name=\"name\"]').forEach(function(el){setVal(el,'${fullName}');});",
            "  document.querySelectorAll('input[name=\"first_name\"],input[name=\"firstName\"],input[placeholder*=\"first\" i]').forEach(function(el){setVal(el,'${firstName}');});",
            "  document.querySelectorAll('input[name=\"last_name\"],input[name=\"lastName\"],input[placeholder*=\"last\" i]').forEach(function(el){setVal(el,'${lastName}');});",
            "  document.querySelectorAll('textarea').forEach(function(el){if(!el.value) setArea(el,'Thích lập trình và xây dựng những thứ hay ho.');});",
            // Chọn ngẫu nhiên option
            "  var opts=Array.from(document.querySelectorAll('[data-testid*=\"option\"],[data-cy*=\"option\"],[class*=\"SelectableCard\"],[class*=\"selectable\"],[class*=\"choice\"],[role=\"checkbox\"],[role=\"radio\"]'));",
            "  if(opts.length>0){var pick=Math.min(opts.length,Math.floor(Math.random()*2)+1);var chosen=[];while(chosen.length<pick){var idx=Math.floor(Math.random()*opts.length);if(!chosen.includes(idx))chosen.push(idx);}chosen.forEach(function(i){opts[i].click();});}",
            "  var radios=document.querySelectorAll('input[type=\"radio\"]');",
            "  if(radios.length>0) radios[Math.floor(Math.random()*radios.length)].click();",
            // Bấm Next — lọc kỹ OAuth
            "  var OB=['google','github','facebook','apple','microsoft','twitter',' x ','with x'];",
            "  function noOb(t){return !OB.some(function(k){return(' '+t+' ').indexOf(k)>=0;});}",
            "  function vis(el){return el.offsetParent!==null&&el.style.display!=='none'&&el.style.visibility!=='hidden';}",
            "  var clicked=false;",
            "  setTimeout(function(){",
            "    var btns=Array.from(document.querySelectorAll('button[type=\"submit\"],button')).filter(vis);",
            "    for(var i=0;i<btns.length;i++){",
            "      var t=(btns[i].textContent||'').toLowerCase().trim();",
            "      if(!noOb(t)) continue;",
            "      var ok=t.indexOf('next')>=0||t.indexOf('get started')>=0||t.indexOf('finish')>=0||t.indexOf('done')>=0",
            "            ||(t.indexOf('continue')>=0&&t.indexOf('with')<0&&t.length<18)",
            "            ||(t.indexOf('start')>=0&&t.indexOf('starter')<0);",
            "      if(ok){btns[i].click();clicked=true;break;}",
            "    }",
            "  },500);",
            "  return clicked?'next-clicked':'filled';",
            "})()"
        ).joinToString("\n")

        webView.evaluateJavascript(js) { result ->
            val r = result?.trim('"') ?: ""
            if (r == "plan-selected") toast("Đã chọn gói miễn phí \u2713")
            webView.postDelayed({
                webView.evaluateJavascript("window.location.pathname") { path ->
                    val p = path?.trim('"') ?: ""
                    if (p.contains("onboarding") || p.contains("plans"))
                        injectOnboardingStep(webView, fullName)
                }
            }, 4000)
        }
    }

    // ─── Wait for verify email ────────────────────────────────────────────────

    private suspend fun checkPageErrors(): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val js = """
                (function(){
                  var b=(document.body?document.body.innerText:'').toLowerCase();
                  if(b.indexOf('captcha')>=0&&(b.indexOf('kh\u00f4ng h\u1ee3p l\u1ec7')>=0||b.indexOf('invalid')>=0||b.indexOf('m\u00e3 1')>=0))
                    return 'captcha';
                  if(b.indexOf('thao t\u00e1c qu\u00e1 nhanh')>=0||b.indexOf('too fast')>=0||
                     b.indexOf('too many')>=0||b.indexOf('rate limit')>=0)
                    return 'tooslow';
                  if(b.indexOf('captcha')>=0) return 'captcha';
                  return 'ok';
                })()
            """.trimIndent()
            binding.mainWebView.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result?.trim('"') ?: "ok")
            }
        }
    }

    private fun waitForVerifyEmail() {
        verifyPollJob?.cancel()
        verifyPollJob = lifecycleScope.launch {
            var retries = 0
            repeat(48) {
                if (prefs.getBoolean(KEY_REPLIT_REG, false)) return@launch
                delay(5000)

                val err = try { checkPageErrors() } catch (_: Exception) { "ok" }
                when (err) {
                    "captcha" -> {
                        // Ẩn overlay, cho user thấy trang để giải CAPTCHA thủ công
                        withContext(Dispatchers.Main) {
                            toast("\u26a0 C\u1ea7n gi\u1ea3i CAPTCHA — h\u00e3y t\u00edch v\u00e0o \u00f4 CAPTCHA r\u1ed3i app t\u1ef1 ti\u1ebfp t\u1ee5c")
                        }
                        return@launch
                    }
                    "tooslow" -> {
                        retries++
                        val wait = 60_000L + (retries * 20_000L)
                        withContext(Dispatchers.Main) {
                            toast("\u26a0 Thao t\u00e1c qu\u00e1 nhanh — ch\u1edd ${wait/1000}s")
                        }
                        delay(wait)
                        withContext(Dispatchers.Main) {
                            binding.mainWebView.loadUrl("https://replit.com/signup")
                        }
                        return@launch
                    }
                }

                fetchMail()
                if (prefs.getBoolean(KEY_REPLIT_REG, false)) return@launch
            }
            withContext(Dispatchers.Main) {
                toast("H\u1ebft th\u1eddi gian — ki\u1ec3m tra h\u1ed9p th\u01b0 th\u1ee7 c\u00f4ng")
            }
        }
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
        /** Được gọi từ MutationObserver khi form email xuất hiện trên trang signup */
        @JavascriptInterface
        fun readyToFill() = runOnUiThread {
            if (autoEmail.isEmpty()) return@runOnUiThread
            val delay = (800L..2000L).random()
            binding.mainWebView.postDelayed({
                injectSignupForm(
                    binding.mainWebView,
                    autoEmail, autoUsername, autoPassword, autoFullName
                )
            }, delay)
        }

        @JavascriptInterface
        fun notifyNotLoggedIn() = runOnUiThread {
            // Không dùng nữa — giữ lại để tránh crash nếu JS cũ vẫn gọi
        }
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
        verifyPollJob?.cancel()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
