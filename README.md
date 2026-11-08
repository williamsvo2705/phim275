# Phim275

Trình duyệt cho **Android TV / máy chiếu / TV box**: chặn quảng cáo và popup, vượt các trang bắt tắt adblock,
điều khiển hoàn toàn bằng remote.

**Tải về:** https://phim275.vercel.app (trên TV: app *Downloader* → gõ `phim275.vercel.app/tv`)

> Trạng thái: **Phase 0 — bản thử nghiệm (PoC)**. Mục tiêu: đo hiệu năng thật trên máy 2GB RAM.

## Tính năng hiện có

| Nhóm | Chi tiết |
|---|---|
| Nhân trình duyệt | [GeckoView](https://mozilla.github.io/geckoview/) 159 (Firefox) |
| Chặn quảng cáo | [uBlock Origin](https://github.com/gorhill/uBlock) 1.75.4 tích hợp sẵn · EasyList, EasyPrivacy, uBO filters, AdGuard Mobile, **ABPVN**, Popup Overlays, Annoyances, [bộ lọc riêng Phim275](filters/phim275.txt) |
| Chặn popup (tầng app) | Chặn tab mới khác tên miền, chặn `intent://` / `market://` mở app khác, tắt `alert()`, từ chối thông báo đẩy |
| DNS | DNS-over-HTTPS (AdGuard DNS): vượt chặn DNS nhà mạng + chặn thêm tên miền quảng cáo |
| Remote | Con trỏ ảo tăng tốc, chạm mép để cuộn (cuộn đúng khung dưới con trỏ), Back/Giữ Back/Menu, CH+/CH− |
| RAM thấp | Tắt Fission & process dự phòng, uBO chạy process riêng, tự khôi phục khi tab bị hệ thống giết |
| Chẩn đoán | Bảng thông số thiết bị, RAM, số popup đã chặn (nút **Chẩn đoán**) |

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
