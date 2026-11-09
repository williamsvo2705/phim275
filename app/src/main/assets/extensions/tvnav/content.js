/*
 * Phim275 TV Navigation — điều hướng mọi trang web bằng phím mũi tên (spatial navigation).
 * GPLv3 — https://github.com/williamsvo2705/phim275
 *
 * App chỉ gửi phím mũi tên / Enter vào trang khi đang ở chế độ "chuyển khối";
 * ở chế độ con trỏ chuột, app tự xử lý phím nên script này không nhận được gì.
 *
 * - ◀ ▲ ▶ ▼: chọn phần tử bấm được gần nhất theo hướng (liên kết, nút, ô phim…), viền đỏ đánh dấu.
 *   Không còn phần tử theo hướng đó (hoặc quá xa) → cuộn trang.
 * - Enter (OK): app chạm thật vào giữa phần tử (bấm được cả iframe trình phát video).
 * - F10 (app gửi khi bấm OK trên thẻ "Tập tiếp theo"): mở tập kế tiếp của trang xem phim.
 *   Không có kênh tới app (vd. trang chủ nội bộ) → element.click().
 */
(function () {
    'use strict';
    if ( window.__phim275TvNav ) { return; }
    window.__phim275TvNav = true;

    const NAV_KEYS = new Set([ 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', 'Escape', 'F9', 'F10' ]);
    const FORWARD_TAG = 'phim275-tvnav-key';
    // Hộp thoại bên trong iframe trình phát ("Tiếp tục xem / Xem lại từ đầu"…): iframe báo trạng thái,
    // trang chính chuyển phím vào để chọn nút.
    const DIALOG_KEY_TAG = 'phim275-tvnav-dialog-key';
    const DIALOG_STATE_TAG = 'phim275-tvnav-dialog-state';
    const DIALOG_BUTTONS = 'button, [role=button], a[href], input[type=button], input[type=submit]';
    // Dữ liệu postMessage thuộc compartment của trang → `instanceof Object` trong content script luôn sai.
    const isObj = d => typeof d === 'object' && d !== null;

    // ── Xem tiếp đúng chỗ (mọi frame, kể cả iframe trình phát khác tên miền) ──
    // Video chính (≥ 2 phút: bỏ quảng cáo chèn đầu, đoạn xem trước) báo vị trí cho app mỗi 5 giây,
    // khi tạm dừng và khi rời trang. Lúc bắt đầu phát, hỏi app vị trí lần trước để tua tới.
    (function trackPlayback() {
        if ( typeof browser === 'undefined' || !browser.runtime ) { return; }
        const MIN_DURATION = 120;
        const REPORT_MS = 5000;
        const asked = new WeakSet();
        let lastReport = 0;
        const videoOf = ev => {
            const t = typeof ev.composedPath === 'function' ? ev.composedPath()[0] : ev.target;
            return t instanceof HTMLVideoElement ? t : null;
        };
        const isMain = v => Number.isFinite(v.duration) && v.duration >= MIN_DURATION;
        // Khung chính tự gửi URL của mình (lúc rời trang, trình duyệt có thể không còn cho biết tab).
        const top = window === window.top ? location.href : null;
        const send = (msg) => { browser.runtime.sendMessage(Object.assign({ top }, msg)).catch(( ) => {}); };
        function report(v, flush) {
            if ( isMain(v) && v.currentTime > 0 ) { send({ type: 'mediaPos', t: v.currentTime, d: v.duration, flush }); }
        }
        function maybeResume(v) {
            if ( asked.has(v) || !isMain(v) ) { return; }
            asked.add(v);
            // c: vị trí lúc bắt đầu — trang tự xem tiếp (bắt đầu giữa phim) thì app nhường trang.
            browser.runtime.sendMessage({ type: 'mediaResume', d: v.duration, c: v.currentTime, top }).then(t => {
                if ( typeof t === 'number' && t > 0 && v.currentTime < t - 5 ) { v.currentTime = t; }
            }, ( ) => {});
        }
        addEventListener('playing', ev => { const v = videoOf(ev); if ( v !== null ) { maybeResume(v); } }, true);
        addEventListener('timeupdate', ev => {
            const v = videoOf(ev);
            if ( v === null || v.paused ) { return; }
            maybeResume(v); // luồng HLS: độ dài chỉ biết sau khi đã phát
            const now = Date.now();
            if ( now - lastReport < REPORT_MS ) { return; }
            lastReport = now;
            report(v, false);
        }, true);
        addEventListener('pause', ev => { const v = videoOf(ev); if ( v !== null ) { report(v, true); } }, true);
        // Hết phim → app đếm ngược sang tập sau (nếu trang có).
        addEventListener('ended', ev => {
            const v = videoOf(ev);
            if ( v !== null && isMain(v) ) { send({ type: 'mediaPos', t: v.duration, d: v.duration, flush: true, ended: true }); }
        }, true);
        addEventListener('pagehide', ( ) => {
            for ( const v of document.querySelectorAll('video') ) { report(v, true); }
        });
    })();

    // ── Trong iframe (vd. trình phát video): không điều hướng, chỉ chuyển phím lên trang chính ──
    // Sau khi bấm vào trình phát, focus bàn phím nằm trong iframe nên phím không tới trang chính.
    if ( window !== window.top ) {
        addEventListener('keydown', ev => {
            if ( !NAV_KEYS.has(ev.key) || ev.altKey || ev.ctrlKey || ev.metaKey ) { return; }
            const ae = document.activeElement;
            const editing = ae !== null && (ae.isContentEditable || ae.tagName === 'TEXTAREA' ||
                (ae.tagName === 'INPUT' && !/^(button|submit|checkbox|radio|range|color|file|image|reset)$/i.test(ae.type)));
            if ( editing ) { return; }
            ev.preventDefault();
            ev.stopImmediatePropagation();
            try { window.top.postMessage({ [FORWARD_TAG]: ev.key }, '*'); } catch { }
        }, true);
        frameDialog();
        return;
    }

    /** Trong iframe: tìm hộp thoại (≥ 2 nút có chữ ở giữa khung), vẽ viền nút đang chọn, nhận phím từ trang chính. */
    function frameDialog() {
        let dlg = null;     // { buttons, index }
        let shown = false;  // trang chính đang chọn trình phát này → mới vẽ viền (tránh viền lạc ở iframe khác)
        let ring = null;
        let pending = null;
        function dialogButtons() {
            const vw = innerWidth, vh = innerHeight;
            const out = [];
            for ( const el of document.querySelectorAll(DIALOG_BUTTONS) ) {
                const r = el.getBoundingClientRect();
                if ( r.width < 6 || r.height < 6 ) { continue; }
                const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
                if ( cx < vw * 0.15 || cx > vw * 0.85 || cy < vh * 0.15 || cy > vh * 0.8 ) { continue; }
                if ( (el.innerText || el.value || '').trim().length < 2 ) { continue; }
                const cs = getComputedStyle(el);
                if ( cs.visibility === 'hidden' || cs.opacity === '0' ) { continue; }
                const hit = document.elementFromPoint(cx, cy);
                if ( hit === null || (hit !== el && !el.contains(hit) && !hit.contains(el)) ) { continue; }
                out.push(el);
            }
            const pos = el => el.getBoundingClientRect();
            out.sort((a, b) => {
                const ra = pos(a), rb = pos(b);
                return Math.abs(ra.top - rb.top) > 8 ? ra.top - rb.top : ra.left - rb.left;
            });
            return out.length >= 2 ? out : [];
        }
        function drawRing() {
            if ( dlg === null || !shown ) {
                if ( ring !== null ) { ring.style.display = 'none'; }
                return;
            }
            if ( ring === null || !ring.isConnected ) {
                ring = document.createElement('div');
                ring.setAttribute('aria-hidden', 'true');
                ring.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;box-sizing:border-box;' +
                    'border:4px solid #e50914;border-radius:10px;' +
                    'box-shadow:0 0 0 3px rgba(255,255,255,.85),0 0 22px 4px rgba(229,9,20,.75)';
                (document.body || document.documentElement).appendChild(ring);
            }
            const r = dlg.buttons[dlg.index].getBoundingClientRect();
            Object.assign(ring.style, {
                display: 'block', left: `${r.left - 4}px`, top: `${r.top - 4}px`,
                width: `${r.width + 8}px`, height: `${r.height + 8}px`,
            });
        }
        function refresh() {
            pending = null;
            const buttons = dialogButtons();
            const had = dlg !== null;
            if ( buttons.length === 0 ) {
                dlg = null;
            } else {
                const prev = dlg !== null ? dlg.buttons[dlg.index] : null;
                let index = prev !== null ? buttons.indexOf(prev) : -1;
                if ( index < 0 ) {
                    const ae = document.activeElement;
                    index = Math.max(0, buttons.findIndex(b => ae !== null && (b === ae || b.contains(ae))));
                }
                dlg = { buttons, index };
            }
            drawRing();
            if ( had !== (dlg !== null) ) {
                try { window.parent.postMessage({ [DIALOG_STATE_TAG]: dlg !== null }, '*'); } catch { }
            }
        }
        const schedule = ( ) => { if ( pending === null ) { pending = setTimeout(refresh, 300); } };
        new MutationObserver(schedule).observe(document.documentElement, {
            childList: true, subtree: true, attributes: true, attributeFilter: [ 'class', 'style', 'hidden' ],
        });
        schedule();
        addEventListener('message', ev => {
            const data = ev.data;
            if ( !isObj(data) || typeof data[DIALOG_KEY_TAG] !== 'string' ) { return; }
            const key = data[DIALOG_KEY_TAG];
            if ( key === 'hide' ) { shown = false; drawRing(); return; }
            shown = true;
            if ( dlg === null ) { return; }
            const n = dlg.buttons.length;
            switch ( key ) {
            case 'show': drawRing(); break;
            case 'ArrowLeft': case 'ArrowUp': dlg.index = (dlg.index - 1 + n) % n; drawRing(); break;
            case 'ArrowRight': case 'ArrowDown': dlg.index = (dlg.index + 1) % n; drawRing(); break;
            case 'Enter': dlg.buttons[dlg.index].click(); schedule(); break;
            }
        });
    }

    const SELECTOR = [
        'a[href]', 'button', 'input:not([type=hidden])', 'select', 'textarea', 'summary', 'video', 'iframe',
        '[role=button]', '[role=link]', '[role=tab]', '[role=menuitem]', '[role=option]', '[role=checkbox]',
        '[onclick]', '[tabindex]:not([tabindex="-1"])', '[contenteditable=""]', '[contenteditable="true"]',
    ].join(',');
    // Phần tử "tự chế" nút bấm bằng CSS cursor:pointer (rất phổ biến ở trang phim).
    const POINTER_TAGS = 'div,span,li,img,i,svg,p,h1,h2,h3,h4,h5,label,figure,section,article,em,strong,b';
    const MAX_POINTER_SCAN = 4000;
    const DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
    const NON_TEXT_INPUT = /^(button|submit|checkbox|radio|range|color|file|image|reset)$/i;

    const DOUBLE_PRESS_MS = 420;
    const SETTLE_MS = 350;              // chờ DOM ngừng thay đổi sau khi trang SPA chuyển nội dung
    const PLAYER_MIN_AREA = 0.18;       // trình phát chiếm ≥ 18% màn hình mới tính là "trang xem phim"
    const MEDIA_HINT_HIDE_MS = 3000;

    let cache = null;
    let current = null;
    let lastRect = null;            // vị trí khối đang chọn gần nhất (để chọn lại khối gần đó khi nó bị gỡ)
    let overlay = null;
    let hint = null;
    let pendingActivate = null;     // chờ xem có bấm OK lần 2 (khối video) không
    let pseudoFull = null;          // { el, style } khi phóng to bằng CSS (trình phát chặn Fullscreen API)
    let fadeTimer = null;

    const isFullscreen = ( ) => document.fullscreenElement !== null || pseudoFull !== null;

    function isMediaBlock(el) {
        if ( el === null ) { return false; }
        if ( el.tagName === 'VIDEO' || el.tagName === 'IFRAME' ) { return true; }
        return el.querySelector('video, iframe') !== null;
    }

    // Trang SPA (Next.js, React…) đổi nội dung mà không tải lại: khối đang chọn có thể bị gỡ khỏi DOM.
    const dialogFrames = new WeakSet();    // cửa sổ iframe đang hiện hộp thoại
    let lastHref = location.href;
    let settleTimer = null;
    let lastDialogCheck = 0;
    const DIALOG_CHECK_MS = 1000;       // trình phát đổi DOM liên tục khi đang phát → kiểm tra thưa (box 1GB)
    new MutationObserver(( ) => {
        cache = null;
        if ( settleTimer === null ) { settleTimer = setTimeout(onDomSettled, SETTLE_MS); }
    }).observe(document.documentElement, {
        childList: true, subtree: true, attributes: true, attributeFilter: [ 'class', 'style', 'hidden', 'href' ],
    });

    function onDomSettled() {
        settleTimer = null;
        if ( current !== null && !isAlive(current) ) {
            current = null;
            drawOverlay();
        }
        // Hộp thoại hiện lên giữa trình phát đang chọn (vd. "Tiếp tục xem / Xem lại từ đầu") → chọn nút của nó.
        const now = Date.now();
        if ( current !== null && isMediaBlock(current) && !isFullscreen() && now - lastDialogCheck >= DIALOG_CHECK_MS ) {
            lastDialogCheck = now;
            const btn = dialogOver(current);
            if ( btn !== null ) { select(btn); }
        }
        if ( location.href !== lastHref ) {
            lastHref = location.href;
            schedulePlayerPick();
            scheduleNextCheck();
        }
    }

    // Trình phát thường nạp sau nội dung trang → thử lại vài lần (bỏ qua nếu người dùng đã chọn khối khác).
    const PLAYER_RETRY_MS = [ 0, 1200, 3000 ];
    function schedulePlayerPick() {
        for ( const ms of PLAYER_RETRY_MS ) { setTimeout(autoSelectPlayer, ms); }
    }

    /** Trang có trình phát lớn trên màn hình (trang xem phim) → chọn sẵn: OK là phát, OK × 2 toàn màn hình. */
    function autoSelectPlayer() {
        if ( isFullscreen() || (current !== null && isAlive(current)) ) { return; }
        const player = findPlayer();
        if ( player !== null ) { select(dialogOver(player) || player); }
    }

    /**
     * Nút của hộp thoại đè lên giữa trình phát (≥ 2 nút có chữ ở vùng giữa khung; thanh điều khiển ở mép
     * dưới không tính) → nút trang đã đặt focus sẵn, hoặc nút đầu tiên. Không có → null.
     */
    function dialogOver(player) {
        const pr = player.getBoundingClientRect();
        const x1 = pr.left + pr.width * 0.15, x2 = pr.right - pr.width * 0.15;
        const y1 = pr.top + pr.height * 0.15, y2 = pr.bottom - pr.height * 0.2;
        const buttons = [];
        // Chỉ xét nút thật (rẻ, không quét cursor:pointer toàn trang).
        for ( const el of document.querySelectorAll(DIALOG_BUTTONS) ) {
            if ( el === player || el.contains(player) || player.contains(el) ) { continue; }
            const r = el.getBoundingClientRect();
            const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            if ( cx < x1 || cx > x2 || cy < y1 || cy > y2 ) { continue; }
            if ( (el.innerText || '').trim().length < 2 || !isUsable(el, r) ) { continue; }
            buttons.push(el);
        }
        if ( buttons.length < 2 ) { return null; }
        const ae = document.activeElement;
        return buttons.find(b => ae !== null && (b === ae || b.contains(ae))) || firstVisible(buttons);
    }

    const area = r => r.width * r.height;

    function isUsable(el, r) {
        if ( r.width < 6 || r.height < 6 ) { return false; }
        const vw = innerWidth, vh = innerHeight;
        if ( r.right < -vw * 0.5 || r.left > vw * 1.5 ) { return false; }     // slide ẩn của carousel
        if ( el.tagName !== 'VIDEO' && el.tagName !== 'IFRAME' && area(r) > vw * vh * 0.6 ) { return false; } // khung bao trùm
        const cs = getComputedStyle(el);
        if ( cs.visibility === 'hidden' || cs.opacity === '0' ) { return false; }
        // Đang nằm trong màn hình: phải là phần tử trên cùng tại tâm của nó. Loại các slide ẩn
        // của carousel, lớp bị che, phần tử opacity 0 ở cha (thường kèm pointer-events:none).
        const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        if ( cx >= 0 && cx < vw && cy >= 0 && cy < vh ) {
            const hit = document.elementFromPoint(cx, cy);
            if ( hit === null ) { return false; }
            if ( hit !== el && !el.contains(hit) && !hit.contains(el) && !sameWidget(el, hit, r) ) { return false; }
        }
        return true;
    }

    /** Còn trên trang và còn kích thước (không kiểm tra bị che: dùng cho khối đang chọn). */
    function isAlive(el) {
        if ( !el.isConnected ) { return false; }
        const r = el.getBoundingClientRect();
        return r.width >= 6 && r.height >= 6;
    }

    /** Video/iframe bị lớp điều khiển của chính trình phát che (nút ▶ to giữa khung) vẫn chọn được. */
    function sameWidget(el, hit, r) {
        if ( el.tagName !== 'VIDEO' && el.tagName !== 'IFRAME' ) { return false; }
        for ( let p = el.parentElement; p !== null; p = p.parentElement ) {
            if ( area(p.getBoundingClientRect()) > area(r) * 1.6 ) { return false; }
            if ( p.contains(hit) ) { return true; }
        }
        return false;
    }

    /** Video/iframe lớn nhất đang nằm trong màn hình (đủ lớn để là trình phát chính). */
    function findPlayer() {
        const vw = innerWidth, vh = innerHeight;
        let best = null, bestArea = vw * vh * PLAYER_MIN_AREA;
        for ( const el of document.querySelectorAll('video, iframe') ) {
            const r = el.getBoundingClientRect();
            const w = Math.min(r.right, vw) - Math.max(r.left, 0);
            const h = Math.min(r.bottom, vh) - Math.max(r.top, 0);
            if ( w <= 0 || h <= 0 || w * h < bestArea ) { continue; }
            const cs = getComputedStyle(el);
            if ( cs.visibility === 'hidden' || cs.opacity === '0' ) { continue; }
            best = el;
            bestArea = w * h;
        }
        return best;
    }

    function collect() {
        if ( cache !== null ) { return cache; }
        const set = new Set(document.querySelectorAll(SELECTOR));
        const extra = document.querySelectorAll(POINTER_TAGS);
        const n = Math.min(extra.length, MAX_POINTER_SCAN);
        for ( let i = 0; i < n; i++ ) {
            const el = extra[i];
            if ( set.has(el) ) { continue; }
            if ( getComputedStyle(el).cursor !== 'pointer' ) { continue; }
            const p = el.parentElement;
            // cursor được kế thừa: chỉ lấy phần tử tự đặt pointer (gốc của vùng bấm được).
            if ( p !== null && getComputedStyle(p).cursor === 'pointer' ) { continue; }
            set.add(el);
        }
        // Bỏ phần tử con nằm trong một phần tử bấm được có kích thước tương đương (giữ lớp ngoài).
        const list = [];
        for ( const el of set ) {
            const r = el.getBoundingClientRect();
            let covered = false;
            for ( let p = el.parentElement; p !== null; p = p.parentElement ) {
                if ( set.has(p) ) {
                    covered = area(p.getBoundingClientRect()) <= area(r) * 3;
                    break;
                }
            }
            if ( !covered ) { list.push(el); }
        }
        cache = list;
        return list;
    }

    function rangeGap(a1, a2, b1, b2) {
        if ( b2 < a1 ) { return a1 - b2; }
        if ( b1 > a2 ) { return b1 - a2; }
        return 0;
    }

    function score(a, b, dir) {
        const acx = (a.left + a.right) / 2, acy = (a.top + a.bottom) / 2;
        const bcx = (b.left + b.right) / 2, bcy = (b.top + b.bottom) / 2;
        let primary, secondary, offset;
        switch ( dir ) {
        case 'down':
            if ( bcy <= acy + 1 || b.bottom <= a.bottom ) { return Infinity; }
            primary = Math.max(0, b.top - a.bottom);
            secondary = rangeGap(a.left, a.right, b.left, b.right);
            offset = Math.abs(acx - bcx);
            break;
        case 'up':
            if ( bcy >= acy - 1 || b.top >= a.top ) { return Infinity; }
            primary = Math.max(0, a.top - b.bottom);
            secondary = rangeGap(a.left, a.right, b.left, b.right);
            offset = Math.abs(acx - bcx);
            break;
        case 'right':
            if ( bcx <= acx + 1 || b.right <= a.right ) { return Infinity; }
            primary = Math.max(0, b.left - a.right);
            secondary = rangeGap(a.top, a.bottom, b.top, b.bottom);
            offset = Math.abs(acy - bcy);
            break;
        default: // left
            if ( bcx >= acx - 1 || b.left >= a.left ) { return Infinity; }
            primary = Math.max(0, a.left - b.right);
            secondary = rangeGap(a.top, a.bottom, b.top, b.bottom);
            offset = Math.abs(acy - bcy);
        }
        const weight = dir === 'left' || dir === 'right' ? 3 : 2;
        return primary + weight * secondary + 0.05 * offset;
    }

    /** Khối bấm được trong màn hình gần [rect] nhất (tâm tới tâm). */
    function nearestTo(list, rect) {
        const tx = rect.left + rect.width / 2, ty = rect.top + rect.height / 2;
        let best = null, bestDist = Infinity;
        for ( const el of list ) {
            const r = el.getBoundingClientRect();
            if ( r.bottom < 0 || r.top > innerHeight || !isUsable(el, r) ) { continue; }
            const dx = r.left + r.width / 2 - tx, dy = r.top + r.height / 2 - ty;
            const dist = dx * dx + dy * dy;
            if ( dist < bestDist ) { bestDist = dist; best = el; }
        }
        return best;
    }

    /** Chọn lại khi chưa có / mất khối: trình phát → khối gần chỗ cũ → khối đầu tiên. */
    function startBlock(list) {
        return findPlayer() || (lastRect !== null ? nearestTo(list, lastRect) : null) || firstVisible(list);
    }

    function firstVisible(list) {
        let best = null, bestKey = Infinity;
        for ( const el of list ) {
            const r = el.getBoundingClientRect();
            if ( !isUsable(el, r) || r.bottom < 0 || r.top > innerHeight ) { continue; }
            const key = Math.max(0, r.top) * 4 + Math.max(0, r.left);
            if ( key < bestKey ) { bestKey = key; best = el; }
        }
        return best;
    }

    function scrollPage(dir) {
        const dy = dir === 'down' ? innerHeight * 0.6 : dir === 'up' ? -innerHeight * 0.6 : 0;
        const dx = dir === 'right' ? innerWidth * 0.6 : dir === 'left' ? -innerWidth * 0.6 : 0;
        window.scrollBy({ top: dy, left: dx, behavior: 'smooth' });
    }

    function move(dir) {
        const list = collect();
        if ( current !== null && !isAlive(current) ) { current = null; }
        if ( current === null ) {
            const first = startBlock(list);
            if ( first !== null ) { select(first); } else { scrollPage(dir); }
            return;
        }
        const cur = current.getBoundingClientRect();
        let best = null, bestRect = null, bestScore = Infinity;
        for ( const el of list ) {
            if ( el === current || el.contains(current) || current.contains(el) ) { continue; }
            const r = el.getBoundingClientRect();
            if ( !isUsable(el, r) ) { continue; }
            const s = score(cur, r, dir);
            if ( s < bestScore ) { bestScore = s; best = el; bestRect = r; }
        }
        if ( best === null ) { scrollPage(dir); return; }
        // Phần tử kế tiếp quá xa (vd. đoạn văn dài) → cuộn để đọc thay vì nhảy cóc.
        const vertical = dir === 'up' || dir === 'down';
        const gap = vertical
            ? (dir === 'down' ? bestRect.top - cur.bottom : cur.top - bestRect.bottom)
            : (dir === 'right' ? bestRect.left - cur.right : cur.left - bestRect.right);
        if ( vertical && gap > innerHeight * 0.85 ) { scrollPage(dir); return; }
        select(best);
    }

    function select(el) {
        if ( current !== el ) {
            tellFrame(current, 'hide');
            tellFrame(el, 'show');
        }
        current = el;
        const r = el.getBoundingClientRect();
        lastRect = r;
        if ( r.top < 70 || r.bottom > innerHeight - 70 || r.left < 0 || r.right > innerWidth ) {
            el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
        }
        showOverlay();
    }

    /** Báo iframe trình phát (có hộp thoại) rằng nó đang / thôi được chọn. */
    function tellFrame(el, what) {
        if ( el === null || el.tagName !== 'IFRAME' || el.contentWindow === null ) { return; }
        if ( !dialogFrames.has(el.contentWindow) ) { return; }
        try { el.contentWindow.postMessage({ [DIALOG_KEY_TAG]: what }, '*'); } catch { }
    }

    function ensureOverlay() {
        if ( overlay !== null && overlay.isConnected ) { return overlay; }
        overlay = document.createElement('div');
        overlay.setAttribute('aria-hidden', 'true');
        overlay.style.cssText = [
            'position:fixed', 'z-index:2147483647', 'pointer-events:none', 'box-sizing:border-box',
            'border:4px solid #e50914', 'border-radius:10px',
            'box-shadow:0 0 0 3px rgba(255,255,255,.85),0 0 22px 4px rgba(229,9,20,.75)',
            'transition:left .12s ease,top .12s ease,width .12s ease,height .12s ease',
            'transition:left .12s ease,top .12s ease,width .12s ease,height .12s ease,opacity .4s ease',
            'display:none',
        ].join(';');
        hint = document.createElement('div');
        hint.textContent = 'OK: phát / dừng  ·  OK × 2: toàn màn hình';
        hint.style.cssText = [
            'position:absolute', 'right:8px', 'bottom:8px', 'padding:6px 12px', 'border-radius:8px',
            'background:rgba(0,0,0,.78)', 'color:#fff', 'font:600 16px/1.3 system-ui,Roboto,sans-serif',
            'display:none',
        ].join(';');
        overlay.appendChild(hint);
        (document.body || document.documentElement).appendChild(overlay);
        return overlay;
    }

    function drawOverlay() {
        if ( current === null || !current.isConnected || isFullscreen() ) {
            if ( overlay !== null ) { overlay.style.display = 'none'; }
            return;
        }
        const r = current.getBoundingClientRect();
        if ( r.width > 0 ) { lastRect = r; }
        const o = ensureOverlay();
        const pad = 4;
        o.style.left = `${r.left - pad}px`;
        o.style.top = `${r.top - pad}px`;
        o.style.width = `${r.width + pad * 2}px`;
        o.style.height = `${r.height + pad * 2}px`;
        o.style.display = 'block';
    }

    /** Hiện viền (và gợi ý nếu là khối video); khối video thì tự mờ đi sau vài giây để không che phim. */
    function showOverlay() {
        drawOverlay();
        if ( overlay === null ) { return; }
        overlay.style.opacity = '1';
        const media = isMediaBlock(current);
        hint.style.display = media ? 'block' : 'none';
        clearTimeout(fadeTimer);
        if ( media ) {
            fadeTimer = setTimeout(( ) => { if ( overlay !== null ) { overlay.style.opacity = '0'; } }, MEDIA_HINT_HIDE_MS);
        }
    }

    function notifyApp(msg) {
        if ( typeof browser === 'undefined' || !browser.runtime ) { return; }
        browser.runtime.sendMessage(msg).catch(( ) => {});
    }

    // ── Toàn màn hình ───────────────────────────────────────────────────────
    function enterFullscreen(el) {
        const target = el.tagName === 'VIDEO' || el.tagName === 'IFRAME'
            ? el
            : (el.querySelector('iframe') || el.querySelector('video') || el);
        let result = null;
        try {
            result = target.requestFullscreen ? target.requestFullscreen() : null;
        } catch {
            result = null;
        }
        if ( result !== null && typeof result.then === 'function' ) {
            result.catch(( ) => setPseudoFullscreen(target, true));
        } else if ( !document.fullscreenElement ) {
            setPseudoFullscreen(target, true);
        }
    }

    /** Phóng to bằng CSS khi trình phát không cho dùng Fullscreen API. */
    function setPseudoFullscreen(el, on) {
        if ( on && pseudoFull === null ) {
            pseudoFull = { el, style: el.getAttribute('style') };
            el.style.cssText += ';position:fixed!important;left:0!important;top:0!important;' +
                'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
                'margin:0!important;z-index:2147483646!important;background:#000!important;border:0!important;';
            notifyApp({ type: 'pseudoFullscreen', on: true });
        } else if ( !on && pseudoFull !== null ) {
            const { el: target, style } = pseudoFull;
            if ( style === null ) { target.removeAttribute('style'); } else { target.setAttribute('style', style); }
            pseudoFull = null;
            notifyApp({ type: 'pseudoFullscreen', on: false });
        }
        queueRedraw();
    }

    addEventListener('fullscreenchange', ( ) => {
        if ( document.fullscreenElement === null ) { showOverlay(); } else { drawOverlay(); }
    });

    let redrawQueued = false;
    function queueRedraw() {
        if ( redrawQueued ) { return; }
        redrawQueued = true;
        requestAnimationFrame(( ) => { redrawQueued = false; drawOverlay(); });
    }
    addEventListener('scroll', queueRedraw, { capture: true, passive: true });
    addEventListener('resize', queueRedraw, { passive: true });

    function activate() {
        const el = current;
        if ( el === null || !isAlive(el) ) { return; }
        const r = el.getBoundingClientRect();
        const vv = window.visualViewport;
        const vx = vv ? vv.offsetLeft : 0, vy = vv ? vv.offsetTop : 0;
        const vw = vv ? vv.width : innerWidth, vh = vv ? vv.height : innerHeight;
        const fx = (r.left + r.width / 2 - vx) / vw;
        const fy = (r.top + r.height / 2 - vy) / vh;
        const canTap = typeof browser !== 'undefined' && browser.runtime && fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1;
        if ( !canTap ) { el.click(); return; }
        browser.runtime.sendMessage({ type: 'tap', fx, fy }).then(ok => {
            if ( ok !== true ) { el.click(); }
        }, ( ) => el.click());
        // Chạm vào iframe (trình phát) làm focus chui vào trong iframe → lấy lại focus cho trang chính.
        if ( isMediaBlock(el) ) { setTimeout(reclaimFocus, 400); }
    }

    function reclaimFocus() {
        const ae = document.activeElement;
        if ( ae !== null && ae.tagName === 'IFRAME' ) {
            ae.blur();
            window.focus();
        }
    }

    function isEditing(el) {
        if ( el === null ) { return false; }
        if ( el.isContentEditable || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' ) { return true; }
        return el.tagName === 'INPUT' && !NON_TEXT_INPUT.test(el.type);
    }

    /** Giữ OK (app gửi phím F9): báo cho app liên kết của khối đang chọn để mở menu. */
    function requestBlockMenu() {
        if ( current === null || !current.isConnected ) { return false; }
        const link = current.closest('a[href]') || current.querySelector('a[href]');
        const img = current.querySelector('img[alt]');
        // Ưu tiên tên thật (title, aria-label, alt ảnh), rồi tới dòng chữ dài nhất (thường là tên phim, không phải nhãn "Song ngữ").
        const longestLine = (current.innerText || '').split('\n').map(s => s.trim())
            .reduce((a, b) => (b.length > a.length ? b : a), '');
        const text = (
            (link && link.title) || current.getAttribute('aria-label') || current.title ||
            (img && img.alt) || longestLine
        ).trim().slice(0, 80);
        notifyApp({ type: 'context', href: link ? link.href : null, text });
        return true;
    }

    /** Xử lý một phím; trả về true nếu đã dùng phím (để chặn hành vi mặc định của trang). */
    function handleKey(key) {
        if ( key === 'F9' ) { return requestBlockMenu(); }
        if ( key === NEXT_KEY ) { goNextEpisode(); return true; }
        // App gửi Escape khi bấm Back lúc đang phóng to bằng CSS.
        if ( key === 'Escape' ) {
            if ( pseudoFull === null ) { return false; }
            setPseudoFullscreen(null, false);
            return true;
        }
        // Đang toàn màn hình: không di chuyển viền (app xử lý tua / dừng / thoát).
        if ( isFullscreen() ) { return false; }
        // Trình phát (iframe) đang chọn có hộp thoại bên trong → phím chọn nút của hộp thoại.
        if ( (DIRS[key] !== undefined || key === 'Enter') && current !== null && current.tagName === 'IFRAME' &&
             current.isConnected && current.contentWindow !== null && dialogFrames.has(current.contentWindow) ) {
            if ( pendingActivate !== null ) { clearTimeout(pendingActivate); pendingActivate = null; }
            current.contentWindow.postMessage({ [DIALOG_KEY_TAG]: key }, '*');
            return true;
        }
        const ae = document.activeElement;
        const editing = isEditing(ae);
        const dir = DIRS[key];
        if ( dir !== undefined ) {
            // Đang gõ chữ: ◀ ▶ di chuyển con trỏ chữ; ▲ ▼ rời khỏi ô (trừ ô nhiều dòng).
            if ( editing && (dir === 'left' || dir === 'right' || ae.tagName === 'TEXTAREA' || ae.isContentEditable) ) { return false; }
            if ( editing ) { ae.blur(); }
            move(dir);
            return true;
        }
        if ( key !== 'Enter' || editing ) { return false; }
        if ( current !== null && !isAlive(current) ) { current = null; }
        if ( current === null ) {
            // Chưa chọn khối (hoặc khối cũ đã bị gỡ khi trang đổi nội dung): chọn khối trước, không bấm mù.
            const start = startBlock(collect());
            if ( start === null ) { return false; }
            select(start);
            return true;
        }
        if ( !isMediaBlock(current) ) { activate(); return true; }
        // Hộp thoại đang đè lên trình phát: chọn nút của hộp thoại trước (bấm OK lần nữa để chọn).
        const dialogBtn = isFullscreen() ? null : dialogOver(current);
        if ( dialogBtn !== null ) { select(dialogBtn); return true; }
        // Khối video: OK 1 lần = phát/dừng (chờ chút xem có lần 2), OK 2 lần = toàn màn hình.
        if ( pendingActivate !== null ) {
            clearTimeout(pendingActivate);
            pendingActivate = null;
            enterFullscreen(current);
        } else {
            showOverlay();
            pendingActivate = setTimeout(( ) => { pendingActivate = null; activate(); }, DOUBLE_PRESS_MS);
        }
        return true;
    }

    // ── Tập tiếp theo ───────────────────────────────────────────────────────
    // Trang xem phim: tìm liên kết / nút sang tập kế (danh sách tập hoặc nút "Tập tiếp") rồi báo app.
    // Cuối phim app hiện thẻ "Tập tiếp theo"; bấm OK (hoặc hết giờ đếm ngược) → app gửi F10 → mở tập đó.
    const NEXT_KEY = 'F10';
    const NEXT_CHECK_MS = [ 600, 2500, 6000 ];
    const fold = s => (s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[đĐ]/g, 'd')
        .toLowerCase().replace(/\s+/g, ' ').trim();
    // "Tập 5", "Tap 05 - END", "Ep 5", "E05", "5", "05 Vietsub"…
    const EP_LABEL = /^(?:tap|ep|episode|e)?\s*\.?\s*0*(\d{1,4})(?:\s*[-:|]?\s*(?:end|full|hd|fhd|vietsub|thuyet minh|long tieng|tm|lt|vs|raw|sd|cam))*$/;
    // "Tập tiếp theo", "Xem tập sau", "Next episode" ("Next" trơn thường là nút của băng chuyền ảnh).
    const NEXT_TEXT = /^(?:xem )?(?:tap (?:tiep(?: theo)?|sau|ke(?: tiep)?)|next (?:episode|ep))(?:\W|$)/;
    const NEXT_ATTR = /next[-_ ]?(?:ep|episode|tap)|(?:ep|episode)[-_ ]?next|tap[-_ ]?(?:tiep|sau)/i;
    const ACTIVE_MARK = /(?:^|[\s_-])(?:active|current|selected|playing|now|on-?air|watching)(?:$|[\s_-])/i;
    const URL_EP = /(?:tap|ep|episode)[-_=]?0*(\d{1,4})(?!\d)/gi;
    const CLICKABLE = 'a[href], button, [role=button]';
    let nextSent = '';
    let nextLabel = null;
    let leaving = false;
    addEventListener('beforeunload', ( ) => { leaving = true; });
    addEventListener('pageshow', ( ) => { leaving = false; });

    const pageKey = u => u.split('#')[0].replace(/\/+$/, '');
    const classOf = el => (typeof el.className === 'string' ? el.className : el.getAttribute('class') || '');
    const isDisabled = el => el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true' ||
        /(?:^|\s)disabled(?:\s|$)/.test(classOf(el));

    function sameSite(a) {
        try {
            const u = new URL(a.href, location.href);
            const base = h => h.split('.').slice(-2).join('.');
            return /^https?:$/.test(u.protocol) && base(u.hostname) === base(location.hostname);
        } catch {
            return false;
        }
    }

    /** Ô tập đang xem được trang đánh dấu (class active/current…, aria-current). */
    function isActiveEp(el) {
        const cur = el.getAttribute('aria-current');
        if ( (cur !== null && cur !== 'false') || el.getAttribute('aria-selected') === 'true' ) { return true; }
        if ( ACTIVE_MARK.test(classOf(el)) ) { return true; }
        const p = el.parentElement;
        return p !== null && p.children.length === 1 && ACTIVE_MARK.test(classOf(p));
    }

    /** Khung chứa danh sách tập (bỏ qua lớp bọc riêng từng ô, vd. ul > li > a → ul). */
    function groupOf(el) {
        let box = el.parentElement;
        if ( box !== null && box.children.length === 1 && box.parentElement !== null ) { box = box.parentElement; }
        return box;
    }

    function episodeItems() {
        const out = [];
        for ( const el of document.querySelectorAll(CLICKABLE) ) {
            const raw = el.textContent;
            if ( raw.length > 40 ) { continue; }
            const m = EP_LABEL.exec(fold(raw) || fold(el.title));
            if ( m === null || (el.tagName === 'A' && !sameSite(el)) ) { continue; }
            out.push({ el, n: parseInt(m[1], 10) });
        }
        return out;
    }

    /** Ô "tập n + 1" trong cùng danh sách với tập n. */
    function nextIn(items, n, group) {
        const list = group === null ? items : items.filter(e => groupOf(e.el) === group);
        if ( list.length < 2 ) { return null; }
        const hit = list.find(e => e.n === n + 1 && !isDisabled(e.el));
        return hit ? { el: hit.el, label: `Tập ${n + 1}` } : null;
    }

    function urlEpisode() {
        let path = location.pathname + location.search;
        try { path = decodeURIComponent(path); } catch { }
        let n = null;
        for ( const m of path.matchAll(URL_EP) ) { n = parseInt(m[1], 10); }
        return n;
    }

    /** { el, label } của tập kế tiếp, hoặc null. */
    function findNextEpisode() {
        const here = pageKey(location.href);
        const items = episodeItems();
        // 1) Danh sách tập, biết chắc tập đang xem (ô trỏ tới đúng trang này, hoặc ô được đánh dấu).
        const cur = items.find(e => e.el.tagName === 'A' && pageKey(e.el.href) === here) || items.find(e => isActiveEp(e.el));
        if ( cur !== undefined ) {
            const next = nextIn(items, cur.n, groupOf(cur.el));
            if ( next !== null ) { return next; }
        }
        // 2) Nút "Tập tiếp / Tập sau / Next episode" do trang làm sẵn.
        for ( const el of document.querySelectorAll(CLICKABLE) ) {
            if ( isDisabled(el) ) { continue; }
            const raw = el.textContent;
            const text = fold(raw.length <= 60 ? raw : '') || fold(el.getAttribute('aria-label')) || fold(el.title);
            if ( !NEXT_TEXT.test(text) && !NEXT_ATTR.test(`${classOf(el)} ${el.id}`) ) { continue; }
            if ( el.tagName === 'A' && (!sameSite(el) || pageKey(el.href) === here) ) { continue; }
            const m = /\d{1,4}/.exec(text);
            return { el, label: m !== null ? `Tập ${parseInt(m[0], 10)}` : 'Tập tiếp theo' };
        }
        // 3) Số tập trong địa chỉ trang (…/tap-5, ?ep=5).
        const n = urlEpisode();
        if ( n !== null ) {
            const at = items.find(e => e.n === n);
            return nextIn(items, n, at !== undefined ? groupOf(at.el) : null);
        }
        return null;
    }

    function reportNextEpisode() {
        // Không cần chờ trình phát: thẻ chỉ hiện khi trang báo vị trí của một video dài (≥ 2 phút).
        const found = findNextEpisode();
        const label = found !== null ? found.label : null;
        const key = `${pageKey(location.href)}|${label}`;
        // Không có tập sau: chỉ báo khi trước đó đã báo có (app tự xóa khi đổi trang).
        if ( key === nextSent || (label === null && nextLabel === null) ) { return; }
        nextSent = key;
        nextLabel = label;
        notifyApp({ type: 'nextEp', label, top: location.href });
    }

    function scheduleNextCheck() {
        nextLabel = null;
        for ( const ms of NEXT_CHECK_MS ) { setTimeout(reportNextEpisode, ms); }
    }
    // Trình phát nạp muộn (sau khi bấm "Xem phim") → kiểm tra lại khi video bắt đầu phát.
    addEventListener('playing', ( ) => { if ( nextLabel === null ) { reportNextEpisode(); } }, true);

    function goNextEpisode() {
        const found = findNextEpisode();
        if ( found === null ) {
            nextSent = '';
            notifyApp({ type: 'nextEp', label: null, top: location.href });
            return;
        }
        if ( pseudoFull !== null ) { setPseudoFullscreen(null, false); }
        const el = found.el;
        const before = location.href;
        el.click(); // trang SPA (Next.js…) tự chuyển tập không cần tải lại
        if ( el.tagName === 'A' && /^https?:/.test(el.href) ) {
            const href = el.href;
            // Trang chặn click giả lập (không đi đâu) → mở thẳng địa chỉ.
            setTimeout(( ) => { if ( !leaving && location.href === before ) { location.assign(href); } }, 1500);
        }
    }

    if ( document.readyState === 'complete' ) {
        schedulePlayerPick();
        scheduleNextCheck();
    } else {
        addEventListener('load', ( ) => { schedulePlayerPick(); scheduleNextCheck(); }, { once: true });
    }

    addEventListener('keydown', ev => {
        if ( ev.altKey || ev.ctrlKey || ev.metaKey ) { return; }
        if ( handleKey(ev.key) ) {
            ev.preventDefault();
            ev.stopImmediatePropagation();
        }
    }, true);

    // Phím được chuyển lên từ iframe (focus đang nằm trong trình phát).
    addEventListener('message', ev => {
        const data = ev.data;
        if ( isObj(data) && typeof data[FORWARD_TAG] === 'string' ) {
            handleKey(data[FORWARD_TAG]);
        }
        // iframe báo đang / thôi hiện hộp thoại.
        if ( isObj(data) && typeof data[DIALOG_STATE_TAG] === 'boolean' && ev.source !== null ) {
            if ( data[DIALOG_STATE_TAG] ) { dialogFrames.add(ev.source); } else { dialogFrames.delete(ev.source); }
            if ( current !== null && current.tagName === 'IFRAME' && current.contentWindow === ev.source ) {
                tellFrame(current, 'show');
            }
        }
    });
})();
