// 擴充功能的 chrome.*／browser.*（照 Chrome 的行為，回呼與 Promise 兩種寫法都支援）。兩種地方用：
//  - 擴充自己的頁面（背景程式、彈出視窗、選項頁）：cfg.page = true，可用完整的 API
//  - 網頁裡的內容腳本：cfg.page = false，只有 runtime、storage、i18n（同 Chrome 的內容腳本）
// post(d)：送訊息給 App；listen(h)：App 送來的訊息交給 h。App 依 d.t 處理，回覆一律是 {t:'res', rid, val|err}
function __oreaMkChrome(cfg, post, listen) {
  var S = JSON.stringify, seq = 0, waits = {}, ports = {}, evs = {}, lastErr;
  var M = cfg.messages || {}, MF = cfg.manifest || {};

  function rid() { return (++seq) + '.' + Math.random().toString(36).slice(2, 9); }
  function E(name) {
    var ls = [];
    var e = {
      addListener: function (f) { if (typeof f === 'function' && ls.indexOf(f) < 0) ls.push(f); },
      removeListener: function (f) { var i = ls.indexOf(f); if (i >= 0) ls.splice(i, 1); },
      hasListener: function (f) { return ls.indexOf(f) >= 0; },
      hasListeners: function () { return ls.length > 0; },
      _ls: ls,
      _fire: function () { var a = arguments; ls.slice().forEach(function (f) { try { f.apply(null, a); } catch (x) { console.error(x); } }); }
    };
    if (name) evs[name] = e;
    return e;
  }
  // 最後一個參數是函式就當回呼：回呼時 lastError 有值（同 Chrome）；沒有回呼就回傳 Promise
  function done(p, cb) {
    if (typeof cb !== 'function') return p;
    p.then(function (v) { cb(v); }, function (e) {
      lastErr = { message: (e && e.message) || String(e) };
      try { cb(); } finally { lastErr = undefined; }
    });
  }
  function call(d, cb) {
    var id = rid(); d.rid = id;
    var p = new Promise(function (ok, no) {
      waits[id] = function (m) { if (m.err) no(new Error(m.err)); else ok(m.val); };
    });
    post(d);
    return done(p, cb);
  }
  function split(args) {
    var a = Array.prototype.slice.call(args), cb = typeof a[a.length - 1] === 'function' ? a.pop() : null;
    return { a: a, cb: cb };
  }
  function api(name) { return function () { var x = split(arguments); return call({ t: 'api', name: name, args: x.a }, x.cb); }; }
  function val(v) { return function () { var x = split(arguments); return done(Promise.resolve(typeof v === 'function' ? v.apply(null, x.a) : v), x.cb); }; }
  function getURL(p) { return cfg.base + String(p == null ? '' : p).replace(/^\.?\//, ''); }

  // ---- 訊息 ----
  var onMessage = E('runtime.onMessage'), onConnect = E('runtime.onConnect');
  function mkPort(pid, name, sender) {
    var p = {
      name: name || '', sender: sender, onMessage: E(), onDisconnect: E(), _open: true,
      postMessage: function (msg) {
        if (!p._open) throw new Error('Attempting to use a disconnected port object');
        post({ t: 'pmsg', pid: pid, msg: msg === undefined ? null : msg });
      },
      disconnect: function () { if (!p._open) return; p._open = false; delete ports[pid]; post({ t: 'pdisc', pid: pid }); }
    };
    ports[pid] = p;
    return p;
  }
  function sendMessage() {
    var x = split(arguments), a = x.a, msg;
    if (a.length >= 3) msg = a[1];
    else if (a.length === 2) msg = (a[0] == null || (typeof a[0] === 'string' && (a[0] === cfg.id || a[0] === cfg.sid))) ? a[1] : a[0];
    else msg = a[0];
    return call({ t: 'send', msg: msg === undefined ? null : msg }, x.cb);
  }
  function connect(a, b) {
    var info = typeof a === 'object' && a ? a : (b || {});
    var pid = cfg.sid + '.' + rid();
    var p = mkPort(pid, info.name);
    post({ t: 'connect', pid: pid, name: info.name || '' });
    return p;
  }
  function onMsg(m) {
    var replied = false, keep = false;
    function respond(v) { if (replied) return; replied = true; post({ t: 'reply', rid: m.rid, has: true, resp: v === undefined ? null : v }); }
    onMessage._ls.slice().forEach(function (f) {
      try {
        var r = f(m.msg, m.sender, respond);
        if (r === true) keep = true;
        else if (r && typeof r.then === 'function') { keep = true; r.then(respond, function () { respond(undefined); }); }
      } catch (e) { console.error(e); }
    });
    if (!replied && !keep) { replied = true; post({ t: 'reply', rid: m.rid, has: false }); }
  }

  // ---- 儲存（存在 App：背景程式、彈出視窗、各網頁的內容腳本看到同一份） ----
  var onChanged = E('storage.onChanged'), areas = {};
  function area(name) {
    var o = {
      get: function (k, cb) { if (typeof k === 'function') { cb = k; k = null; } return call({ t: 'storage', op: 'get', area: name, arg: k === undefined ? null : k }, cb); },
      set: function (items, cb) { return call({ t: 'storage', op: 'set', area: name, arg: items || {} }, cb); },
      remove: function (k, cb) { return call({ t: 'storage', op: 'remove', area: name, arg: k }, cb); },
      clear: function (cb) { return call({ t: 'storage', op: 'clear', area: name }, cb); },
      getBytesInUse: val(0), getKeys: function (cb) { return done(o.get(null).then(Object.keys), cb); },
      setAccessLevel: val(undefined), onChanged: E(),
      QUOTA_BYTES: 10485760
    };
    areas[name] = o;
    return o;
  }
  var storage = { local: area('local'), sync: area('sync'), session: area('session'), managed: area('managed'), onChanged: onChanged };

  // ---- 語系 ----
  var i18n = {
    getMessage: function (n, s) {
      n = String(n);
      if (n === '@@extension_id') return cfg.id;
      if (n === '@@ui_locale') return (cfg.lang || 'en').replace('-', '_');
      if (n === '@@bidi_dir') return 'ltr';
      if (n === '@@bidi_reversed_dir') return 'rtl';
      if (n === '@@bidi_start_edge') return 'left';
      if (n === '@@bidi_end_edge') return 'right';
      var m = M[n] || M[Object.keys(M).find(function (x) { return x.toLowerCase() === n.toLowerCase(); })];
      if (!m) return '';
      var t = m.message || '';
      s = [].concat(s == null ? [] : s);
      if (m.placeholders) for (var p in m.placeholders) {
        var c = String(m.placeholders[p].content || '').replace(/\$(\d)/g, function (_, d) { return s[d - 1] == null ? '' : s[d - 1]; });
        t = t.replace(new RegExp('\\$' + p + '\\$', 'gi'), c);
      }
      return t.replace(/\$(\d)/g, function (_, d) { return s[d - 1] == null ? '' : s[d - 1]; }).replace(/\$\$/g, '$');
    },
    getUILanguage: function () { return cfg.lang || navigator.language; },
    getAcceptLanguages: val([navigator.language]),
    detectLanguage: val({ isReliable: false, languages: [] })
  };

  var runtime = {
    id: cfg.id, getURL: getURL, getManifest: function () { return MF; },
    sendMessage: sendMessage, connect: connect, onMessage: onMessage, onConnect: onConnect,
    onMessageExternal: E(), onConnectExternal: E(),
    get lastError() { return lastErr; },
    OnInstalledReason: { INSTALL: 'install', UPDATE: 'update', CHROME_UPDATE: 'chrome_update', SHARED_MODULE_UPDATE: 'shared_module_update' },
    PlatformOs: { ANDROID: 'android', LINUX: 'linux', MAC: 'mac', WIN: 'win', CROS: 'cros' }
  };
  var extension = { getURL: getURL, inIncognitoContext: false, sendMessage: sendMessage, onRequest: E(), onMessage: onMessage };
  var c = { runtime: runtime, storage: storage, i18n: i18n, extension: extension };

  if (cfg.page) {
    // ---- 只有擴充頁面才有的 API ----
    runtime.onInstalled = E('runtime.onInstalled');
    runtime.onStartup = E('runtime.onStartup');
    runtime.onSuspend = E(); runtime.onSuspendCanceled = E(); runtime.onUpdateAvailable = E(); runtime.onRestartRequired = E();
    runtime.getPlatformInfo = val({ os: 'android', arch: 'arm64', nacl_arch: 'arm' });
    runtime.getBrowserInfo = val({ name: 'Chrome', vendor: 'Google', version: (navigator.userAgent.match(/Chrome\/([\d.]+)/) || [])[1] || '', buildID: '' });
    runtime.openOptionsPage = api('runtime.openOptionsPage');
    runtime.setUninstallURL = val(undefined);
    runtime.reload = function () { location.reload(); };
    runtime.requestUpdateCheck = val({ status: 'no_update' });
    runtime.getContexts = val([]);
    runtime.getBackgroundPage = val(null);
    runtime.sendNativeMessage = function () { var x = split(arguments); return done(Promise.reject(new Error('Native messaging is not supported')), x.cb); };
    runtime.connectNative = function () { var p = mkPort('native', 'native'); setTimeout(function () { p._open = false; p.onDisconnect._fire(p); }, 0); return p; };
    extension.getBackgroundPage = function () { return null; };
    extension.getViews = function () { return []; };
    extension.isAllowedIncognitoAccess = val(false);
    extension.isAllowedFileSchemeAccess = val(false);

    var tabEvents = ['onUpdated', 'onActivated', 'onRemoved', 'onCreated', 'onReplaced', 'onHighlighted', 'onMoved', 'onAttached', 'onDetached', 'onZoomChange', 'onSelectionChanged', 'onActiveChanged'];
    c.tabs = {
      TAB_ID_NONE: -1,
      query: api('tabs.query'), get: api('tabs.get'), getCurrent: api('tabs.getCurrent'),
      create: api('tabs.create'), update: api('tabs.update'), remove: api('tabs.remove'), reload: api('tabs.reload'),
      duplicate: api('tabs.duplicate'), highlight: val(undefined), move: val(undefined), discard: val(undefined),
      executeScript: api('tabs.executeScript'), insertCSS: api('tabs.insertCSS'), removeCSS: val(undefined),
      detectLanguage: val('und'), getZoom: val(1), setZoom: val(undefined), getZoomSettings: val({ mode: 'automatic', scope: 'per-origin' }),
      captureVisibleTab: function () { var x = split(arguments); return done(Promise.reject(new Error('Not supported')), x.cb); },
      sendMessage: function (tabId, msg, opts, cb) {
        if (typeof opts === 'function') { cb = opts; opts = null; }
        return call({ t: 'send', toCs: true, tab: tabId, frameId: opts && opts.frameId != null ? opts.frameId : null, msg: msg === undefined ? null : msg }, cb);
      },
      connect: function (tabId, info) {
        info = info || {};
        var pid = cfg.sid + '.' + rid(), p = mkPort(pid, info.name);
        post({ t: 'connect', pid: pid, name: info.name || '', toCs: true, tab: tabId, frameId: info.frameId != null ? info.frameId : null });
        return p;
      }
    };
    tabEvents.forEach(function (n) { c.tabs[n] = E('tabs.' + n); });

    var win = { id: 1, focused: true, top: 0, left: 0, width: screen.width, height: screen.height, incognito: false, type: 'normal', state: 'maximized', alwaysOnTop: false };
    c.windows = {
      WINDOW_ID_NONE: -1, WINDOW_ID_CURRENT: -2,
      get: val(win), getCurrent: val(win), getLastFocused: val(win), getAll: val([win]),
      create: api('windows.create'), update: val(win), remove: val(undefined),
      onCreated: E(), onRemoved: E(), onFocusChanged: E(), onBoundsChanged: E()
    };

    c.scripting = {
      executeScript: function (inj, cb) {
        var d = { target: inj.target || {}, world: inj.world || 'ISOLATED', files: inj.files || null };
        if (inj.func) d.code = '(' + inj.func.toString() + ').apply(null,' + S(inj.args || []) + ')';
        return call({ t: 'api', name: 'scripting.executeScript', args: [d] }, cb);
      },
      insertCSS: api('scripting.insertCSS'), removeCSS: val(undefined),
      registerContentScripts: api('scripting.registerContentScripts'), unregisterContentScripts: api('scripting.unregisterContentScripts'),
      getRegisteredContentScripts: api('scripting.getRegisteredContentScripts'), updateContentScripts: api('scripting.updateContentScripts'),
      ExecutionWorld: { ISOLATED: 'ISOLATED', MAIN: 'MAIN' }
    };

    // 鬧鐘：背景程式開著時用計時器（Orea 開著時背景程式一直在）
    var alarms = {}, onAlarm = E('alarms.onAlarm');
    function pub(a) { return { name: a.name, scheduledTime: a.scheduledTime, periodInMinutes: a.periodInMinutes }; }
    function arm(a) {
      a._t = setTimeout(function () {
        onAlarm._fire(pub(a));
        if (a.periodInMinutes) { a.scheduledTime = Date.now() + a.periodInMinutes * 60000; arm(a); } else delete alarms[a.name];
      }, Math.max(0, a.scheduledTime - Date.now()));
    }
    c.alarms = {
      create: function (name, info, cb) {
        if (typeof name === 'object') { cb = info; info = name; name = ''; }
        name = name || ''; info = info || {};
        if (alarms[name]) clearTimeout(alarms[name]._t);
        var delay = info.delayInMinutes != null ? info.delayInMinutes : (info.periodInMinutes || 0);
        var a = { name: name, scheduledTime: info.when || Date.now() + delay * 60000, periodInMinutes: info.periodInMinutes };
        alarms[name] = a; arm(a);
        return done(Promise.resolve(), cb);
      },
      get: function (name, cb) { if (typeof name === 'function') { cb = name; name = ''; } return done(Promise.resolve(alarms[name || ''] ? pub(alarms[name || '']) : undefined), cb); },
      getAll: function (cb) { return done(Promise.resolve(Object.keys(alarms).map(function (k) { return pub(alarms[k]); })), cb); },
      clear: function (name, cb) { if (typeof name === 'function') { cb = name; name = ''; } var a = alarms[name || '']; if (a) { clearTimeout(a._t); delete alarms[name || '']; } return done(Promise.resolve(!!a), cb); },
      clearAll: function (cb) { Object.keys(alarms).forEach(function (k) { clearTimeout(alarms[k]._t); }); alarms = {}; return done(Promise.resolve(true), cb); },
      onAlarm: onAlarm
    };

    // 工具列按鈕：Orea 沒有工具列，按鈕在「擴展工具管理」裡（點了開彈出視窗或觸發 onClicked）
    var popup = (MF.action || MF.browser_action || MF.page_action || {}).default_popup || '';
    function action(name) {
      return {
        setBadgeText: val(undefined), getBadgeText: val(''), setBadgeBackgroundColor: val(undefined), getBadgeBackgroundColor: val([0, 0, 0, 0]),
        setBadgeTextColor: val(undefined), getBadgeTextColor: val([0, 0, 0, 0]), setIcon: val(undefined), setTitle: val(undefined),
        getTitle: val(MF.name || ''), setPopup: function (d, cb) { popup = (d && d.popup) || ''; return done(Promise.resolve(), cb); },
        getPopup: function () { var x = split(arguments); return done(Promise.resolve(popup ? getURL(popup) : ''), x.cb); },
        enable: val(undefined), disable: val(undefined), isEnabled: val(true), show: val(undefined), hide: val(undefined),
        openPopup: val(undefined), getUserSettings: val({ isOnToolbar: true }), onClicked: E(name + '.onClicked'), onUserSettingsChanged: E()
      };
    }
    c.action = action('action'); c.browserAction = action('browserAction'); c.pageAction = action('pageAction');

    var menuSeq = 0;
    c.contextMenus = c.menus = {
      create: function (p, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return (p && p.id) || ++menuSeq; },
      update: val(undefined), remove: val(undefined), removeAll: val(undefined), onClicked: E(), onShown: E(), onHidden: E(),
      ContextType: { ALL: 'all', PAGE: 'page', SELECTION: 'selection', LINK: 'link', IMAGE: 'image', VIDEO: 'video', AUDIO: 'audio', FRAME: 'frame', EDITABLE: 'editable', ACTION: 'action' }
    };
    c.commands = { getAll: val([]), onCommand: E() };
    c.permissions = {
      contains: val(true), request: val(true), remove: val(true),
      getAll: val({ permissions: MF.permissions || [], origins: (MF.host_permissions || []).concat(MF.optional_host_permissions || []) }),
      onAdded: E(), onRemoved: E()
    };
    c.notifications = {
      create: function (id, opts, cb) {
        if (typeof id === 'object') { cb = opts; opts = id; id = ''; }
        return call({ t: 'api', name: 'notifications.create', args: [id || rid(), opts || {}] }, cb);
      },
      update: val(true), clear: val(true), getAll: val({}), getPermissionLevel: val('granted'),
      onClicked: E(), onClosed: E(), onButtonClicked: E(), onShowSettings: E(), onPermissionLevelChanged: E()
    };
    c.downloads = { download: api('downloads.download'), search: val([]), pause: val(undefined), resume: val(undefined), cancel: val(undefined), erase: val([]), open: val(undefined), show: val(undefined), showDefaultFolder: val(undefined), onCreated: E(), onChanged: E(), onErased: E(), onDeterminingFilename: E() };
    c.cookies = { get: api('cookies.get'), getAll: api('cookies.getAll'), set: api('cookies.set'), remove: api('cookies.remove'), getAllCookieStores: val([{ id: '0', tabIds: [] }]), onChanged: E() };
    c.management = {
      getSelf: val({ id: cfg.id, name: MF.name, shortName: MF.short_name || MF.name, version: MF.version, description: MF.description || '', enabled: true, installType: 'normal', type: 'extension', mayDisable: true, offlineEnabled: false, optionsUrl: MF.options_page || (MF.options_ui && MF.options_ui.page) ? getURL(MF.options_page || MF.options_ui.page) : '', permissions: MF.permissions || [], hostPermissions: MF.host_permissions || [], icons: [] }),
      getAll: function () { var x = split(arguments); return done(c.management.getSelf().then(function (s) { return [s]; }), x.cb); },
      get: function (id, cb) { return done(c.management.getSelf(), cb); },
      setEnabled: val(undefined), uninstallSelf: val(undefined), onEnabled: E(), onDisabled: E(), onInstalled: E(), onUninstalled: E()
    };
    var rq = ['onBeforeRequest', 'onBeforeSendHeaders', 'onSendHeaders', 'onHeadersReceived', 'onAuthRequired', 'onResponseStarted', 'onBeforeRedirect', 'onCompleted', 'onErrorOccurred'];
    c.webRequest = { handlerBehaviorChanged: val(undefined), MAX_HANDLER_BEHAVIOR_CHANGED_CALLS_PER_10_MINUTES: 20 };
    rq.forEach(function (n) { c.webRequest[n] = E(); });
    c.webNavigation = { getFrame: val(null), getAllFrames: val([]) };
    ['onBeforeNavigate', 'onCommitted', 'onDOMContentLoaded', 'onCompleted', 'onErrorOccurred', 'onCreatedNavigationTarget', 'onReferenceFragmentUpdated', 'onHistoryStateUpdated', 'onTabReplaced'].forEach(function (n) { c.webNavigation[n] = E('webNavigation.' + n); });
    c.declarativeNetRequest = {
      updateDynamicRules: val(undefined), getDynamicRules: val([]), updateSessionRules: val(undefined), getSessionRules: val([]),
      updateEnabledRulesets: val(undefined), getEnabledRulesets: val([]), updateStaticRules: val(undefined), getDisabledRuleIds: val([]),
      getAvailableStaticRuleCount: val(30000), isRegexSupported: val({ isSupported: true }), setExtensionActionOptions: val(undefined),
      getMatchedRules: val({ rulesMatchedInfo: [] }), testMatchOutcome: val({ matchedRules: [] }), onRuleMatchedDebug: E(),
      MAX_NUMBER_OF_DYNAMIC_RULES: 30000, MAX_NUMBER_OF_SESSION_RULES: 5000, MAX_NUMBER_OF_REGEX_RULES: 1000, GUARANTEED_MINIMUM_STATIC_RULES: 30000, MAX_NUMBER_OF_ENABLED_STATIC_RULESETS: 50
    };
    c.offscreen = { createDocument: val(undefined), closeDocument: val(undefined), hasDocument: val(false), Reason: { DOM_PARSER: 'DOM_PARSER', CLIPBOARD: 'CLIPBOARD', AUDIO_PLAYBACK: 'AUDIO_PLAYBACK', BLOBS: 'BLOBS', LOCAL_STORAGE: 'LOCAL_STORAGE', WORKERS: 'WORKERS', IFRAME_SCRIPTING: 'IFRAME_SCRIPTING' } };
    c.sidePanel = { setOptions: val(undefined), getOptions: val({}), setPanelBehavior: val(undefined), getPanelBehavior: val({ openPanelOnActionClick: false }), open: val(undefined) };
    c.fontSettings = { getFontList: val([]), getFont: val({ fontId: '', levelOfControl: 'controllable_by_this_extension' }), setFont: val(undefined), clearFont: val(undefined), onFontChanged: E() };
    c.idle = { queryState: val('active'), setDetectionInterval: function () {}, getAutoLockDelay: val(0), onStateChanged: E() };
    c.identity = { getRedirectURL: function (p) { return 'https://' + cfg.id + '.chromiumapp.org/' + (p || ''); }, launchWebAuthFlow: function () { var x = split(arguments); return done(Promise.reject(new Error('Not supported')), x.cb); }, getAuthToken: function () { var x = split(arguments); return done(Promise.reject(new Error('Not supported')), x.cb); }, getProfileUserInfo: val({ email: '', id: '' }), removeCachedAuthToken: val(undefined), onSignInChanged: E() };
    c.tts = { speak: val(undefined), stop: function () {}, isSpeaking: val(false), getVoices: val([]), onEvent: E() };
    c.history = { search: val([]), getVisits: val([]), addUrl: val(undefined), deleteUrl: val(undefined), deleteRange: val(undefined), deleteAll: val(undefined), onVisited: E(), onVisitRemoved: E() };
    c.bookmarks = { get: val([]), getTree: val([]), getChildren: val([]), getRecent: val([]), search: val([]), create: val({}), update: val({}), remove: val(undefined), onCreated: E(), onRemoved: E(), onChanged: E(), onMoved: E() };
    c.topSites = { get: val([]) };
    c.browsingData = { remove: val(undefined), removeCache: val(undefined), removeCookies: val(undefined) };
    c.privacy = {};
    c.contentSettings = {};
    c.userScripts = { configureWorld: val(undefined), register: val(undefined), getScripts: val([]), unregister: val(undefined), update: val(undefined) };
    c.dom = { openOrClosedShadowRoot: function (el) { return el && (el.openOrClosedShadowRoot || el.shadowRoot) || null; } };
  } else {
    c.dom = { openOrClosedShadowRoot: function (el) { return el && el.shadowRoot || null; } };
  }

  // ---- App 送來的訊息 ----
  listen(function (m) {
    switch (m.t) {
      case 'res': { var w = waits[m.rid]; if (w) { delete waits[m.rid]; w(m); } break; }
      case 'msg': onMsg(m); break;
      case 'connect': { var p = mkPort(m.pid, m.name, m.sender); onConnect._fire(p); break; }
      case 'pmsg': { var q = ports[m.pid]; if (q) q.onMessage._fire(m.msg, q); break; }
      case 'pdisc': {
        var r = ports[m.pid];
        if (r) { delete ports[m.pid]; r._open = false; if (m.err) lastErr = { message: m.err }; try { r.onDisconnect._fire(r); } finally { lastErr = undefined; } }
        break;
      }
      case 'schanged': onChanged._fire(m.changes, m.area); if (areas[m.area]) areas[m.area].onChanged._fire(m.changes); break;
      case 'ev': { var e = evs[m.name]; if (e) e._fire.apply(null, m.args || []); break; }
    }
  });
  post({ t: 'hello', top: window === window.top, url: location.href });

  if (!cfg.page) return c;
  Object.defineProperty(c, '__call', { value: call });
  // 擴充頁面：沒實作的 API 給一個不會出錯的空殼（呼叫回傳空結果、onXxx 是事件），避免整個背景程式因為一個 API 不存在就停掉
  function stub(ns) {
    var cache = {};
    return new Proxy({}, {
      get: function (_, k) {
        if (typeof k !== 'string') return undefined;
        if (!(k in cache)) cache[k] = /^on[A-Z]/.test(k) ? E() : /^[A-Z_]+$/.test(k) ? {} : val(undefined);
        return cache[k];
      }
    });
  }
  return new Proxy(c, {
    get: function (t, k) {
      if (k in t || typeof k !== 'string') return t[k];
      if (/^(then|toJSON|constructor|prototype)$/.test(k)) return undefined;
      return (t[k] = stub(k));
    }
  });
}
