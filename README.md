# Phim275

Trình duyệt cho **Android TV / máy chiếu / TV box**: chặn quảng cáo và popup, vượt các trang bắt tắt adblock,
điều khiển hoàn toàn bằng remote.

**Tải về:** https://phim275.vercel.app (trên TV: app *Downloader* → gõ `phim275.vercel.app/tv`)

> Trạng thái: **0.2.0** — đủ tính năng chính, đang thử nghiệm trên máy thật (máy chiếu 2GB RAM).

## Tính năng

| Nhóm | Chi tiết |
|---|---|
| Nhân trình duyệt | [GeckoView](https://mozilla.github.io/geckoview/) 159 (Firefox) · nhiều tab (tối đa 6, 2 tab trong RAM, tab nền tự giải phóng) · trang lỗi tiếng Việt · tải file vào thư mục Download |
| Chặn quảng cáo | [uBlock Origin](https://github.com/gorhill/uBlock) 1.75.4 tích hợp sẵn · EasyList, EasyPrivacy, uBO filters, AdGuard Mobile, **ABPVN**, Popup Overlays, Annoyances, [bộ lọc riêng Phim275](filters/phim275.txt) |
| Điều khiển uBO từ app | Cầu nối native messaging ([phim275-bridge.js](tools/ublock-bridge/phim275-bridge.js)): bật/tắt chặn theo trang, chọn phần tử để ẩn, xóa tạm phần tử, cập nhật bộ lọc, đếm yêu cầu đã chặn |
| Chặn popup (tầng app) | Chặn tab mới khác tên miền, chặn `intent://` / `market://`, tắt `alert()`, từ chối thông báo đẩy · mở lại popup vừa chặn từ Menu |
| DNS | DNS-over-HTTPS (AdGuard DNS), bật/tắt trong Cài đặt |
| Remote | Con trỏ ảo tăng tốc · giữ OK = menu chuột phải (mở link tab mới, bookmark link) · chế độ phím Tab · cuộn đúng khung dưới con trỏ · Back/Giữ Back/Menu · CH+/CH− · tìm kiếm giọng nói |
| Nhập chữ | Ép hiện bàn phím TV (LeanKey, Gboard…) · ô nhập dự phòng tự bật khi bàn phím không hiện |
| Tiện ích | Bookmark (hiện trên trang chủ), lịch sử, chọn công cụ tìm kiếm (Google, Cốc Cốc, Bing, DuckDuckGo, YouTube), Desktop/Mobile, xóa dữ liệu |
| Báo lỗi | Menu → Báo trang lỗi → mã QR mở GitHub Issue điền sẵn (quét bằng điện thoại) · ghi log crash |
| Tự cập nhật | Kiểm tra `latest.json` mỗi ngày → tải, kiểm SHA-256, cài bằng PackageInstaller |
| RAM thấp | Tắt Fission & process dự phòng, uBO chạy process riêng, giải phóng tab nền khi thiếu RAM, tự khôi phục tab bị giết |

Đo trên emulator Android TV 2GB RAM, bản release, trang 24h.com.vn: **~630MB** tổng RAM của app ·
adblock-tester.com: **100/100**.

## Cấu trúc

```
app/src/main/java/phim275/tv/com/
  MainActivity.kt          giao diện, phím remote, delegate GeckoView (popup, quyền, hộp thoại)
  browser/BrowserRuntime   GeckoRuntime + uBO tích hợp + tinh chỉnh RAM
  browser/SitePolicy       luật chặn popup / chuyển app (thuần Kotlin, có unit test)
  browser/UrlInput         thanh địa chỉ → URL hoặc tìm kiếm
  cursor/                  con trỏ ảo (vẽ, điều khiển, vật lý tăng tốc)
  diag/Diagnostics         bảng chẩn đoán
app/src/main/assets/
  extensions/ublock/       uBlock Origin (đã vá cấu hình — sinh bởi tools/update_ublock.py)
  home/index.html          trang chủ
filters/phim275.txt        bộ lọc riêng (uBO tự cập nhật từ repo này mỗi ngày)
tools/update_ublock.py     tải + vá uBlock Origin
web/                       trang tải trên Vercel
.github/workflows/         phát hành APK khi đẩy tag v*
```

## Build

Yêu cầu: JDK 17, Android SDK (platform `android-37.2`).

```bash
./gradlew :app:testDebugUnitTest      # unit test
./gradlew :app:assembleDebug          # APK debug (mỗi ABI một file)
./gradlew :app:assembleRelease        # APK release (cần khóa ký, xem app/build.gradle.kts)
python tools/update_ublock.py 1.76.0  # nâng uBlock Origin
```

> **Windows, thư mục có dấu tiếng Việt:** `gradlew` không chạy được trên đường dẫn không phải ASCII.
> Tạo ổ ảo rồi build từ đó: `subst P: "D:\đường\dẫn\dự án"` → `cd /d P:\` → `gradlew.bat assembleDebug`.

## Phát hành

1. Tăng `versionName` / `baseVersionCode` trong `app/build.gradle.kts`.
2. `git tag v0.2.0 && git push origin v0.2.0` → GitHub Actions build, ký, tạo Release
   (`phim275-armeabi-v7a.apk`, `phim275-arm64-v8a.apk`, `SHA256SUMS.txt`, `latest.json`).
3. Trang `phim275.vercel.app` luôn trỏ tới Release mới nhất — không cần sửa.

## Giấy phép

Phim275 phát hành theo **GPLv3** (xem [LICENSE](LICENSE)).

- uBlock Origin © Raymond Hill và cộng sự — GPLv3 — https://github.com/gorhill/uBlock
- GeckoView © Mozilla — MPL-2.0 — https://hg.mozilla.org/mozilla-central
- Các danh sách lọc thuộc về tác giả tương ứng (EasyList, AdGuard, ABPVN, …).
