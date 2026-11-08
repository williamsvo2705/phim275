// Phim275 TV Navigation — chuyển yêu cầu từ content script sang app (native messaging):
//   tap              : chạm thật vào giữa khối đang chọn
//   pseudoFullscreen : trang đang/thôi phóng to video bằng CSS (để app xử lý Back, tua…)
//   context          : giữ OK — liên kết của khối đang chọn để app mở menu
//   mediaPos         : vị trí video chính đang xem (app nhớ để xem tiếp đúng chỗ)
//   mediaResume      : video vừa phát → hỏi app vị trí cần tua tới (trả về giây, 0 = từ đầu)
// GPLv3 — https://github.com/williamsvo2705/phim275
'use strict';

const FORWARDED = new Set([ 'tap', 'pseudoFullscreen', 'context', 'mediaPos', 'mediaResume' ]);

browser.runtime.onMessage.addListener((msg, sender) => {
    if ( msg instanceof Object === false || !FORWARDED.has(msg.type) ) { return; }
    // URL trang chính của tab (kể cả khi tin nhắn đến từ iframe trình phát khác tên miền).
    const tabUrl = sender && sender.tab && typeof sender.tab.url === 'string' ? sender.tab.url : null;
    const page = tabUrl || (typeof msg.top === 'string' ? msg.top : null);
    return browser.runtime.sendNativeMessage('phim275tv', Object.assign({}, msg, { page })).catch(( ) => false);
});
