package com.golike.ops


import com.rem2.browser.R
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Withdraw flow v8.0 — CAPTCHA ảnh thủ công, không bypass:
 *  1. Gửi OTP rút thẻ về email GoLike
 *  2. Lấy OTP: @web-library.net → Mail.tm tự động; còn lại → nhập tay (tối đa MAX_OTP_ASKS lần)
 *  3. Hiện WebView CAPTCHA ngay — user tick "I'm not a robot" → nhấn Xác nhận
 *  4. Submit; nếu CAPTCHA sai → reload; nếu OTP sai + token còn hạn → nhập OTP lại rồi submit;
 *     nếu OTP sai + token hết hạn → reload WebView + hỏi OTP song song
 */
class WithdrawActivity : AppCompatActivity() {

    // ── UI refs ───────────────────────────────────────────────────────────────
    private lateinit var spinnerAccount  : Spinner
    private lateinit var tvAccInfo       : TextView
    private lateinit var tvMailStatus    : TextView
    private lateinit var layoutMailPass  : LinearLayout
    private lateinit var etMailPass      : TextInputEditText
    private lateinit var spinnerCard     : Spinner
    private lateinit var spinnerAmount   : Spinner
    private lateinit var btnStart        : MaterialButton
    private lateinit var btnBack         : MaterialButton

    private lateinit var sectionStatus   : LinearLayout
    private lateinit var tvLog           : TextView

    private lateinit var sectionCaptcha  : LinearLayout
    private lateinit var webViewCaptcha  : WebView
    private lateinit var tvCaptchaStatus : TextView
    private lateinit var btnConfirmCap   : MaterialButton
    private lateinit var btnCopyAudio    : MaterialButton   // ẩn — không dùng nữa

    private lateinit var sectionResult   : LinearLayout
    private lateinit var tvResult        : TextView

    // ── Session state ─────────────────────────────────────────────────────────
    @Volatile private var captchaToken       = ""
    @Volatile private var captchaTokenExpiry = 0L
    @Volatile private var otpCode            = ""
    @Volatile private var currentPhone       = ""

    private var otpAskCount = 0
    private val isSubmitting = AtomicBoolean(false)
    private var selectedAccount: GoLikeAccount? = null
    private var allAccounts: List<GoLikeAccount> = emptyList()

    // ── Constants ─────────────────────────────────────────────────────────────
    private val CARD_LABELS = listOf(
        "📱 Viettel", "📱 Mobifone", "📱 Vinaphone", "📱 Vietnamobile",
        "🎮 Garena", "🎮 Zing / ZingPlay", "🎮 VCoin (VTC)"
    )
    private val CARD_TYPES = listOf(
        GoLikeApi.CardType.VIETTEL, GoLikeApi.CardType.MOBIFONE,
        GoLikeApi.CardType.VINAPHONE, GoLikeApi.CardType.VIETNAMOBILE,
        GoLikeApi.CardType.GARENA, GoLikeApi.CardType.ZING, GoLikeApi.CardType.VCOIN
    )
    private val AMOUNTS       = listOf(10_000, 20_000, 50_000, 100_000, 200_000, 500_000)
    private val AMOUNT_LABELS = listOf("10.000đ","20.000đ","50.000đ","100.000đ","200.000đ","500.000đ")

