# Cam Monitor

App Android biến điện thoại thành monitor rời cho máy ảnh, nhận hình qua USB (UVC).
Viết cho Samsung M12 + Sony A7C, nhưng chạy được với mọi máy ảnh/capture card chuẩn UVC.

## Tính năng

- Chế độ hiển thị: Thường / False color / Đen trắng
- Focus peaking: đỏ, xanh, vàng; 3 mức độ nhạy
- Zebra: 70% (da), 95%, 100% (cháy sáng)
- Histogram độ sáng
- Khung tỉ lệ: 9:16, 4:5, 1:1, 2.35:1 (làm tối phần ngoài khung)
- Lưới 1/3, tâm, vùng an toàn 90%
- Zoom 2×/4× để soi nét (kéo ngón tay để dời vùng xem)
- Lật ngang/dọc, anamorphic de-squeeze 1.33×/1.5×/2×
- Chạm vào hình để ẩn/hiện thanh công cụ; cài đặt được lưu lại

## Cách lấy file APK

### Cách A: Android Studio (trên máy tính)

1. Cài Android Studio, chọn File > Open, mở thư mục này.
2. Chờ Gradle tải thư viện xong (lần đầu mất vài phút).
3. Bật "Gỡ lỗi USB" trên điện thoại, cắm vào máy tính, bấm Run.
   Hoặc Build > Build APK(s) rồi chép file `app/build/outputs/apk/debug/app-debug.apk` sang điện thoại.

### Cách B: GitHub Actions (không cần cài gì)

1. Tạo một repository trên GitHub, tải toàn bộ thư mục này lên.
2. Vào tab Actions, chờ job "Build APK" chạy xong.
3. Tải file trong mục Artifacts, giải nén ra `app-debug.apk`, cài lên điện thoại.

## Cách dùng với Sony A7C

- Nối thẳng USB-C: trên máy ảnh chọn "Live Stream (USB Streaming)" khi cắm dây.
- Hoặc qua HDMI: micro HDMI > capture card UVC > USB-C điện thoại.
  Trên máy ảnh tắt "HDMI Info. Display" và đặt HDMI 1080p.
- Lần đầu app sẽ hỏi quyền Camera và quyền truy cập thiết bị USB, chọn Cho phép.

## Cấu trúc mã nguồn

- `MainActivity.kt`: giao diện, kết nối camera UVC, chọn độ phân giải
- `MonitorRenderer.kt`: OpenGL ES + shader (peaking, zebra, false color, histogram)
- `OverlayView.kt`: khung, lưới, biểu đồ histogram
- `MonitorState.kt`: trạng thái các công cụ

Thư viện UVC: [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) (Apache 2.0).
