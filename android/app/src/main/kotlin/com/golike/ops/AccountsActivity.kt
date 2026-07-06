package com.golike.ops


import com.rem2.browser.R
  import android.app.Activity
  import android.content.ClipData
  import android.content.ClipboardManager
  import android.content.Context
  import android.content.Intent
  import android.net.Uri
  import android.os.Bundle
  import android.view.Gravity
  import android.view.View
  import android.widget.*
  import com.google.android.material.dialog.MaterialAlertDialogBuilder
  import androidx.appcompat.app.AppCompatActivity
  import androidx.core.content.ContextCompat
  import androidx.lifecycle.lifecycleScope
  import com.google.android.material.button.MaterialButton
  import kotlinx.coroutines.launch

  class AccountsActivity : AppCompatActivity() {

      companion object {
          const val REQUEST_ADD        = 1001
          const val REQUEST_IMPORT     = 1002
          const val REQUEST_CARD_FILE  = 1003

          private val CARD_LABELS = arrayOf(
              "📱 Viettel", "📱 Mobifone", "📱 Vinaphone", "📱 Vietnamobile",
              "🎮 Garena", "🎮 Zing", "🎮 VCoin"
          )
          private val CARD_TYPES = arrayOf(
              GoLikeApi.CardType.VIETTEL, GoLikeApi.CardType.MOBIFONE,
              GoLikeApi.CardType.VINAPHONE, GoLikeApi.CardType.VIETNAMOBILE,
              GoLikeApi.CardType.GARENA, GoLikeApi.CardType.ZING, GoLikeApi.CardType.VCOIN
          )
          private val AMOUNTS       = intArrayOf(10_000, 20_000, 50_000, 100_000, 200_000, 500_000)
          private val AMOUNT_LABELS = arrayOf("10.000đ","20.000đ","50.000đ","100.000đ","200.000đ","500.000đ")
      }

      // ── Tab 1 views ───────────────────────────────────────────────────────────
      private lateinit var tab1Content : LinearLayout
      private lateinit var llAccounts  : LinearLayout
      private lateinit var tvEmpty     : TextView
      private lateinit var btnAdd      : MaterialButton
      private lateinit var btnImport   : MaterialButton

      // ── Tab 2 views ───────────────────────────────────────────────────────────
      private lateinit var tab2Content      : LinearLayout
      private lateinit var llCardChip       : LinearLayout
      private lateinit var tvCardChipLabel  : TextView
      private lateinit var btnCardChipRemove: MaterialButton
      private lateinit var llCardPickBar    : LinearLayout
      private lateinit var btnPickCardFile  : MaterialButton
      private lateinit var btnLoadSaved     : MaterialButton
      private lateinit var tvCardEmpty      : TextView
      private lateinit var llCardGroups     : LinearLayout

      // ── Tab bar ───────────────────────────────────────────────────────────────
      private lateinit var tabAccount     : TextView
      private lateinit var tabCardHistory : TextView

      private lateinit var tabBrowser: TextView

      private var activeTab = 1

      // ── Card data ─────────────────────────────────────────────────────────────
      data class CardEntry(val username: String, val carrier: String, val amount: String, val date: String, val serial: String, val code: String)
      data class CardGroup(val carrier: String, val amount: String, val cards: MutableList<CardEntry> = mutableListOf())

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_accounts)

          tab1Content       = findViewById(R.id.tab1Content)
          llAccounts        = findViewById(R.id.llAccounts)
          tvEmpty           = findViewById(R.id.tvEmpty)
          btnAdd            = findViewById(R.id.btnAdd)
          btnImport         = findViewById(R.id.btnImport)

          tab2Content       = findViewById(R.id.tab2Content)
          llCardChip        = findViewById(R.id.llCardChip)
          tvCardChipLabel   = findViewById(R.id.tvCardChipLabel)
          btnCardChipRemove = findViewById(R.id.btnCardChipRemove)
          llCardPickBar     = findViewById(R.id.llCardPickBar)
          btnPickCardFile   = findViewById(R.id.btnPickCardFile)
          btnLoadSaved      = findViewById(R.id.btnLoadSaved)
          tvCardEmpty       = findViewById(R.id.tvCardEmpty)
          llCardGroups      = findViewById(R.id.llCardGroups)

          tabAccount     = findViewById(R.id.tabAccount)
          tabCardHistory = findViewById(R.id.tabCardHistory)
          tabBrowser     = findViewById(R.id.tabBrowser)

          // Tab switching
          tabAccount.setOnClickListener     { switchTab(1) }
          tabCardHistory.setOnClickListener { switchTab(2) }
          // Tab 3: launch Rem2 Browser (independent WebView)
          tabBrowser.setOnClickListener     {
              startActivity(Intent(this, Rem2BrowserActivity::class.java))
          }

          // Tab 1 actions
          btnAdd.setOnClickListener {
              startActivityForResult(Intent(this, LoginActivity::class.java), REQUEST_ADD)
          }
          btnImport.setOnClickListener {
              startActivityForResult(Intent(this, ImportActivity::class.java), REQUEST_IMPORT)
          }

          // Tab 2 actions
          btnPickCardFile.setOnClickListener {
              val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                  addCategory(Intent.CATEGORY_OPENABLE)
                  type = "text/plain"
              }
              startActivityForResult(intent, REQUEST_CARD_FILE)
          }
          btnLoadSaved.setOnClickListener { loadTodayCardFile() }
          btnCardChipRemove.setOnClickListener { resetCardTab() }
      }

      override fun onResume() { super.onResume(); if (activeTab == 1) renderAccounts() }

      override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
          super.onActivityResult(requestCode, resultCode, data)
          when (requestCode) {
              REQUEST_ADD, REQUEST_IMPORT -> renderAccounts()
              REQUEST_CARD_FILE -> {
                  if (resultCode == Activity.RESULT_OK) {
                      val uri: Uri = data?.data ?: return
                      try {
                          val content = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                          val label   = uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
                          loadCardContent(content, label)
                      } catch (e: Exception) {
                          toast("❌ Không đọc được file: ${e.message}")
                      }
                  }
              }
          }
      }

      // ─── Tab switching ────────────────────────────────────────────────────────

      private fun switchTab(tab: Int) {
          activeTab = tab
          when (tab) {
              1 -> {
                  tab1Content.visibility = View.VISIBLE
                  tab2Content.visibility = View.GONE
                  tabAccount.setTextColor(0xFFFFFFFF.toInt())
                  tabCardHistory.setTextColor(0xFFAACCFF.toInt())
                  tabBrowser.setTextColor(0xFFAACCFF.toInt())
                  renderAccounts()
              }
              2 -> {
                  tab1Content.visibility = View.GONE
                  tab2Content.visibility = View.VISIBLE
                  tabAccount.setTextColor(0xFFAACCFF.toInt())
                  tabCardHistory.setTextColor(0xFFFFFFFF.toInt())
                  tabBrowser.setTextColor(0xFFAACCFF.toInt())
              }
          }
      }

      // ─── Tab 1: Account list ──────────────────────────────────────────────────

      private fun renderAccounts() {
          llAccounts.removeAllViews()
          val accounts = AccountManager.getAll(this)
          tvEmpty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
          accounts.forEachIndexed { idx, acc ->
              llAccounts.addView(buildAccountCard(acc, idx + 1))
          }
      }

      private fun showWithdrawDialog(acc: GoLikeAccount) {
          if (acc.password.isEmpty()) {
              MaterialAlertDialogBuilder(this)
                  .setTitle("⚠️ Thiếu mật khẩu")
                  .setMessage("Tài khoản @${acc.username} chưa có mật khẩu.\nXóa và thêm lại bằng nút \"Đăng nhập\" để rút được.")
                  .setPositiveButton("OK", null)
                  .show()
              return
          }
          var selectedCard = 0
          MaterialAlertDialogBuilder(this)
              .setTitle("Chọn loại thẻ")
              .setSingleChoiceItems(CARD_LABELS, 0) { _, which -> selectedCard = which }
              .setPositiveButton("Tiếp tục") { _, _ ->
                  val cardType = CARD_TYPES[selectedCard]
                  MaterialAlertDialogBuilder(this)
                      .setTitle("Chọn mệnh giá")
                      .setItems(AMOUNT_LABELS) { _, i ->
                          val amount = AMOUNTS[i]
                          startActivity(
                              Intent(this, WebWithdrawActivity::class.java).apply {
                                  putStringArrayListExtra(WebWithdrawActivity.EXTRA_ACCOUNT_IDS, arrayListOf(acc.id))
                                  putExtra(WebWithdrawActivity.EXTRA_CARD_TYPE, cardType.name)
                                  putExtra(WebWithdrawActivity.EXTRA_AMOUNT, amount)
                              }
                          )
                      }
                      .setNegativeButton("← Quay lại") { _, _ -> showWithdrawDialog(acc) }
                      .show()
              }
              .setNegativeButton("Hủy", null)
              .show()
      }

      private fun buildAccountCard(acc: GoLikeAccount, idx: Int): LinearLayout {
          val card = LinearLayout(this).apply {
              orientation = LinearLayout.VERTICAL
              background  = ContextCompat.getDrawable(this@AccountsActivity, R.drawable.card_bg)
              setPadding(dp(12), dp(12), dp(12), dp(12))
              layoutParams = LinearLayout.LayoutParams(
                  LinearLayout.LayoutParams.MATCH_PARENT,
                  LinearLayout.LayoutParams.WRAP_CONTENT
              ).also { it.bottomMargin = dp(10) }
          }
          val row = LinearLayout(this).apply {
              orientation = LinearLayout.HORIZONTAL
              gravity     = Gravity.CENTER_VERTICAL
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
          }
          row.addView(TextView(this).apply {
              text     = "$idx"
              textSize = 16f
              setTextColor(ContextCompat.getColor(this@AccountsActivity, R.color.text_secondary))
              layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
          })
          row.addView(TextView(this).apply {
              text     = "@${acc.username}  •  ${acc.coin}xu"
              textSize = 14f
              setTextColor(ContextCompat.getColor(this@AccountsActivity, R.color.text_primary))
              typeface = android.graphics.Typeface.DEFAULT_BOLD
              layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          })
          row.addView(MaterialButton(this).apply {
              text = "💳 Rút"; textSize = 12f
              setPadding(dp(10), dp(6), dp(10), dp(6))
              backgroundTintList = ContextCompat.getColorStateList(this@AccountsActivity, R.color.accent_green)
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(4) }
              setOnClickListener { showWithdrawDialog(acc) }
          })
          row.addView(MaterialButton(this).apply {
              text = "🗑"; textSize = 12f
              setPadding(dp(6), dp(6), dp(6), dp(6))
              backgroundTintList = ContextCompat.getColorStateList(this@AccountsActivity, R.color.accent_red)
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(4) }
              setOnClickListener {
                  MaterialAlertDialogBuilder(this@AccountsActivity)
                      .setTitle("Xóa tài khoản?")
                      .setMessage("@${acc.username}")
                      .setPositiveButton("Xóa") { _, _ -> AccountManager.remove(this@AccountsActivity, acc.id); renderAccounts() }
                      .setNegativeButton("Hủy", null).show()
              }
          })
          card.addView(row)
          card.addView(TextView(this).apply {
              text = when {
                  acc.password.isEmpty() -> "⚠️ Chưa có mật khẩu — xóa & thêm lại để rút được"
                  acc.hasMail            -> "✅ Có email Mail.tm — OTP sẽ tự đọc khi rút"
                  else                   -> "ℹ️ Không có email Mail.tm — cần nhập OTP thủ công"
              }
              textSize = 11f
              setTextColor(ContextCompat.getColor(this@AccountsActivity,
                  if (acc.password.isEmpty()) R.color.accent_red
                  else if (acc.hasMail)       R.color.accent_green
                  else                        R.color.text_muted))
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
          })
          return card
      }

      // ─── Tab 2: Card history ──────────────────────────────────────────────────

      private fun loadTodayCardFile() {
          val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
          val dir   = getExternalFilesDir(null) ?: filesDir
          val file  = java.io.File(dir, "the_cao_${today}.txt")
          if (!file.exists()) {
              toast("❌ Chưa có file the_cao_${today}.txt — hãy rút thẻ trước")
              return
          }
          val content = file.readText(Charsets.UTF_8)
          loadCardContent(content, file.name)
      }

      private fun loadCardContent(content: String, label: String) {
          val groups = parseCardGroups(content)
          llCardGroups.removeAllViews()

          if (groups.isEmpty()) {
              tvCardEmpty.visibility  = View.VISIBLE
              tvCardEmpty.text        = "Không tìm thấy thẻ hợp lệ trong file này."
              llCardChip.visibility   = View.GONE
              llCardPickBar.visibility= View.VISIBLE
              return
          }

          // Hiện chip, ẩn picker
          tvCardChipLabel.text   = "$label  •  ${groups.sumOf { it.cards.size }} thẻ"
          llCardChip.visibility  = View.VISIBLE
          llCardPickBar.visibility = View.GONE
          tvCardEmpty.visibility  = View.GONE

          groups.forEach { group -> llCardGroups.addView(buildGroupCard(group)) }
      }

      private fun resetCardTab() {
          llCardChip.visibility  = View.GONE
          llCardPickBar.visibility = View.VISIBLE
          tvCardEmpty.visibility  = View.VISIBLE
          tvCardEmpty.text        = "Chọn file the_cao_...txt để xem thẻ theo nhóm.\nNhấn \"File hôm nay\" để tải file đã rút hôm nay."
          llCardGroups.removeAllViews()
      }

      private fun parseCardGroups(text: String): List<CardGroup> {
          val map   = LinkedHashMap<String, CardGroup>()
          var curUser = ""; var curCarrier = ""; var curAmount = ""; var curDate = ""
          for (line in text.lines()) {
              val t = line.trim()
              if (t.isEmpty() || t.endsWith("--")) continue
              val hdr = t.matchesHeader()
              if (hdr != null) { curUser = hdr[0]; curCarrier = hdr[1]; curAmount = hdr[2]; curDate = hdr[3]; continue }
              val card = t.matchesCard()
              if (card != null && curUser.isNotEmpty()) {
                  val key = "${curCarrier}|${curAmount}"
                  map.getOrPut(key) { CardGroup(curCarrier, curAmount) }
                      .cards.add(CardEntry(curUser, curCarrier, curAmount, curDate, card[0], card[1]))
                  curUser = ""
              }
          }
          return map.values.toList()
      }

      private fun String.matchesHeader(): Array<String>? {
          val m = Regex("^@(\\S+)\\s*/\\s*(\\w+)\\s*/\\s*(\\d+k)\\s*[-–]\\s*(.+)$", RegexOption.IGNORE_CASE).find(this) ?: return null
          return arrayOf(m.groupValues[1], m.groupValues[2].uppercase(), m.groupValues[3], m.groupValues[4].trim())
      }

      private fun String.matchesCard(): Array<String>? {
          val m = Regex("Seri\\|Mã thẻ\\s*:\\s*\\.?([^|]+)\\|(.+)$", RegexOption.IGNORE_CASE).find(this) ?: return null
          return arrayOf(m.groupValues[1].trim(), m.groupValues[2].trim())
      }

      private fun buildGroupCard(group: CardGroup): LinearLayout {
          val emoji = mapOf("ZING" to "🎵", "VIETTEL" to "📱", "MOBIFONE" to "📱",
              "VINAPHONE" to "📱", "VIETNAMOBILE" to "📱", "GARENA" to "🎮", "VCOIN" to "💎")
          val card = LinearLayout(this).apply {
              orientation = LinearLayout.VERTICAL
              background  = ContextCompat.getDrawable(this@AccountsActivity, R.drawable.card_bg)
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(12) }
          }

          // Header row
          val header = LinearLayout(this).apply {
              orientation = LinearLayout.HORIZONTAL
              gravity     = Gravity.CENTER_VERTICAL
              setPadding(dp(12), dp(10), dp(12), dp(10))
              setBackgroundColor(ContextCompat.getColor(this@AccountsActivity, R.color.bg_surface))
          }
          header.addView(TextView(this).apply {
              text     = "${emoji[group.carrier] ?: "💳"} ${group.carrier}  ${group.amount}"
              textSize = 14f
              textStyle(android.graphics.Typeface.BOLD)
              setTextColor(ContextCompat.getColor(this@AccountsActivity, R.color.text_primary))
              layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          })
          header.addView(TextView(this).apply {
              text     = "${group.cards.size} thẻ"
              textSize = 12f
              setTextColor(ContextCompat.getColor(this@AccountsActivity, R.color.text_muted))
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(8) }
          })
          // Copy all button
          header.addView(MaterialButton(this).apply {
              text = "📋 Copy hết"; textSize = 11f
              setPadding(dp(8), dp(4), dp(8), dp(4))
              backgroundTintList = ContextCompat.getColorStateList(this@AccountsActivity, R.color.accent_blue)
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
              setOnClickListener {
                  val txt = group.cards.joinToString("\n") { "${it.serial}|${it.code}" }
                  copyText(txt)
                  text = "✅ Đã copy!"; postDelayed({ text = "📋 Copy hết" }, 1500)
              }
          })
          card.addView(header)

          // Card rows
          group.cards.forEach { entry ->
              val row = LinearLayout(this).apply {
                  orientation = LinearLayout.HORIZONTAL
                  gravity     = Gravity.CENTER_VERTICAL
                  setPadding(dp(12), dp(8), dp(8), dp(8))
              }
              row.addView(LinearLayout(this).apply {
                  orientation = LinearLayout.VERTICAL
                  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                  addView(TextView(this@AccountsActivity).apply {
                      text     = "@${entry.username}"
                      textSize = 10f
                      setTextColor(ContextCompat.getColor(this@AccountsActivity, R.color.text_muted))
                  })
                  addView(TextView(this@AccountsActivity).apply {
                      text     = "${entry.serial}|${entry.code}"
                      textSize = 12f
                      setTextColor(ContextCompat.getColor(this@AccountsActivity, R.color.text_primary))
                      typeface = android.graphics.Typeface.MONOSPACE
                      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) }
                  })
              })
              row.addView(MaterialButton(this).apply {
                  text = "📋"; textSize = 14f
                  setPadding(dp(6), dp(4), dp(6), dp(4))
                  backgroundTintList = ContextCompat.getColorStateList(this@AccountsActivity, R.color.btn_secondary)
                  layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(4) }
                  setOnClickListener {
                      copyText("${entry.serial}|${entry.code}")
                      text = "✅"; postDelayed({ text = "📋" }, 1200)
                  }
              })
              card.addView(row)
              // Divider
              card.addView(View(this).apply {
                  setBackgroundColor(ContextCompat.getColor(this@AccountsActivity, R.color.bg_input))
                  layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
              })
          }
          return card
      }

      private fun TextView.textStyle(style: Int) { typeface = android.graphics.Typeface.defaultFromStyle(style) }

      private fun copyText(text: String) {
          val clip = ClipData.newPlainText("card", text)
          (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
          toast("✅ Đã copy!")
      }

      private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
      private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
  }
