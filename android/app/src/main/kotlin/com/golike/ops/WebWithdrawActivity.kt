package com.golike.ops


import com.rem2.browser.R
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebWithdrawActivity — rút thẻ cào qua website thật GoLike.
 *
 * Luồng mỗi tài khoản:
 *  1. Đăng nhập form web thật
 *  2. Inject fetch interceptor (fix Lịch sử đổi thưởng)
 *  3. Điền form + chọn mệnh giá / SĐT
 *  4. Chờ CAPTCHA (nếu có) — tự skip ngay nếu không có widget
 *  5. Inject OtpFillBridge + monitor TRƯỚC submit
 *  6. Submit → OTP email gửi → poll Mail.tm → tự điền
 *  7. OTP banner hiện nổi bật trong header (dù auto-fill thành công hay không)
 *     → user nhấn banner để copy OTP vào clipboard, hoặc nhấn ô nhập để retry
 *
 * Parallel:
 *  - Tất cả tài khoản chạy đồng thời (async/awaitAll)
 *  - Mỗi tài khoản dùng WebView riêng (inflate trong processAccount)
 *  - Mail.tm token được prefetch song song trước khi submit
 */
@Suppress("SetJavaScriptEnabled")
class WebWithdrawActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCOUNT_IDS = "account_ids"
        const val EXTRA_CARD_TYPE   = "card_type"
        const val EXTRA_AMOUNT      = "amount"
        // Chữ ký cố định — KHÔNG ĐỔI khi cập nhật phiên bản (đảm bảo data không mất)
        const val APP_SIG           = "golike_ops"

        private const val LOGIN_URL    = "https://app.golike.net/login"
        private const val WITHDRAW_URL = "https://app.golike.net/account/withdraw"

        private const val LOGIN_TIMEOUT_MS    = 30_000L
        private const val CAPTCHA_PROBE_MS    = 6_000L    // thời gian dò CAPTCHA ban đầu
        private const val CAPTCHA_TIMEOUT_MS  = 300_000L  // tối đa 5 phút nếu thực sự có CAPTCHA
        private const val OTP_TIMEOUT_MS      = 180_000L
    }

    private val PROVIDER_LABELS = mapOf(
        GoLikeApi.CardType.VIETTEL      to "Viettel",
        GoLikeApi.CardType.MOBIFONE     to "Mobifone",
        GoLikeApi.CardType.VINAPHONE    to "Vinaphone",
        GoLikeApi.CardType.VIETNAMOBILE to "Vietnamobile",
        GoLikeApi.CardType.GARENA       to "Garena",
        GoLikeApi.CardType.ZING         to "Zing",
        GoLikeApi.CardType.VCOIN        to "VCoin"
    )

    // ── Views (single-account mode) ───────────────────────────────────────────
    private lateinit var webView       : WebView
    private lateinit var tvStatus      : TextView
    private lateinit var layoutOtpBanner: LinearLayout
    private lateinit var tvOtpBanner   : TextView
    private lateinit var tvLog         : TextView
    private lateinit var scrollLog     : ScrollView
    private lateinit var progress      : ProgressBar
    private lateinit var btnToggle     : TextView

    private var logVisible = false

    private var cardType: GoLikeApi.CardType = GoLikeApi.CardType.VIETTEL
    private var amount: Int = 10_000
    private var queue: List<GoLikeAccount> = emptyList()

    // ── Per-account OTP state (reset each account) ────────────────────────────
    // AtomicBoolean: compareAndSet(false,true) = atomic lock, no race condition
    private val otpFetchInProgress = AtomicBoolean(false)
    @Volatile private var cachedMailToken : String? = null
    @Volatile private var cachedOtp       : String? = null
    private var mailFetchJob : Job? = null
    private var otpPollJob   : Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_withdraw)

        webView          = findViewById(R.id.webViewWW)
        tvStatus         = findViewById(R.id.tvWWStatus)
        layoutOtpBanner  = findViewById(R.id.layoutOtpBanner)
        tvOtpBanner      = findViewById(R.id.tvOtpBanner)
        tvLog            = findViewById(R.id.tvLogWW)
        scrollLog        = findViewById(R.id.scrollLogWW)
        progress         = findViewById(R.id.progressWW)
        btnToggle        = findViewById(R.id.btnToggleLog)

        btnToggle.setOnClickListener { toggleLog() }

        cardType = runCatching {
            GoLikeApi.CardType.valueOf(intent.getStringExtra(EXTRA_CARD_TYPE) ?: "VIETTEL")
        }.getOrDefault(GoLikeApi.CardType.VIETTEL)
        amount = intent.getIntExtra(EXTRA_AMOUNT, 10_000)

        val ids = intent.getStringArrayListExtra(EXTRA_ACCOUNT_IDS)
        queue = if (ids.isNullOrEmpty()) AccountManager.getAll(this)
                else ids.mapNotNull { AccountManager.getById(this, it) }

        setupWebView()

        if (queue.isEmpty()) {
            addLog("⚠️ Không có tài khoản nào để xử lý")
        } else {
            lifecycleScope.launch { processQueue() }
        }
    }

    private fun toggleLog() {
        logVisible = !logVisible
        scrollLog.layoutParams = (scrollLog.layoutParams as android.widget.LinearLayout.LayoutParams).also {
            it.height = if (logVisible) (120 * resources.displayMetrics.density).toInt() else 0
        }
        scrollLog.visibility = if (logVisible) View.VISIBLE else View.GONE
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            allowFileAccess          = false
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; SM-G991B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "coc_coc_browser/124.0.6367 Chrome/124.0.6367.82 " +
                "Mobile Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
            override fun onJsAlert(v: WebView, u: String, msg: String, r: android.webkit.JsResult): Boolean {
                r.confirm(); return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript(ANTI_BOT_JS, null)
            }
        }
    }

    private val ANTI_BOT_JS = """
        (function(){
          try {
            Object.defineProperty(navigator,'webdriver',{get:function(){return undefined;},configurable:true});
            if(!window.chrome){window.chrome={app:{isInstalled:false},runtime:{id:undefined,connect:function(){},sendMessage:function(){}},loadTimes:function(){return{};},csi:function(){return{};}}}
            if(!navigator.plugins||navigator.plugins.length===0){Object.defineProperty(navigator,'plugins',{get:function(){var a=[{name:'Chrome PDF Plugin',filename:'internal-pdf-viewer',description:'Portable Document Format'},{name:'Chrome PDF Viewer',filename:'mhjfbmdgcfjbbpaeojofohoefgiehjai',description:''},{name:'Native Client',filename:'internal-nacl-plugin',description:''}];a.refresh=function(){};a.item=function(i){return a[i];};a.namedItem=function(n){return a.find(function(p){return p.name===n;})||null;};return a;},configurable:true});}
            Object.defineProperty(navigator,'languages',{get:function(){return['vi-VN','vi','en-US','en'];},configurable:true});
            if(navigator.permissions){var _q=navigator.permissions.query.bind(navigator.permissions);navigator.permissions.query=function(p){if(p&&p.name==='notifications'){return Promise.resolve({state:Notification.permission});}return _q(p);};}
          } catch(e){}
        })();
    """.trimIndent()

    private fun clearWebSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    // ── JS helpers ────────────────────────────────────────────────────────────

    private suspend fun evalJs(script: String): String = suspendCancellableCoroutine { cont ->
        webView.post {
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resumeWith(Result.success(result ?: "null"))
            }
        }
    }

    private suspend fun loadUrlAndWait(url: String) = suspendCancellableCoroutine<Unit> { cont ->
        var resumed = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
            override fun onPageStarted(v: WebView, u: String, b: android.graphics.Bitmap?) {
                super.onPageStarted(v, u, b); v.evaluateJavascript(ANTI_BOT_JS, null)
            }
            override fun onPageFinished(v: WebView, u: String) {
                super.onPageFinished(v, u)
                if (!resumed) { resumed = true; if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
            }
        }
        webView.loadUrl(url)
    }

    private fun jsStr(s: String): String = JSONObject.quote(s)

    private val JS_HELPERS = """
        (function(){
          if(window.__remReady)return;window.__remReady=true;
          window.__remSetVal=function(el,value){
            try{var p=Object.getPrototypeOf(el);var d=Object.getOwnPropertyDescriptor(p,'value')||Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');if(d&&d.set)d.set.call(el,value);else el.value=value;}catch(e){el.value=value;}
            el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
          };
          window.__remFindByText=function(txt){
            var all=Array.prototype.slice.call(document.querySelectorAll('button,a,div,span,li,label'));
            var m=all.filter(function(e){if(e.offsetParent===null)return false;var t=(e.textContent||'').replace(/\s+/g,' ').trim();return t.indexOf(txt)!==-1&&t.length>0;});
            if(!m.length)return null;m.sort(function(a,b){return a.textContent.trim().length-b.textContent.trim().length;});return m[0];
          };
        })();
    """.trimIndent()

    private suspend fun ensureHelpers() { evalJs(JS_HELPERS) }

    private suspend fun injectFetchInterceptor() {
        evalJs("""
            (function(){
              if(window.__remFetchPatched)return;window.__remFetchPatched=true;
              var _orig=window.fetch;
              window.fetch=function(input,init){
                var url=typeof input==='string'?input:((input&&input.url)||'');
                if(url.indexOf('/api/withdraw')!==-1){
                  var hdrs={};
                  try{var h=init&&init.headers;if(h){if(typeof h.forEach==='function'){h.forEach(function(v,k){hdrs[k]=v;});}else{var keys=Object.keys(h);for(var i=0;i<keys.length;i++)hdrs[keys[i]]=h[keys[i]];}}}catch(e){}
                  var bodyStr='';try{bodyStr=(init&&init.body)?String(init.body):'';}catch(e){}
                  return new Promise(function(resolve,reject){
                    window.__remWithdrawResolve=resolve;window.__remWithdrawReject=reject;
                    try{AndroidBridge.onWithdrawFetch(url,JSON.stringify(hdrs),bodyStr);}
                    catch(e){window.__remWithdrawResolve=null;window.__remWithdrawReject=null;_orig.apply(window,[input,init]).then(resolve).catch(reject);}
                  });
                }
                return _orig.apply(this,arguments);
              };
            })();
        """.trimIndent())
    }

    /**
     * Inject OTP auto-fill monitor.
     *
     * Fix code-review: debounce bằng timestamp thay vì remove/re-add listener
     * (tránh handler stacking). MutationObserver dùng `__remOtpBound` flag trên
     * element (không xóa cờ sau blur nữa → listener chỉ gắn 1 lần).
     * Debounce 3s giữa các lần trigger bằng `__remOtpLastTrigger`.
     */
    private suspend fun injectOtpAutoFillMonitor() {
        evalJs("""
            (function(){
              if(window.__remOtpMonitorActive)return;
              window.__remOtpMonitorActive=true;

              function findOtpInput(){
                return Array.prototype.slice.call(document.querySelectorAll('input')).find(function(i){
                  if(i.offsetParent===null)return false;
                  var ml=i.getAttribute('maxlength');
                  return ml==='6'||ml==='8'||ml==='4'||i.type==='number'||i.inputMode==='numeric';
                })||null;
              }

              function attachOnce(el){
                if(!el||el.__remOtpBound)return;
                el.__remOtpBound=true;
                if(!el.value)el.setAttribute('placeholder','Nhấn vào đây để tự nhập OTP từ email...');
                el.addEventListener('click',onActivate);
                el.addEventListener('focus',onActivate);
              }

              function onActivate(){
                var now=Date.now();
                if(window.__remOtpLastTrigger&&(now-window.__remOtpLastTrigger)<3000)return;
                window.__remOtpLastTrigger=now;
                var el=findOtpInput();
                if(el)el.setAttribute('placeholder','⏳ Đang lấy OTP từ email...');
                try{OtpFillBridge.onOtpFieldClicked();}catch(e){}
              }

              attachOnce(findOtpInput());
              var obs=new MutationObserver(function(){attachOnce(findOtpInput());});
              obs.observe(document.body,{childList:true,subtree:true});
            })();
        """.trimIndent())
    }

    // ── AndroidBridge ─────────────────────────────────────────────────────────

    inner class AndroidBridge {
        @JavascriptInterface
        fun onWithdrawFetch(url: String, headersJson: String, body: String) {
            addLog("🌐 Intercepted withdraw → relay native...")
            lifecycleScope.launch {
                try {
                    val cm = CookieManager.getInstance()
                    val gw  = cm.getCookie("https://gateway.golike.net") ?: ""
                    val app = cm.getCookie("https://app.golike.net") ?: ""
                    val cookies = listOf(gw, app).filter { it.isNotEmpty() }.joinToString("; ")
                    val extra = mapOf(
                        "Origin"  to "https://app.golike.net",
                        "Referer" to "https://app.golike.net/account/withdraw",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                    val (code, respBody) = GoLikeApi.relayPost(url, body, headersJson, cookies, extra)
                    addLog(if (code in 200..299) "✅ Relay OK ($code)" else "⚠️ Relay $code")
                    val escaped = JSONObject.quote(respBody)
                    evalJs("""
                        (function(){
                          var r=window.__remWithdrawResolve;
                          window.__remWithdrawResolve=null;window.__remWithdrawReject=null;
                          if(r)try{r(new Response($escaped,{status:$code,headers:{'Content-Type':'application/json'}}));}catch(e){r(new Response($escaped,{status:$code}));}
                        })();
                    """.trimIndent())
                } catch (e: Exception) {
                    addLog("💥 Relay error: ${e.message}")
                    evalJs("""
                        (function(){
                          var rej=window.__remWithdrawReject;
                          window.__remWithdrawResolve=null;window.__remWithdrawReject=null;
                          if(rej)rej(new Error(${jsStr(e.message?:"relay error")}));
                        })();
                    """.trimIndent())
                }
            }
        }
    }

    /**
     * OtpFillBridge — fix code-review:
     *  - AtomicBoolean.compareAndSet(false,true) = atomic mutex, không race condition
     *  - Luôn clear trong finally
     *  - Ưu tiên cachedOtp (không fetch lại nếu đã có)
     *  - Hiện OTP banner trong header sau khi tìm thấy
     */
    inner class OtpFillBridge {
        @JavascriptInterface
        fun onOtpFieldClicked() {
            // compareAndSet(false, true): chỉ 1 coroutine được vào, phần còn lại bị bỏ qua
            if (!otpFetchInProgress.compareAndSet(false, true)) return
            lifecycleScope.launch {
                try {
                    val existing = cachedOtp
                    if (existing != null) {
                        // OTP đã có → copy lại vào clipboard (không tự điền)
                        addLog("⚡ OTP đã có sẵn: $existing — copy lại vào clipboard...")
                        showOtpBanner(existing)  // showOtpBanner tự copy
                        return@launch
                    }
                    addLog("🔄 Nhấn ô OTP — đang lấy từ Mail.tm...")
                    val mToken = cachedMailToken
                    if (mToken == null) {
                        addLog("⚠️ Chưa có mail token — nhập OTP thủ công")
                        return@launch
                    }
                    val otp = GoLikeApi.pollOtpFromMail(mToken, maxWaitSec = 60, initialDelaySec = 1)
                    if (otp != null) {
                        cachedOtp = otp
                        // showOtpBanner tự copy clipboard, không fillOtpField
                        showOtpBanner(otp)
                    } else {
                        addLog("⚠️ Không tìm thấy OTP — kiểm tra email rồi nhập tay")
                    }
                } finally {
                    otpFetchInProgress.set(false)
                }
            }
        }
    }

    // ── OTP UI helpers ────────────────────────────────────────────────────────

    /**
     * Hiện OTP trong banner xanh + TỰ ĐỘNG copy vào clipboard ngay.
     * Không tự điền vào ô web — user paste thủ công hoặc nhấn banner để copy lại.
     */
    private fun showOtpBanner(otp: String) {
        runOnUiThread {
            // Auto-copy ngay khi tìm thấy OTP
            val clip = ClipData.newPlainText("OTP", otp)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)

            tvOtpBanner.text = "🔑 OTP: $otp"
            layoutOtpBanner.visibility = View.VISIBLE

            // Nhấn lại → copy lại + toast
            layoutOtpBanner.setOnClickListener {
                val clip2 = ClipData.newPlainText("OTP", otp)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip2)
                Toast.makeText(this, "✅ Đã copy: $otp", Toast.LENGTH_SHORT).show()
            }

            tvStatus.text = "🔑 OTP: $otp  (đã tự copy — paste vào ô nhập)"
            addLog("🔑 OTP: $otp  ← đã tự copy vào clipboard")
        }
    }

    private fun hideOtpBanner() {
        runOnUiThread { layoutOtpBanner.visibility = View.GONE }
    }

    private suspend fun fillOtpField(otp: String) {
        val q = jsStr(otp)
        evalJs("""
            (function(){
              try {
                var el=Array.prototype.slice.call(document.querySelectorAll('input')).find(function(i){
                  if(i.offsetParent===null)return false;
                  var ml=i.getAttribute('maxlength');
                  return ml==='6'||ml==='8'||ml==='4'||i.type==='number'||i.inputMode==='numeric';
                });
                if(!el){
                  var vis=Array.prototype.slice.call(document.querySelectorAll('input')).filter(function(i){return i.offsetParent!==null&&i.type!=='hidden';});
                  el=vis[vis.length-1]||null;
                }
                if(el){window.__remSetVal(el,$q);el.setAttribute('placeholder','✅ OTP đã điền!');}
              }catch(e){}
            })();
        """.trimIndent())
    }

    private suspend fun resetOtpInputPlaceholder(msg: String) {
        val q = jsStr(msg)
        evalJs("""
            (function(){
              var el=Array.prototype.slice.call(document.querySelectorAll('input')).find(function(i){
                if(i.offsetParent===null)return false;
                var ml=i.getAttribute('maxlength');
                return ml==='6'||ml==='8'||ml==='4'||i.type==='number'||i.inputMode==='numeric';
              });
              if(el)el.setAttribute('placeholder',$q);
            })();
        """.trimIndent())
    }

    // ── Log/UI ────────────────────────────────────────────────────────────────

    private fun addLog(line: String) {
        runOnUiThread {
            tvLog.append((if (tvLog.text.isNotEmpty()) "\n" else "") + line)
            if (logVisible) scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
            // Chỉ update tvStatus nếu không đang hiện OTP (tránh đè banner OTP)
            if (tvOtpBanner.visibility != View.VISIBLE) tvStatus.text = line
        }
    }

    // ── Queue: tất cả accounts chạy song song ────────────────────────────────

    private suspend fun processQueue() {
        addLog("🚀 Bắt đầu xử lý ${queue.size} tài khoản song song...")
        queue.map { acc ->
            lifecycleScope.async {
                if (acc.password.isEmpty()) {
                    addLog("⚠️ @${acc.username}: chưa có mật khẩu — bỏ qua")
                    return@async
                }
                runCatching { processAccount(acc) }
                    .onFailure { addLog("💥 @${acc.username}: ${it.message}") }
            }
        }.awaitAll()
        addLog("🏁 Hoàn tất tất cả tài khoản.")
    }

    // ── processAccount ────────────────────────────────────────────────────────

    private suspend fun processAccount(acc: GoLikeAccount) {
        // Reset state per-account (fix code-review: cancel tracked jobs)
        otpFetchInProgress.set(false)
        cachedMailToken = null
        cachedOtp       = null
        mailFetchJob?.cancel(); mailFetchJob = null
        otpPollJob?.cancel();   otpPollJob   = null
        hideOtpBanner()

        clearWebSession()

        // ── Bước 1: Đăng nhập ────────────────────────────────────────────────
        addLog("═══ @${acc.username} ═══")
        addLog("🔐 Mở trang đăng nhập...")
        loadUrlAndWait(LOGIN_URL)
        delay(1800)
        ensureHelpers()

        val fillResult = evalJs("""
            (function(){
              try{
                var pass=document.querySelector('input[type="password"]');
                if(!pass)return JSON.stringify({ok:false,reason:'no_password_field'});
                var scope=pass.closest('form')||document;
                var user=scope.querySelector('input[type="text"],input[type="email"]')||document.querySelector('input[type="text"],input[type="email"]');
                if(!user)return JSON.stringify({ok:false,reason:'no_username_field'});
                window.__remSetVal(user,${jsStr(acc.username)});
                window.__remSetVal(pass,${jsStr(acc.password)});
                return JSON.stringify({ok:true});
              }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
            })();
        """.trimIndent())

        if (!isOk(fillResult)) {
            addLog("⚠️ Không điền được form (${reason(fillResult)}) — hãy đăng nhập tay.")
        } else {
            addLog("✍️ Đã điền tài khoản/mật khẩu...")
            delay(400)
            evalJs("(function(){var btn=window.__remFindByText('Đăng nhập')||document.querySelector('button[type=\"submit\"]');if(btn)btn.click();})();")
        }

        if (!waitForLoginSuccess()) {
            addLog("❌ Đăng nhập thất bại — bỏ qua."); return
        }
        addLog("✅ Đăng nhập thành công")

        // ── Bước 2: Trang rút thẻ + fetch interceptor ────────────────────────
        addLog("➡️ Mở trang rút thẻ...")
        loadUrlAndWait(WITHDRAW_URL)
        delay(2000)
        ensureHelpers()
        injectFetchInterceptor()
        addLog("🔌 Fetch interceptor đã kích hoạt")

        // ── Bước 3: Điền form ─────────────────────────────────────────────────
        val phone = if (cardType.needsPhone) AccountManager.generateUniquePhone(this) else ""
        if (phone.isNotEmpty()) addLog("📱 SĐT nhận thẻ: $phone")
        fillWithdrawForm(cardType, amount, phone)

        // ── Bước 4 (TRƯỚC CAPTCHA): Inject bridge + bắt đầu fetch mail token ─
        //
        // QUAN TRỌNG: Phải inject TRƯỚC khi chờ CAPTCHA.
        // Lý do: GoLike tự động submit form sau khi user giải CAPTCHA xong
        // (CAPTCHA callback → Vue.js auto-submit → OTP modal xuất hiện ngay).
        // Nếu inject sau CAPTCHA thì bridge chưa có khi modal hiện → không tự điền.
        // Mail token cũng cần thời gian fetch (~2-3s) → bắt đầu sớm hơn.
        runOnUiThread { webView.addJavascriptInterface(OtpFillBridge(), "OtpFillBridge") }
        delay(100)
        injectOtpAutoFillMonitor()

        val mailEmail = acc.mailEmail.ifEmpty { acc.email }
        val mailPass  = acc.mailPass.ifEmpty { acc.password }
        if (mailEmail.isNotEmpty() && mailPass.isNotEmpty()) {
            mailFetchJob = lifecycleScope.launch {
                addLog("📬 Đăng nhập Mail.tm: $mailEmail...")
                val token = GoLikeApi.getMailToken(mailEmail, mailPass)
                if (token != null) {
                    cachedMailToken = token
                    addLog("📬 Mail.tm sẵn sàng")
                } else {
                    addLog("⚠️ Không đăng nhập được Mail.tm — OTP cần nhập tay")
                }
            }
        }

        // ── Bước 5: CAPTCHA + submit thông minh ──────────────────────────────
        //
        // probeForCaptcha(6s): kiểm tra có iframe reCAPTCHA widget không.
        //   - Không có → click submit ngay
        //   - Có → hiện thông báo, chờ user tick
        //
        // waitForCaptchaSolvedOrOtpModal(): thay thế waitForCaptchaSolved() cũ.
        //   Thoát khi MỘT TRONG HAI điều kiện đúng:
        //   a) OTP modal xuất hiện  → GoLike đã auto-submit sau CAPTCHA (không cần click nữa)
        //   b) grecaptcha.getResponse() có token → click submit thủ công
        //   Không dùng grecaptcha.getResponse() làm điều kiện DUY NHẤT vì nó
        //   không đáng tin trong WebView (hay trả về "" dù đã giải xong).
        addLog("🔐 Kiểm tra CAPTCHA... (nếu có thì tick, nếu không sẽ tự động tiếp)")
        val hasCaptcha = probeForCaptcha(CAPTCHA_PROBE_MS)
        val autoSubmitted: Boolean
        if (hasCaptcha) {
            addLog("🔐 Cần CAPTCHA — vui lòng tick. App tự tiếp khi OTP modal hiện...")
            autoSubmitted = waitForCaptchaSolvedOrOtpModal()
            if (!autoSubmitted) {
                // grecaptcha.getResponse() OK nhưng GoLike chưa auto-submit → click thủ công
                addLog("💸 CAPTCHA xong — đang bấm xác nhận rút...")
                clickSubmitButton()
                delay(1200)
            } else {
                addLog("✅ Form đã tự submit (GoLike auto-submit sau CAPTCHA)")
                delay(500)
            }
        } else {
            addLog("✅ Không cần CAPTCHA — đang bấm xác nhận rút...")
            autoSubmitted = false
            clickSubmitButton()
            delay(1200)
        }

        // ── Bước 7: OTP flow ──────────────────────────────────────────────────
        //
        // FIX: Không dùng `!modalSeen` làm guard cho việc start polling.
        // Vấn đề cũ: `modalSeen=true` chỉ chạy 1 lần → nếu cachedMailToken
        // chưa về kịp, bỏ qua luôn và không bao giờ retry khi token về sau.
        //
        // Fix mới: dùng `otpPollJob == null` làm guard → mỗi vòng lặp 500ms
        // đều check lại, hễ token về là start poll ngay. showOtpBanner() luôn
        // được gọi sau khi tìm được OTP dù auto-fill có thành công hay không.
        val otpDeadline = System.currentTimeMillis() + OTP_TIMEOUT_MS
        var modalSeen   = false
        var bannerShown = false   // guard: không gọi showOtpBanner nhiều lần

        while (System.currentTimeMillis() < otpDeadline) {
            if (isOtpModalPresent()) {
                if (!modalSeen) {
                    modalSeen = true
                    addLog("📨 OTP modal xuất hiện — đọc Mail.tm, OTP sẽ tự copy vào clipboard...")
                }

                val alreadyHaveOtp = cachedOtp != null
                val pollStarted    = otpPollJob != null

                when {
                    // OTP đã có sẵn VÀ poll chưa start VÀ banner chưa hiện
                    // (trường hợp OTP pre-cached trước khi modal xuất hiện)
                    // Nếu pollStarted=true → background job đã xử lý showOtpBanner rồi
                    alreadyHaveOtp && !pollStarted && !bannerShown -> {
                        bannerShown = true
                        showOtpBanner(cachedOtp!!)   // auto-copy clipboard
                        // Không fillOtpField / clickSubmitButton — user tự paste + bấm Xác Nhận
                    }

                    // Có token, chưa bắt đầu poll → start background poll
                    !pollStarted && cachedMailToken != null -> {
                        val mToken = cachedMailToken!!
                        addLog("📬 Bắt đầu đọc OTP từ Mail.tm...")
                        otpPollJob = lifecycleScope.launch {
                            if (!otpFetchInProgress.compareAndSet(false, true)) return@launch
                            try {
                                val otp = GoLikeApi.pollOtpFromMail(
                                    mToken, maxWaitSec = 120, initialDelaySec = 2
                                )
                                if (otp != null && cachedOtp == null) {
                                    cachedOtp = otp
                                    // showOtpBanner tự copy clipboard — user paste thủ công
                                    showOtpBanner(otp)
                                    addLog("📋 OTP đã copy — paste vào ô nhập rồi bấm Xác Nhận")
                                } else if (otp == null) {
                                    addLog("⚠️ Hết thời gian đọc mail — nhấn ô OTP để thử lại")
                                }
                            } finally {
                                otpFetchInProgress.set(false)
                            }
                        }
                    }

                    // Chưa có token — chờ tiếp (mailFetchJob đang chạy background)
                    !pollStarted && cachedMailToken == null -> { /* chờ */ }
                }

            } else if (modalSeen) {
                // Modal đã đóng → thoát
                break
            }
            delay(500)
        }

        // Chờ response submit
        delay(2500)

        // ── Bước 8: Đọc kết quả ───────────────────────────────────────────────
        val resultMsg = readResultMessage()
        addLog("📋 Kết quả: $resultMsg")
        AccountManager.addWithdrawRecord(this, acc.id, WithdrawRecord(
            cardType = cardType.name, amount = amount, phone = phone, message = resultMsg
        ))
    }

    // ── Step helpers ──────────────────────────────────────────────────────────

    private suspend fun waitForLoginSuccess(): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < LOGIN_TIMEOUT_MS) {
            ensureHelpers()
            val r = evalJs("""
                (function(){
                  var url=location.href;var hasToken=false;
                  try{var keys=["token","access_token","auth_token","user_token","golike_token","jwt","userToken","authToken","accessToken","gl_token"];for(var i=0;i<keys.length;i++){var v=localStorage.getItem(keys[i]);if(v&&v.length>20){hasToken=true;break;}}}catch(e){}
                  return JSON.stringify({hasToken:hasToken,isLoginPage:url.indexOf('/login')!==-1});
                })();
            """.trimIndent())
            val json = parseJson(r)
            if (json?.optBoolean("hasToken", false) == true ||
                json?.optBoolean("isLoginPage", true) == false) return true
            delay(800)
        }
        return false
    }

    private suspend fun fillWithdrawForm(cardType: GoLikeApi.CardType, amount: Int, phone: String) {
        if (cardType.needsTelco) {
            clickTextIfFound("Thẻ cào"); delay(600)
            clickTextIfFound(PROVIDER_LABELS[cardType] ?: "Viettel"); delay(600)
        } else {
            val label = PROVIDER_LABELS[cardType] ?: ""
            if (label.isNotEmpty()) { clickTextIfFound(label); delay(600) }
        }
        val fmt = "%,d".format(amount).replace(",", ".")
        if (!clickTextIfFound(fmt)) clickTextIfFound(amount.toString())
        delay(500)
        if (phone.isNotEmpty()) {
            val r = evalJs("""
                (function(){
                  try{
                    var inputs=Array.prototype.slice.call(document.querySelectorAll('input[type="tel"],input[type="text"],input[type="number"]'));
                    var el=inputs.find(function(i){if(i.offsetParent===null)return false;var ph=(i.placeholder||'').toLowerCase();return ph.indexOf('điện thoại')!==-1||ph.indexOf('sdt')!==-1||ph.indexOf('phone')!==-1||ph.indexOf('0xxx')!==-1||ph.indexOf('09')!==-1;});
                    if(!el){var vis=inputs.filter(function(i){return i.offsetParent!==null;});el=vis[vis.length-1]||null;}
                    if(!el)return JSON.stringify({ok:false});
                    window.__remSetVal(el,${jsStr(phone)});return JSON.stringify({ok:true});
                  }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
                })();
            """.trimIndent())
            if (!isOk(r)) addLog("⚠️ Không tìm thấy ô SĐT — vui lòng nhập tay nếu cần.")
        }
    }

    private suspend fun clickTextIfFound(text: String): Boolean {
        val r = evalJs("(function(){var el=window.__remFindByText(${jsStr(text)});if(!el)return JSON.stringify({ok:false});el.click();return JSON.stringify({ok:true});})();")
        return isOk(r)
    }

    /** Probe nhanh: kiểm tra CAPTCHA widget có xuất hiện trong [timeMs] ms không. */
    private suspend fun probeForCaptcha(timeMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeMs) {
            val r = evalJs("""
                (function(){
                  var frames=Array.prototype.slice.call(document.querySelectorAll('iframe'));
                  var f=frames.find(function(f){var s=f.src||'';return s.indexOf('recaptcha/api2/anchor')!==-1||s.indexOf('recaptcha/api2/bframe')!==-1;});
                  return JSON.stringify({present:!!(f&&f.offsetParent!==null)});
                })();
            """.trimIndent())
            if (parseJson(r)?.optBoolean("present", false) == true) return true
            delay(500)
        }
        return false
    }

    /**
     * Chờ CAPTCHA xong HOẶC OTP modal xuất hiện — cái nào đến trước thì thoát.
     *
     * Returns true  = OTP modal đã xuất hiện (GoLike auto-submit sau CAPTCHA)
     *         false = grecaptcha.getResponse() có token (cần click submit thủ công)
     *
     * Tại sao không dùng chỉ grecaptcha.getResponse():
     *   - WebView hay trả "" dù user đã tick xong (Cloudflare/GoLike reset token)
     *   - Dấu hiệu đáng tin hơn là OTP modal xuất hiện = form đã submit thành công
     */
    private suspend fun waitForCaptchaSolvedOrOtpModal(): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < CAPTCHA_TIMEOUT_MS) {
            // Ưu tiên check OTP modal — tin cậy hơn grecaptcha
            if (isOtpModalPresent()) {
                addLog("✅ OTP modal xuất hiện → GoLike đã auto-submit sau CAPTCHA")
                return true
            }
            // Fallback: grecaptcha.getResponse() (hoạt động với một số phiên bản)
            val r = evalJs("""
                (function(){
                  var resp='';try{resp=grecaptcha.getResponse();}catch(e){}
                  return JSON.stringify({solved:!!(resp&&resp.length>10)});
                })();
            """.trimIndent())
            if (parseJson(r)?.optBoolean("solved", false) == true) {
                addLog("✅ CAPTCHA đã xong (grecaptcha token)")
                return false  // caller cần click submit
            }
            delay(800)
        }
        addLog("⏱️ Hết thời gian chờ CAPTCHA — thử submit.")
        return isOtpModalPresent()  // kiểm tra lần cuối
    }

    private suspend fun clickSubmitButton() {
        val labels = listOf("Xác nhận", "Đổi thưởng", "Rút", "Gửi lệnh", "Xác nhận rút", "Submit")
        for (l in labels) { if (clickTextIfFound(l)) { addLog("👆 Đã bấm \"$l\""); return } }
        evalJs("(function(){var btn=document.querySelector('button[type=\"submit\"]');if(btn&&btn.offsetParent!==null)btn.click();})();")
        addLog("⚠️ Không tìm thấy nút xác nhận — vui lòng tự bấm.")
    }

    private suspend fun isOtpModalPresent(): Boolean {
        val r = evalJs("""
            (function(){
              try{
                var body=document.body.innerText||'';
                var hasOtp=body.indexOf('OTP')!==-1||body.indexOf('mã xác nhận')!==-1||body.indexOf('xác nhận email')!==-1;
                var hasInput=Array.prototype.slice.call(document.querySelectorAll('input')).some(function(i){
                  if(i.offsetParent===null)return false;var ml=i.getAttribute('maxlength');
                  return ml==='6'||ml==='8'||ml==='4'||i.type==='number'||i.inputMode==='numeric';
                });
                return JSON.stringify({present:hasOtp&&hasInput});
              }catch(e){return JSON.stringify({present:false});}
            })();
        """.trimIndent())
        return parseJson(r)?.optBoolean("present", false) ?: false
    }

    private suspend fun readResultMessage(): String {
        val r = evalJs("""
            (function(){
              try{
                var sel='[class*="toast"],[class*="notif"],[class*="alert"],[role="alert"]';
                var toasts=Array.prototype.slice.call(document.querySelectorAll(sel));
                var vis=toasts.filter(function(t){return t.offsetParent!==null&&t.textContent.trim().length>0;});
                if(vis.length)return JSON.stringify({msg:vis[vis.length-1].textContent.trim()});
                return JSON.stringify({msg:''});
              }catch(e){return JSON.stringify({msg:''});}
            })();
        """.trimIndent())
        return parseJson(r)?.optString("msg","")?.ifEmpty {
            "Không đọc được thông báo — kiểm tra Lịch sử đổi thưởng để xác nhận."
        } ?: "Lỗi đọc kết quả."
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun parseJson(raw: String): JSONObject? = runCatching {
        val s = raw.trim()
        val unescaped = if (s.startsWith("\""))
            org.json.JSONTokener(s).nextValue() as? String ?: s
        else s
        JSONObject(unescaped)
    }.getOrNull()

    private fun isOk(raw: String)   = parseJson(raw)?.optBoolean("ok",    false) ?: false
    private fun reason(raw: String) = parseJson(raw)?.optString("reason", "?")   ?: "?"

    override fun onDestroy() {
        // fix code-review: cancel tất cả jobs khi destroy
        mailFetchJob?.cancel()
        otpPollJob?.cancel()
        webView.destroy()
        super.onDestroy()
    }
}
