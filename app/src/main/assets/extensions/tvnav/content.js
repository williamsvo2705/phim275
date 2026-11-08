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
 *   Không có kênh tới app (vd. trang chủ nội bộ) → element.click().
 */
(function () {
    'use strict';
    if ( window.__phim275TvNav ) { return; }
    window.__phim275TvNav = true;

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

    let cache = null;
    let current = null;
    let overlay = null;

    new MutationObserver(( ) => { cache = null; }).observe(document.documentElement, {
        childList: true, subtree: true, attributes: true, attributeFilter: [ 'class', 'style', 'hidden', 'href' ],
    });

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
            if ( hit !== el && !el.contains(hit) && !hit.contains(el) ) { return false; }
        }
        return true;
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
        if ( current !== null && (!current.isConnected || !isUsable(current, current.getBoundingClientRect())) ) {
            current = null;
        }
        if ( current === null ) {
            const first = firstVisible(list);
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
        current = el;
        const r = el.getBoundingClientRect();
        if ( r.top < 70 || r.bottom > innerHeight - 70 || r.left < 0 || r.right > innerWidth ) {
            el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
        }
        drawOverlay();
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
            'display:none',
        ].join(';');
        (document.body || document.documentElement).appendChild(overlay);
        return overlay;
    }

    function drawOverlay() {
        if ( current === null || !current.isConnected ) {
            if ( overlay !== null ) { overlay.style.display = 'none'; }
            return;
        }
        const r = current.getBoundingClientRect();
        const o = ensureOverlay();
        const pad = 4;
        o.style.left = `${r.left - pad}px`;
        o.style.top = `${r.top - pad}px`;
        o.style.width = `${r.width + pad * 2}px`;
        o.style.height = `${r.height + pad * 2}px`;
        o.style.display = 'block';
    }

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
        if ( el === null ) { return; }
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
    }

    function isEditing(el) {
        if ( el === null ) { return false; }
        if ( el.isContentEditable || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' ) { return true; }
        return el.tagName === 'INPUT' && !NON_TEXT_INPUT.test(el.type);
    }

    addEventListener('keydown', ev => {
        if ( ev.altKey || ev.ctrlKey || ev.metaKey ) { return; }
        const ae = document.activeElement;
        const editing = isEditing(ae);
        const dir = DIRS[ev.key];
        if ( dir !== undefined ) {
            // Đang gõ chữ: ◀ ▶ di chuyển con trỏ chữ; ▲ ▼ rời khỏi ô (trừ ô nhiều dòng).
            if ( editing && (dir === 'left' || dir === 'right' || ae.tagName === 'TEXTAREA' || ae.isContentEditable) ) { return; }
            if ( editing ) { ae.blur(); }
            ev.preventDefault();
            ev.stopImmediatePropagation();
            move(dir);
            return;
        }
        if ( ev.key === 'Enter' && !editing && current !== null ) {
            ev.preventDefault();
            ev.stopImmediatePropagation();
            activate();
        }
    }, true);
})();
