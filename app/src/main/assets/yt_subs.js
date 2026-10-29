// YouTube 字幕翻譯：攔下播放器自己下載的字幕檔（/api/timedtext；YouTube 現在要求播放器產生的權杖，自己去抓會被拒），
// 整條字幕先交給 App 翻譯（從目前播放位置開始分批），再用自己的字幕層依播放時間顯示雙語（譯文在上、原文在下）。
//  - App 呼叫 __lqYt.start() 開啟、stop() 關閉；翻好的批次以 __lqYt.apply(id, 譯文陣列) 回填
//  - 還沒有字幕檔時，透過播放器介面自動打開字幕（不必先手動按 CC）
//  - 換影片（YouTube 是單頁應用，網址會變但不重新載入）時丟掉舊字幕
(function () {
  if (window.__lqYt || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  var BATCH = 40;
  var cues = [], key = '', on = false, seq = 0, pending = {}, box = null, raf = 0, sent = 0, done = 0;

  function post(msg) { try { if (window.LiquidBridge) LiquidBridge.postMessage(JSON.stringify(msg)); } catch (e) {} }
  function videoId() { var m = location.search.match(/[?&]v=([\w-]{6,})/) || location.pathname.match(/\/(?:shorts|embed|live)\/([\w-]{6,})/); return m ? m[1] : ''; }

  // json3：{events:[{tStartMs,dDurationMs,segs:[{utf8}]}]}；自動產生字幕是逐字的 segs，合併成一句
  function parseJson3(j) {
    var out = [];
    (j.events || []).forEach(function (e) {
      if (!e.segs) return;
      var t = e.segs.map(function (s) { return s.utf8 || ''; }).join('').replace(/\s+/g, ' ').trim();
      if (!t) return;
      var s = e.tStartMs || 0, d = e.dDurationMs || 2000;
      out.push({ s: s, e: s + d, text: t });
    });
    // 自動字幕的事件會重疊（滾動顯示）：每句結束時間截到下一句開始
    for (var i = 0; i < out.length - 1; i++) if (out[i].e > out[i + 1].s) out[i].e = out[i + 1].s;
    return out;
  }
  // srv3／xml：<p t="毫秒" d="毫秒">…</p> 或舊格式 <text start="秒" dur="秒">…</text>
  function parseXml(txt) {
    var doc = new DOMParser().parseFromString(txt, 'text/xml'), out = [];
    Array.prototype.forEach.call(doc.querySelectorAll('p, text'), function (p) {
      var ms = p.tagName === 'p';
      var s = ms ? +p.getAttribute('t') : +p.getAttribute('start') * 1000;
      var d = ms ? +p.getAttribute('d') : +p.getAttribute('dur') * 1000;
      var t = (p.textContent || '').replace(/\s+/g, ' ').trim();
      if (t) out.push({ s: s || 0, e: (s || 0) + (d || 2000), text: t });
    });
    return out;
  }

  function onTrack(url, body) {
    var list = [];
    try { list = body.trim().charAt(0) === '{' ? parseJson3(JSON.parse(body)) : parseXml(body); } catch (e) { return; }
    if (!list.length) return;
    var lang = (url.match(/[?&]lang=([\w-]+)/) || [])[1] || '';
    var k = videoId() + '|' + lang + '|' + (/[?&]kind=asr/.test(url) ? 'asr' : '');
    if (k === key) return;
    // 播放器切到「自動翻譯」的軌時也會下載：只要原始語言的軌（有 tlang 的略過）
    if (/[?&]tlang=/.test(url) && cues.length) return;
    key = k; cues = list; pending = {}; sent = 0; done = 0;
    if (on) translateAll();
  }

  // 攔截 fetch 與 XHR：只看 timedtext 的回應，其他請求原樣放行
  var isTrack = function (u) { return typeof u === 'string' && u.indexOf('/api/timedtext') >= 0; };
  var origFetch = window.fetch;
  if (origFetch) window.fetch = function (input, init) {
    var u = typeof input === 'string' ? input : (input && input.url) || '';
    var p = origFetch.apply(this, arguments);
    if (isTrack(u)) p.then(function (r) { r.clone().text().then(function (b) { onTrack(u, b); }); }).catch(function () {});
    return p;
  };
  var open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) {
    if (isTrack(String(u))) {
      var url = String(u);
      this.addEventListener('load', function () {
        try { onTrack(url, typeof this.response === 'string' ? this.response : this.responseText); } catch (e) {}
      });
    }
    return open.apply(this, arguments);
  };

  function video() { return document.querySelector('video'); }

  // 從目前播放位置開始送：先送接下來要看的，再補前面的
  function translateAll() {
    var v = video(), now = v ? v.currentTime * 1000 : 0;
    var order = cues.map(function (c, i) { return i; });
    var start = 0; while (start < cues.length && cues[start].e < now) start++;
    order = order.slice(start).concat(order.slice(0, start));
    for (var i = 0; i < order.length; i += BATCH) {
      var idx = order.slice(i, i + BATCH).filter(function (n) { return cues[n].tr == null; });
      if (!idx.length) continue;
      var id = ++seq; pending[id] = { idx: idx, key: key };
      sent++;
      post({ type: 'yt_tr', id: id, texts: idx.map(function (n) { return cues[n].text; }) });
    }
    state();
  }
  function state() { post({ type: 'yt_state', sent: sent, done: done, cues: cues.length }); }

  // 自動打開字幕：播放器介面（#movie_player）載入字幕模組並選第一條軌
  function enableCaptions() {
    var p = document.getElementById('movie_player');
    try {
      if (p && p.loadModule) {
        p.loadModule('captions');
        var list = p.getOption && p.getOption('captions', 'tracklist');
        var cur = p.getOption && p.getOption('captions', 'track');
        if (list && list.length && (!cur || !cur.languageCode)) p.setOption('captions', 'track', list[0]);
        return true;
      }
    } catch (e) {}
    // 沒有播放器介面：按播放器上的字幕鈕
    var b = document.querySelector('.ytp-subtitles-button[aria-pressed="false"], button.ytmClosedCaptioningButtonButton[aria-pressed="false"]');
    if (b) { b.click(); return true; }
    return false;
  }

  function ensureBox() {
    var v = video(); if (!v) return null;
    var host = v.closest('#movie_player, .html5-video-player, #player-container-id, .player-container') || v.parentElement;
    if (box && box.parentElement === host) return box;
    if (box) box.remove();
    box = document.createElement('div');
    box.className = 'notranslate';
    box.style.cssText = 'position:absolute;left:4%;right:4%;bottom:12%;z-index:60;text-align:center;pointer-events:none;' +
      'font:600 16px/1.35 -apple-system,system-ui,sans-serif;color:#fff;text-shadow:0 1px 3px rgba(0,0,0,.9),0 0 2px #000';
    if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
    host.appendChild(box);
    return box;
  }
  var hideNative = null;
  function setNativeHidden(h) {
    if (h && !hideNative) {
      hideNative = document.createElement('style');
      hideNative.textContent = '.ytp-caption-window-container,.caption-window,.captions-text,.ytm-caption-window{opacity:0!important}';
      (document.head || document.documentElement).appendChild(hideNative);
    } else if (!h && hideNative) { hideNative.remove(); hideNative = null; }
  }

  var last = -1;
  function tick() {
    raf = requestAnimationFrame(tick);
    var v = video(); if (!v || !cues.length) return;
    var t = v.currentTime * 1000, i = -1;
    // 二分搜尋目前這句
    var lo = 0, hi = cues.length - 1;
    while (lo <= hi) { var mid = (lo + hi) >> 1; if (cues[mid].s <= t) { i = mid; lo = mid + 1; } else hi = mid - 1; }
    var c = i >= 0 && t < cues[i].e ? cues[i] : null;
    var sig = c ? i + (c.tr != null ? 't' : '') : -1;
    if (sig === last) return;
    last = sig;
    var b = ensureBox(); if (!b) return;
    if (!c) { b.innerHTML = ''; return; }
    var tr = c.tr != null && c.tr !== c.text ? '<div style="font-size:1.12em">' + esc(c.tr) + '</div>' : '';
    b.innerHTML = '<span style="display:inline-block;background:rgba(0,0,0,.55);border-radius:8px;padding:4px 10px">' + tr +
      '<div style="font-weight:500;opacity:.85;font-size:.88em">' + esc(c.text) + '</div></span>';
  }
  function esc(s) { return s.replace(/[&<>]/g, function (ch) { return ch === '&' ? '&amp;' : ch === '<' ? '&lt;' : '&gt;'; }); }

  // 換影片：清掉舊字幕
  var lastVid = videoId();
  setInterval(function () {
    var v = videoId();
    if (v !== lastVid) { lastVid = v; cues = []; key = ''; pending = {}; sent = 0; done = 0; last = -1; if (box) box.innerHTML = ''; if (on) setTimeout(enableCaptions, 1500); }
  }, 1000);

  window.__lqYt = {
    start: function () {
      on = true; last = -1;
      setNativeHidden(true);
      if (!raf) raf = requestAnimationFrame(tick);
      if (cues.length) translateAll();
      else if (!enableCaptions()) post({ type: 'yt_state', sent: 0, done: 0, cues: 0, noCaptions: true });
    },
    stop: function () {
      on = false; setNativeHidden(false);
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      if (box) { box.remove(); box = null; }
    },
    apply: function (id, texts) {
      var b = pending[id]; delete pending[id];
      if (!b || b.key !== key) return;
      b.idx.forEach(function (n, j) { if (cues[n]) cues[n].tr = texts[j]; });
      done++; last = -1; state();
    },
    fail: function (id) { delete pending[id]; done++; state(); },
    active: function () { return on; },
  };
})();
