// Phim275 TV Navigation — chuyển yêu cầu từ content script sang app (native messaging):
//   tap              : chạm thật vào giữa khối đang chọn
//   pseudoFullscreen : trang đang/thôi phóng to video bằng CSS (để app xử lý Back, tua…)
// GPLv3 — https://github.com/williamsvo2705/phim275
'use strict';

const FORWARDED = new Set([ 'tap', 'pseudoFullscreen' ]);

browser.runtime.onMessage.addListener(msg => {
    if ( msg instanceof Object === false || !FORWARDED.has(msg.type) ) { return; }
    return browser.runtime.sendNativeMessage('phim275tv', msg).catch(( ) => false);
});
