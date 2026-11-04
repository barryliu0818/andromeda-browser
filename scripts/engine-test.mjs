// 網頁核心冒煙測試：透過 WebView 的 DevTools（debug 版才開）檢查 Orea 在目前核心上的基本功能。
// 用法：adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>；node scripts/engine-test.mjs [9333]
// 只驗證網頁端（載入、導覽、注入的腳本與 LiquidBridge、影片全螢幕）；原生畫面（播放器、選單）另用截圖確認。
const port = process.argv[2] ?? 9333
const base = `http://localhost:${port}`
const version = await (await fetch(`${base}/json/version`)).json()
const page = (await (await fetch(`${base}/json`)).json()).find(t => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise(r => ws.addEventListener('open', r, { once: true }))
let id = 0
const pending = new Map(), listeners = []
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id) } else listeners.forEach(l => l(m))
})
const send = (method, params = {}) => new Promise(r => { pending.set(++id, r); ws.send(JSON.stringify({ id, method, params })) })
const evaluate = async (expression, userGesture = false) =>
  (await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture })).result?.result?.value
const sleep = ms => new Promise(r => setTimeout(r, ms))
const loaded = (ms = 30000) => new Promise(r => {
  const t = setTimeout(() => r(false), ms)
  listeners.push(m => { if (m.method === 'Page.loadEventFired') { clearTimeout(t); r(true) } })
})
await send('Page.enable')

const results = []
const check = async (name, fn) => {
  try { const v = await fn(); results.push([v ? 'PASS' : 'FAIL', name]) } catch (e) { results.push(['FAIL', `${name}: ${e.message}`]) }
}
const go = async url => { const l = loaded(); await send('Page.navigate', { url }); return l }

await check(`核心版本 ${version.Browser}`, () => /Chrome\/\d+/.test(version.Browser))
await check('載入維基百科', async () => (await go('https://en.m.wikipedia.org/wiki/Web_browser')) && /browser/i.test(await evaluate('document.title')))
await check('開頁注入腳本＋LiquidBridge', async () => { await sleep(500); return (await evaluate('typeof LiquidBridge')) === 'object' })
await check('站內連結導覽', async () => (await go('https://en.m.wikipedia.org/wiki/Chromium_(web_browser)')) && /Chromium/.test(await evaluate('document.title')))
await check('上一頁', async () => { const l = loaded(); await evaluate('history.back()'); await l; await sleep(500); return /Web_browser/.test(await evaluate('location.href')) })
await check('JavaScript 與版面（innerWidth、捲動）', async () => (await evaluate('scrollTo(0, 800), innerWidth > 0 && scrollY > 0')) === true)
await check('影片頁載入', async () => (await go('https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4'), await sleep(1000), await evaluate('!!document.querySelector("video")')))
await check('影片可播放（H.264/WebM 解碼）', async () =>
  await evaluate(`(async () => { const v = document.querySelector('video'); v.muted = true; await v.play(); await new Promise(r => setTimeout(r, 1500)); return v.currentTime > 0 })()`, true))
await check('影片全螢幕（Orea 會接手成原生播放器）', async () =>
  await evaluate(`document.querySelector('video').requestFullscreen().then(() => true, e => String(e))`, true))
await sleep(1500)

for (const [s, n] of results) console.log(`${s}  ${n}`)
console.log(`${results.filter(r => r[0] === 'PASS').length}/${results.length} 通過`)
ws.close()
process.exit(results.every(r => r[0] === 'PASS') ? 0 : 1)
