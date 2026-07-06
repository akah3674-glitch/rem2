package com.golike.ops


import com.rem2.browser.R
  import android.app.Activity
  import android.content.Intent
  import android.net.Uri
  import android.os.Bundle
  import android.view.View
  import android.widget.*
  import androidx.appcompat.app.AppCompatActivity
  import androidx.core.content.ContextCompat
  import com.google.android.material.button.MaterialButton
  import com.google.android.material.textfield.TextInputEditText

  class ImportActivity : AppCompatActivity() {

      companion object {
          const val REQUEST_OPEN_FILE = 2001
      }

      private lateinit var etContent      : TextInputEditText
      private lateinit var btnPickFile    : MaterialButton
      private lateinit var btnParse       : MaterialButton
      private lateinit var btnImportAll   : MaterialButton
      private lateinit var tvResult       : TextView
      private lateinit var llPreview      : LinearLayout
      private lateinit var tvCount        : TextView

      // Chip views
      private lateinit var llFileChip     : LinearLayout
      private lateinit var tvChipLabel    : TextView
      private lateinit var tvChipCount    : TextView
      private lateinit var btnChipRemove  : MaterialButton
      private lateinit var llInputSections: LinearLayout

      private var parsedAccounts: List<GoLikeAccount> = emptyList()
      private var sourceLabel   : String = ""

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_import)

          etContent       = findViewById(R.id.etImportContent)
          btnPickFile     = findViewById(R.id.btnPickFile)
          btnParse        = findViewById(R.id.btnParse)
          btnImportAll    = findViewById(R.id.btnImportAll)
          tvResult        = findViewById(R.id.tvImportResult)
          llPreview       = findViewById(R.id.llImportPreview)
          tvCount         = findViewById(R.id.tvImportCount)
          llFileChip      = findViewById(R.id.llFileChip)
          tvChipLabel     = findViewById(R.id.tvChipLabel)
          tvChipCount     = findViewById(R.id.tvChipCount)
          btnChipRemove   = findViewById(R.id.btnChipRemove)
          llInputSections = findViewById(R.id.llInputSections)

          btnPickFile.setOnClickListener {
              val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                  addCategory(Intent.CATEGORY_OPENABLE)
                  type = "text/plain"
              }
              startActivityForResult(intent, REQUEST_OPEN_FILE)
          }

          btnParse.setOnClickListener {
              val content = etContent.text.toString().trim()
              if (content.isEmpty()) {
                  showResult("⚠️ Chưa có nội dung để phân tích", false)
                  return@setOnClickListener
              }
              sourceLabel = "Nội dung đã dán"
              parseContent(content)
          }

          btnImportAll.setOnClickListener {
              if (parsedAccounts.isEmpty()) return@setOnClickListener
              AccountManager.addAll(this, parsedAccounts)
              showResult("✅ Đã nhập ${parsedAccounts.size} tài khoản thành công!", true)
              btnImportAll.isEnabled = false
              llPreview.postDelayed({
                  setResult(Activity.RESULT_OK)
                  finish()
              }, 1200)
          }

          btnChipRemove.setOnClickListener { resetToInput() }

          findViewById<MaterialButton>(R.id.btnImportBack).setOnClickListener { finish() }
      }

      override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
          super.onActivityResult(requestCode, resultCode, data)
          if (requestCode == REQUEST_OPEN_FILE && resultCode == Activity.RESULT_OK) {
              val uri: Uri = data?.data ?: return
              try {
                  val content = contentResolver.openInputStream(uri)
                      ?.bufferedReader()?.readText() ?: ""
                  // Lấy tên file từ URI
                  sourceLabel = uri.lastPathSegment?.substringAfterLast('/')
                      ?: uri.lastPathSegment
                      ?: "file.txt"
                  etContent.setText(content)
                  parseContent(content)
              } catch (e: Exception) {
                  showResult("❌ Không đọc được file: ${e.message}", false)
              }
          }
      }

      private fun parseContent(content: String) {
          parsedAccounts = AccountManager.parseRemFile(content)
          llPreview.removeAllViews()

          if (parsedAccounts.isEmpty()) {
              showResult("⚠️ Không tìm thấy tài khoản nào trong nội dung này", false)
              btnImportAll.isEnabled = false
              return
          }

          // Ẩn textarea section, hiện chip gọn
          showFileChip(sourceLabel, parsedAccounts.size)

          tvCount.text = "Tìm thấy ${parsedAccounts.size} tài khoản:"
          tvCount.visibility = View.VISIBLE

          parsedAccounts.forEach { acc ->
              val row = LinearLayout(this).apply {
                  orientation = LinearLayout.VERTICAL
                  background  = ContextCompat.getDrawable(this@ImportActivity, R.drawable.card_bg)
                  setPadding(dp(12), dp(10), dp(12), dp(10))
                  layoutParams = LinearLayout.LayoutParams(
                      LinearLayout.LayoutParams.MATCH_PARENT,
                      LinearLayout.LayoutParams.WRAP_CONTENT
                  ).also { it.bottomMargin = dp(8) }
              }
              val tvUser = TextView(this).apply {
                  text     = "@${acc.username}  —  ${acc.coin}xu"
                  textSize = 14f
                  setTextColor(ContextCompat.getColor(this@ImportActivity, R.color.text_primary))
                  typeface = android.graphics.Typeface.DEFAULT_BOLD
              }
              val tvMail = TextView(this).apply {
                  text = if (acc.isMailTm) "📬 ${acc.email}  (Mail.tm — tự đọc OTP)"
                         else "✉️ ${acc.email}  (nhập OTP thủ công)"
                  textSize = 12f
                  setTextColor(
                      if (acc.isMailTm) ContextCompat.getColor(this@ImportActivity, R.color.accent_green)
                      else              ContextCompat.getColor(this@ImportActivity, R.color.text_secondary)
                  )
              }
              row.addView(tvUser)
              row.addView(tvMail)
              llPreview.addView(row)
          }

          btnImportAll.isEnabled = true
          btnImportAll.text = "✅ Nhập tất cả (${parsedAccounts.size})"
          showResult("", true)
      }

      /** Ẩn các ô nhập, hiện chip gọn */
      private fun showFileChip(label: String, count: Int) {
          llInputSections.visibility = View.GONE
          llFileChip.visibility      = View.VISIBLE
          tvChipLabel.text           = label
          tvChipCount.text           = "$count tài khoản tìm thấy ✓"
      }

      /** Reset về trạng thái ban đầu */
      private fun resetToInput() {
          llFileChip.visibility      = View.GONE
          llInputSections.visibility = View.VISIBLE
          etContent.setText("")
          tvCount.visibility = View.GONE
          tvResult.text      = ""
          llPreview.removeAllViews()
          parsedAccounts     = emptyList()
          sourceLabel        = ""
          btnImportAll.isEnabled = false
          btnImportAll.text  = "✅ Nhập tất cả"
      }

      private fun showResult(msg: String, ok: Boolean) {
          tvResult.text = msg
          tvResult.setTextColor(
              if (ok) ContextCompat.getColor(this, R.color.accent_green)
              else    ContextCompat.getColor(this, R.color.accent_red)
          )
      }

      private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
  }
  