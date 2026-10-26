// 原地翻譯：直接替換頁面上的文字節點，保留原本排版；restore() 一鍵切回原文。
//  - 只收集畫面上下各 1.5 個畫面內的文字節點（略過程式碼、輸入框、notranslate），畫面內的先翻；捲動時再補翻（請求量小，不易被限流）；
//  - 分批（最多 30 段或 2500 字）交給 App 翻譯，App 以 __lqTr.apply(id, 譯文陣列) 回填；
//  - 翻譯中與翻譯後持續觀察新加入的內容（無限捲動、動態載入）並補翻。
(function () {
  if (window.__lqTr) return;
  var SKIP = /^(SCRIPT|STYLE|NOSCRIPT|CODE|PRE|TEXTAREA|INPUT|SELECT|OPTION|SVG|MATH|KBD|SAMP|VAR|IFRAME|CANVAS)$/;
  var MAX_ITEMS = 30, MAX_CHARS = 2500;
  var batches = {}, seq = 0, on = false, observer = null, timer = 0, sent = 0, done = 0;

  function post(msg) { try { if (window.LiquidBridge) LiquidBridge.postMessage(JSON.stringify(msg)); } catch (e) {} }

  function skipped(el) {
    for (var p = el; p; p = p.parentElement) {
      if (SKIP.test(p.tagName) || p.isContentEditable) return true;
      if (p.getAttribute && (p.getAttribute('translate') === 'no' || (p.classList && p.classList.contains('notranslate')))) return true;
    }
    return false;
  }

  function collect(root) {
    var out = [];
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        if (n.__lqState) return NodeFilter.FILTER_REJECT;
        var t = n.nodeValue;
        if (!t || !/[\p{L}]/u.test(t) || t.trim().length < 2) return NodeFilter.FILTER_REJECT;
        if (!n.parentElement || skipped(n.parentElement)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var vh = window.innerHeight, near = [];
    while (w.nextNode()) {
      var n = w.currentNode, r = n.parentElement.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) continue; // 隱藏的元素
      var dist = r.bottom < 0 ? -r.bottom : r.top > vh ? r.top - vh : 0;
      if (dist > vh * 1.5) continue; // 太遠的等捲過去再翻
      n.__lqDist = dist;
      near.push(n);
    }
    // 畫面內的優先，其次依與畫面的距離
    near.sort(function (a, b) { return a.__lqDist - b.__lqDist; });
    return near;
  }

  function send(nodes) {
    var cur = [], chars = 0;
    function flush() {
      if (!cur.length) return;
      var id = ++seq;
      batches[id] = cur;
      sent += cur.length;
      post({ type: 'tr', id: id, texts: cur.map(function (n) { return n.nodeValue.trim(); }) });
      cur = []; chars = 0;
    }
    nodes.forEach(function (n) {
      n.__lqState = 'pending';
      var len = n.nodeValue.length;
      if (cur.length >= MAX_ITEMS || (chars + len > MAX_CHARS && cur.length)) flush();
      cur.push(n); chars += len;
    });
    flush();
    progress();
  }

  function progress() { post({ type: 'tr_state', sent: sent, done: done }); }

  function scan() { if (on) send(collect(document.body)); }
  function onScroll() { if (!on) return; clearTimeout(timer); timer = setTimeout(scan, 250); }

  window.__lqTr = {
    start: function () {
      if (on) return;
      on = true;
      // 之前翻過又切回原文：直接套回已有的譯文，不重新請求
      var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      while (w.nextNode()) {
        var n = w.currentNode;
        if (n.__lqState === 'orig' && n.__lqTrans != null) { n.nodeValue = n.__lqTrans; n.__lqState = 'done'; }
      }
      scan();
      observer = new MutationObserver(function () { clearTimeout(timer); timer = setTimeout(scan, 600); });
      observer.observe(document.body, { childList: true, subtree: true, characterData: false });
      window.addEventListener('scroll', onScroll, { passive: true });
    },
    apply: function (id, texts) {
      var nodes = batches[id];
      if (!nodes) return;
      delete batches[id];
      nodes.forEach(function (n, i) {
        var t = texts && texts[i];
        if (t == null || !n.isConnected) return;
        var v = n.nodeValue;
        var lead = v.match(/^\s*/)[0], trail = v.match(/\s*$/)[0];
        n.__lqOrig = v;
        n.__lqTrans = lead + t + trail;
        if (on) { n.nodeValue = n.__lqTrans; n.__lqState = 'done'; } else n.__lqState = 'orig';
      });
      done += nodes.length;
      progress();
    },
    // 某批翻譯失敗：讓這些節點下次掃描時可以重試
    fail: function (id) {
      var nodes = batches[id];
      if (!nodes) return;
      delete batches[id];
      nodes.forEach(function (n) { n.__lqState = undefined; });
      sent -= nodes.length;
      progress();
    },
    // 換目標語言：還原原文並清掉舊語言的譯文，下次 start() 會重新翻譯
    reset: function () {
      window.__lqTr.restore();
      var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      while (w.nextNode()) { var n = w.currentNode; n.__lqTrans = undefined; n.__lqOrig = undefined; n.__lqState = undefined; }
      batches = {}; sent = 0; done = 0;
    },
    restore: function () {
      on = false;
      if (observer) observer.disconnect();
      window.removeEventListener('scroll', onScroll);
      clearTimeout(timer);
      var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      while (w.nextNode()) {
        var n = w.currentNode;
        if (n.__lqState === 'done' && n.__lqOrig != null) { n.nodeValue = n.__lqOrig; n.__lqState = 'orig'; }
      }
    }
  };
})();
