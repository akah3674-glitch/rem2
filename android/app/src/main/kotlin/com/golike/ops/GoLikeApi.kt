package com.golike.ops

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GoLikeApi {
    const val GW      = "https://gateway.golike.net"
    const val MAILTM  = "https://api.mail.tm"
    const val APK_UA  = "AutoGoLike/26.06.19.1 (Android)"
    const val SITEKEY = "6Leo5PMrAAAAAArIr7KjV49Pz4zRgLq05wIZy33w"
    private const val REM2_SERVER = "https://zkdjjc--hemv5x7n7p.replit.app"

    enum class CardType(val method: String, val needsTelco: Boolean, val needsPhone: Boolean) {
        VIETTEL     ("card",   true,  true),
        MOBIFONE    ("card",   true,  true),
        VINAPHONE   ("card",   true,  true),
        VIETNAMOBILE("card",   true,  true),
        GARENA      ("garena", false, false),
        ZING        ("zing",   false, false),
        VCOIN       ("vcoin",  false, false),
    }

    data class WithdrawResult(
        val success: Boolean,
        val message: String,
        val captchaError: Boolean = false,
        val otpError: Boolean = false,
        val cardCode: String = "",
        val serial: String = "",
        val raw: String = ""
    )

    fun isCaptchaError(r: JSONObject): Boolean {
        if (r.optBoolean("captcha_error", false)) return true
        if (r.has("captcha") && !r.optBoolean("captcha", true)) return true
        val msg = r.optString("message", "").lowercase()
        return "captcha" in msg || "robot" in msg || "xac minh" in msg
                || "verify" in msg || "human" in msg
    }

    fun isOtpError(r: JSONObject): Boolean {
        val msg = r.optString("message", "").lowercase()
        return "otp" in msg || "ma xac thuc" in msg || "code" in msg
    }

    private fun tVal(): String {
        val ts    = System.currentTimeMillis().div(1000).toString().toByteArray()
        val first = Base64.encodeToString(ts, Base64.NO_WRAP)
        return Base64.encodeToString(first.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * httpRequest — hỗ trợ cookie web thật (cần cho /api/withdraw hiện lịch sử).
     * [cookie]: chuỗi "key=val; key2=val2" lấy từ WebView CookieManager.
     *            Nếu null → không gửi Cookie header (hành vi cũ).
     */
    private fun httpRequest(
        method: String,
        urlStr: String,
        body: JSONObject? = null,
        token: String? = null,
        ua: String = APK_UA,
        cookie: String? = null
    ): Pair<Int, JSONObject> {
        val url  = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("t", tVal())
            if (token  != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookie != null && cookie.isNotEmpty())
                conn.setRequestProperty("Cookie", cookie)
            conn.connectTimeout = 25_000
            conn.readTimeout    = 25_000

            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, "UTF-8").use {
                    it.write(body.toString()); it.flush()
                }
            }

            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text   = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            Pair(code, runCatching { JSONObject(text) }.getOrElse { JSONObject() })
        } catch (ex: Exception) {
            Pair(0, JSONObject().put("error", ex.message ?: "network error"))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * relayPost — relay native một POST request từ fetch interceptor.
     *
     * Mục đích: khi Vue.js gọi POST /api/withdraw, WebWithdrawActivity bắt lại
     * toàn bộ headers+body rồi gọi hàm này để gửi natively với đầy đủ:
     *  - headers gốc từ Vue.js (Authorization: Bearer, Content-Type, v.v.)
     *  - cookies của gateway.golike.net + app.golike.net từ CookieManager
     *  - Origin + Referer header → GoLike server nhận diện "web request"
     *    → ghi vào "Lịch sử đổi thưởng" thay vì chỉ "Nhật ký hoạt động"
     */
    suspend fun relayPost(
        url: String,
        body: String,
        headersJson: String,
        cookie: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout    = 20_000
            // Áp dụng headers gốc từ Vue.js fetch
            runCatching {
                val obj = JSONObject(headersJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (!k.equals("content-length", ignoreCase = true) &&
                        !k.equals("host",           ignoreCase = true)) {
                        conn.setRequestProperty(k, obj.getString(k))
                    }
                }
            }
            // Extra headers (Origin, Referer, v.v.) — ghi đè nếu trùng
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            // Cookies
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            // Đảm bảo Content-Type
            if (conn.getRequestProperty("Content-Type").isNullOrEmpty()) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.readText() ?: "{}"
            }.getOrDefault("{}")
            Pair(code, resp)
        } catch (e: Exception) {
            Pair(0, """{"error":"${e.message}"}""")
        } finally {
            conn.disconnect()
        }
    }

    // ── GoLike API ────────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String): Pair<String?, JSONObject> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("username", username).put("password", password)
            val (code, r) = httpRequest("POST", "$GW/api/auto/login", body)
            val token = if (code == 200) r.optString("token").takeIf { it.isNotEmpty() } else null
            Pair(token, r)
        }

    suspend fun getUserInfo(token: String): JSONObject =
        withContext(Dispatchers.IO) {
            val (_, r) = httpRequest("GET", "$GW/api/users/me", token = token)
            r.optJSONObject("data") ?: JSONObject()
        }

    suspend fun sendWithdrawOtp(token: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("type", "withdraw")
            val (code, r) = httpRequest("POST", "$GW/api/otp", body, token)
            val ok  = code in 200..201 || r.optBoolean("success")
            val msg = r.optString("message", "Gui OTP that bai")
            Pair(ok, msg)
        }

    /**
     * submitWithdraw — hỗ trợ cookie web thật.
     *
     * Nếu [webCookie] được truyền vào (lấy từ WebLoginActivity), request gửi
     * kèm Cookie header → GoLike server nhận diện là "web withdrawal" → ghi vào
     * "Lich su doi thuong" thay vì chi Nhat ky hoat dong.
     *
     * Nếu [webCookie] null/rỗng → hành vi cũ (chỉ Bearer token).
     */
    suspend fun submitWithdraw(
        token: String,
        amount: Int,
        cardType: CardType,
        otp: String,
        captchaToken: String,
        phone: String = "",
        webCookie: String = ""
    ): WithdrawResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("amount",        amount)
            .put("methods",       cardType.method)
            .put("otp",           otp)
            .put("captcha_token", captchaToken)

        if (cardType.needsTelco) {
            val telco = when (cardType) {
                CardType.VIETTEL      -> "viettel"
                CardType.MOBIFONE     -> "mobifone"
                CardType.VINAPHONE    -> "vinaphone"
                CardType.VIETNAMOBILE -> "vietnamobile"
                else                  -> "viettel"
            }
            body.put("telco", telco)
            if (phone.isNotEmpty()) body.put("phone", phone)
        }

        val effectiveCookie = webCookie.ifEmpty { null }
        val (code, r) = httpRequest("POST", "$GW/api/withdraw", body, token,
                                    cookie = effectiveCookie)

        val success = code in 200..201 && (r.optBoolean("success") || r.optInt("status") == 1)
        val message = r.optString("message", if (success) "Thanh cong" else "That bai")
        val capErr  = !success && isCaptchaError(r)
        val otpErr  = !success && !capErr && isOtpError(r)

        val data     = r.optJSONObject("data")
        val cardCode = data?.run {
            listOf("card_code","code","pin","card_pin","card_num","card_number","the_code","ma_the")
                .firstNotNullOfOrNull { k -> optString(k).takeIf { it.isNotEmpty() } }
        } ?: ""
        val serial = data?.run {
            listOf("serial","seri","card_serial","so_series","series")
                .firstNotNullOfOrNull { k -> optString(k).takeIf { it.isNotEmpty() } }
        } ?: ""

        WithdrawResult(
            success      = success,
            message      = message,
            captchaError = capErr,
            otpError     = otpErr,
            cardCode     = cardCode,
            serial       = serial,
            raw          = r.toString()
        )
    }

    // ── Mail.tm ──────────────────────────────────────────────────────────────

    suspend fun getMailToken(email: String, password: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("address", email).put("password", password)
            var (code, r) = httpRequest("POST", "$MAILTM/token", body, ua = "Mozilla/5.0")
            if (code == 0) {
                delay(2000)
                val retry = httpRequest("POST", "$MAILTM/token", body, ua = "Mozilla/5.0")
                code = retry.first; r = retry.second
            }
            if (code == 200) r.optString("token").takeIf { it.isNotEmpty() } else null
        }

    /**
     * pollOtpFromMail — đọc OTP từ inbox Mail.tm.
     *
     * Đã chuyển việc poll Mail.tm (nhiều request lặp lại mỗi 4s) sang server
     * cloud (rem2 API) để giảm tải CPU/mạng trên máy — điện thoại chỉ hỏi
     * trạng thái job nhẹ mỗi vài giây thay vì tự fetch+parse email.
     * Nếu server lỗi/không phản hồi → tự fallback về polling cục bộ (hành vi cũ).
     *
     * [initialDelaySec]: delay trước lần poll đầu tiên.
     *   - Mặc định 6s (đủ thời gian email đến) khi gọi ngay sau submit.
     *   - Truyền 1 khi đã chờ đủ (user click vào ô OTP sau vài giây) để
     *     tránh mất thêm thời gian.
     */
    suspend fun pollOtpFromMail(
        mailToken: String,
        maxWaitSec: Int = 180,
        initialDelaySec: Int = 6
    ): String? = withContext(Dispatchers.IO) {
        if (initialDelaySec > 0) delay(initialDelaySec * 1000L)

        val cloudOtp = runCatching { pollOtpFromMailCloud(mailToken, maxWaitSec) }.getOrNull()
        if (cloudOtp != null) return@withContext cloudOtp

        // Fallback: server khong kha dung -> poll truc tiep tu may (hanh vi cu)
        val deadline = System.currentTimeMillis() + maxWaitSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            try {
                val (code, r) = httpRequest(
                    "GET", "$MAILTM/messages", token = mailToken, ua = "Mozilla/5.0"
                )
                if (code == 200) {
                    val total = r.optInt("hydra:totalItems", 0)
                    if (total > 0) {
                        val msgs = r.optJSONArray("hydra:member")
                        if (msgs != null && msgs.length() > 0) {
                            val msgId = msgs.getJSONObject(0).optString("id")
                            if (msgId.isNotEmpty()) {
                                val (_, msg) = httpRequest(
                                    "GET", "$MAILTM/messages/$msgId",
                                    token = mailToken, ua = "Mozilla/5.0"
                                )
                                val combined = msg.optString("text","") + " " + msg.optString("html","")
                                val otp = Regex("\\b(\\d{6})\\b").find(combined)?.value
                                if (otp != null) return@withContext otp
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            delay(4_000)
        }
        null
    }

    /**
     * pollOtpFromMailCloud — nhờ server rem2 poll Mail.tm hộ (server chạy trên
     * Replit, không tốn pin/CPU của điện thoại). Máy chỉ hỏi trạng thái job
     * mỗi 3s bằng 1 request nhẹ, thay vì tự fetch+parse email mỗi 4s.
     * Trả về null nếu server lỗi/timeout để caller fallback polling cục bộ.
     */
    private suspend fun pollOtpFromMailCloud(mailToken: String, maxWaitSec: Int): String? {
        val createBody = JSONObject().put("mailToken", mailToken).put("maxWaitSec", maxWaitSec)
        val (createCode, createResp) = httpRequest(
            "POST", "$REM2_SERVER/api/rem2/otp/create", createBody, ua = "Mozilla/5.0"
        )
        if (createCode != 200) return null
        val jobId = createResp.optString("jobId").takeIf { it.isNotEmpty() } ?: return null

        val deadline = System.currentTimeMillis() + (maxWaitSec + 15) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(3_000)
            val (code, r) = httpRequest("GET", "$REM2_SERVER/api/rem2/otp/status/$jobId", ua = "Mozilla/5.0")
            if (code != 200) continue
            when (r.optString("status")) {
                "done"  -> return r.optString("otp").takeIf { it.isNotEmpty() }
                "error" -> return null
            }
        }
        return null
    }

    // ── CAPTCHA HTML (WebView) ────────────────────────────────────────────────

    fun buildCaptchaHtml(): String = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
  <style>
    *{box-sizing:border-box}
    body{margin:0;padding:16px;background:#F3F4F6;display:flex;flex-direction:column;
         align-items:center;font-family:-apple-system,sans-serif}
    .g-recaptcha{margin:14px auto}
    .title{color:#111827;font-size:15px;font-weight:600;text-align:center;margin-bottom:4px}
    .sub{color:#6B7280;font-size:13px;text-align:center;margin-bottom:10px;line-height:1.5}
    .hint{color:#1D4ED8;font-size:12px;text-align:center;margin-top:10px}
    .divider{width:100%;border:none;border-top:1px solid #E5E7EB;margin:12px 0}
    textarea{width:100%;height:72px;margin-top:4px;border-radius:8px;padding:8px;font-size:11px;
             background:#FFFFFF;color:#111827;border:1.5px solid #D1D5DB;resize:none;outline:none}
    textarea:focus{border-color:#2563EB}
    .btn{margin-top:8px;padding:10px 0;border:none;border-radius:8px;font-size:13px;
         font-weight:600;cursor:pointer;width:100%}
    .btn-green{background:#16A34A;color:#fff}
    #status{color:#B45309;font-size:12px;margin-top:6px;min-height:18px;text-align:center}
  </style>
  <script src="https://www.google.com/recaptcha/api.js" async defer></script>
  <script>
    function onCaptchaDone(token){
      document.getElementById('status').textContent='Xac nhan xong — nhan Xac nhan trong app';
      document.getElementById('status').style.color='#16A34A';
      try{Android.onCaptchaToken(token)}catch(e){}
    }
    function onCaptchaExpired(){
      document.getElementById('status').textContent='Het han — tick lai o xac nhan';
      document.getElementById('status').style.color='#B45309';
      try{Android.onCaptchaExpired()}catch(e){}
    }
    function useManual(){
      var t=document.getElementById('manualToken').value.trim();
      if(t.length>20){
        document.getElementById('status').textContent='Da dung token thu cong';
        document.getElementById('status').style.color='#16A34A';
        try{Android.onCaptchaToken(t)}catch(e){}
      }else{alert('Token qua ngan (phai > 20 ky tu)!')}
    }
  </script>
</head>
<body>
  <p class="title">Xac nhan ban khong phai robot</p>
  <p class="sub">Tick vao o ben duoi roi nhan <b>Xac nhan</b> trong app</p>
  <div class="g-recaptcha"
       data-sitekey="$SITEKEY"
       data-callback="onCaptchaDone"
       data-expired-callback="onCaptchaExpired"
       data-theme="light"></div>
  <div id="status"></div>
  <hr class="divider">
  <p class="hint">Hoac lay token tu app.golike.net/withdraw (F12 -> Network -> /withdraw -> captcha_token):</p>
  <textarea id="manualToken" placeholder="Dan captcha token o day..."></textarea>
  <button class="btn btn-green" onclick="useManual()">Dung token thu cong</button>
</body>
</html>
""".trimIndent()
}
