/*******************************************************************************

    Phim275 ↔ uBlock Origin bridge
    Copyright (C) 2026 Phim275 contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Chèn vào background của uBlock Origin (tools/update_ublock.py) để app
    Phim275 điều khiển uBO qua native messaging của GeckoView:
      - bật/tắt chặn quảng cáo theo từng trang web
      - lấy số yêu cầu đã chặn
      - cập nhật bộ lọc ngay
      - mở công cụ chọn phần tử để ẩn (element picker / zapper)
      - điều khiển video bằng phím media của remote (phát/dừng, tua)
      - chế độ RAM thấp: chuyển sang bộ lọc gọn cho box 1GB

*******************************************************************************/

import µb from './background.js';
import io from './assets.js';

const NATIVE_APP = 'phim275';
let port = null;

// Chế độ RAM thấp (box 1GB): chỉ giữ bộ lọc cốt lõi (~90k luật thay vì ~140k).
const LEAN_LISTS = [
    'user-filters',
    'ublock-filters', 'ublock-badware', 'ublock-privacy', 'ublock-unbreak', 'ublock-quick-fixes',
    'easylist',
    'VIE-1',            // ABPVN — trang Việt
    'phim275-filters',
];
const SAVED_SELECTION_KEY = 'phim275SavedFilterLists';

const sameSet = (a, b) => a.length === b.length && a.every(x => b.includes(x));

/** Chờ uBO nạp xong cấu hình (µb.readyToFilter) — trước đó selectedFilterLists còn rỗng. */
async function waitUntilReady(timeoutMs = 60000) {
    const start = Date.now();
    while ( µb.readyToFilter !== true ) {
        if ( Date.now() - start > timeoutMs ) { throw new Error('uBO chưa sẵn sàng'); }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
}

/** Bật: lưu lựa chọn hiện tại rồi chuyển sang bộ lọc gọn. Tắt: khôi phục lựa chọn đã lưu. */
async function setLowMemoryLists(enabled) {
    await waitUntilReady();
    // Bộ lọc ẩn phần tử chung (generic cosmetic): đo trên máy 1GB tốn ~90MB RAM mà chỉ ẩn thêm
    // các ô quảng cáo trống (chặn tầng mạng vẫn giữ nguyên) → tắt ở chế độ RAM thấp.
    if ( µb.userSettings.ignoreGenericCosmeticFilters !== enabled ) {
        µb.changeUserSettings('ignoreGenericCosmeticFilters', enabled);
    }
    const current = µb.selectedFilterLists.slice();
    let target;
    if ( enabled ) {
        if ( sameSet(current, LEAN_LISTS) ) { return false; }
        // Không bao giờ lưu một lựa chọn rỗng làm "lựa chọn cũ" (khôi phục sẽ làm mất hết bộ lọc).
        if ( current.length !== 0 ) {
            await browser.storage.local.set({ [SAVED_SELECTION_KEY]: current });
        }
        target = LEAN_LISTS;
    } else {
        const bin = await browser.storage.local.get(SAVED_SELECTION_KEY);
        const saved = bin[SAVED_SELECTION_KEY];
        if ( !Array.isArray(saved) || saved.length === 0 || !sameSet(current, LEAN_LISTS) ) { return false; }
        await browser.storage.local.remove(SAVED_SELECTION_KEY);
        target = saved;
    }
    µb.applyFilterListSelection({ toSelect: target });
    µb.loadFilterLists();
    return true;
}

function send(data) {
    if ( port === null ) { return; }
    try {
        port.postMessage(data);
    } catch (ex) {
        console.error('[phim275] postMessage', ex);
    }
}

async function activeTabId() {
    const tabs = await browser.tabs.query({ active: true });
    return tabs.length !== 0 ? tabs[0].id : undefined;
}

async function handle(msg) {
    switch ( msg.type ) {
    case 'getSiteState':
        return { enabled: µb.getNetFilteringSwitch(msg.url) };
    case 'setSiteState':
        µb.toggleNetFilteringSwitch(msg.url, 'site', msg.enabled === true);
        return { enabled: µb.getNetFilteringSwitch(msg.url) };
    case 'stats':
        return {
            blocked: µb.requestStats.blockedCount,
            allowed: µb.requestStats.allowedCount,
        };
    case 'setLowMemoryLists':
        return { changed: await setLowMemoryLists(msg.enabled === true), lists: µb.selectedFilterLists.length };
    case 'updateFilters':
        // uBO chỉ tải lại danh sách đã hết hạn → đánh dấu bộ lọc Phim275 (đổi thường xuyên) là cũ
        // để "Cập nhật bộ lọc ngay" luôn lấy bản mới; các danh sách lớn khác tự cập nhật theo hạn.
        await io.purge(/^phim275-filters$/);
        µb.scheduleAssetUpdater({ now: true, fetchDelay: 100, auto: true });
        return { ok: true };
    case 'media': {
        const tabId = await activeTabId();
        if ( tabId === undefined ) { return { ok: false, error: 'no-active-tab' }; }
        const code = `(${mediaControl.toString()})(${JSON.stringify(String(msg.action))}, ${Number(msg.seconds) || 10})`;
        const results = await browser.tabs.executeScript(tabId, {
            code,
            allFrames: true,
            matchAboutBlank: true,
            runAt: 'document_idle',
        });
        return { ok: Array.isArray(results) && results.some(r => r === true) };
    }
    case 'pickElement': {
        const tabId = await activeTabId();
        if ( tabId === undefined ) { return { ok: false, error: 'no-active-tab' }; }
        await µb.elementPickerExec(tabId, 0, '', msg.zap === true);
        return { ok: true };
    }
    default:
        return { error: `unknown message type: ${msg.type}` };
    }
}

// Chạy trong trang (mọi frame): điều khiển video/audio lớn nhất đang có trong frame đó.
// Trả về true nếu frame này có media để điều khiển.
function mediaControl(action, seconds) {
    const all = Array.from(document.querySelectorAll('video, audio'))
        .filter(m => m.readyState > 0 || m.currentSrc);
    if ( all.length === 0 ) { return false; }
    const area = m => (m.clientWidth || 1) * (m.clientHeight || 1);
    const media = all.sort((a, b) => area(b) - area(a))[0];
    const duration = Number.isFinite(media.duration) ? media.duration : Infinity;
    switch ( action ) {
    case 'toggle':
        if ( media.paused ) { media.play().catch(( ) => {}); } else { media.pause(); }
        break;
    case 'play':
        media.play().catch(( ) => {});
        break;
    case 'pause':
        media.pause();
        break;
    case 'forward':
        media.currentTime = Math.min(duration, media.currentTime + seconds);
        break;
    case 'rewind':
        media.currentTime = Math.max(0, media.currentTime - seconds);
        break;
    default:
        return false;
    }
    return true;
}

function connect() {
    try {
        port = browser.runtime.connectNative(NATIVE_APP);
    } catch (ex) {
        console.error('[phim275] connectNative', ex);
        port = null;
        return;
    }
    port.onMessage.addListener(msg => {
        if ( msg instanceof Object === false ) { return; }
        handle(msg).then(
            data => send({ id: msg.id, ...data }),
            ex => send({ id: msg.id, error: String(ex) }),
        );
    });
    port.onDisconnect.addListener(( ) => {
        port = null;
        setTimeout(connect, 2000);
    });
    send({ type: 'hello', version: browser.runtime.getManifest().version });
}

connect();
