package com.golike.ops


import com.rem2.browser.R
import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etUser   = findViewById<TextInputEditText>(R.id.etUsername)
        val etPass   = findViewById<TextInputEditText>(R.id.etPassword)
        val etMail   = findViewById<TextInputEditText>(R.id.etMailEmail)
        val etMailP  = findViewById<TextInputEditText>(R.id.etMailPass)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val btnBack  = findViewById<MaterialButton>(R.id.btnBack)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnBack.setOnClickListener { finish() }

        btnLogin.setOnClickListener {
            val username  = etUser.text.toString().trim()
            val password  = etPass.text.toString().trim()
            val mailEmail = etMail.text.toString().trim()
            val mailPass  = etMailP.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                tvStatus.text = "Vui lòng nhập username và mật khẩu"
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvStatus.text = "⏳ Đang đăng nhập..."

            lifecycleScope.launch {
                val (token, resp) = GoLikeApi.login(username, password)
                if (token != null) {
                    val data  = resp.optJSONObject("data")
                    val email = data?.optString("email") ?: ""
                    val coin  = data?.optInt("coin", 0) ?: 0

                    // Detect mail.tm from the resolved email
                    val mEmail = mailEmail.ifEmpty { email }
                    val mailTm = mEmail.isMailTmDomain()

                    val acc = GoLikeAccount(
                        username         = username,
                        password         = password,
                        token            = token,
                        email            = email,
                        coin             = coin,
                        mailEmail        = if (mailEmail.isNotEmpty()) mailEmail
                                           else if (mailTm) email else "",
                        mailPass         = mailPass.ifEmpty { password },
                        isMailTm         = mailTm || mailEmail.isMailTmDomain(),
                        importedFromFile = false
                    )
                    AccountManager.add(this@LoginActivity, acc)

                    // Also keep legacy single-account prefs for backward compat
                    getSharedPreferences("golike_prefs", MODE_PRIVATE).edit()
                        .putString("token",      token)
                        .putString("username",   username)
                        .putString("password",   password)
                        .putString("mail_email", mEmail)
                        .putString("mail_pass",  mailPass.ifEmpty { password })
                        .putString("email",      email)
                        .putInt   ("coin",       coin)
                        .apply()

                    runOnUiThread {
                        tvStatus.setTextColor(ContextCompat.getColor(this@LoginActivity, R.color.accent_green))
                        tvStatus.text = "✅ Đăng nhập OK — @$username (${coin}xu)"
                        // Return success so AccountsActivity refreshes
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                } else {
                    runOnUiThread {
                        btnLogin.isEnabled = true
                        tvStatus.setTextColor(ContextCompat.getColor(this@LoginActivity, R.color.accent_red))
                        tvStatus.text = "❌ ${resp.optString("message", "Sai username/mật khẩu")}"
                    }
                }
            }
        }
    }
}
