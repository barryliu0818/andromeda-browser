package com.liquid.browser.browser

import android.net.Uri

/**
 * Brave 式防追蹤（Shields）裡廣告阻擋以外的部分（廣告／追蹤器阻擋在 [AdBlocker]，追蹤參數在 [LinkCleaner]）：
 *  - 防指紋（Brave 的 farbling）：canvas、音訊、CPU 核心數回傳「每個網站、每次開 App 都不同」的細微雜訊，
 *    同一個網站在這次開 App 期間看到的值固定（網站不會壞），但不同網站拿到的值對不起來，無法跨網站認出你
 *  - Global Privacy Control：告訴網站「不要出售／分享我的資料」（navigator.globalPrivacyControl）
 *  - HTTPS 升級：http 網址先改用 https，連不上才退回 http（記住這個網站，這次開 App 期間不再升級）
 *  - 跳過追蹤跳轉頁：見 [Debounce]
 * ponytail: 雜訊只套用在網頁本身（含 iframe），Web Worker／OffscreenCanvas 沒處理；要更完整再注入 Worker
 */
object Shields {
    /** 這次開 App 的隨機種子：雜訊每次開 App 都不同 */
    private val sessionKey = java.security.SecureRandom().nextInt(Int.MAX_VALUE)

    /** 升級 https 失敗過的網站（這次開 App 期間直接用 http） */
    private val httpOnly = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** http 網址改成 https；不該升級（本機、區網、IP、失敗過）回傳 null */
    fun upgrade(url: String): String? {
        if (!url.startsWith("http://")) return null
        val host = Uri.parse(url).host?.lowercase() ?: return null
        if (host in httpOnly || host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".localhost") ||
            host.all { it.isDigit() || it == '.' } || ':' in host || '.' !in host
        ) return null
        return "https://" + url.removePrefix("http://")
    }

    /** 升級後連不上：記住這個網站，回傳原本的 http 網址 */
    fun fallback(httpsUrl: String): String {
        Uri.parse(httpsUrl).host?.lowercase()?.let(httpOnly::add)
        return "http://" + httpsUrl.removePrefix("https://")
    }

    /** 每個頁面（含 iframe）開始時執行的防指紋腳本 */
    val fingerprintJs: String get() = FP_JS.replace("%KEY%", sessionKey.toString())

    private const val FP_JS = """(function(){
  if (window.__oreaFp) return; window.__oreaFp = 1;
  // 種子＝這次開 App 的亂數 × 網站主網域（近似 eTLD+1）：同網站固定、跨網站不同
  var h = (location.hostname || '').split('.').slice(-2).join('.'), S = %KEY% >>> 0;
  for (var i = 0; i < h.length; i++) S = Math.imul(S ^ h.charCodeAt(i), 16777619) >>> 0;
  // 同一張圖每次加的雜訊一樣（從同一個種子開始），網站重複讀不會發現值在跳
  function farble(d) {
    var step = Math.max(4, ((d.length / 4000) | 0) * 4), r = S;
    for (var i = 0; i < d.length; i += step) { r = (Math.imul(r, 1103515245) + 12345) >>> 0; if (r & 0x10000) d[i] ^= 1; }
  }
  try {
    var C2 = CanvasRenderingContext2D.prototype, gid = C2.getImageData;
    C2.getImageData = function () { var im = gid.apply(this, arguments); farble(im.data); return im; };
    var HC = HTMLCanvasElement.prototype, tdu = HC.toDataURL, tb = HC.toBlob;
    // 匯出圖片前先複製一份加雜訊（不改原本畫面上的 canvas）；跨網站圖片（tainted）照原樣丟錯
    function copy(c) {
      try {
        if (!c.width || !c.height) return c;
        var k = document.createElement('canvas'); k.width = c.width; k.height = c.height;
        var x = k.getContext('2d'); x.drawImage(c, 0, 0);
        var im = gid.call(x, 0, 0, k.width, k.height); farble(im.data); x.putImageData(im, 0, 0); return k;
      } catch (e) { return c; }
    }
    HC.toDataURL = function () { return tdu.apply(copy(this), arguments); };
    HC.toBlob = function () { return tb.apply(copy(this), arguments); };
  } catch (e) {}
  try {
    var gcd = AudioBuffer.prototype.getChannelData, seen = new WeakSet(), f = 1 + (S % 1000) / 1e7;
    AudioBuffer.prototype.getChannelData = function () {
      var d = gcd.apply(this, arguments);
      if (!seen.has(d)) { seen.add(d); for (var i = 0; i < d.length; i += 97) d[i] *= f; }
      return d;
    };
    var gf = AnalyserNode.prototype.getFloatFrequencyData, o = (S % 100) / 1e5;
    AnalyserNode.prototype.getFloatFrequencyData = function (a) { gf.apply(this, arguments); for (var i = 0; i < a.length; i += 31) a[i] += o; };
  } catch (e) {}
  try {
    // CPU 核心數：2～實際核心數之間（每個網站不同）
    var hc = navigator.hardwareConcurrency || 4, v = 2 + S % Math.max(1, hc - 1);
    Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get: function () { return v; }, configurable: true });
  } catch (e) {}
  try { Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', { get: function () { return true; }, configurable: true }); } catch (e) {}
})();"""
}
