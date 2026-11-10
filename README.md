# Phim275

Trình duyệt cho **Android TV / máy chiếu / TV box**: chặn quảng cáo và popup, vượt các trang bắt tắt adblock,
điều khiển hoàn toàn bằng remote.

**Tải về:** https://phim275.vercel.app (trên TV: app *Downloader* → gõ `phim275.vercel.app/tv`)

> Trạng thái: **0.17.1** — đủ tính năng chính, đã chạy trên máy thật (máy chiếu OnePlus H93A, Android 14, 1,6GB RAM).

## Tính năng

| Nhóm | Chi tiết |
|---|---|
| Nhân trình duyệt | [GeckoView](https://mozilla.github.io/geckoview/) 159 (Firefox) · nhiều tab (tối đa 6, 2 tab trong RAM, tab nền tự giải phóng) · trang lỗi tiếng Việt · tải file vào thư mục Download |
| Chặn quảng cáo | [uBlock Origin](https://github.com/gorhill/uBlock) 1.75.4 tích hợp sẵn · EasyList, EasyPrivacy, uBO filters, AdGuard Mobile, **ABPVN**, Popup Overlays, Annoyances, [bộ lọc riêng Phim275](filters/phim275.txt) |
| Điều khiển uBO từ app | Cầu nối native messaging ([phim275-bridge.js](tools/ublock-bridge/phim275-bridge.js)): bật/tắt chặn theo trang, chọn phần tử để ẩn, xóa tạm phần tử, cập nhật bộ lọc, đếm yêu cầu đã chặn |
| Chặn popup (tầng app) | Chặn tab mới khác tên miền, chặn `intent://` / `market://`, tắt `alert()`, từ chối thông báo đẩy · mở lại popup vừa chặn từ Menu |
| DNS | DNS-over-HTTPS (AdGuard DNS), bật/tắt trong Cài đặt |
| Chuyển khối (mặc định) | Mọi trang web điều khiển kiểu Android TV: ◀▲▶▼ nhảy giữa các khối bấm được (liên kết, nút, ô phim) có viền đỏ, OK = chạm thật vào giữa khối (bấm được cả iframe trình phát) · trang xem phim: tự chọn sẵn trình phát · hộp thoại trong trình phát ("Tiếp tục xem / Xem lại từ đầu") chọn bằng ◀▶ + OK · trang SPA (Next.js/React) đổi nội dung vẫn chọn đúng khối · extension [tvnav](app/src/main/assets/extensions/tvnav/content.js) |
| Remote | Con trỏ ảo tăng tốc (đổi nhanh ở đầu ☰ Menu) · giữ OK = menu chuột phải (mở link tab mới, bookmark link) · chế độ phím Tab · cuộn đúng khung dưới con trỏ · Back/Giữ Back/Menu · CH+/CH− · tìm kiếm giọng nói |
| Xem tiếp đúng chỗ | Nhớ vị trí video đang xem (cả trong iframe trình phát) theo từng trang → mở lại tự tua tới chỗ cũ, báo "Xem tiếp từ 12:34" · trang chủ "Xem tiếp" có thanh tiến độ + thời gian còn lại · bỏ qua quảng cáo chèn đầu (< 2 phút), xem gần hết thì phát từ đầu · trang có sẵn tính năng xem tiếp riêng thì app tự nhường |
| Tập tiếp theo | Trang xem phim có danh sách tập (hoặc nút "Tập tiếp") → cuối phim hiện thẻ "Tập tiếp theo" (cả khi đang toàn màn hình): OK = xem ngay, Back = ẩn · hết phim tự chuyển sau 10 giây (tắt được trong Cài đặt → Xem phim) · chạy với cả trang SPA (Cobephim/RoPhim) · tập mới tự phát, đang toàn màn hình thì tập mới cũng toàn màn hình |
| Tùy chọn video | Đang xem toàn màn hình bấm ▼ (hoặc Menu), giữ OK trên khối trình phát, hoặc menu chính → "Tùy chọn video": tốc độ 0,5×–2× (mọi trang) · chất lượng Tự động/1080p/720p… khi trình phát có nhiều mức (JW Player, hls.js) · bảng hiện độ phân giải đang phát · **nguồn phát / bản dịch**: các nút server, Vietsub/Thuyết minh, 1080/4K của trang — đổi xong tự phát lại đúng chỗ đang xem |
| Xem video | Khối video: OK = phát/dừng, **OK × 2 = toàn màn hình** (viền đỏ tự ẩn), Back / OK × 2 để thoát · giữ màn hình sáng khi đang phát · Phím media của remote (Play/Pause, tua ±10s) điều khiển video kể cả trong iframe · toàn màn hình: ◀ ▶ tua, OK dừng/phát, ▲▼ hiện con trỏ · cỡ chữ 100–175% · tua hiện vị trí thật "⏩ 12:40 / 45:12" · giữ ◀ ▶ tua nhanh dần (cộng dồn, tua một lần khi buông) · phím số 0–9 nhảy tới 0%–90% |
| Nhập chữ | Ép hiện bàn phím TV (LeanKey, Gboard…) · ô nhập dự phòng tự bật khi bàn phím không hiện |
| Tiện ích | Bookmark (hiện trên trang chủ), lịch sử, chọn công cụ tìm kiếm (Google, Cốc Cốc, Bing, DuckDuckGo, YouTube), Desktop/Mobile, xóa dữ liệu |
| Báo lỗi | Menu → Báo trang lỗi → mã QR mở GitHub Issue điền sẵn (quét bằng điện thoại) · ghi log crash |
| Tự cập nhật | Kiểm tra `latest.json` mỗi ngày → tải, kiểm SHA-256, cài bằng PackageInstaller |
| Khôi phục | Mở lại các tab lần trước khi khởi động (sau cập nhật / khi Android đóng app vì thiếu RAM) |
| Box 1GB (chế độ RAM thấp) | Tự bật khi máy < 1,5GB RAM (hoặc bật tay): bộ lọc gọn 9 danh sách, bỏ lọc ẩn phần tử chung, uBO chung process chính, cache ảnh 48MB, tối đa 3 tab · đo trên emulator 1GB: 505MB → 326MB · xem phim lâu: giới hạn bộ đệm video MSE (40MB, máy thường 80MB) nên RAM không tăng dần — process trang sau 5 phút 281MB → 209MB |
| Xem mượt | Gộp bộ vẽ (process GPU) vào process chính: bớt ~200MB RAM · luồng vẽ của Gecko được ưu tiên ngang luồng giao diện (nice −10) khi phát video / vào toàn màn hình · đo trên máy chiếu H93A, Cobephim toàn màn hình: khung trễ 60–100ms giảm từ 3–11 xuống 0–3 mỗi ~5 giây, 0 khung bị bỏ · nhật ký số khung hiện/bỏ mỗi 30 giây (`adb logcat -s Phim275TvNav`) |
| Trình phát mượt | Xem toàn màn hình: nếu trang dùng luồng HLS / MP4 chuẩn, Phim275 lấy luồng của trang (kèm Referer, bản chép playlist có token dùng một lần) và phát bằng ExoPlayer qua lớp video phần cứng của TV — không qua GPU của trình duyệt nên không lệch nhịp 24 hình/giây trên màn 60Hz · OK: phát/dừng · ◀▶ tua · 0–9 nhảy % · ▼ tốc độ, chất lượng, "Dùng trình phát của trang" · Back: về trang đúng chỗ · hết phim → thẻ Tập tiếp theo · playlist trang tự mã hóa rồi giải bằng WebCrypto (vd. Cobephim, #ENC-AESGCM) → lấy bản trang vừa giải mã (chỉ đọc kết quả bắt đầu bằng #EXTM3U) · mã hóa kiểu khác hoặc có phụ đề rời → giữ trình phát của trang · bật/tắt trong Cài đặt → Xem phim |
| RAM thấp | Tắt Fission & process dự phòng, uBO chạy process riêng, giải phóng tab nền khi thiếu RAM, tự khôi phục tab bị giết · đang xem phim mà trang bị Android đóng vì thiếu RAM → tự mở lại, tua về chỗ cũ, phát tiếp (giữ toàn màn hình) |

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
