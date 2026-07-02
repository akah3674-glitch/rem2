# Rem2 Browser

  Android app với WebView chính mở **Replit.com** + cửa sổ nổi **Mail.tm** tự động xác thực email.

  ## Tính năng

  - 🌐 **Màn hình chính**: WebView mở Replit.com (có thể dùng như browser thật)
  - 📧 **Cửa sổ mail.tm nổi**: Kéo được, tắt/mở bằng nút — tự poll inbox mỗi 3 giây
  - 🔗 **Tự động verify**: Phát hiện email xác thực Replit → tự click link → xác thực xong
  - ➕ **Đăng ký tự động**: Tạo email mail.tm → mở form Replit → điền tự động → chờ verify

  ## Tải APK

  Vào **Actions** tab → chọn build mới nhất → download **rem2-debug-apk**

  ## Build thủ công

  ```bash
  npm install
  npx expo prebuild --platform android
  cd android && ./gradlew assembleDebug
  ```

  APK output: `android/app/build/outputs/apk/debug/app-debug.apk`