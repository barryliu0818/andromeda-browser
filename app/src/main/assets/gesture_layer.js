// JS 手勢層：疊在網頁 <video> 上的透明層，以 JS 直接控制 playbackRate 與 currentTime。
// 行為與原生端 VideoGestures.kt 一致（數值見 SPEC）。動畫只用 requestAnimationFrame 與 CSS transform／opacity。
(function () {
  if (window.__liquidGestures) return;
  // 網站要求 Trusted Types（例如 YouTube 的 Chromium 版）時 innerHTML 只收 TrustedHTML：用本腳本自己的政策包一層
  // （只用在腳本自己組的固定介面，不放網頁內容；網站本身的保護不受影響）
  var TT = null;
  try { if (window.trustedTypes && trustedTypes.createPolicy) TT = trustedTypes.createPolicy('lq-gesture', { createHTML: function (s) { return s; } }); } catch (e) {}
  function H(s) { return TT ? TT.createHTML(s) : s; }

  var SPEC = {
    longPressMs: 400,
    doubleTapMs: 280,
    accumulateMs: 700,
    seekStep: 10,          // 秒
    sideZone: 0.35,
    scrubThreshold: 16,    // CSS px（約等於 16dp）
    verticalThreshold: 16,
    screenWidthSec: 90,
    boost: 2.0
  };

  // 與原生玻璃一致的樣式：模糊＋飽和度＋半透明深色＋上緣亮邊
  var css =
    '.lq-layer{position:absolute;z-index:2147483646;pointer-events:none;overflow:hidden}' +
    '.lq-glass{position:absolute;color:#fff;font:600 15px/1.2 system-ui,sans-serif;background:rgba(20,20,22,.32);' +
    '-webkit-backdrop-filter:blur(22px) saturate(1.4);backdrop-filter:blur(22px) saturate(1.4);' +
    'box-shadow:inset 0 1px 0 rgba(255,255,255,.35),inset 0 -1px 0 rgba(0,0,0,.2),0 6px 24px rgba(0,0,0,.18);' +
    'opacity:0;transition:opacity .2s,transform .28s cubic-bezier(.2,.9,.3,1.2);pointer-events:none;white-space:nowrap}' +
    '.lq-pill{left:50%;top:max(16px,11%);padding:7px 16px;border-radius:999px;transform:translate(-50%,-140%)}' +
    '.lq-pill.on{opacity:1;transform:translate(-50%,0)}' +
    '.lq-scrub{left:50%;top:50%;padding:10px 20px;border-radius:18px;text-align:center;transform:translate(-50%,-50%) scale(.85)}' +
    '.lq-scrub.on{opacity:1;transform:translate(-50%,-50%) scale(1)}' +
    '.lq-scrub small{display:block;font-weight:400;font-size:13px;opacity:.75}' +
    '.lq-ripple{position:absolute;top:0;bottom:0;width:38%;overflow:hidden;opacity:0;transition:opacity .2s;pointer-events:none;' +
    'display:flex;align-items:center;justify-content:center;flex-direction:column;color:#fff;font:600 14px system-ui,sans-serif}' +
    '.lq-ripple.on{opacity:1}' +
    '.lq-ripple::before{content:"";position:absolute;top:-15%;bottom:-15%;width:200%;border-radius:50%;background:rgba(255,255,255,.10)}' +
    '.lq-ripple.r{right:0}.lq-ripple.r::before{left:0}.lq-ripple.l{left:0}.lq-ripple.l::before{right:0}' +
    '.lq-wave{position:absolute;border-radius:50%;background:rgba(255,255,255,.18);transform:scale(0);pointer-events:none}' +
    '.lq-arrows{position:relative;letter-spacing:-3px;font-size:13px}.lq-arrows span{opacity:.25}' +
    '.lq-ripple.l .lq-arrows{transform:scaleX(-1)}' +
    '.lq-sec{position:relative;margin-top:6px;font-variant-numeric:tabular-nums}' +
    // 全螢幕控制層（取代網站自己的控制列）：上下漸層遮罩讓文字在任何畫面上都清楚；
    // 只有小元件用毛玻璃（整片模糊影片很耗 GPU，叫出控制列時會卡）；觸控目標都 ≥ 44px
    '.lq-ui{position:absolute;inset:0;pointer-events:none;opacity:0;transition:opacity .18s ease-out}' +
    '.lq-ui.on{opacity:1}.lq-ui.on .lq-hit{pointer-events:auto}' +
    '.lq-top,.lq-bottom{position:absolute;left:0;right:0;display:flex;align-items:center;transition:transform .22s cubic-bezier(.2,.9,.3,1)}' +
    '.lq-top{top:0;padding:max(30px,7%) 16px 40px;gap:10px;background:linear-gradient(rgba(0,0,0,.55),rgba(0,0,0,0));transform:translateY(-10px)}' +
    '.lq-bottom{bottom:0;flex-direction:column;align-items:stretch;padding:40px 14px max(12px,3%);background:linear-gradient(rgba(0,0,0,0),rgba(0,0,0,.6));transform:translateY(10px)}' +
    '.lq-ui.on .lq-top,.lq-ui.on .lq-bottom{transform:none}' +
    '.lq-round{width:44px;height:44px;border-radius:50%;display:flex;align-items:center;justify-content:center;flex:none;' +
    'background:rgba(255,255,255,.16);-webkit-backdrop-filter:blur(16px) saturate(1.5);backdrop-filter:blur(16px) saturate(1.5);' +
    'box-shadow:inset 0 1px 0 rgba(255,255,255,.3);transition:transform .15s,background .15s}' +
    '.lq-round:active{transform:scale(.92);background:rgba(255,255,255,.28)}.lq-round svg{width:22px;height:22px;fill:#fff}' +
    // 右上角倍速下拉：膠囊按鈕＋往下展開的毛玻璃選單（最快 5 倍）
    '.lq-rate{position:relative;margin-left:auto}' +
    '.lq-rate .lq-speed svg{width:14px;height:14px;fill:#fff;margin-left:2px;transition:transform .2s}' +
    '.lq-rate.open .lq-speed svg{transform:rotate(180deg)}' +
    '.lq-menu{position:absolute;right:0;top:44px;min-width:128px;padding:6px;border-radius:18px;background:rgba(28,28,30,.55);' +
    '-webkit-backdrop-filter:blur(24px) saturate(1.6);backdrop-filter:blur(24px) saturate(1.6);box-shadow:inset 0 1px 0 rgba(255,255,255,.2),0 10px 30px rgba(0,0,0,.35);' +
    'opacity:0;transform:scale(.9) translateY(-6px);transform-origin:top right;transition:opacity .16s,transform .2s cubic-bezier(.2,.9,.3,1.2);pointer-events:none}' +
    '.lq-rate.open .lq-menu{opacity:1;transform:none;pointer-events:auto}' +
    '.lq-opt{height:40px;padding:0 12px;border-radius:12px;display:flex;align-items:center;justify-content:space-between;gap:16px;color:#fff;font:500 15px system-ui,sans-serif}' +
    '.lq-opt:active{background:rgba(255,255,255,.14)}.lq-opt svg{width:18px;height:18px;fill:#0A84FF;visibility:hidden}.lq-opt.sel svg{visibility:visible}' +
    '.lq-track{height:28px;display:flex;align-items:center;touch-action:none}' +
    '.lq-track i{position:relative;flex:1;height:3px;border-radius:2px;background:rgba(255,255,255,.28);transition:height .15s}' +
    '.lq-track.drag i{height:6px}' +
    '.lq-track u{position:absolute;left:0;top:0;bottom:0;border-radius:2px;background:rgba(255,255,255,.35)}' +
    '.lq-track b{position:absolute;left:0;top:0;bottom:0;border-radius:2px;background:#fff}' +
    '.lq-track b::after{content:"";position:absolute;right:-6px;top:50%;width:12px;height:12px;margin-top:-6px;border-radius:50%;background:#fff;' +
    'box-shadow:0 1px 4px rgba(0,0,0,.35);transform:scale(.75);transition:transform .15s}' +
    '.lq-track.drag b::after{transform:scale(1.25)}' +
    '.lq-row{display:flex;align-items:center;gap:8px;margin-top:2px}' +
    '.lq-btn{width:44px;height:44px;border-radius:50%;display:flex;align-items:center;justify-content:center;flex:none;transition:transform .15s,background .15s}' +
    '.lq-btn:active{transform:scale(.9);background:rgba(255,255,255,.16)}.lq-btn svg{width:26px;height:26px;fill:#fff}' +
    '.lq-time{color:#fff;font:500 13px system-ui,sans-serif;font-variant-numeric:tabular-nums;opacity:.92;text-shadow:0 1px 3px rgba(0,0,0,.4)}' +
    '.lq-speed{min-width:60px;height:36px;padding:0 12px;border-radius:999px;display:flex;align-items:center;justify-content:center;color:#fff;' +
    'font:600 13px system-ui,sans-serif;background:rgba(255,255,255,.16);box-shadow:inset 0 1px 0 rgba(255,255,255,.25);transition:transform .15s}' +
    '.lq-speed:active{transform:scale(.92)}' +
    // 右下角下載鈕與二次確認卡片
    '.lq-dl{margin-left:auto}' +
    '.lq-confirm{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.35);opacity:0;pointer-events:none;transition:opacity .16s}' +
    '.lq-confirm.open{opacity:1;pointer-events:auto}' +
    '.lq-card{width:min(300px,78%);padding:20px 18px 14px;border-radius:24px;text-align:center;color:#fff;background:rgba(28,28,30,.6);' +
    '-webkit-backdrop-filter:blur(28px) saturate(1.6);backdrop-filter:blur(28px) saturate(1.6);box-shadow:inset 0 1px 0 rgba(255,255,255,.2),0 16px 40px rgba(0,0,0,.4);' +
    'transform:scale(.9);transition:transform .22s cubic-bezier(.2,.9,.3,1.2)}' +
    '.lq-confirm.open .lq-card{transform:none}' +
    '.lq-card h4{margin:0 0 6px;font:600 17px system-ui,sans-serif}.lq-card p{margin:0 0 16px;font:400 14px/1.4 system-ui,sans-serif;opacity:.75}' +
    '.lq-acts{display:flex;gap:10px}.lq-acts div{flex:1;height:44px;border-radius:14px;display:flex;align-items:center;justify-content:center;font:600 15px system-ui,sans-serif}' +
    '.lq-acts .no{background:rgba(255,255,255,.14)}.lq-acts .yes{background:#0A84FF}.lq-acts div:active{opacity:.75}' +
    // 暫停時畫面中央的大播放鈕
    '.lq-center{position:absolute;left:50%;top:50%;width:72px;height:72px;margin:-36px 0 0 -36px;transform:scale(.8);opacity:0;' +
    'transition:transform .2s cubic-bezier(.2,.9,.3,1.2),opacity .18s}' +
    '.lq-center.show{transform:scale(1);opacity:1}.lq-center svg{width:34px;height:34px;margin-left:3px}';

  function injectStyle(root) {
    if (root.querySelector && root.querySelector('style[data-lq]')) return;
    var s = document.createElement('style');
    s.setAttribute('data-lq', '');
    s.textContent = css;
    (root.head || root).appendChild(s);
  }

  function fmt(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return h ? h + ':' + p(m) + ':' + p(s) : m + ':' + p(s);
  }

  function haptic() {
    try { if (window.LiquidBridge) LiquidBridge.postMessage(JSON.stringify({ type: 'haptic' })); } catch (e) {}
  }

  // 依序閃動的三個箭頭（rAF 驅動）
  function animateArrows(el, until) {
    var spans = el.querySelectorAll('span');
    function frame(t) {
      var phase = (t % 750) / 250;
      for (var i = 0; i < 3; i++) spans[i].style.opacity = Math.max(.25, 1 - Math.abs(phase - i - .5) / 1.5);
      if (performance.now() < until()) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  function attach(video) {
    if (video.__lq) return;
    video.__lq = true;
    injectStyle(document);

    var layer = document.createElement('div');
    layer.className = 'lq-layer';
    var pill = document.createElement('div');
    pill.className = 'lq-glass lq-pill';
    var PILL_HTML = '2x <span class="lq-arrows"><span>▶</span><span>▶</span><span>▶</span></span>';
    pill.innerHTML = H(PILL_HTML);
    var scrub = document.createElement('div');
    scrub.className = 'lq-glass lq-scrub';
    var rippleL = document.createElement('div');
    rippleL.className = 'lq-ripple l';
    var rippleR = document.createElement('div');
    rippleR.className = 'lq-ripple r';
    [rippleL, rippleR].forEach(function (r) {
      r.innerHTML = H('<div class="lq-arrows"><span>▶</span><span>▶</span><span>▶</span></div><div class="lq-sec"></div>');
    });
    var ICON_PLAY = '<svg viewBox="0 0 24 24"><path d="M8 5.5v13l10.5-6.5z"/></svg>';
    var ICON_PAUSE = '<svg viewBox="0 0 24 24"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/></svg>';
    var ICON_CLOSE = '<svg viewBox="0 0 24 24"><path d="M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z"/></svg>';
    var SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4, 5];
    var ICON_DL = '<svg viewBox="0 0 24 24"><path d="M11 4h2v8.2l3.1-3.1 1.4 1.4L12 16l-5.5-5.5 1.4-1.4 3.1 3.1zM5 18h14v2H5z"/></svg>';
    // 依網頁語言顯示確認文字（App 介面語言傳不進網頁，用瀏覽器語言判斷）
    var lang = (navigator.language || '').toLowerCase();
    var T = lang.indexOf('zh') === 0
      ? (/(cn|hans|sg)/.test(lang) ? { title: '下载这个视频？', body: '会以最高画质下载到手机的「下载」文件夹', no: '取消', yes: '下载', ok: '已开始下载' }
                                   : { title: '下載這部影片？', body: '會以最高畫質下載到手機的「下載」資料夾', no: '取消', yes: '下載', ok: '已開始下載' })
      : lang.indexOf('ja') === 0 ? { title: 'この動画をダウンロードしますか？', body: '最高画質で「ダウンロード」フォルダに保存します', no: 'キャンセル', yes: 'ダウンロード', ok: 'ダウンロードを開始しました' }
      : { title: 'Download this video?', body: 'Saves the highest quality to your Downloads folder', no: 'Cancel', yes: 'Download', ok: 'Download started' };
    var ICON_DOWN = '<svg viewBox="0 0 24 24"><path d="M7 9.5 12 14.5 17 9.5z"/></svg>';
    var ICON_CHECK = '<svg viewBox="0 0 24 24"><path d="M9.5 16.2 5.3 12l-1.4 1.4 5.6 5.6L20.1 8.4 18.7 7z"/></svg>';
    var ui = document.createElement('div');
    ui.className = 'lq-ui';
    ui.innerHTML = H(
      '<div class="lq-top"><div class="lq-round lq-hit lq-close">' + ICON_CLOSE + '</div>' +
      '<div class="lq-rate lq-hit"><div class="lq-speed">1x' + ICON_DOWN + '</div><div class="lq-menu">' +
      SPEEDS.map(function (r) { return '<div class="lq-opt" data-r="' + r + '">' + r + 'x' + ICON_CHECK + '</div>'; }).join('') + '</div></div></div>' +
      '<div class="lq-round lq-hit lq-center">' + ICON_PLAY + '</div>' +
      '<div class="lq-bottom"><div class="lq-track lq-hit"><i><u></u><b></b></i></div>' +
      '<div class="lq-row"><div class="lq-btn lq-hit lq-pp"></div><div class="lq-time"></div><div class="lq-btn lq-hit lq-dl">' + ICON_DL + '</div></div></div>' +
      '<div class="lq-confirm"><div class="lq-card"><h4>' + T.title + '</h4><p>' + T.body + '</p>' +
      '<div class="lq-acts"><div class="no">' + T.no + '</div><div class="yes">' + T.yes + '</div></div></div></div>');
    layer.append(rippleL, rippleR, pill, scrub, ui);
    var $ = function (q) { return ui.querySelector(q); };
    // App 控制層上的按下事件不讓網站播放器收到（它可能在外層攔觸控、切換自己的狀態）
    ['pointerdown', 'touchstart', 'mousedown'].forEach(function (t) {
      ui.addEventListener(t, function (e) { if (e.target.closest && e.target.closest('.lq-hit,.lq-confirm.open')) e.stopPropagation(); });
    });
    var barTimer = null, barTick = null, seeking = false;
    function refreshBar() {
      $('.lq-pp').innerHTML = H(video.paused ? ICON_PLAY : ICON_PAUSE);
      $('.lq-dl').style.display = canDownload() ? '' : 'none';
      $('.lq-center').classList.toggle('show', video.paused);
      $('.lq-time').textContent = fmt(video.currentTime) + ' / ' + fmt(video.duration);
      $('.lq-speed').innerHTML = H(video.playbackRate + 'x' + ICON_DOWN);
      Array.prototype.forEach.call(ui.querySelectorAll('.lq-opt'), function (o) { o.classList.toggle('sel', +o.dataset.r === video.playbackRate); });
      var d = video.duration || 0;
      if (d && video.buffered && video.buffered.length) $('.lq-track u').style.width = (video.buffered.end(video.buffered.length - 1) / d * 100) + '%';
      if (!seeking) $('.lq-track b').style.width = (d ? video.currentTime / d * 100 : 0) + '%';
    }
    function showBar() {
      refreshBar();
      ui.classList.add('on');
      clearInterval(barTick); barTick = setInterval(refreshBar, 250);
      keepBar();
    }
    function hideBar() { if (seeking || rate.classList.contains('open') || confirmBox.classList.contains('open')) return; ui.classList.remove('on'); clearInterval(barTick); }
    function keepBar() { clearTimeout(barTimer); barTimer = setTimeout(function () { if (!video.paused) hideBar(); }, 3000); }
    var barOn = function () { return ui.classList.contains('on'); };
    function togglePlay(e) { e.stopPropagation(); video.paused ? video.play() : video.pause(); refreshBar(); keepBar(); }
    $('.lq-pp').addEventListener('pointerup', togglePlay);
    $('.lq-center').addEventListener('pointerup', togglePlay);
    // 下載：影片有直接網址，或 App 偵測到這頁有可下載的串流（__lqHasMedia）才顯示下載鈕；按下先二次確認
    function canDownload() { return /^https?:/i.test(video.currentSrc || video.src || '') || !!window.__lqHasMedia; }
    var confirmBox = $('.lq-confirm');
    function bridge(m) { try { if (window.LiquidBridge) LiquidBridge.postMessage(JSON.stringify(m)); } catch (e) {} }
    $('.lq-dl').addEventListener('pointerup', function (e) { e.stopPropagation(); confirmBox.classList.add('open'); clearTimeout(barTimer); });
    confirmBox.querySelector('.no').addEventListener('pointerup', function (e) { e.stopPropagation(); confirmBox.classList.remove('open'); keepBar(); });
    confirmBox.addEventListener('pointerup', function (e) { if (e.target === confirmBox) { confirmBox.classList.remove('open'); keepBar(); } });
    confirmBox.querySelector('.yes').addEventListener('pointerup', function (e) {
      e.stopPropagation();
      confirmBox.classList.remove('open');
      var src = video.currentSrc || video.src || '';
      if (/^https?:/i.test(src)) bridge({ type: 'download', src: src, title: document.title });
      else bridge({ type: 'download_best' });
      // 簡短提示（全螢幕時看不到 App 的提示訊息）
      pill.innerHTML = H(T.ok); pill.classList.add('on');
      setTimeout(function () { pill.classList.remove('on'); setTimeout(function () { pill.innerHTML = H(PILL_HTML); }, 300); }, 1500);
      keepBar();
    });

    // 倍速下拉：打開時控制層不自動收起；選了就關閉
    var rate = $('.lq-rate');
    $('.lq-speed').addEventListener('pointerup', function (e) {
      e.stopPropagation();
      rate.classList.toggle('open');
      if (rate.classList.contains('open')) clearTimeout(barTimer); else keepBar();
    });
    Array.prototype.forEach.call(ui.querySelectorAll('.lq-opt'), function (o) {
      o.addEventListener('pointerup', function (e) {
        e.stopPropagation();
        video.playbackRate = +o.dataset.r;
        rate.classList.remove('open');
        refreshBar(); keepBar();
      });
    });
    $('.lq-close').addEventListener('pointerup', function (e) {
      e.stopPropagation();
      var f = document.exitFullscreen || document.webkitExitFullscreen;
      if (f) f.call(document);
    });
    // 進度條：點或拖到哪就跳到哪；拖動時變粗、圓點放大
    var track = $('.lq-track'), trackI = track.querySelector('i');
    function seekTo(e) {
      var r = trackI.getBoundingClientRect();
      var p = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
      trackI.querySelector('b').style.width = (p * 100) + '%';
      $('.lq-time').textContent = fmt(p * (video.duration || 0)) + ' / ' + fmt(video.duration);
      return p;
    }
    track.addEventListener('pointerdown', function (e) { e.stopPropagation(); seeking = true; track.classList.add('drag'); track.setPointerCapture(e.pointerId); seekTo(e); clearTimeout(barTimer); });
    track.addEventListener('pointermove', function (e) { if (seeking) seekTo(e); });
    track.addEventListener('pointerup', function (e) {
      if (!seeking) return;
      var p = seekTo(e); seeking = false; track.classList.remove('drag');
      if (video.duration) video.currentTime = p * video.duration;
      keepBar();
    });
    track.addEventListener('pointercancel', function () { seeking = false; track.classList.remove('drag'); keepBar(); });
    video.addEventListener('pause', function () { if (fullscreen()) showBar(); });
    // 進入全螢幕先顯示控制層，離開時收起
    document.addEventListener('fullscreenchange', function () {
      var f = document.fullscreenElement;
      if (f && (f === video || f.contains(video))) setTimeout(showBar, 80); else hideBar();
    });

    // 手勢層跟隨影片的位置與大小；全螢幕時放進全螢幕元素內
    function host() {
      var fs = document.fullscreenElement || document.webkitFullscreenElement;
      return fs && fs !== video && fs.contains(video) ? fs : document.body;
    }
    function place() {
      if (!video.isConnected) { layer.remove(); return; }
      var h = host();
      if (layer.parentNode !== h) h.appendChild(layer);
      var r = video.getBoundingClientRect();
      var base = h === document.body ? { left: -window.scrollX, top: -window.scrollY } : h.getBoundingClientRect();
      layer.style.left = (r.left - base.left) + 'px';
      layer.style.top = (r.top - base.top) + 'px';
      layer.style.width = r.width + 'px';
      layer.style.height = r.height + 'px';
      layer.style.display = r.width > 40 && r.height > 40 ? 'block' : 'none';
    }
    place();
    var ro = window.ResizeObserver ? new ResizeObserver(place) : null;
    if (ro) ro.observe(video);
    window.addEventListener('scroll', place, { passive: true });
    window.addEventListener('resize', place);
    document.addEventListener('fullscreenchange', function () { setTimeout(place, 50); });
    setInterval(place, 1000); // 版面被頁面腳本改動時的保險

    var pendingTap = null, lastTapAt = 0, lastZone = 0, accumulating = false, accumulated = 0, rippleTimer = null;
    var prevRate = 1;

    function zoneOf(x) {
      var w = layer.clientWidth;
      return x < w * SPEC.sideZone ? -1 : x > w * (1 - SPEC.sideZone) ? 1 : 0;
    }

    function showSeek(zone, x, y) {
      accumulated += 1;
      video.currentTime = Math.max(0, Math.min((video.duration || 1e9), video.currentTime + zone * SPEC.seekStep));
      var r = zone > 0 ? rippleR : rippleL;
      var sec = r.querySelector('.lq-sec');
      sec.textContent = (accumulated * SPEC.seekStep) + ' 秒';
      // 秒數累加時輕微彈跳
      sec.animate && sec.animate([{ transform: 'scale(1.25)' }, { transform: 'scale(1)' }], { duration: 280, easing: 'cubic-bezier(.2,.9,.3,1.3)' });
      // 從點擊位置擴散的漣漪
      var wave = document.createElement('div');
      wave.className = 'lq-wave';
      var rr = r.getBoundingClientRect(), lr = layer.getBoundingClientRect();
      var size = Math.hypot(rr.width, rr.height) * 2;
      wave.style.width = wave.style.height = size + 'px';
      wave.style.left = (x - (rr.left - lr.left) - size / 2) + 'px';
      wave.style.top = (y - size / 2) + 'px';
      r.appendChild(wave);
      wave.animate([{ transform: 'scale(0)', opacity: 1 }, { transform: 'scale(1)', opacity: 0 }], { duration: 450, easing: 'ease-out' })
        .onfinish = function () { wave.remove(); };
      r.classList.add('on');
      var until = performance.now() + SPEC.accumulateMs + 200;
      animateArrows(r.querySelector('.lq-arrows'), function () { return until; });
      clearTimeout(rippleTimer);
      rippleTimer = setTimeout(function () {
        rippleL.classList.remove('on'); rippleR.classList.remove('on');
        accumulating = false; accumulated = 0;
      }, SPEC.accumulateMs + 200);
    }

    // 觸控在影片範圍內嗎（clientX/Y）
    function inside(t) {
      // 全螢幕時整個畫面都算（包括影片外的黑邊）
      var fs = document.fullscreenElement || document.webkitFullscreenElement;
      if (fs && (fs === video || fs.contains(video))) return true;
      if (layer.style.display === 'none') return false;
      var r = video.getBoundingClientRect();
      return t.clientX >= r.left && t.clientX <= r.right && t.clientY >= r.top && t.clientY <= r.bottom;
    }
    function fullscreen() { return !!(document.fullscreenElement || document.webkitFullscreenElement); }

    // 只在全螢幕時啟用，而且只「監聽」觸控、不攔截：單擊照常交給網站（網站自己的控制列都能用），
    // 雙擊（暫停／左右快轉）、長按（2 倍速）、左右拖動（調進度）、上下拖動（亮度／音量）由這裡處理
    // 回傳 true＝這一下被當成雙擊／連擊處理了（呼叫端會吃掉這次點擊，網站收不到，不會觸發它自己的雙擊切換全螢幕）
    function onTap(x, at) {
      var zone = zoneOf(x), handled = false;
      if (accumulating && zone !== 0 && zone === lastZone && at - lastTapAt < SPEC.accumulateMs) {
        showSeek(zone, x, layer.clientHeight / 2); handled = true;
      } else if (at - lastTapAt < SPEC.doubleTapMs && zone === lastZone) {
        if (zone === 0) { video.paused ? video.play() : video.pause(); }
        else { accumulating = true; accumulated = 0; showSeek(zone, x, layer.clientHeight / 2); }
        handled = true;
      } else {
        accumulating = false;
        // 單擊：等一下確定不是雙擊，再叫出／收起控制列
        clearTimeout(pendingTap);
        if (!barOn()) showBar();
        else pendingTap = setTimeout(function () { pendingTap = null; hideBar(); }, SPEC.doubleTapMs);
      }
      if (handled) { clearTimeout(pendingTap); pendingTap = null; }
      lastTapAt = zone === 0 && handled ? 0 : at; lastZone = zone; // 中間雙擊後重新計算，第三下不算
      return handled;
    }

    // 長按時不要跳出瀏覽器的內容選單或選取文字
    window.addEventListener('contextmenu', function (e) { if (down && down.mode === 'boost') e.preventDefault(); }, true);
    // 全螢幕時雙擊由我們處理（暫停／快轉）：不讓網站收到 dblclick，否則長按放開後再點一下，
    // 網站播放器（Plyr 等）會當成雙擊而退出全螢幕
    window.addEventListener('dblclick', function (e) {
      if (!fullscreen()) return;
      e.stopImmediatePropagation();
      if (e.cancelable) e.preventDefault();
    }, true);

    var down = null;
    window.addEventListener('touchstart', function (e) {
      if (e.touches.length !== 1) { cancel(); return; }
      // 沒有全螢幕就沒有任何手勢：一般瀏覽時影片完全照網站原本的方式操作
      if (!fullscreen()) return;
      var t = e.touches[0];
      if (!inside(t)) return;
      // 點在 App 控制層的按鈕、進度條上：交給按鈕，不當成手勢。全螢幕時網站自己的控制列已隱藏，不必排除
      // （之前用 [class*=control] 排除網站元素，播放器外層的 plyr--hide-controls 也會中，整片點擊被忽略）
      var tg = e.target;
      if (tg && tg.closest && tg.closest('.lq-hit,.lq-confirm.open')) return;
      var lr = layer.getBoundingClientRect();
      down = { x: t.clientX - lr.left, y: t.clientY - lr.top, mode: 'wait' };
      down.timer = setTimeout(function () {
        if (!down || down.mode !== 'wait') return;
        // 長按：2 倍速
        down.mode = 'boost';
        prevRate = video.playbackRate;
        video.playbackRate = SPEC.boost;
        pill.classList.add('on');
        haptic();
        animateArrows(pill.querySelector('.lq-arrows'), function () { return down && down.mode === 'boost' ? Infinity : 0; });
      }, SPEC.longPressMs);
    }, { capture: true, passive: true });

    window.addEventListener('touchmove', function (e) {
      if (!down) return;
      var t = e.touches[0];
      var lr = layer.getBoundingClientRect();
      var x = t.clientX - lr.left, y = t.clientY - lr.top;
      var dx = x - down.x, dy = y - down.y;
      if (down.mode === 'wait') {
        if (Math.abs(dx) > SPEC.scrubThreshold && Math.abs(dx) >= Math.abs(dy)) {
          down.mode = 'scrub'; down.start = video.currentTime; clearTimeout(down.timer);
          scrub.classList.add('on');
        } else if (Math.abs(dy) > SPEC.verticalThreshold) {
          // 上下滑動不做任何事（不調音量、亮度）
          clearTimeout(down.timer); down = null; return;
        }
      }
      if (down.mode === 'wait') return;
      if (down.mode === 'scrub') {
        var dur = video.duration || 0;
        var secPerPx = Math.min(SPEC.screenWidthSec, dur || SPEC.screenWidthSec) / layer.clientWidth;
        down.target = Math.max(0, Math.min(dur || 1e9, down.start + dx * secPerPx));
        var delta = Math.round(down.target - down.start);
        scrub.innerHTML = H(fmt(down.target) + ' / ' + fmt(dur) + '<small>' + (delta >= 0 ? '+' : '−') + Math.abs(delta) + ' 秒</small>');
      }
      if (e.cancelable) e.preventDefault(); // 拖動調整時不要捲動網頁
    }, { capture: true, passive: false });

    function cancel() {
      if (!down) return;
      clearTimeout(down.timer);
      if (down.mode === 'boost') { video.playbackRate = prevRate; pill.classList.remove('on'); }
      scrub.classList.remove('on');
      down = null;
    }

    window.addEventListener('touchend', function (e) {
      if (!down) return;
      clearTimeout(down.timer);
      if (down.mode === 'wait') { if (onTap(down.x, performance.now()) && e.cancelable) e.preventDefault(); }
      else if (down.mode === 'boost') { video.playbackRate = prevRate; pill.classList.remove('on'); if (e.cancelable) e.preventDefault(); }
      else if (down.mode === 'scrub') { if (down.target != null) video.currentTime = down.target; scrub.classList.remove('on'); if (e.cancelable) e.preventDefault(); }
      down = null;
    }, { capture: true, passive: false });
    window.addEventListener('touchcancel', cancel, true);
  }

  window.__liquidGestures = { attach: attach, injectStyle: injectStyle };
})();
