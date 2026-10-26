# Liquid 瀏覽器

Android 瀏覽器，介面參照 iOS 26／27 Safari 的精簡版面與液態玻璃風格，內建 YouTube 式手勢的原生影片播放器。

- Kotlin、Jetpack Compose（Material 3）、單一 Activity、MVVM
- WebView（Chromium）、Media3 ExoPlayer（MP4／HLS／DASH）
- Room（書籤、歷史、閱讀列表、網站圖示、播放進度）、DataStore（設定）
- 液態玻璃：[Haze](https://github.com/chrisbanes/haze) 2.1（AGSL 折射＋模糊＋高光，玻璃容器 metaball 融合）
- minSdk 26、targetSdk／compileSdk 37（Android 17）

## 專案結構

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── gesture_layer.js     JS 手勢層（blob:/MediaSource 影片用，行為與原生端一致）
│   ├── video_detect.js      MutationObserver 偵測 <video>，顯示「用原生播放器播放」按鈕
│   └── reader.js            閱讀器模式
└── java/com/liquid/browser/
    ├── MainActivity.kt      edge-to-edge、120Hz、子母畫面、JankStats、區域網路權限
    ├── core/
    │   ├── Tokens.kt        ★ 動畫（彈簧、時長）、玻璃（模糊、折射、高光）、尺寸、色彩：調手感只改這裡
    │   ├── HighRefreshRate.kt  選最高刷新率顯示模式、Surface.setFrameRate
    │   └── Images.kt        Coil（使用 WebView User-Agent）
    ├── data/                Room 資料表與 DAO、DataStore 設定
    ├── browser/             ViewModel、Tab（WebView 包裝）、下載
    ├── player/
    │   ├── VideoGestures.kt     ★ 原生手勢模組（長按 2x、雙擊累加 ±10 秒、拖動進度、亮度／音量）
    │   ├── GestureFeedback.kt   手勢視覺回饋（2x 膠囊、半圓漣漪、時間提示）
    │   ├── PlayerController.kt  ExoPlayer、標頭、進度記憶
    │   └── PlayerScreen.kt      播放器畫面與玻璃控制列
    └── ui/
        ├── glass/
        │   ├── LiquidGlass.kt   ★ 液態玻璃模組（含降級方案、降低透明度）
        │   ├── GlassMenu.kt     從按鈕「長出」的玻璃選單
        │   └── GlassSheet.kt    可往下拖曳關閉的玻璃底部面板
        ├── browser/         主畫面、底部控制列、網址建議、頁面選單、長按連結預覽
        ├── tabs/            分頁總覽與 shared element 轉場
        ├── start/           起始頁（常用網站／書籤／閱讀列表／歷史紀錄）
        ├── settings/        設定頁
        ├── Gestures.kt      拖曳排序、滑動刪除
        └── Icons.kt         Material Symbols Rounded（細線條）
baselineprofile/             Baseline Profile 產生器與 Macrobenchmark
```

## 編譯與執行

開發工具全部裝在 `~/Desktop/AndroidDev.nosync`（JDK 21、Android SDK、模擬器、Gradle 快取、建置輸出）。
資料夾名稱以 `.nosync` 結尾，iCloud 不會同步它。專案結束後刪除這個資料夾即可移除全部工具。

```bash
source ~/Desktop/AndroidDev.nosync/env.sh
```

啟動 Android 17 模擬器：

```bash
emulator -avd Pixel_A17_2 -gpu host &
```

編譯並安裝 debug 版：

```bash
./gradlew :app:installDebug
```

編譯 release 版（R8 最佳化＋Baseline Profile，目前以 debug 金鑰簽章方便測試）：

```bash
./gradlew :app:assembleRelease
```

APK 輸出位置：`~/Desktop/AndroidDev.nosync/build/LiquidBrowser/app/outputs/apk/`

## 120Hz 與效能測試

### 確認實際刷新率（需真機，模擬器只有 60Hz）

1. 手機「設定 → 顯示」開啟高刷新率／流暢模式。
2. 「開發人員選項 → 顯示重新整理頻率」開啟，畫面左上角會顯示目前 Hz。
3. 開啟 App，捲動網頁、收合網址列、進出分頁總覽、拖動播放器進度，數字應維持在 120。

App 啟動時選擇解析度相同、刷新率最高的顯示模式（`preferredDisplayModeId`），Android 15+ 另要求高幀率類別，
影片 Surface 由 ExoPlayer 依影片幀率呼叫 `setFrameRate`（`core/HighRefreshRate.kt`）。

### 即時掉幀紀錄（JankStats）

```bash
adb logcat -s LiquidJank
```

每筆掉幀會附上當時畫面（`screen=browse／start／overview`）。

### Baseline Profile

產生（需連接裝置或模擬器，會執行開網頁、捲動、進出分頁總覽）：

```bash
./gradlew :app:generateBaselineProfile
```

結果寫入 `app/src/release/generated/baselineProfiles/`，release 版會自動打包。

### Macrobenchmark

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

- `coldStartup`：冷啟動時間（`timeToInitialDisplayMs`）
- `scrollAndOverview`：捲動、網址列收合、分頁總覽轉場的逐幀耗時（`frameDurationCpuMs`、`frameOverrunMs` 的 P50／P90／P99）

120Hz 每幀預算為 8.3ms：`frameOverrunMs` 應為負值（有餘裕）。模擬器數據只能參考，正式量測請用真機。

### 手動測試影片手勢

- MP4：在網址列輸入 `.mp4` 網址會直接開原生播放器
- HLS：`https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`
- JS 手勢層：任何以 blob:／MediaSource 播放的網頁影片（例如 YouTube 行動版）

## 與規格的差異

| 項目 | 說明 |
| --- | --- |
| 按壓膨脹 1.05 倍 | Haze 玻璃的互動縮放上限為 1，改由 `pressInflate`（graphicsLayer）實作 |
| 降低透明度 | Haze 內建的設定沒有作用，改在 `LiquidGlass` 自行繪製不透明底＋高光邊框 |
| 狀態列 | 網頁頂端內容從狀態列下方開始（否則會被時間與圖示蓋住），狀態列以 theme-color／頁面背景色染色；底部仍延伸到控制列與手勢列下方 |
| 起始頁清單刪除 | 起始頁左右滑動用於切換分段，所以歷史紀錄與閱讀列表改用長按選單刪除 |
| 影片畫面 | 使用 TextureView（而非 SurfaceView），玻璃控制列才能取樣影片畫面 |
| 總覽底部元件 | 進出總覽時底部元件以淡入淡出切換，尚未做成三個元件直接形變 |
| 毛玻璃 | Haze 玻璃的模糊上限約 38px（偏透明玻璃），Android 13+ 改為「hazeBlur 強模糊底層 → 液態玻璃折射／高光 → 自繪霧面色調」三層；玻璃容器內成員會忽略 Haze 的 tint，所以色調一律由 `liquidGlass` 自己繪製。參數在 `GlassTokens`（`BlurRadius`、`TintLight`、`TintDark`、`FrostContrast`） |
| WebView 模糊驗證 | Haze 能直接擷取 WebView（API 37.0 錄製來源路徑與 37.2 原生 backdrop 路徑皆實測可行），未使用定時截圖備案 |

## 下載引擎

`browser/DownloadEngine.kt`，類似電腦版 IDM：

- 伺服器支援 Range 時最多 8 條連線同時下載；任一連線做完就把剩餘最多的那段切一半接手（動態分段），不會有連線閒著
- 暫停／繼續從斷點接著下，網路錯誤每段自動重試
- 影片：MP4／WebM 等直接多連線下載；HLS（m3u8）自動選最高畫質、並行下載片段（支援 AES-128）後合併成 .ts（fMP4 為 .mp4）
- 入口：網頁下載、長按圖片儲存、原生播放器右上角的下載鈕；頁面選單「下載項目」看進度（速度、剩餘時間、連線數）
- 完成後存到系統「下載」資料夾；下載期間以前景服務保活並在通知列顯示進度
- 限制：blob:／MediaSource 串流（例如 YouTube）與 DASH（.mpd）無法下載；任務清單只存在記憶體，App 被系統結束後不保留

## 網頁翻譯

頁面選單「翻譯網頁」：原地替換頁面文字（只送文字、不送 HTML），點網址列上方的「已翻譯」切回原文。

- 引擎：免費（Google，免金鑰）、通義千問 3.8 Flash、DeepSeek V4.1 Flash、智譜 GLM-5.3 Flash；後三者經阿里雲百煉（`cn-beijing.maas.qianwenaiapi.com`），共用一把金鑰
- 金鑰：安裝包不含任何金鑰，每個人在設定 →「網頁翻譯」→「API 金鑰」按「貼上」填自己的；GLM-5.3 Flash 需先在百煉控制台開通
- 夜間（22:00–08:00）可優先改用 DeepSeek（設定可關閉）
- 翻過的文字存在手機上（`translations.db`），重新整理或再次造訪不再花額度；只翻畫面附近，捲動時補翻
- 自己編譯時可在 `local.properties` 填 `QWEN_API_KEY=` 當預設金鑰（會編進 APK，分享前請清空）

## 識別為 iPhone（Safari）

設定 →「網站識別為 iPhone（Safari）」，預設開啟（`browser/Tab.kt` 的 `applyIdentity`）：

- User-Agent：行動版為 iPhone Safari（iOS 26 格式），桌面版為 macOS Safari
- Client Hints：`Sec-CH-UA-Platform` 為 iOS／macOS，`Sec-CH-UA` 只留中性佔位品牌（不出現 Android WebView、Chromium）
- 頁面 JS：`navigator.platform`、`vendor`、`maxTouchPoints` 改為 Apple 的值，移除 Safari 沒有的 `userAgentData`
- `X-Requested-With`：支援 allow-list 的 WebView 完全不送；不支援的版本（例如 WebView 149）只能把主頁面請求覆寫為空值，頁面內點連結產生的請求仍會帶套件名稱

注意：網頁引擎仍是 Chromium，部分網站（例如 Google）可能因「宣稱 Safari 但行為像 Chrome」或流量異常而要求人機驗證；遇到問題可在設定關閉此選項。

## 已知限制

- 玻璃元件不能放在常駐的祖先 `graphicsLayer` 內（原生 backdrop 會取樣不到網頁）；位移請用 `Modifier.offset`，淡出／縮放只在動畫進行中加 layer。
- Haze 玻璃容器（metaball）目前只在 `2.1.0-SNAPSHOT`；正式版發佈後修改 `gradle/libs.versions.toml` 並移除 `settings.gradle.kts` 的 snapshot 倉庫。
- Material Symbols 字型目前完整打包（約 15MB），之後可子集化。
- Android 11 降級畫面（85% 不透明純色底）已實作，尚未在模擬器驗證。
