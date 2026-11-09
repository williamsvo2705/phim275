// Phim275 TV Navigation — chuyển yêu cầu từ content script sang app (native messaging):
//   tap              : chạm thật vào giữa khối đang chọn
//   pseudoFullscreen : trang đang/thôi phóng to video bằng CSS (để app xử lý Back, tua…)
//   context          : giữ OK — liên kết của khối đang chọn để app mở menu
//   mediaPos         : vị trí video chính đang xem (app nhớ để xem tiếp đúng chỗ)
//   mediaResume      : video vừa phát → hỏi app vị trí cần tua tới (trả về giây, 0 = từ đầu)
//   nextEp           : trang xem phim có tập kế tiếp (tên tập, null = không có) → app hiện thẻ "Tập tiếp theo"
// Chiều ngược lại (app → trang) qua port native: { id, type: 'video', cmd: 'info' | 'rate' | 'level', value }
//   → chuyển tới mọi frame của tab đang xem; frame có video chính trả lời trạng thái trình phát.
// GPLv3 — https://github.com/williamsvo2705/phim275
'use strict';

const FORWARDED = new Set([ 'tap', 'pseudoFullscreen', 'context', 'mediaPos', 'mediaResume', 'nextEp' ]);

browser.runtime.onMessage.addListener((msg, sender) => {
    if ( msg instanceof Object === false || !FORWARDED.has(msg.type) ) { return; }
    // URL trang chính của tab (kể cả khi tin nhắn đến từ iframe trình phát khác tên miền).
    const tabUrl = sender && sender.tab && typeof sender.tab.url === 'string' ? sender.tab.url : null;
    const page = tabUrl || (typeof msg.top === 'string' ? msg.top : null);
    return browser.runtime.sendNativeMessage('phim275tv', Object.assign({}, msg, { page })).catch(( ) => false);
});

let port = null;

async function handlePortMessage(msg) {
    if ( msg.type !== 'video' ) { return { error: 'unknown' }; }
    const tabs = await browser.tabs.query({ active: true });
    if ( tabs.length === 0 ) { return { ok: false }; }
    const info = await browser.tabs.sendMessage(tabs[0].id, { phim275Video: true, cmd: msg.cmd, value: msg.value })
        .catch(( ) => null);
    return info instanceof Object ? { ok: true, info } : { ok: false };
}

function connect() {
    try {
        port = browser.runtime.connectNative('phim275tv');
    } catch {
        port = null;
        return;
    }
    port.onMessage.addListener(msg => {
        if ( msg instanceof Object === false || typeof msg.id !== 'number' ) { return; }
        handlePortMessage(msg).then(
            data => { if ( port !== null ) { port.postMessage(Object.assign({ id: msg.id }, data)); } },
            ex => { if ( port !== null ) { port.postMessage({ id: msg.id, error: String(ex) }); } },
        );
    });
    port.onDisconnect.addListener(( ) => {
        port = null;
        setTimeout(connect, 2000);
    });
}

connect();
