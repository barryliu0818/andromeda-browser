// 閱讀器模式：找出段落文字最多的區塊視為正文，移除雜訊後以乾淨排版取代整頁。
// 回傳 true 表示成功轉換，false 表示頁面不像文章（正文太短）。
(function () {
  try {
    var candidates = [].slice.call(document.querySelectorAll(
      'article, main, [role="main"], .post, .entry-content, .article, .post-content, #content, .content, body'));
    var best = null, score = 0;
    candidates.forEach(function (el) {
      var total = 0;
      el.querySelectorAll('p').forEach(function (p) { total += (p.innerText || '').trim().length; });
      // 越精準的容器（article、main）略加權，避免直接選到 body
      if (el.tagName === 'ARTICLE' || el.tagName === 'MAIN') total *= 1.2;
      if (total > score) { score = total; best = el; }
    });
    if (!best || score < 400) return false;

    var h1 = document.querySelector('h1');
    var title = (h1 && h1.innerText.trim()) || document.title;
    var clone = best.cloneNode(true);
    clone.querySelectorAll(
      'script, style, noscript, nav, aside, footer, header, form, iframe, button, svg, ' +
      '[class*="share"], [class*="comment"], [class*="related"], [class*="advert"], [id*="ad-"], .ad, .ads'
    ).forEach(function (n) { n.remove(); });
    // 只由短連結組成的清單（導覽列、標籤列）移除
    clone.querySelectorAll('ul, ol').forEach(function (list) {
      var items = list.querySelectorAll('li');
      var linkOnly = [].every.call(items, function (li) { var a = li.querySelector('a'); return a && li.innerText.trim() === a.innerText.trim(); });
      if (items.length && linkOnly && list.innerText.length < 200) list.remove();
    });
    // 內文第一個 h1 與標題重複時移除
    var dup = clone.querySelector('h1');
    if (dup && dup.innerText.trim() === title) dup.remove();

    var dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    var bg = dark ? '#1c1c1e' : '#ffffff', fg = dark ? '#e5e5ea' : '#1c1c1e', sub = '#8e8e93',
        link = dark ? '#0a84ff' : '#007aff';
    var esc = function (s) { var d = document.createElement('div'); d.textContent = s; return d.innerHTML; };

    document.head.innerHTML =
      '<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
      '<meta name="theme-color" content="' + bg + '"><title>' + esc(title) + '</title>' +
      '<style>' +
      'html,body{background:' + bg + ';color:' + fg + '}' +
      'body{margin:0 auto;max-width:680px;padding:28px 22px 160px;font:19px/1.7 "Noto Serif",Georgia,serif;word-wrap:break-word}' +
      'h1{font:700 28px/1.3 system-ui,sans-serif;margin:0 0 8px}' +
      '.host{color:' + sub + ';font:14px system-ui,sans-serif;margin-bottom:28px}' +
      'h2,h3{font-family:system-ui,sans-serif;line-height:1.35;margin-top:1.6em}' +
      'img,video,figure{max-width:100%;height:auto;border-radius:12px}' +
      'figcaption{color:' + sub + ';font-size:14px;text-align:center}' +
      'a{color:' + link + ';text-decoration:none}' +
      'pre{overflow:auto;background:rgba(127,127,127,.12);padding:12px;border-radius:10px;font-size:14px}' +
      'blockquote{margin:1em 0;padding-left:16px;border-left:3px solid ' + sub + ';color:' + sub + '}' +
      '</style>';
    document.body.removeAttribute('class');
    document.body.removeAttribute('style');
    document.body.innerHTML = '<h1>' + esc(title) + '</h1><div class="host">' + esc(location.hostname) + '</div>' + clone.innerHTML;
    window.scrollTo(0, 0);
    return true;
  } catch (e) {
    return false;
  }
})();
