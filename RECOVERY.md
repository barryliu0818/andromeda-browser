# 還原說明（2026-10-26）

原本的專案資料夾與開發環境在 2026-10-23～26 間被清理軟體刪除，垃圾桶與 iCloud 都沒有備份。
這份程式碼是從 Claude Code 的對話記錄依時間順序重放每一次讀寫與修改重建而成。

## 已還原
- `app/src/main/java/**`：全部 Kotlin 原始碼（分頁、網頁、系統模糊玻璃、選單面板、設定、播放器、下載、翻譯、廣告阻擋、多國語言）
- `app/src/main/assets/`：注入網頁的腳本（手勢層、影片偵測、翻譯、閱讀器）與內建廣告清單
- `app/src/main/res/`：App 圖示、主題、檔案分享設定
- `build.gradle.kts`（根目錄）、`baselineprofile/build.gradle.kts`

最後幾天的 16 項功能已逐項核對存在（下拉重新整理、緩慢漸進動畫、GPU 快取、預備視窗池、小米樣式、播放器玻璃、選單互斥、「前往」按鈕、預設瀏覽器、下載影片用內建播放器等）。

## 尚未還原（記錄裡沒有完整內容，需要重寫）
- `settings.gradle.kts`、`app/build.gradle.kts`、`gradle/libs.versions.toml`、`gradle.properties`、Gradle wrapper
- `app/src/main/AndroidManifest.xml`
- `app/proguard-rules.pro`
- baseline profile 規則檔

已知版本：Kotlin 2.4.21、Compose BOM 2026.09.00、Gradle 9.8.2、Haze 2.1.0 測試版（鎖定 2.1.0-20261022.145728-86）。

## 簽章
原本的簽章金鑰無法救回。用新金鑰建置的版本不能覆蓋安裝舊版，需先解除安裝舊版。

## 2026-10-26 接手後重寫（Windows）
- 已重寫：`settings.gradle.kts`、`app/build.gradle.kts`（personal／share 兩版）、`gradle/libs.versions.toml`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`AndroidManifest.xml`、`app/proguard-rules.pro`
- 根目錄 `build.gradle.kts` 移除了把建置輸出導到 `~/Desktop/AndroidDev.nosync` 的設定（專案已在不同步的 `~/Developer/andromeda`）
- Haze：原本鎖定的 `2.1.0-20261022.145728-86` 已被快照倉庫刪除，改鎖 2026-10-26 的快照（haze -152、haze-blur／haze-glass -150）；已確認可編譯，執行時表現還沒在實機驗證
- 已補：圖示字型（Google 官方 material-design-icons）、Gradle wrapper（鎖定 9.8.2 校驗碼）；搜尋引擎標誌暫用 Segoe UI 文字輪廓向量圖，之後換正式圖檔
- 仍缺：`baselineprofile/src`（產生器與 Macrobenchmark 測試）
- 編譯修正：Tokens.kt 重複 import、repeatOnLifecycle 呼叫寫法、補 LocalHideBlurWindows import 與 DEBUG_FORCE_SYSBLUR、長按返回改為列出上一頁歷史（原本實作不在記錄裡，依常見行為重寫）
