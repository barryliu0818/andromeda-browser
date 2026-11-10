# Orea 優化空間盤點

盤點日期：2026-11-10　基準版本：`d5208b6`（versionName 0.4）

## 這份文件怎麼來的

- 讀過 `browser/`（Tab、BrowserViewModel、Translator、AdBlocker、TranslationCache、Extensions）、`MainActivity`、`data/`、`core/Engine`、`core/I18n`、注入的 JS、Gradle 設定，並拆開 release APK 看內容。
- **全部是讀程式碼得到的推論，沒有實測數字。** 每一項的「預期效果」是估計，動手前後要量（做法見各項的「驗證」）。
- 這次沒有讀到手機上的異常紀錄（盤點時手機沒接上）。動手前先跑 `scripts/buglogs.ps1`，看 `jank`、`freeze`、`memory`、`renderer`、`slow` 哪一類最多，再決定順序。注意腳本裡的 adb 路徑還是舊電腦的，要改成 `D:\Android\platform-tools-35\adb.exe`。
- UI 層（Compose）已經調得很細：捲動時的 `vm.collapse` 都在排版階段讀取、面板滑動用凍結畫面、鍵盤位移不重新量測。這一層沒有列出項目。

## 總表

優先度：P0＝效果大且風險可控，先做；P1＝值得做；P2＝順手清理。

| 編號 | 項目 | 類別 | 優先 | 工作量 |
|---|---|---|---|---|
| A1 | 圖示字型 15.2 MB 只用了約 47 個圖示 | 安裝包 | P0 | 小 |
| A2 | release 版帶了 x86_64 的原生程式庫 | 安裝包 | P0 | 很小 |
| A3 | 內建 Chromium 核心佔 APK 的 84% | 安裝包／儲存空間 | P1 | 大 |
| B1 | 還原工作階段時每個分頁都立刻建立 WebView | 啟動／記憶體 | P0 | 中 |
| B2 | 設定還沒讀完就還原分頁（可能重複載入、桌面版設定失效） | 啟動／正確性 | P0 | 小 |
| B3 | 存工作階段在主執行緒寫檔 | 卡頓 | P1 | 小 |
| C1 | 背景分頁永遠不釋放 | 記憶體 | P0 | 中 |
| C2 | 每個分頁最多留 6 張返回用截圖，記憶體吃緊時也不清 | 記憶體 | P1 | 小 |
| C3 | 開面板時配置一張全解析度點陣圖（約 18 MB） | 記憶體 | P2 | 小 |
| D1 | 65 KB 腳本注入每一個 iframe | 網頁載入 | P1 | 小 |
| D2 | 底部固定元素掃描：整頁掃、永不停止 | 網頁卡頓 | P1 | 小 |
| D3 | 每個子資源請求都重算一次網站例外 | 網頁載入 | P2 | 小 |
| D4 | `onPageFinished` 連續三次 `evaluateJavascript` | 網頁載入 | P2 | 很小 |
| E1 | 離開頁面後翻譯請求照跑（浪費 API 額度） | 翻譯／費用 | P1 | 小 |
| E2 | 連網全用 `HttpURLConnection`，App 裡其實已有 OkHttp | 翻譯／下載速度 | P1 | 中 |
| E3 | 翻譯快取每次查詢都寫一次資料庫 | 翻譯 | P2 | 小 |
| F1 | 沒用到的「常造訪網站」查詢一直在跑 | 資料庫 | P0 | 很小 |
| F2 | 歷史紀錄沒有上限 | 資料庫 | P1 | 小 |
| G1 | 中國大陸網路下還有幾處依賴 Google | 可用性 | P1 | 中 |
| H1 | 自用版 release 開著 WebView 遠端除錯；金鑰編進 APK | 安全 | P1 | 小 |
| I1 | 小清理（重複設定、迴圈裡編譯正規表示式等） | 程式品質 | P2 | 小 |
| I2 | BrowserViewModel 1151 行、Tab.Listener 約 40 個方法 | 可維護性 | P2 | 大 |
| I3 | 單元測試只有 4 個檔案 | 品質 | P2 | 中 |

---

## A. 安裝包大小

目前 `app-personal-release.apk` 是 97.2 MB（解壓後 208 MB）。壓縮後的組成：

