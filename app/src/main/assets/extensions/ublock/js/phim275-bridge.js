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

*******************************************************************************/

import µb from './background.js';

const NATIVE_APP = 'phim275';
let port = null;

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
    case 'updateFilters':
        µb.scheduleAssetUpdater({ now: true, fetchDelay: 100 });
        return { ok: true };
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
