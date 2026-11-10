// Phim275 TV Navigation — chuyển yêu cầu từ content script sang app (native messaging):
//   tap              : chạm thật vào giữa khối đang chọn
//   pseudoFullscreen : trang đang/thôi phóng to video bằng CSS (để app xử lý Back, tua…)
//   context          : giữ OK — liên kết của khối đang chọn để app mở menu
//   mediaPos         : vị trí video chính đang xem (app nhớ để xem tiếp đúng chỗ)
//   mediaResume      : video vừa phát → hỏi app vị trí cần tua tới (trả về giây, 0 = từ đầu)
//   nextEp           : trang xem phim có tập kế tiếp (tên tập, null = không có) → app hiện thẻ "Tập tiếp theo"
// Chiều ngược lại (app → trang) qua port native: { id, type: 'video', cmd: 'info' | 'rate' | 'level', value }
//   → chuyển tới mọi frame của tab đang xem; frame có video chính trả lời trạng thái trình phát.
//   { id, type: 'stream' } → luồng (playlist HLS / file video) của video chính để app phát bằng trình phát mượt.
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

// ── Luồng video cho trình phát mượt của app ─────────────────────────────────
// Ghi lại playlist HLS / file video mà trang tải, kèm Referer / Origin / User-Agent trang đã gửi, theo tab và frame.
const MAX_STREAMS = 8;
const streams = new Map();     // tabId → [{ url, kind, frame, referer, origin, ua, at }]
const sentHeaders = new Map(); // requestId → { referer, origin, ua }
// Cookie trang trình phát gửi cho từng máy chủ (vd. cf_clearance của Cloudflare — thiếu là 403). Trình phát mượt
// chỉ gửi lại cookie cho đúng máy chủ đã nhận nó.
const frameCookies = new Map(); // frame → Map(host → cookie)
// Nội dung playlist trang đã tải: nhiều trang cấp link playlist dùng một lần (token) → trình phát mượt không xin lại
// được, nên giữ bản chép nhận được lúc trang tải (theo frame) và gửi kèm cho app.
const MAX_PLAYLIST_CHARS = 400000;
const playlists = new Map();   // frame → Map(url → nội dung)
// hook.js (bắt playlist trang tự giải mã) chỉ chạy ở tên miền trình phát đã gặp playlist tự mã hóa — không chạy
// trên mọi trang. Gặp tên miền mới → ghi nhớ, từ lần mở trang sau hook có hiệu lực.
const HOOK_SEED = [ 'streamc.xyz' ]; // Cobephim
const hookDomains = new Set(HOOK_SEED);
let hookRegistration = null;
const baseDomain = host => host.split('.').slice(-2).join('.');

async function registerHook() {
    if ( !browser.contentScripts ) { return; }
    try { if ( hookRegistration ) { await hookRegistration.unregister(); } } catch { }
    hookRegistration = null;
    const matches = [ ...hookDomains ].flatMap(d => [ `*://${d}/*`, `*://*.${d}/*` ]);
    try {
        hookRegistration = await browser.contentScripts.register({
            matches, js: [ { file: 'hook.js' } ], runAt: 'document_start', allFrames: true,
        });
    } catch { }
}

function rememberHookDomain(frame) {
    let domain;
    try { domain = baseDomain(new URL(frame).hostname); } catch { return; }
    if ( domain === '' || hookDomains.has(domain) ) { return; }
    hookDomains.add(domain);
    browser.storage.local.set({ hookDomains: [ ...hookDomains ].slice(-50) }).catch(( ) => {});
    registerHook();
}

browser.storage.local.get('hookDomains').then(
    r => { for ( const d of (r && r.hookDomains) || [] ) { hookDomains.add(d); } registerHook(); },
    ( ) => registerHook(),
);

// Playlist trang tự giải mã (hook.js bắt kết quả crypto.subtle.decrypt) — ghép với playlist mã hóa cùng frame.
const decoded = new Map();     // frame → [nội dung chưa ghép]

/** Ghép playlist đã giải mã với playlist mã hóa (opaque) của cùng frame, theo thứ tự tải. */
function attachDecoded(tabId, frame) {
    const texts = decoded.get(frame);
    if ( !texts || texts.length === 0 ) { return; }
    const waiting = (streams.get(tabId) || []).filter(s => s.frame === frame && s.opaque);
    while ( texts.length !== 0 && waiting.length !== 0 ) {
        const s = waiting.shift();
        s.opaque = false;
        const map = playlists.get(frame) || new Map();
        map.set(s.url, texts.shift());
        playlists.set(frame, map);
    }
}