| 內容 | 壓縮後 | 佔比 |
|---|---|---|
| `assets/orea_core/core.bin`（Chromium 核心） | 81.4 MB | 84% |
| `res/font/material_symbols_rounded.ttf` | 6.6 MB | 7% |
| `lib/x86_64/liboreaadblock.so` | 3.1 MB | 3% |
| `lib/arm64-v8a/liboreaadblock.so` | 3.0 MB | 3% |
| `classes.dex` | 2.8 MB | 3% |

### A1　圖示字型只留用到的字（P0）

- **現況**：[Icons.kt:36](../app/src/main/java/com/liquid/browser/ui/Icons.kt) 載入完整的 Material Symbols Rounded 可變字型，15.2 MB。程式裡用到的圖示名稱約 47 個（`Sym("…")`、`IconTile("…")`）。
- **做法**：用 `fonttools` 的 `pyftsubset` 只留用到的字形與連字（ligature），或改成各圖示一個 vector drawable。字型子集化改動最小：程式碼不用動，只換檔案。要寫一個腳本從原始碼抓出圖示名稱清單，之後新增圖示時重跑。
- **預期效果**：APK 少約 6.5 MB，字型檔從 15 MB 降到 100 KB 以內。
- **風險**：漏掉的圖示會顯示成文字（圖示名稱）。腳本要涵蓋所有寫法，包含用變數傳進去的名稱。
- **驗證**：每個畫面（設定、選單、分頁總覽、下載、權限對話框、播放器）截圖比對。這台電腦沒裝 Python，要先裝在 D 槽，或用其他子集化工具。

### A2　release 只打包 arm64（P0）

- **現況**：`app/src/main/jniLibs/` 有 arm64-v8a 與 x86_64 兩份 `liboreaadblock.so`，release 版兩份都帶。內建核心本來就只有 arm64（[Engine.kt](../app/src/main/java/com/liquid/browser/core/Engine.kt) 的註解）。
- **做法**：`app/build.gradle.kts` 的 release buildType 加 `ndk { abiFilters += "arm64-v8a" }`；debug 保留 x86_64 給模擬器用。
- **預期效果**：APK 少 3.1 MB。
- **風險**：之後在模擬器上只能裝 debug 版（目前模擬器測試用的是 release 版，流程要跟著改）。

### A3　Chromium 核心改成按需下載（P1，工作量大）

- **現況**：核心 181 MB 打包在 assets。第一次開 App 解壓到私有資料夾，[Engine.kt](../app/src/main/java/com/liquid/browser/core/Engine.kt) 註解寫「解壓一次要寫入約 400MB」。所以手機上同時有 APK 裡的一份（約 81 MB）和解壓後的一份（約 400 MB）。
- **做法（擇一）**：
  1. 核心不打包，第一次啟動後在 Wi-Fi 下從 GitHub Release 下載（handoff 提到核心已經是 Release 資產），驗證雜湊後解壓。APK 降到約 16 MB。
  2. 保留打包，但分享版（`share` flavor）不帶核心，用系統 WebView。
- **風險**：GitHub 在中國大陸下載不穩，需要鏡像或斷點續傳；下載完成前是系統 WebView，行為可能跟核心不同。這一項要先跟使用者確認方向再做。

---

## B. 啟動

### B1　還原的分頁延後建立 WebView（P0）

- **現況**：[BrowserViewModel.kt:503](../app/src/main/java/com/liquid/browser/browser/BrowserViewModel.kt) 的 `restoreSession()` 對每個存下來的分頁都 `Tab(...)`，而 [Tab.kt:144](../app/src/main/java/com/liquid/browser/browser/Tab.kt) 的 `val webView = WebView(context)` 是建構時就建立。網頁內容雖然延後載入（`pendingUrl`），但 WebView 物件、它的設定、好幾段開頁腳本的註冊，全部在啟動時的主執行緒上做完。
- **影響**：存了 20 個分頁，啟動就建 20 個 WebView。每個 WebView 都有原生端的記憶體，也拖慢第一個畫面。
- **做法**：`webView` 改成第一次需要時才建立（`setForeground(true)`、`loadPending()`、`load()` 時）。還沒建立的分頁只有網址、標題、縮圖、還原用的 Bundle。所有直接讀 `tab.webView` 的地方（`close`、`onCleared`、`clearData`、`BrowserScreen` 的 `AndroidView`）要改成容許「還沒有」。
- **預期效果**：啟動時間與記憶體不再跟分頁數成正比。
- **風險**：`tab.webView` 被很多地方直接使用，漏改一處就是閃退。這也是 C1（釋放背景分頁）的前置工作，兩項一起設計。
- **驗證**：存 15 個以上分頁，比較改動前後的冷啟動時間（`adb shell am start -W`）和 `dumpsys meminfo`；逐一切到每個分頁確認能載入、上一頁紀錄還在。

