// 密碼自動填入：找出登入表單、通知 App 顯示「使用密碼」列、由 App 填入、登入送出時交給 App 詢問是否儲存。
// 只在最上層頁面、HTTPS 上執行；App 端也會再核對訊息來源（主框架、https）才處理。
(function () {
  if (window.__lqPw || window.top !== window || location.protocol !== 'https:') return;
  var post = function (m) { try { if (window.LiquidBridge) LiquidBridge.postMessage(JSON.stringify(m)); } catch (e) {} };
  var form = null; // 最近一次聚焦的登入區塊（form，或沒有 form 時的父層）

  // 有畫在畫面上（getClientRects：固定定位的彈出式登入框也算；offsetParent 對 position:fixed 一律是 null）
  var visible = function (el) { return el && el.getClientRects().length > 0 && !el.disabled && !el.readOnly; };
  var passwords = function (root) { return Array.prototype.filter.call(root.querySelectorAll('input[type=password]'), visible); };
  // 帳號欄：密碼欄前面最後一個可見的文字／email／電話欄位
  var userField = function (root, pw) {
    var all = Array.prototype.filter.call(root.querySelectorAll('input'), function (i) {
      return visible(i) && /^(text|email|tel|)$/i.test(i.type) && !/search|captcha|code|otp/i.test((i.name || '') + (i.id || '') + (i.autocomplete || ''));
    });
    var before = all.filter(function (i) { return pw && (i.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING); });
    return before[before.length - 1] || all[0] || null;
  };
  // 登入區塊：有 form 用 form；沒有就往上找到含密碼欄的最近父層
  var areaOf = function (el) {
    if (el.form) return el.form;
    for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) if (passwords(p).length) return p;
    return null;
  };
  var isNew = function (area) {
    var pws = passwords(area);
    return pws.length >= 2 || pws.some(function (p) { return /new-password/i.test(p.autocomplete || ''); });
  };

  document.addEventListener('focusin', function (e) {
    var t = e.target;
    if (!(t instanceof HTMLInputElement)) return;
    var area = areaOf(t);
    if (!area || !passwords(area).length) return;
    var pw = passwords(area)[0];
    if (t.type !== 'password' && t !== userField(area, pw)) return;
    form = area;
    post({ type: 'pw_focus', mode: isNew(area) ? 'new' : 'login' });
  }, true);
  document.addEventListener('focusout', function () {
    setTimeout(function () {
      var a = document.activeElement;
      if (!(a instanceof HTMLInputElement) || !form || !form.contains(a)) post({ type: 'pw_blur' });
    }, 150);
  }, true);

  // 用原生 setter 設值並觸發 input／change：React 等框架才會收到（直接改 .value 會被它蓋回去）
  var setValue = function (el, v) {
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };

  // 記住輸入過的帳號與密碼（Chrome 的做法）：登入按鈕常不在表單裡、送出後密碼欄馬上被移除，
  // 分兩步的登入（Google：先帳號、下一頁才密碼）在密碼頁根本沒有帳號欄
  var typedPw = null, typedUser = '';
  document.addEventListener('input', function (e) {
    var t = e.target;
    if (!(t instanceof HTMLInputElement)) return;
    if (t.type === 'password') { typedPw = t; return; }
    var area = areaOf(t);
    // 還沒出現密碼欄的帳號頁（分兩步登入的第一步）：帳號類欄位交給 App 記著，到密碼頁再配對
    if (/^(email|tel|text)$/i.test(t.type) && /user|login|mail|account|identifier|phone|name/i.test((t.name || '') + (t.id || '') + (t.autocomplete || '') + t.type)) {
      typedUser = t.value;
      if (!area) post({ type: 'pw_user', user: t.value });
    }
  }, true);

  var lastSent = '';
  var capture = function () {
    var pw = typedPw;
    if (!pw || !pw.value) return;
    var area = form && form.contains(pw) ? form : areaOf(pw);
    var u = area ? userField(area, pw) : null;
    var user = (u && u.value) || typedUser;
    var sig = user + '\u0000' + pw.value;
    if (sig === lastSent) return;
    lastSent = sig;
    post({ type: 'pw_submit', user: user, pass: pw.value });
  };
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('button, input[type=submit], input[type=button], [role=button], a');
    if (!b || !typedPw) return;
    // 密碼欄旁邊的「顯示密碼」眼睛不算送出
    if (typedPw.parentElement && typedPw.parentElement.contains(b) && !(b.textContent || '').trim() && !b.matches('[type=submit]')) return;
    setTimeout(capture, 0);
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && e.target instanceof HTMLInputElement) capture();
  }, true);
  window.addEventListener('pagehide', capture, true);

  window.__lqPw = {
    fill: function (user, pass) {
      if (!form) return;
      var pws = passwords(form), u = userField(form, pws[0]);
      if (u && user) setValue(u, user);
      if (pws[0]) setValue(pws[0], pass);
    },
    // 註冊表單：新密碼與確認密碼一起填
    fillNew: function (pass) {
      if (!form) return;
      passwords(form).forEach(function (p) { setValue(p, pass); });
    },
  };
})();
