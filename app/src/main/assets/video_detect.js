// iOS 影片全螢幕接口補丁：網站識別為 iPhone 時，播放器會呼叫 iOS Safari 專用的 video.webkitEnterFullscreen()，
// Android WebView 沒有這個方法，網站偵測不到就什麼都不做。這裡補上（轉成標準的 requestFullscreen），
// 並補上 webkitSupportsFullscreen／webkitDisplayingFullscreen 與 webkitbeginfullscreen／webkitendfullscreen 事件。
(function () {
  var V = window.HTMLVideoElement && HTMLVideoElement.prototype;
  if (!V || V.__lqIosFs) return;
  V.__lqIosFs = true;
  // 全螢幕的對象：整個播放器（往外找跟影片同大小的最外層，例如 Plyr 的 .plyr、16:9 比例框），
  // 網站自己的控制列在這一層裡，會跟著進全螢幕；只包影片的內層（.plyr__video-wrapper）不含控制列
  function target(v) {
    // YouTube：整個播放器（字幕、控制列與 App 的翻譯字幕層都在裡面；只把 <video> 全螢幕會把它們全蓋掉）
    var yt = v.closest && v.closest('#movie_player, .html5-video-player');
    if (yt) return yt;
    var a = v.getBoundingClientRect(), best = v;
    for (var p = v.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      var b = p.getBoundingClientRect();
      // 只往外到「跟影片幾乎同大小」的那層（播放器本體，控制列疊在上面）；更大的外層（含標題、按鈕列）不要
      if (Math.abs(b.width - a.width) > 8 || Math.abs(b.height - a.height) > 8) break;
      best = p;
    }
    return best;
  }
  // 進出全螢幕時 WebView 會讓網頁短暫變成「隱藏」（visibilitychange），有些網站一隱藏就暫停影片：
  // 全螢幕期間與進出前後 2 秒內，網頁看到的一律是「可見」，那次 visibilitychange 也攔掉（App 真的到背景時照常）
  var fsQuietUntil = 0;
  function quiet() { return !!(document.fullscreenElement || document.webkitFullscreenElement) || Date.now() < fsQuietUntil; }
  function mark() { fsQuietUntil = Date.now() + 2000; }
  var D = Document.prototype;
  var hiddenDesc = Object.getOwnPropertyDescriptor(D, 'hidden'), visDesc = Object.getOwnPropertyDescriptor(D, 'visibilityState');
  if (hiddenDesc && hiddenDesc.get) Object.defineProperty(D, 'hidden', { get: function () { return quiet() ? false : hiddenDesc.get.call(this); }, configurable: true });
  if (visDesc && visDesc.get) Object.defineProperty(D, 'visibilityState', { get: function () { return quiet() ? 'visible' : visDesc.get.call(this); }, configurable: true });
  document.addEventListener('visibilitychange', function (e) { if (quiet()) e.stopImmediatePropagation(); }, true);
  window.addEventListener('visibilitychange', function (e) { if (quiet()) e.stopImmediatePropagation(); }, true);
  ['requestFullscreen', 'webkitRequestFullscreen'].forEach(function (n) {
    var o = Element.prototype[n];
    if (o) Element.prototype[n] = function () { mark(); return o.apply(this, arguments); };
  });
  document.addEventListener('fullscreenchange', mark, true);
  // 離開全螢幕那一下也要保護（網站常在那時收到 visibilitychange 而暫停）
  ['exitFullscreen', 'webkitExitFullscreen'].forEach(function (n) {
    var o = Document.prototype[n];
    if (o) Document.prototype[n] = function () { mark(); return o.apply(this, arguments); };
  });

  function enter() {
    // 已經是全螢幕：忽略（播放器判斷錯誤時會重複呼叫，換全螢幕對象會讓畫面變黑）
    if (document.fullscreenElement || document.webkitFullscreenElement) return;
    var t = target(this), req = t.requestFullscreen || t.webkitRequestFullscreen;
    var r = req && req.call(t);
    if (r && r.catch) { var v = this; r.catch(function () { var q = v.requestFullscreen; q && q.call(v); }); }
  }
  function exit() { var f = document.exitFullscreen || document.webkitExitFullscreen; if (f && (document.fullscreenElement || document.webkitFullscreenElement)) f.call(document); }
  if (!V.webkitEnterFullscreen) V.webkitEnterFullscreen = enter;
  if (!V.webkitEnterFullScreen) V.webkitEnterFullScreen = enter;
  if (!V.webkitExitFullscreen) V.webkitExitFullscreen = exit;
  if (!V.webkitExitFullScreen) V.webkitExitFullScreen = exit;
  function fsOf(v) { var f = document.fullscreenElement || document.webkitFullscreenElement; return !!f && (f === v || f.contains(v)); }
  if (!('webkitSupportsFullscreen' in V)) Object.defineProperty(V, 'webkitSupportsFullscreen', { get: function () { return true; }, configurable: true });
  if (!('webkitDisplayingFullscreen' in V)) Object.defineProperty(V, 'webkitDisplayingFullscreen', { get: function () { return fsOf(this); }, configurable: true });
  // 全螢幕時把影片與外面那幾層（固定 16:9 比例框、padding-bottom 撐高的包裝層）暫時撐滿畫面，離開時還原
  var saved = [];
  function fill(f, v) {
    var STYLE = 'width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;padding-top:0!important;padding-bottom:0!important;';
    for (var el = v; el && el !== f.parentElement; el = el.parentElement) {
      saved.push([el, el.getAttribute('style')]);
      el.setAttribute('style', (el.getAttribute('style') || '') + ';' + STYLE + (el === v ? 'object-fit:contain!important;' : ''));
    }
  }
  // 全螢幕時隱藏網站自己的控制列、全螢幕鈕等：影片到全螢幕元素這一路上的「旁支」全部隱藏，改用 App 的控制列（gesture_layer.js）
  var hidden = [];
  function hideSiteControls(f, v) {
    for (var el = v; el && el !== f; el = el.parentElement) {
      var par = el.parentElement;
      if (!par) break;
      Array.prototype.forEach.call(par.children, function (c) {
        if (c === el || (c.classList && c.classList.contains('lq-layer')) || (c.hasAttribute && c.hasAttribute('data-lq-keep')) || /^(STYLE|SCRIPT|LINK)$/.test(c.tagName)) return;
        hidden.push([c, c.style.visibility]);
        c.style.setProperty('visibility', 'hidden', 'important');
      });
    }
  }
  function unfill() {
    saved.forEach(function (p) { if (p[1] == null) p[0].removeAttribute('style'); else p[0].setAttribute('style', p[1]); });
    saved = [];
    hidden.forEach(function (p) { p[0].style.removeProperty('visibility'); if (p[1]) p[0].style.visibility = p[1]; });
    hidden = [];
  }
  // iOS 播放器靠這兩個事件更新按鈕狀態
  var last = null;
  document.addEventListener('fullscreenchange', function () {
    var f = document.fullscreenElement;
    if (f) {
      unfill();
      last = f.tagName === 'VIDEO' ? f : f.querySelector('video');
      // 全螢幕看這部時，網頁上其他還在播的影片（廣告預覽、另一部）一起暫停：同時解碼好幾部影片會讓全螢幕播放卡頓。
      // 全螢幕的是 iframe（嵌入的播放器）時 last 為 null：這一層的影片全部暫停，iframe 裡的自己處理
      Array.prototype.forEach.call(document.querySelectorAll('video'), function (o) { if (o !== last && !o.paused) try { o.pause(); } catch (e) {} });
      if (last) {
        if (f !== last) { fill(f, last); hideSiteControls(f, last); }
        last.dispatchEvent(new Event('webkitbeginfullscreen'));
      }
    } else {
      unfill();
      if (last) { last.dispatchEvent(new Event('webkitendfullscreen')); last = null; }
    }
  }, true);
})();