### B2　等設定讀完再還原分頁（P0，先確認再改）

- **現況**：`settings` 是 `stateIn(…, Eagerly, Settings())`（[BrowserViewModel.kt:73](../app/src/main/java/com/liquid/browser/browser/BrowserViewModel.kt)），DataStore 在背景讀。`MainActivity.onCreate` 建立 ViewModel 後馬上 `restoreSession()`，這時 `settings.value` 多半還是預設值。
- **推論出的後果**（需要實測確認）：
  1. 使用者把「識別為 iPhone」關掉時：分頁先用預設（開）建立並開始載入，設定讀到後 `setIdentifyAsIPhone(false)` 觸發 `applyIdentity(reload = true)`，目前分頁載入兩次。
  2. 「預設桌面版」只在建構時傳入，之後沒有重新套用：還原的分頁可能一律是行動版。
  3. 閒置時的預先連線用的是預設搜尋引擎（Google），不是使用者選的。
- **做法**：`restoreSession()` 前先等 DataStore 的第一筆資料（幾毫秒到十幾毫秒），或把還原移到第一筆設定到達之後。
- **驗證**：把「識別為 iPhone」關掉、「預設桌面版」打開，殺掉 App 重開，看 logcat 裡目前分頁有沒有載入兩次、UA 對不對。

### B3　存工作階段不要在主執行緒寫檔（P1）

- **現況**：[BrowserViewModel.kt:480](../app/src/main/java/com/liquid/browser/browser/BrowserViewModel.kt) 的 `saveSession()` 在 `onStop` 的主執行緒上，對每個分頁 `webView.saveState()`、序列化、Base64（每個分頁最多 256 KB），然後 `sessionFile.writeText()` 同步寫檔。`restoreSession()` 也是同步讀檔加解碼。
- **做法**：`saveState()` 必須在主執行緒，但序列化後的寫檔移到 IO 執行緒（跟現在的縮圖一樣）；寫檔用「寫暫存檔再改名」避免寫到一半被殺掉留下壞檔。沒變動的分頁可以沿用上次的 Bundle。
- **驗證**：開 20 個分頁按 Home，看 `events.log` 有沒有 `jank`／`anr`；改完後強制結束再重開，確認分頁都還原。

---

## C. 記憶體

### C1　記憶體吃緊時釋放背景分頁（P0）

- **現況**：分頁一旦載入，WebView 就一直留著，直到使用者關掉。`onTrimMemory`（[MainActivity.kt](../app/src/main/java/com/liquid/browser/MainActivity.kt)）只清模糊視窗的池子。系統記憶體不夠時只能殺渲染程序，走 `onRendererGone` 重建，使用者看到的是「網頁已重新載入」。
- **做法**（照 Chromium 的 tab discarding）：`onTrimMemory` 等級到 `RUNNING_LOW` 以上時，把最久沒用、不是目前分頁、沒在播放聲音的分頁轉回「待載入」狀態：`saveState()` 存下紀錄、保留縮圖、`destroy()` WebView。切回去時走現有的 `loadPending()`。另外可以設一個同時存活的 WebView 上限（例如 5 個）。
- **預期效果**：小米 HyperOS 這類積極回收的系統上，目前分頁被殺掉的機率下降。
- **風險**：被釋放的分頁表單內容、捲動位置會丟（`saveState` 只存導覽紀錄）；正在背景播音樂的分頁不能釋放。
- **驗證**：開 15 個分頁後 `adb shell am send-trim-memory com.liquid.browser.next RUNNING_CRITICAL`，看 `dumpsys meminfo` 降多少、切回分頁是否正常。

### C2　返回用截圖要有總量上限（P1）

