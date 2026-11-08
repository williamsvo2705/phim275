// Phim275 TV Navigation — chuyển yêu cầu "chạm" từ content script sang app (native messaging).
// GPLv3 — https://github.com/williamsvo2705/phim275
'use strict';

browser.runtime.onMessage.addListener(msg => {
    if ( msg instanceof Object === false || msg.type !== 'tap' ) { return; }
    return browser.runtime.sendNativeMessage('phim275tv', msg).catch(( ) => false);
});