// 影片偵測：用 MutationObserver 找出頁面上的 <video>。
// 每部有來源的影片交給 JS 手勢層（gesture_layer.js）記下；第一次全螢幕時才建立手勢與控制層。
// （以前在影片右上角疊「用原生播放器播放」與下載鈕，已停用、程式碼也拿掉了：一律用網頁自己的播放器，下載用 App 底部的「下載影片」）
(function () {
  if (window.__liquidDetect) return;
  window.__liquidDetect = true;
  function sourceOf(v) {
    var s = v.currentSrc || v.src || '';
    if (!s) {
      var el = v.querySelector('source[src]');
      if (el) s = el.src;
    }
    return s;
  }

  function check(v) {
    if (sourceOf(v) && window.__liquidGestures) window.__liquidGestures.attach(v);
  }

  function watch(v) {
    if (v.__lqWatched) return;
    v.__lqWatched = true;
    ['loadedmetadata', 'loadstart', 'play', 'emptied'].forEach(function (t) {
      v.addEventListener(t, function () { check(v); });
    });
    check(v);
  }

  function scan(root) {
    if (root.tagName === 'VIDEO') watch(root);
    if (root.querySelectorAll) root.querySelectorAll('video').forEach(watch);
  }

  function start() {
    scan(document);
    new MutationObserver(function (list) {
      list.forEach(function (m) {
        m.addedNodes.forEach(function (n) { if (n.nodeType === 1) scan(n); });
        if (m.type === 'attributes' && m.target.tagName === 'VIDEO') check(m.target);
      });
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();

// 蓋在影片上的透明廣告層：App 擋下這次點擊造成的廣告跳轉／彈窗後呼叫 __lqUnblock()，
// 讓剛才點到的那一層變成點擊穿透，下一下就點得到真正的影片（叫出控制列、全螢幕）。
// 只處理「蓋住影片一半以上、本身沒有圖示或文字」的元素，播放鍵這類有內容的不動。
(function () {
  if (window.__lqTapTrack) return;
  window.__lqTapTrack = true;
  window.addEventListener('pointerdown', function (e) {
    window.__lqLastDown = e.target;
    // 點在影片畫面上：告訴 App，這一下若開出彈窗，幾乎一定是蓋在影片上的廣告（看影片的人不會想開新視窗）
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.width > 120 && r.height > 80 && e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) {
        try { if (window.LiquidBridge) LiquidBridge.postMessage('{"type":"vtap"}'); } catch (x) {}
        break;
      }
    }
  }, true);
  function overlap(a, b) {
    var w = Math.min(a.right, b.right) - Math.max(a.left, b.left), h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
    return w > 0 && h > 0 ? (w * h) / Math.max(1, b.width * b.height) : 0;
  }
  window.__lqUnblock = function () {
    var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
    var n = 0;
    for (var el = window.__lqLastDown; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
      if (el.tagName === 'VIDEO' || el.querySelector('video')) break; // 到了播放器本身就停
      var r = el.getBoundingClientRect();
      var covers = videos.some(function (v) { return overlap(r, v.getBoundingClientRect()) > 0.5; });
      var empty = !el.querySelector('img,svg,canvas,button,input') && !(el.innerText || '').trim();
      if (covers && (empty || el.tagName === 'A')) { el.style.setProperty('pointer-events', 'none', 'important'); n++; }
    }
    return n;
  };
})();

// 下拉重新整理的防誤觸：手指按下的元素自己處理拖曳（地圖、畫布：touch-action 不含 pan-y）、
// 或位在還沒捲到頂的內層捲動區、或網頁攔下了 touchmove，就通知 App 這次手勢不要觸發重新整理
(function () {
  if (window.__lqPullGuard) return;
  window.__lqPullGuard = true;
  var sent = false;
  function nopull() {
    if (sent) return;
    sent = true;
    try { if (window.LiquidBridge) LiquidBridge.postMessage('{"type":"nopull"}'); } catch (e) {}
  }
  function blocks(el) {
    if (window !== window.top && (window.scrollY || 0) > 0) return true;
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
      var cs = getComputedStyle(n), ta = cs.touchAction;
      if (ta && ta !== 'auto' && ta !== 'manipulation' && ta.indexOf('pan-y') < 0) return true;
      var oy = cs.overflowY;
      if ((oy === 'auto' || oy === 'scroll') && n.scrollTop > 0 && n.scrollHeight > n.clientHeight + 1) return true;
    }
    return false;
  }
  addEventListener('touchstart', function (e) {
    sent = false;
    if (e.touches.length === 1 && blocks(e.target)) nopull();
  }, { passive: true, capture: true });
  addEventListener('touchmove', function (e) { if (e.defaultPrevented) nopull(); }, { passive: true });
})();