- **現況**：[Tab.kt:235](../app/src/main/java/com/liquid/browser/browser/Tab.kt) 每個分頁留最多 6 張離開頁面前的截圖（0.4 倍、ARGB_8888）。在 1440×3200 的螢幕上一張約 2.9 MB，一個分頁最多約 17.7 MB，10 個分頁就是 177 MB，而且記憶體吃緊時不會清。
- **做法**：背景分頁只留 1 張（或全清）；`onTrimMemory` 時清掉所有非目前分頁的 `pageShots`；格式改 `RGB_565`（不需要透明度，省一半）。
- **驗證**：`dumpsys meminfo` 的 Graphics／Native Heap，改動前後各瀏覽同一組頁面比較。

### C3　凍結畫面不必全解析度（P2）

- **現況**：[Tab.kt:1064](../app/src/main/java/com/liquid/browser/browser/Tab.kt) `freezeFrame` 用 `scale = 1f`，每次開面板配置一張約 18 MB 的點陣圖。
- **做法**：降到 0.75 倍或重複使用同一張點陣圖。面板蓋住大半畫面又有模糊，肉眼不易分辨。
- **風險**：面板拉下來的瞬間可能看得出畫質差異，要實機看。

---

## D. 網頁載入與卡頓

### D1　注入腳本依對象分開（P1）

- **現況**：[BrowserViewModel.kt:118](../app/src/main/java/com/liquid/browser/browser/BrowserViewModel.kt) 把 `gesture_layer.js`（31 KB）、`video_detect.js`（17 KB）、`yt_subs.js`（11 KB）、`pw_autofill.js`（5 KB）接成一段 65 KB 的腳本，用 `addDocumentStartJavaScript(…, setOf("*"))` 注入每個頁面的每個 iframe。廣告多的頁面有十幾個 iframe，每個都要解析這 65 KB。
- **做法**：
  - `yt_subs.js`：`allowedOriginRules` 改成只有 `https://*.youtube.com`（現在是腳本自己判斷網域，但解析成本已經付了）。
  - `pw_autofill.js`：原生端本來就丟掉 iframe 來的密碼訊息（[Tab.kt:706](../app/src/main/java/com/liquid/browser/browser/Tab.kt) 的 `!mainFrame`），腳本開頭加 `if (window !== window.top) return;`，或研究能否只注入主框架。
  - 建置時把注入腳本壓縮（去註解與空白）。
- **驗證**：用 DevTools（自用版已開遠端除錯）的 Performance 面板，錄一個 iframe 多的新聞網站，比較 Evaluate Script 的時間。

### D2　底部固定元素掃描要能停（P1）

- **現況**：[Tab.kt:1095](../app/src/main/java/com/liquid/browser/browser/Tab.kt) 的 `LIFT_FIXED_JS`：每次掃描走訪 `document.body.getElementsByTagName('*')` 全部元素，讀 `offsetParent`（會強制排版）；前 30 秒每 2 秒掃一次；`MutationObserver` 監看整個 body，任何 DOM 變動 400 ms 後就再整頁掃一次，而且永遠不停。無限捲動、即時更新的頁面會一直重掃。
- **做法**：MutationObserver 只檢查新增的節點（和它們的子孫）；找到並處理過、或頁面穩定一段時間後降低頻率；`inset()` 的結果在同一輪掃描外快取。
- **驗證**：在長的無限捲動頁面（微博、Reddit）用 DevTools Performance 看 `scan` 佔的時間；確認 cookie 橫幅、底部導覽列仍被推到網址列上方。

### D3　網站例外的判斷每頁算一次（P2）

- **現況**：`shouldInterceptRequest`（網路執行緒）對每個子資源呼叫 `blocksTrackers(page)` → `adBlockFor()` → `baseDomain(pageHost)`。`baseDomain`（[AdBlocker.kt:219](../app/src/main/java/com/liquid/browser/browser/AdBlocker.kt)）每次都 `split`、而且每次呼叫都新建一個 10 個元素的 `setOf(...)`。一頁上百個請求就算上百次同樣的結果。
- **做法**：`secondLevel` 提到最上層當常數；`onPageStarted` 時算好「這一頁要不要擋」存成 `@Volatile` 欄位，請求時直接讀。設定或例外清單改變時重算。

### D4　載入完成後的三次 JS 呼叫合成一次（P2）

- **現況**：[Tab.kt:556](../app/src/main/java/com/liquid/browser/browser/Tab.kt) 起連續三次 `evaluateJavascript`（主題色、頁面語言、圖示）。
- **做法**：合成一段腳本回傳一個 JSON。

