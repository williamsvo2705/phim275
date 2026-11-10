// Phim275 TV Navigation — bắt playlist HLS mà trang tự giải mã, để trình phát mượt của app phát được.
// Vd. Cobephim (trình phát streamc): playlist tải về bị mã hóa AES-GCM (#ENC-AESGCM), JS của trang giải bằng
// crypto.subtle.decrypt rồi mới đưa cho hls.js. Chỉ đọc kết quả bắt đầu bằng "#EXTM3U" (playlist video);
// mọi dữ liệu giải mã khác bỏ qua ngay, không đọc, không lưu.
// Chạy sớm (document_start) ở mọi frame để kịp bọc hàm trước khi trình phát của trang chạy.
// GPLv3 — https://github.com/williamsvo2705/phim275
'use strict';

(function() {
    const w = window.wrappedJSObject;
    if ( !w || !w.crypto || !w.crypto.subtle || typeof exportFunction !== 'function' ) { return; }
    const MAGIC = [ 0x23, 0x45, 0x58, 0x54, 0x4d, 0x33, 0x55 ]; // "#EXTM3U"
    const MAX_BYTES = 2000000;

    function inspect(buf) {
        try {
            if ( !buf || buf.byteLength < 16 || buf.byteLength > MAX_BYTES ) { return; }
            const head = new w.Uint8Array(buf, 0, MAGIC.length);
            for ( let i = 0; i < MAGIC.length; i++ ) {
                if ( head[i] !== MAGIC[i] ) { return; }
            }
            // Giải mã chữ bằng TextDecoder của trang: chuỗi đi qua ranh giới extension ↔ trang nhanh, mảng byte thì chậm.
            const text = String(new w.TextDecoder('utf-8').decode(buf));
            if ( !/#EXTINF|#EXT-X-STREAM-INF/.test(text) ) { return; }
            browser.runtime.sendMessage({ type: 'playlistText', text, frame: location.href.split('#')[0] }).catch(( ) => {});
        } catch { }
    }

    try {
        const proto = Object.getPrototypeOf(w.crypto.subtle);
        const original = proto.decrypt;
        if ( typeof original !== 'function' ) { return; }
        const ignore = exportFunction(( ) => {}, w);
        const onResult = exportFunction(inspect, w);
        exportFunction(function decrypt(algorithm, key, data) {
            const result = original.call(this, algorithm, key, data);
            try { result.then(onResult, ignore); } catch { }
            return result;
        }, proto, { defineAs: 'decrypt' });
    } catch { }
})();