browser.runtime.onMessage.addListener((msg, sender) => {
    if ( msg instanceof Object === false || msg.type !== 'playlistText' || typeof msg.text !== 'string' ) { return; }
    if ( !sender || !sender.tab || sender.tab.id < 0 ) { return; }
    const frame = noHash(typeof msg.frame === 'string' ? msg.frame : sender.url);
    const texts = decoded.get(frame) || [];
    texts.push(msg.text);
    while ( texts.length > 4 ) { texts.shift(); }
    decoded.set(frame, texts);
    while ( decoded.size > 8 ) { decoded.delete(decoded.keys().next().value); }
    attachDecoded(sender.tab.id, frame);
});
const HLS_TYPE = /mpegurl/i;
const HLS_URL = /\.m3u8(?:[?#]|$)/i;
const noHash = u => typeof u === 'string' ? u.split('#')[0] : '';

function header(list, name) {
    for ( const h of list || [] ) {
        if ( h.name.toLowerCase() === name ) { return h.value || ''; }
    }
    return '';
}

/** Chép nội dung playlist khi trang tải (trang vẫn nhận nguyên vẹn). */
function capturePlaylist(d) {
    const frame = noHash(d.documentUrl || d.originUrl || '');
    let filter;
    try { filter = browser.webRequest.filterResponseData(d.requestId); } catch { return; }
    const chunks = [];
    let size = 0;
    filter.ondata = ev => {
        filter.write(ev.data);
        if ( size < MAX_PLAYLIST_CHARS ) { chunks.push(ev.data); size += ev.data.byteLength; }
    };
    filter.onstop = ( ) => {
        filter.close();
        if ( size >= MAX_PLAYLIST_CHARS ) { return; }
        const text = new TextDecoder('utf-8').decode(concat(chunks));
        if ( !text.startsWith('#EXTM3U') ) { return; }
        // Playlist tự mã hóa (vd. #ENC-AESGCM — chỉ JS của trang giải được) → trình phát mượt không đọc được.
        if ( !/#EXTINF|#EXT-X-STREAM-INF/.test(text) ) {
            for ( const s of streams.get(d.tabId) || [] ) {
                if ( s.url === d.url ) { s.opaque = true; }
            }
            rememberHookDomain(frame);
            attachDecoded(d.tabId, frame); // trang có thể đã giải mã xong trước khi phản hồi kết thúc
            return;
        }
        const map = playlists.get(frame) || new Map();
        map.set(d.url, text);
        while ( map.size > 12 ) { map.delete(map.keys().next().value); }
        playlists.set(frame, map);
        while ( playlists.size > 16 ) { playlists.delete(playlists.keys().next().value); }
    };
    filter.onerror = ( ) => { try { filter.disconnect(); } catch { } };
}

function concat(chunks) {
    const out = new Uint8Array(chunks.reduce((n, c) => n + c.byteLength, 0));
    let at = 0;
    for ( const c of chunks ) { out.set(new Uint8Array(c), at); at += c.byteLength; }
    return out;
}

function rememberStream(d, kind) {
    const frame = noHash(d.documentUrl || d.originUrl || '');
    const list = streams.get(d.tabId) || [];
    // Playlist đầu tiên của mỗi frame là playlist gốc (master); playlist từng chất lượng tải sau → bỏ qua.
    if ( list.some(s => s.frame === frame && s.kind === kind) ) { return; }
    const h = sentHeaders.get(d.requestId) || {};
    list.push({ url: d.url, kind, frame, referer: h.referer || '', origin: h.origin || '', ua: h.ua || '', at: Date.now() });
    while ( list.length > MAX_STREAMS ) { list.shift(); }
    streams.set(d.tabId, list);
}

if ( browser.webRequest ) {
    const filter = { urls: [ '<all_urls>' ], types: [ 'xmlhttprequest', 'media', 'other' ] };
    browser.webRequest.onSendHeaders.addListener(d => {
        if ( d.tabId < 0 ) { return; }
        sentHeaders.set(d.requestId, {
            referer: header(d.requestHeaders, 'referer'),
            origin: header(d.requestHeaders, 'origin'),
            ua: header(d.requestHeaders, 'user-agent'),
        });
        const cookie = header(d.requestHeaders, 'cookie');
        if ( cookie !== '' ) {
            const frame = noHash(d.documentUrl || d.originUrl || '');
            try {
                const host = new URL(d.url).hostname;
                const map = frameCookies.get(frame) || new Map();
                map.set(host, cookie);
                while ( map.size > 8 ) { map.delete(map.keys().next().value); }
                frameCookies.set(frame, map);
                while ( frameCookies.size > 16 ) { frameCookies.delete(frameCookies.keys().next().value); }
            } catch { }
        }
        if ( sentHeaders.size > 300 ) { sentHeaders.delete(sentHeaders.keys().next().value); }
    }, filter, [ 'requestHeaders' ]);
    browser.webRequest.onHeadersReceived.addListener(d => {
        if ( d.tabId >= 0 && d.statusCode < 400 ) {
            const type = header(d.responseHeaders, 'content-type');
            if ( HLS_TYPE.test(type) || HLS_URL.test(d.url) ) {
                rememberStream(d, 'hls');
                capturePlaylist(d);
            } else if ( d.type === 'media' && /^video\//i.test(type) ) {
                rememberStream(d, 'file');
            }
        }
        sentHeaders.delete(d.requestId);
    }, filter, [ 'responseHeaders', 'blocking' ]);
    browser.webRequest.onErrorOccurred.addListener(d => { sentHeaders.delete(d.requestId); }, filter);
    // Trang / iframe trình phát tải lại → quên luồng cũ của nó.
    browser.webRequest.onBeforeRequest.addListener(d => {
        if ( d.tabId < 0 ) { return; }
        if ( d.type === 'main_frame' ) { streams.delete(d.tabId); return; }
        const list = streams.get(d.tabId);
        if ( list ) { streams.set(d.tabId, list.filter(s => s.frame !== noHash(d.url))); }
        playlists.delete(noHash(d.url));
        decoded.delete(noHash(d.url));
        frameCookies.delete(noHash(d.url));
    }, { urls: [ '<all_urls>' ], types: [ 'main_frame', 'sub_frame' ] });
    browser.tabs.onRemoved.addListener(id => { streams.delete(id); });
}

/** Luồng của video chính: đúng URL trình phát báo → cùng frame với video → (không rõ frame) HLS mới nhất. */
function pickStream(tabId, video) {
    const list = (streams.get(tabId) || []).filter(s => !s.opaque).reverse();
    const src = video && typeof video.src === 'string' ? video.src : '';
    const frame = video ? noHash(video.frame) : '';
    if ( (streams.get(tabId) || []).some(s => s.opaque && (s.url === src || s.frame === frame)) ) { return null; }
    const found = list.find(s => src !== '' && s.url === src)
        || list.find(s => frame !== '' && s.frame === frame && s.kind === 'hls')
        || list.find(s => frame !== '' && s.frame === frame)
        || (frame === '' ? list.find(s => s.kind === 'hls') : undefined);
    if ( found ) { return found; }
    if ( /^https?:/.test(src) ) { // trình phát báo URL nhưng không thấy yêu cầu (vd. tải trước khi extension chạy)
        let origin = '';
        try { origin = new URL(frame).origin; } catch { }
        return { url: src, kind: HLS_URL.test(src) ? 'hls' : 'file', frame, referer: origin ? origin + '/' : '', origin, ua: '', at: 0 };
    }
    return null;
}

async function handlePortMessage(msg) {
    if ( msg.type === 'stream' ) {
        const tabs = await browser.tabs.query({ active: true });
        if ( tabs.length === 0 ) { return { ok: false }; }
        const video = await browser.tabs.sendMessage(tabs[0].id, { phim275Video: true, cmd: 'info' }).catch(( ) => null);
        const found = pickStream(tabs[0].id, video instanceof Object ? video : null);
        if ( !found ) { return { ok: false }; }
        const copies = playlists.get(found.frame);
        let origin = found.origin;
        try { if ( !origin ) { origin = new URL(found.frame).origin; } } catch { }
        const stream = Object.assign({}, found, {
            origin,
            referer: found.referer || (origin ? origin + '/' : ''),
            playlists: copies ? Object.fromEntries(copies) : {},
            cookies: Object.fromEntries(frameCookies.get(found.frame) || []),
        });
        return { ok: true, info: { stream, video: video instanceof Object ? video : null } };
    }
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