---

## E. 翻譯與連網

### E1　離開頁面就取消翻譯（P1）

- **現況**：`onTranslateBatch`（[BrowserViewModel.kt:946](../app/src/main/java/com/liquid/browser/browser/BrowserViewModel.kt)）在 `viewModelScope` 發出請求，跟分頁、頁面都沒有綁定。翻到一半換頁或關分頁，已送出的 AI 請求（最多 8 個同時）會繼續跑完、照算 token，結果丟給已經不存在的頁面。`HttpURLConnection` 的阻塞讀取也不會因為協程取消而中斷。
- **做法**：每個分頁一個翻譯用的 `Job`，`onPageStarted`、`showOriginal()`、`close()` 時取消；HTTP 層要能真的中斷（見 E2，或取消時 `conn.disconnect()`）。
- **驗證**：開長文按翻譯後馬上換頁，看 logcat 的 `OreaTr` 紀錄是否還有請求完成。

### E2　連網統一用 OkHttp（P1）

- **現況**：`Translator`、`AdBlocker`、`Extensions`、`MediaProbe`、`DownloadEngine`、以圖搜尋、搜尋建議全部用 `HttpURLConnection`。App 裡已經有 OkHttp 5.5（Coil 帶進來的，[Images.kt](../app/src/main/java/com/liquid/browser/core/Images.kt)）。
- **做法**：建一個共用的 `OkHttpClient`，先換 `Translator`（好處最明顯），再換其他。
- **預期效果**：HTTP/2 多工（8 個同時的 AI 請求共用一條連線，不用 8 次 TLS 握手）、連線池、`Call.cancel()` 可以配合協程取消（解決 E1）。`MainActivity` 裡調 `http.maxConnections` 的設定之後可以拿掉。
- **風險**：`DownloadEngine` 的分段下載、HLS 邏輯比較複雜，放最後換，換完要重測下載。

### E3　翻譯快取少寫幾次（P2）

- **現況**：[TranslationCache.kt](../app/src/main/java/com/liquid/browser/browser/TranslationCache.kt) 的 `get()` 每查一批就 `UPDATE … SET used`，不在交易裡（每次都是獨立寫入），資料庫也沒開 WAL。翻過的頁面重新整理時會連續查很多批。
- **做法**：建構時 `setWriteAheadLoggingEnabled(true)`；`get()` 包進一個交易；`used` 只在距離上次更新超過一天時才寫。

---

## F. 資料庫

### F1　拿掉沒用到的 `frequent` 查詢（P0）

- **現況**：[BrowserViewModel.kt:360](../app/src/main/java/com/liquid/browser/browser/BrowserViewModel.kt) 的 `frequent` 用 `SharingStarted.Eagerly` 一直訂閱，整個專案沒有任何地方讀它（常用網站已改成只用使用者自己加的，見 `topSites` 的註解）。每寫一筆歷史紀錄，Room 就重跑一次 30 天的 `GROUP BY host`。
- **做法**：刪掉這個屬性；`HistoryDao.frequent` 和 `FrequentSite` 沒有其他用途的話一起刪。

### F2　歷史紀錄設上限（P1）

- **現況**：每次造訪插入一筆，沒有清理。網址列建議（`match`）和歷史搜尋都是 `LIKE '%q%'`，每打一個字全表掃一次。
- **做法**：啟動後在背景刪掉超過一年、或超出 5 萬筆的舊紀錄。資料量大了以後再考慮 FTS。Room 要加 migration 才能改結構，單純刪資料不用。

---

## G. 中國大陸網路下的可用性

登入功能就是因為 Supabase 連不上才拿掉的。同類的依賴還有：

| 位置 | 依賴 | 連不上時 |
|---|---|---|
| [Suggestions.kt:56](../app/src/main/java/com/liquid/browser/ui/browser/Suggestions.kt) | `suggestqueries.google.com` | 沒有搜尋建議 |
| `Translator`：拉丁文、印地文 | Google 翻譯 | 翻譯失敗 |
| 預設搜尋引擎、預設常用網站 | Google、YouTube、Reddit 等 | 新安裝的起始頁大半打不開 |
| 以圖搜尋 | Google Lens | 失敗 |

