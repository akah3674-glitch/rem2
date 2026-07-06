package com.golike.ops

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val MAILTM_DOMAINS = setOf("web-library.net")

fun String.isMailTmDomain(): Boolean {
    val trimmed = this.trim()
    if (!trimmed.contains("@")) return false
    val domain = trimmed.substringAfter("@").lowercase().trim()
    return domain in MAILTM_DOMAINS
}

// ── Lich su rut the (local) ───────────────────────────────────────────────────
data class WithdrawRecord(
    val timestamp: Long   = System.currentTimeMillis(),
    val cardType: String  = "",
    val amount: Int       = 0,
    val phone: String     = "",
    val cardCode: String  = "",
    val serial: String    = "",
    val message: String   = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp).put("cardType", cardType)
        .put("amount",    amount)   .put("phone",    phone)
        .put("cardCode",  cardCode) .put("serial",   serial)
        .put("message",   message)

    companion object {
        fun fromJson(j: JSONObject) = WithdrawRecord(
            timestamp = j.optLong("timestamp"), cardType  = j.optString("cardType"),
            amount    = j.optInt("amount"),     phone     = j.optString("phone"),
            cardCode  = j.optString("cardCode"),serial    = j.optString("serial"),
            message   = j.optString("message")
        )
    }
}

data class GoLikeAccount(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val password: String = "",
    val token: String,
    val email: String,
    val coin: Int = 0,
    val mailEmail: String = "",
    val mailPass: String = "",
    val isMailTm: Boolean = false,
    val importedFromFile: Boolean = false,
    val withdrawHistory: List<WithdrawRecord> = emptyList(),
    /** Cookie web that tu WebLoginActivity.
     *  Khi co cookie nay, /api/withdraw gui kem Cookie header
     *  -> GoLike ghi vao "Lich su doi thuong" (nhu rut thu cong tren web). */
    val webCookie: String = ""
) {
    val displayLabel: String get() = "@$username  •  ${coin}xu"
    val hasMail: Boolean    get() = isMailTm && mailEmail.isNotEmpty()
    /** True = rut the se hien trong Lich su doi thuong */
    val hasWebCookie: Boolean get() = webCookie.isNotEmpty()

    fun toJson(): JSONObject {
        val histArr = JSONArray()
        withdrawHistory.forEach { histArr.put(it.toJson()) }
        return JSONObject()
            .put("id",               id)
            .put("username",         username)
            .put("password",         password)
            .put("token",            token)
            .put("email",            email)
            .put("coin",             coin)
            .put("mailEmail",        mailEmail)
            .put("mailPass",         mailPass)
            .put("isMailTm",         isMailTm)
            .put("importedFromFile", importedFromFile)
            .put("withdrawHistory",  histArr)
            .put("webCookie",        webCookie)
    }

    companion object {
        fun fromJson(j: JSONObject): GoLikeAccount? = runCatching {
            val histArr = j.optJSONArray("withdrawHistory") ?: JSONArray()
            val hist    = (0 until histArr.length()).map { WithdrawRecord.fromJson(histArr.getJSONObject(it)) }
            GoLikeAccount(
                id               = j.optString("id").ifEmpty { UUID.randomUUID().toString() },
                username         = j.optString("username"),
                password         = j.optString("password",""),
                token            = j.optString("token"),
                email            = j.optString("email",""),
                coin             = j.optInt("coin",0),
                mailEmail        = j.optString("mailEmail",""),
                mailPass         = j.optString("mailPass",""),
                isMailTm         = j.optBoolean("isMailTm",false),
                importedFromFile = j.optBoolean("importedFromFile",false),
                withdrawHistory  = hist,
                webCookie        = j.optString("webCookie","")
            )
        }.getOrNull()

        fun fromRemBlock(block: String): GoLikeAccount? {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val m = mutableMapOf<String, String>()
            var bearerToken = ""

            lines.forEach { line ->
                // Format 1: dòng bắt đầu thẳng bằng "Bearer eyJ..." (không có key)
                if (line.startsWith("Bearer ", ignoreCase = true) && line.length > 10) {
                    bearerToken = line.substring(7).trim()
                    return@forEach
                }
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key   = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    m[key] = value
                }
            }

            // Token từ: dòng Bearer thẳng > "auth: Bearer ..." > "token: ..."
            val token = when {
                bearerToken.isNotEmpty() -> bearerToken
                m["auth"]?.startsWith("Bearer ", ignoreCase = true) == true ->
                    m["auth"]!!.substring(7).trim()
                else -> m["token"] ?: ""
            }

            // Username — bỏ @ ở đầu nếu có
            val rawUser = m["username"] ?: m["user"] ?: return null
            val username = rawUser.removePrefix("@").trim()
            if (username.isEmpty()) return null

            val pass   = m["password"] ?: m["pass"] ?: ""
            // Cần ít nhất token hoặc mật khẩu
            if (token.isEmpty() && pass.isEmpty()) return null

            val email  = m["email"] ?: ""
            val coin   = (m["xu"] ?: m["coin"] ?: "0").trimEnd(';').toIntOrNull() ?: 0

            val mailEm = m["mail_email"] ?: m["mailemail"] ?: ""
            val mailPa = m["mail_pass"]  ?: m["mailpass"]  ?: ""

            // Nếu email chính là Mail.tm và không có mail_email riêng → dùng email chính
            val isMailTm = if (mailEm.isNotEmpty()) mailEm.isMailTmDomain()
                           else email.isMailTmDomain()
            val effectiveMailEmail = when {
                mailEm.isNotEmpty() -> mailEm
                isMailTm            -> email
                else                -> ""
            }
            val effectiveMailPass = mailPa.ifEmpty { if (isMailTm) pass else "" }

            return GoLikeAccount(
                username         = username,
                password         = pass,
                token            = token,
                coin             = coin,
                email            = email,
                mailEmail        = effectiveMailEmail,
                mailPass         = effectiveMailPass,
                isMailTm         = isMailTm,
                importedFromFile = true
            )
        }
    }
}