    private val MAX_OTP_ASKS = 2
    private val CAPTCHA_TTL  = 110_000L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw)
        bindViews()
        loadAccounts()
        setupWebView()
        btnBack.setOnClickListener { finish() }
        btnStart.setOnClickListener { initiateWithdraw() }
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private fun bindViews() {
        spinnerAccount  = findViewById(R.id.spinnerAccount)
        tvAccInfo       = findViewById(R.id.tvAccInfo)
        tvMailStatus    = findViewById(R.id.tvMailStatus)
        layoutMailPass  = findViewById(R.id.layoutMailPass)
        etMailPass      = findViewById(R.id.etMailPassWithdraw)
        spinnerCard     = findViewById(R.id.spinnerCard)
        spinnerAmount   = findViewById(R.id.spinnerAmount)
        btnStart        = findViewById(R.id.btnStart)
        btnBack         = findViewById(R.id.btnBack)
        sectionStatus   = findViewById(R.id.sectionStatus)
        tvLog           = findViewById(R.id.tvLog)
        sectionCaptcha  = findViewById(R.id.sectionCaptcha)
        webViewCaptcha  = findViewById(R.id.webViewCaptcha)
        tvCaptchaStatus = findViewById(R.id.tvCaptchaStatus)
        btnConfirmCap   = findViewById(R.id.btnConfirmCaptcha)
        btnCopyAudio    = findViewById(R.id.btnCopyAudio)
        sectionResult   = findViewById(R.id.sectionResult)
        tvResult        = findViewById(R.id.tvResult)

        // Nút audio không dùng nữa — ẩn đi
        btnCopyAudio.visibility = View.GONE

        btnConfirmCap.setOnClickListener {
            if (!isCaptchaTokenValid()) {
                tvCaptchaStatus.text = "⚠️ Token hết hạn — giải lại CAPTCHA"
                tvCaptchaStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_yellow))
                return@setOnClickListener
            }
            addLog("🔐 Dùng CAPTCHA token — submit...")
            submitWithCaptcha()
        }
    }

    private fun loadAccounts() {
        allAccounts = AccountManager.getAll(this)
        val passedId = intent.getStringExtra("account_id")
        if (allAccounts.isEmpty()) {
            tvAccInfo.text = "⚠️ Chưa có tài khoản"; btnStart.isEnabled = false; return
        }

        spinnerAccount.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            allAccounts.map { "@${it.username}  •  ${it.coin}xu" })

        val preIdx = passedId?.let { id ->
            allAccounts.indexOfFirst { it.id == id }.coerceAtLeast(0)
        } ?: 0
        spinnerAccount.setSelection(preIdx)
        onAccountSelected(allAccounts[preIdx])

        spinnerAccount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                onAccountSelected(allAccounts[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spinnerCard.adapter   = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, CARD_LABELS)
        spinnerAmount.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, AMOUNT_LABELS)
    }

    private fun onAccountSelected(acc: GoLikeAccount) {
        selectedAccount = acc
        tvAccInfo.text  = "✉️ ${acc.email}  |  💰 ${acc.coin}xu"

        if (acc.isMailTm) {
            tvMailStatus.text = "📬 @web-library.net — OTP tự động qua Mail.tm"
            tvMailStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            layoutMailPass.visibility = if (acc.mailPass.isEmpty()) View.VISIBLE else View.GONE
            if (acc.mailPass.isEmpty()) etMailPass.setText(acc.password)
        } else {
            tvMailStatus.text = "✍️ Nhập OTP thủ công (không phải @web-library.net)"
            tvMailStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            layoutMailPass.visibility = View.GONE
        }

        if (acc.withdrawHistory.isNotEmpty()) {
            val hist = acc.withdrawHistory.takeLast(3).reversed().joinToString("\n") { r ->
                val dt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(r.timestamp))
                val ok = if (r.cardCode.isNotEmpty() || r.serial.isNotEmpty()) "✅" else "❌"
                "$ok [$dt] ${r.cardType} ${"%,d".format(r.amount).replace(",",".")}đ" +
                if (r.serial.isNotEmpty()) " | Seri: ${r.serial}" else ""
            }
            tvAccInfo.text = "${tvAccInfo.text}\n📋 $hist"
        }
    }

    // ── WebView ───────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true); setAcceptThirdPartyCookies(webViewCaptcha, true); flush()
        }
        with(webViewCaptcha.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            databaseEnabled      = true
            mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort      = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            allowContentAccess   = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.6367.82 Mobile Safari/537.36"
        }
        webViewCaptcha.addJavascriptInterface(CaptchaJsBridge(), "Android")
        webViewCaptcha.webChromeClient = WebChromeClient()
        webViewCaptcha.webViewClient   = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
        }
    }

    inner class CaptchaJsBridge {
        @JavascriptInterface
        fun onCaptchaToken(token: String) {
            captchaToken       = token
            captchaTokenExpiry = System.currentTimeMillis() + CAPTCHA_TTL
            runOnUiThread {
                tvCaptchaStatus.text = "✅ CAPTCHA xong! Token hiệu lực ~${CAPTCHA_TTL/1000}s — nhấn Xác nhận"
                tvCaptchaStatus.setTextColor(ContextCompat.getColor(this@WithdrawActivity, R.color.accent_green))
                btnConfirmCap.isEnabled = true
                addLog("✅ Nhận CAPTCHA token — nhấn Xác nhận để rút")
            }
        }

        @JavascriptInterface
        fun onCaptchaExpired() {
            captchaToken       = ""
            captchaTokenExpiry = 0L
            runOnUiThread {
                tvCaptchaStatus.text = "⚠️ Token hết hạn — tick lại ô xác nhận"
                tvCaptchaStatus.setTextColor(ContextCompat.getColor(this@WithdrawActivity, R.color.accent_yellow))
                btnConfirmCap.isEnabled = false
            }
        }
    }

    private fun isCaptchaTokenValid() =
        captchaToken.length > 20 && System.currentTimeMillis() < captchaTokenExpiry

    private fun remainingCaptchaSec() =
        ((captchaTokenExpiry - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    // ── Bắt đầu phiên rút ────────────────────────────────────────────────────
    // lifecycleScope.launch mặc định Dispatchers.Main; GoLikeApi.* dùng withContext(IO) nội bộ
    // → sau khi suspend call trả về, ta đang ở Main → gọi UI trực tiếp được

    private fun initiateWithdraw() {
        val acc = selectedAccount ?: run { toast("Chưa chọn tài khoản"); return }

        captchaToken = ""; captchaTokenExpiry = 0L
        otpCode = ""; otpAskCount = 0
        isSubmitting.set(false)

        sectionStatus.visibility  = View.VISIBLE
        sectionCaptcha.visibility = View.GONE
        sectionResult.visibility  = View.GONE
        tvLog.text = ""
        btnStart.isEnabled = false

        val cardType  = CARD_TYPES[spinnerCard.selectedItemPosition]
        val cardLabel = CARD_LABELS[spinnerCard.selectedItemPosition]
        val amount    = AMOUNTS[spinnerAmount.selectedItemPosition]

        currentPhone = if (cardType.needsPhone) {
            AccountManager.generateUniquePhone(this).also { addLog("📱 Số điện thoại: $it") }
        } else ""

        addLog("▶ Bắt đầu: @${acc.username} — $cardLabel — ${"%,d".format(amount).replace(",",".")}đ")

        lifecycleScope.launch {
            // ── Bước 1: Gửi OTP ──────────────────────────────────────────────
            addLog("📨 Gửi OTP rút thẻ...")
            val (otpSent, otpMsg) = GoLikeApi.sendWithdrawOtp(acc.token)
            addLog(if (otpSent) "✅ OTP đã gửi về email" else "⚠️ Gửi OTP: $otpMsg")

            // ── Bước 2: Lấy OTP → rồi hiện CAPTCHA ──────────────────────────
            // Mọi nhánh đều kết thúc bằng: otpCode đã có → showCaptchaSection()
            //                              hoặc: thất bại → re-enable btnStart
            if (acc.isMailTm) {
                val mailEmail = acc.mailEmail.ifEmpty { acc.email }
                val mailPass  = if (layoutMailPass.visibility == View.VISIBLE)
                    etMailPass.text.toString().trim().ifEmpty { acc.mailPass }
                else acc.mailPass

                addLog("📬 Đọc OTP từ Mail.tm ($mailEmail)...")
                val mailToken = GoLikeApi.getMailToken(mailEmail, mailPass)
                if (mailToken != null) {
                    addLog("✅ Mail.tm OK — chờ OTP (tối đa 3 phút)...")
                    val polled = GoLikeApi.pollOtpFromMail(mailToken, 180)
                    if (polled != null) {
                        // OTP tự động → hiện CAPTCHA ngay (đang trên Main thread)
                        otpCode = polled
                        addLog("✅ OTP tự động: $otpCode")
                        addLog("🔐 Giải CAPTCHA bên dưới rồi nhấn Xác nhận")
                        showCaptchaSection()
                    } else {
                        // Poll hết giờ → nhập tay; onOk mới hiện CAPTCHA
                        addLog("⚠️ Không nhận được OTP — nhập thủ công")
                        askForOtp {
                            addLog("🔐 Giải CAPTCHA bên dưới rồi nhấn Xác nhận")
                            showCaptchaSection()
                        }
                    }
                } else {
                    addLog("⚠️ Mail.tm đăng nhập thất bại — nhập OTP thủ công")
                    askForOtp {
                        addLog("🔐 Giải CAPTCHA bên dưới rồi nhấn Xác nhận")
                        showCaptchaSection()
                    }
                }
            } else {
                // Không phải @web-library.net → luôn nhập tay
                askForOtp {
                    addLog("🔐 Giải CAPTCHA bên dưới rồi nhấn Xác nhận")
                    showCaptchaSection()
                }
            }
        }
    }

    // ── OTP helpers ───────────────────────────────────────────────────────────

    /**
     * Hỏi OTP rồi submit lại với CAPTCHA token hiện tại còn hạn.
     * onCaptchaToken đã enable btnConfirmCap; ở đây chỉ cần re-enable sau khi nhập OTP.
     */
    private fun askForOtpThenResubmit() {
        val sec = remainingCaptchaSec()
        addLog("❌ OTP sai — nhập lại (CAPTCHA token còn ${sec}s, tái dùng)")
        tvCaptchaStatus.text = "✅ Token còn ${sec}s — nhập OTP rồi nhấn Xác nhận"
        tvCaptchaStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        btnConfirmCap.isEnabled = false  // tắt tạm trong khi nhập OTP
        askForOtp {
            // Sau khi nhập OTP, re-enable nếu token vẫn còn hạn
            if (isCaptchaTokenValid()) {
                btnConfirmCap.isEnabled = true
            } else {
                addLog("⚠️ Token CAPTCHA vừa hết hạn trong khi nhập OTP — giải lại")
                reloadCaptchaFresh()
            }
        }
    }

    /**
     * OTP sai + CAPTCHA token hết hạn: reload WebView song song với hỏi OTP.
     * Callback chỉ log — btnConfirmCap sẽ được enable bởi onCaptchaToken.
     */
    private fun askForOtpWhileCaptchaReloads() {
        addLog("❌ OTP sai + CAPTCHA hết hạn — nhập OTP và giải CAPTCHA mới")
        tvCaptchaStatus.text = "⚠️ Giải CAPTCHA mới → nhập OTP → nhấn Xác nhận"
        tvCaptchaStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_yellow))
        btnConfirmCap.isEnabled = false
        reloadCaptchaFresh()
        askForOtp { addLog("✍️ OTP nhập xong — giải CAPTCHA bên dưới rồi nhấn Xác nhận") }
    }

    /**
     * Hiện dialog nhập OTP. Giới hạn MAX_OTP_ASKS lần/phiên.
     * [onOk] chạy trên Main thread sau khi user nhấn OK với OTP hợp lệ.
     */
    private fun askForOtp(onOk: () -> Unit) {
        if (otpAskCount >= MAX_OTP_ASKS) {
            addLog("⛔ Đã hỏi OTP $MAX_OTP_ASKS lần — dừng")
            sectionResult.visibility = View.VISIBLE
            btnStart.isEnabled       = true
            tvResult.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            tvResult.text = "⛔ Đã thử OTP $MAX_OTP_ASKS lần không thành công.\nKiểm tra email GoLike và nhấn Bắt đầu lại."
            return
        }
        otpAskCount++

        val et = android.widget.EditText(this).apply {
            hint      = "Mã OTP 6 số từ email GoLike"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Nhập OTP (lần $otpAskCount/$MAX_OTP_ASKS)")
            .setMessage("Kiểm tra email GoLike để lấy mã OTP:")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val entered = et.text.toString().trim()
                if (entered.length >= 4) {
                    otpCode = entered
                    addLog("✍️ OTP: $entered")
                    onOk()   // chạy trên Main thread (click listener)
                } else {
                    addLog("⚠️ OTP không hợp lệ")
                    btnStart.isEnabled = true
                }
            }
            .setCancelable(false)
            .show()
    }

    // ── Submit với CAPTCHA token ──────────────────────────────────────────────

    private fun submitWithCaptcha() {
        if (!isSubmitting.compareAndSet(false, true)) return
        val acc      = selectedAccount ?: run { isSubmitting.set(false); return }
        val cardType = CARD_TYPES[spinnerCard.selectedItemPosition]
        val amount   = AMOUNTS[spinnerAmount.selectedItemPosition]
        val tok      = captchaToken

        addLog("💸 Submit với CAPTCHA (còn ${remainingCaptchaSec()}s)...")
        btnConfirmCap.isEnabled = false

        lifecycleScope.launch {
            val result = GoLikeApi.submitWithdraw(
                token = acc.token, amount = amount, cardType = cardType,
                otp = otpCode, captchaToken = tok, phone = currentPhone,
                webCookie = acc.webCookie   // cookie web that -> hien trong Lich su doi thuong
            )
            // Đang trên Main thread sau khi withContext(IO) hoàn thành
            isSubmitting.set(false)
            when {
                result.success -> {
                    saveHistory(acc, cardType, amount, result)
                    showSuccess(result)
                }
                result.captchaError -> {
                    captchaToken = ""; captchaTokenExpiry = 0L
                    addLog("❌ Token CAPTCHA bị từ chối — giải lại")
                    tvCaptchaStatus.text = "❌ Token không hợp lệ — giải lại CAPTCHA bên dưới"
                    tvCaptchaStatus.setTextColor(ContextCompat.getColor(this@WithdrawActivity, R.color.accent_red))
                    btnConfirmCap.isEnabled = false
                    webViewCaptcha.reload()
                }
                result.otpError -> {
                    if (isCaptchaTokenValid()) askForOtpThenResubmit()
                    else askForOtpWhileCaptchaReloads()
                }
                else -> showFailure(result)
            }
        }
    }

    // ── CAPTCHA UI ────────────────────────────────────────────────────────────

    private fun showCaptchaSection() {
        sectionCaptcha.visibility = View.VISIBLE
        if (isCaptchaTokenValid()) {
            val sec = remainingCaptchaSec()
            tvCaptchaStatus.text = "✅ Tái dùng token cũ (còn ${sec}s) — nhấn Xác nhận"
            tvCaptchaStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnConfirmCap.isEnabled = true
            addLog("♻️ Tái dùng CAPTCHA token (còn ${sec}s)")
        } else {
            reloadCaptchaFresh()
        }
    }

    private fun reloadCaptchaFresh() {
        captchaToken = ""; captchaTokenExpiry = 0L
        sectionCaptcha.visibility = View.VISIBLE
        btnConfirmCap.isEnabled   = false
        tvCaptchaStatus.text = "⏳ Đang tải CAPTCHA — hãy tick ô \"I'm not a robot\" bên dưới"
        tvCaptchaStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_yellow))
        webViewCaptcha.loadDataWithBaseURL(
            "https://app.golike.net/withdraw",
            GoLikeApi.buildCaptchaHtml(),
            "text/html", "utf-8", null
        )
    }

    // ── Result UI ─────────────────────────────────────────────────────────────

    private fun showSuccess(result: GoLikeApi.WithdrawResult) {
        sectionCaptcha.visibility = View.GONE
        sectionResult.visibility  = View.VISIBLE
        btnStart.isEnabled        = true
        tvResult.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        val _ct  = CARD_TYPES[spinnerCard.selectedItemPosition]
        val _amt = AMOUNTS[spinnerAmount.selectedItemPosition]
        val _dtD = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        val _amtL = when (_amt) {
            10_000 -> "10k"; 20_000 -> "20k"; 50_000 -> "50k"
            100_000 -> "100k"; 200_000 -> "200k"; 500_000 -> "500k"
            else -> "${_amt / 1000}k"
        }
        tvResult.text = buildString {
            append("✅ RÚT THÀNH CÔNG!\n\n")
            selectedAccount?.let { acc ->
                append("@${acc.username} / ${_ct.name} / $_amtL - $_dtD\n")
            }
            if (result.serial.isNotEmpty() || result.cardCode.isNotEmpty()) {
                append("Seri|Mã thẻ : .${result.serial}|${result.cardCode}\n")
            }
            if (currentPhone.isNotEmpty()) append("📱 SĐT: $currentPhone\n")
            if (result.serial.isEmpty() && result.cardCode.isEmpty())
                append("\n📧 Thẻ sẽ được gửi qua email.\n")
            if (result.message.isNotEmpty()) append("\n${result.message}")
        }

        // Nút copy mã thẻ / serial
        val copyTarget = result.cardCode.ifEmpty { result.serial }
        if (copyTarget.isNotEmpty()) {
            val btnCopy = MaterialButton(this).apply {
                text = "📋 Copy mã thẻ"
                textSize = 14f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                backgroundTintList = ContextCompat.getColorStateList(
                    this@WithdrawActivity, R.color.accent_blue)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(10) }
            }
            btnCopy.setOnClickListener {
                val clip = ClipData.newPlainText("card_code", copyTarget)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(clip)
                toast("✅ Đã copy mã thẻ!")
            }
            (sectionResult as LinearLayout).addView(btnCopy)
        }

        addLog("🎉 THÀNH CÔNG!")
    }

    private fun showFailure(result: GoLikeApi.WithdrawResult) {
        sectionResult.visibility = View.VISIBLE
        btnStart.isEnabled       = true
        tvResult.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        tvResult.text = "❌ Thất bại: ${result.message}\n\n[raw] ${result.raw.take(300)}"
    }

    private fun saveHistory(
        acc: GoLikeAccount, cardType: GoLikeApi.CardType, amount: Int,
        result: GoLikeApi.WithdrawResult
    ) {
        AccountManager.addWithdrawRecord(this, acc.id, WithdrawRecord(
            cardType = cardType.name, amount = amount, phone = currentPhone,
            cardCode = result.cardCode, serial = result.serial, message = result.message
        ))
        saveToFile(acc, cardType, amount, result)
        addLog("💾 Đã lưu lịch sử")
    }

    private fun saveToFile(
        acc: GoLikeAccount, cardType: GoLikeApi.CardType, amount: Int,
        result: GoLikeApi.WithdrawResult
    ) {
        try {
            val today       = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
            val dateDisplay = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            val amtLabel    = when (amount) {
                10_000 -> "10k"; 20_000 -> "20k"; 50_000 -> "50k"
                100_000 -> "100k"; 200_000 -> "200k"; 500_000 -> "500k"
                else -> "${amount / 1000}k"
            }
            val dir  = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, "the_cao_${today}.txt")
            val line = buildString {
                append("@${acc.username} / ${cardType.name} / $amtLabel - $dateDisplay\n")
                if (result.serial.isNotEmpty() || result.cardCode.isNotEmpty()) {
                    append("Seri|Mã thẻ : .${result.serial}|${result.cardCode}\n")
                } else {
                    append("📧 Thẻ gửi qua email\n")
                }
                append("\n")
            }
            file.appendText(line, Charsets.UTF_8)
            addLog("📁 Lưu: ${file.name}")
        } catch (e: Exception) {
            addLog("⚠️ Không lưu file: ${e.message}")
        }
    }

    private fun addLog(msg: String) {
        runOnUiThread {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            tvLog.append("[$ts] $msg\n")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