**做法**：搜尋建議跟著使用者選的搜尋引擎走（百度、Bing 都有建議端點）；第一次啟動依系統地區（`zh-CN`）選預設搜尋引擎和常用網站；拉丁文、印地文在 Google 失敗時改用已設定金鑰的 AI 引擎。

---

## H. 安全

### H1　自用版的除錯開關與金鑰（P1）

- **WebView 遠端除錯**：[MainActivity.kt](../app/src/main/java/com/liquid/browser/MainActivity.kt) 在 `personal` flavor 的 release 版也開 `setWebContentsDebuggingEnabled(true)`。手機開著 USB 偵錯接上任何電腦，對方都能用 `chrome://inspect` 看到所有分頁內容、執行 JS、讀 Cookie。建議改成設定裡的開關，預設關。
- **翻譯金鑰**：`QWEN_API_KEY` 編進 `BuildConfig`，從 APK 就能取出；使用者輸入的金鑰以明文存在 DataStore（程式裡已有 `ponytail:` 註解標記）。自用版只在自己手機上問題不大，但 APK 不能外流。要給別人就用 `share` flavor。
- **`QUERY_ALL_PACKAGES`**：上架 Google Play 會被審查。目前不上架就不用處理，記在這裡備查。

---

## I. 程式品質

### I1　小清理（P2）

- [Tab.kt:354](../app/src/main/java/com/liquid/browser/browser/Tab.kt) 與 [Tab.kt:383](../app/src/main/java/com/liquid/browser/browser/Tab.kt)：往返快取（BFCache）開了兩次，留第一段。
- [AdBlocker.kt:182](../app/src/main/java/com/liquid/browser/browser/AdBlocker.kt)：`Regex(...)` 寫在迴圈裡，提到外面。
- [Translator.kt:70](../app/src/main/java/com/liquid/browser/browser/Translator.kt)：`Regex("\\s+")` 每段文字編譯一次，提成常數。
- `MediaProbe`、`DownloadEngine`、`Extensions.packageUrl`、`AppLinks.mapped` 裡的行內 `Regex(...)` 同樣處理。
- [BrowserViewModel.kt:572](../app/src/main/java/com/liquid/browser/browser/BrowserViewModel.kt) `onScroll`：每個捲動事件取消並重開一個協程。可改成單一協程加時間戳。
- [Tab.kt:445](../app/src/main/java/com/liquid/browser/browser/Tab.kt)：每擋一個請求就 `main.post { blockedCount++ }`。廣告多的頁面一秒幾十次，可合併成每 100 ms 更新一次。
- `BrowserViewModel` 註解寫「AI 引擎 4 批」，實際是 `Semaphore(8)`，註解或數字擇一修正。
- `scripts/buglogs.ps1` 的 adb 路徑是舊電腦的。

### I2　拆分 BrowserViewModel（P2，工作量大）

`BrowserViewModel` 1151 行，同時管分頁、權限、翻譯、下載、密碼、App 跳轉；`Tab.Listener` 約 40 個方法。建議把翻譯（`mergedTranslate`、`translateTexts`、快取）、網站權限、密碼各自抽成獨立的類別，ViewModel 只做轉接。這一項不影響使用者，等 B1、C1 做完再動，免得互相衝突。

### I3　補單元測試（P2）

現有測試只有 `DebounceTest`、`ExtensionsTest`、`TranslatorLinesTest`、`UrlInputTest`。純邏輯、容易測、出錯影響大的還有：`baseDomain`、`AdBlocker.blocks`、`LinkCleaner.clean`、`SitePermissionStore` 的決定與到期邏輯、`geoApproxScript` 的來源清單、`Tab.alreadyIn`。

---

## 建議的順序

1. **先量**：拉手機的 `events.log`，記下目前的冷啟動時間、15 個分頁時的記憶體、APK 大小，當作基準。
2. **第一批（小而確定）**：F1、A2、I1、D3、D4。每項都是幾行到幾十行。
3. **第二批（安裝包）**：A1。
4. **第三批（啟動與記憶體，要一起設計）**：B2 → B1 → C1 → C2 → B3。
5. **第四批（網頁與翻譯）**：D1、D2、E1、E2、E3。
6. **需要先決定方向**：A3（核心按需下載）、G1（大陸預設值）、H1（除錯開關）。
7. **最後**：I2、I3、F2、C3。

每一批做完都要在模擬器跑一輪基本操作，再裝到手機上實測，並且一定要測 AI 翻譯。