object AccountManager {
    private const val PREFS_NAME  = "golike_accounts"
    private const val KEY_LIST    = "accounts_json"
    private const val PHONE_PREFS = "golike_phones"
    private const val PHONE_KEY   = "used_phones"

    fun getAll(ctx: Context): List<GoLikeAccount> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw   = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { GoLikeAccount.fromJson(arr.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    // internal: dung duoc tu AccountsActivity trong cung package
    internal fun saveAll(ctx: Context, list: List<GoLikeAccount>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun add(ctx: Context, acc: GoLikeAccount) {
        val list = getAll(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.username == acc.username }
        if (idx >= 0) list[idx] = acc else list.add(acc)
        saveAll(ctx, list)
    }

    /** Them nhieu account cung luc (dung cho import hang loat). Trung username -> ghi de. */
    fun addAll(ctx: Context, accounts: List<GoLikeAccount>) {
        if (accounts.isEmpty()) return
        val list = getAll(ctx).toMutableList()
        accounts.forEach { acc ->
            val idx = list.indexOfFirst { it.username == acc.username }
            if (idx >= 0) list[idx] = acc else list.add(acc)
        }
        saveAll(ctx, list)
    }

    /** Cap nhat webCookie cho account, giu nguyen moi truong khac */
    fun updateWebCookie(ctx: Context, accountId: String, cookie: String) {
        val list = getAll(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.id == accountId }
        if (idx >= 0) { list[idx] = list[idx].copy(webCookie = cookie); saveAll(ctx, list) }
    }

    fun remove(ctx: Context, id: String) =
        saveAll(ctx, getAll(ctx).filter { it.id != id })

    fun addWithdrawRecord(ctx: Context, accountId: String, record: WithdrawRecord) {
        val list = getAll(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.id == accountId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(withdrawHistory = (list[idx].withdrawHistory + record).takeLast(50))
            saveAll(ctx, list)
        }
    }

    fun getById(ctx: Context, id: String): GoLikeAccount? =
        getAll(ctx).firstOrNull { it.id == id }

    fun clear(ctx: Context) = saveAll(ctx, emptyList())

    fun parseRemFile(content: String): List<GoLikeAccount> =
        content.split("---").map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { GoLikeAccount.fromRemBlock(it) }

    fun generateUniquePhone(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PHONE_PREFS, Context.MODE_PRIVATE)
        val used  = prefs.getStringSet(PHONE_KEY, mutableSetOf())!!.toMutableSet()
        val rng   = java.util.Random()
        var phone: String; var tries = 0
        do {
            phone = "0" + (1..9).map { rng.nextInt(10) }.joinToString("")
            if (++tries > 10_000) { used.clear(); break }
        } while (used.contains(phone))
        used.add(phone)
        prefs.edit().putStringSet(PHONE_KEY, used).apply()
        return phone
    }
}
