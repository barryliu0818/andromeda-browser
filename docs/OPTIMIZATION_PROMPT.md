# 執行優化用的 Prompt

用法：開一個新的 Claude Code 工作階段（工作目錄 `D:\Dev\andromeda-browser`），把下面「Prompt 本文」整段貼上，並把最後一行的 `【這次要做】` 換成要做的批次。一次做一批，做完驗收再開下一批。

各批次的內容在 [OPTIMIZATION.md](OPTIMIZATION.md)，編號（A1、B2…）對應那份文件。

---

## Prompt 本文

```text
你要幫 Orea 這個 Android 瀏覽器做效能與體積優化。Orea 是我自己用的瀏覽器（Kotlin、Jetpack Compose、
android.webkit.WebView，可換上自己編的 Chromium 核心），介面是 Safari 風格，功能行為照 Brave。
我不看程式碼，只看結果，所以請你自己把每一步驗證到位，最後用白話告訴我改了什麼、量到什麼。

## 先讀這些

1. docs/OPTIMIZATION.md：優化項目清單。每一項有現況、做法、風險、驗證方式。
   這份清單是讀程式碼推論出來的，沒有實測過。動手前先確認現況真的如文件所寫
   （行號可能已經位移，以程式碼為準）；發現文件寫錯就照實告訴我，並把文件改對。
2. handoff/README.md：這台電腦的環境和還沒完成的事。
3. 你的記憶（MEMORY.md）裡的規則。

## 環境

- 所有東西放 D 槽。Android SDK 在 D:\Android。
- adb 用 D:\Android\platform-tools-35\adb.exe（新版 adb 連不上我的手機）。
- 手機序號 491d3cc5（Xiaomi 15 Ultra），要插在機殼背面的 USB 孔才連得上。
  套件名稱 com.liquid.browser.next。
- 模擬器：Pixel_A17_2（x86_64）。
- 建置：.\gradlew.bat :app:assemblePersonalRelease
- 這台電腦沒有 Python。需要的話先問我，裝在 D 槽。
- 我在中國大陸，手機通常沒開 VPN：Google、GitHub、Supabase 連不上或不穩。
  電腦有開代理，所以電腦上測得通不代表手機上也通。

## 一定要遵守

- 金鑰、簽章檔、local.properties、keystore.properties 永遠不進 git。
- 功能行為照 Brave（brave-core／Chromium 的原始碼），不要自己發明行為；
  這次是優化，使用者看得到的行為不應該改變，除非那一項本來就是在修錯誤。
- 每完成一項就 commit 並 push，一項一個 commit，訊息寫清楚做了什麼、為什麼。
- 回覆我一律用繁體中文。
- 操作手機前先看 mCurrentFocus：我正在用別的 App 就不要動畫面，只能做背景安裝這類不打擾的事。
  不要在手機上留下測試資料。
- 移除手機上的 App、清除 App 資料之前要先問我。
- 不要代我輸入任何密碼或登入任何帳號。

## 做法

1. 先量基準，改完再量一次，用同樣的方法。沒有量到改善就照實說，不要用「應該會變快」帶過。
   - 冷啟動：adb shell am start -W（取 5 次的中位數）
   - 記憶體：adb shell dumpsys meminfo com.liquid.browser.next
   - 卡頓：手機上的 events.log（scripts/buglogs.ps1，裡面的 adb 路徑要先改對），
     看 jank、freeze、anr、memory、renderer、slow 各有幾筆
   - APK：檔案大小，和 unzip -v 列出的各檔壓縮後大小
   - 網頁內的腳本耗時：自用版已開 WebView 遠端除錯，可用 chrome://inspect 的 Performance 面板
2. 一次只做一項。做完先跑單元測試（.\gradlew.bat :app:testPersonalDebugUnitTest），
   再建置，再到模擬器上實際操作一遍受影響的功能。
3. 每一批全部做完後，在模擬器跑一輪基本操作：開網頁、返回／前進、開關分頁、分頁總覽、
   翻譯一個英文頁面成繁體中文、擋廣告的網站、下載一個檔案、設定頁每一項開關、
   殺掉 App 重開確認分頁還原。然後裝到手機上（覆蓋安裝），等我有空時請我實測。
4. AI 翻譯每一批都要測，不管那一批有沒有動到翻譯。
5. 動到資料庫結構要寫 Room migration，不能讓舊資料消失。
6. 文件裡標了「先確認再改」「要先決定方向」的項目（B2 要先重現問題；A3、G1、H1 要先問我），
   不要直接做。
7. 遇到文件沒寫到的問題：跟這一批有關而且小，就一起修並告訴我；
   無關或比較大的，記進 docs/OPTIMIZATION.md 的對應段落，不要順手擴大範圍。
8. 一項做到一半發現風險比文件寫的大（例如要改的地方比預期多很多、會改變使用者看得到的行為），
   停下來告訴我情況和你的建議，等我決定。

## 完成後回報

每一批結束時給我一份簡短的報告：

- 做了哪幾項、各自的 commit。
- 每一項改動前後量到的數字（沒量到差別也要寫）。
- 哪些測過、在哪裡測的（模擬器或手機）、哪些還沒測。
- 文件裡有哪些地方跟實際不符，已經怎麼更正。
- 需要我決定或需要我在手機上確認的事。

做完的項目在 docs/OPTIMIZATION.md 的總表裡標上「已完成」和 commit 編號。

【這次要做】第一批：F1、A2、I1、D3、D4。
```

---

## 各批次要換上的最後一行

| 批次 | 最後一行換成 |
|---|---|
| 第一批（小而確定） | `【這次要做】第一批：F1、A2、I1、D3、D4。` |
| 第二批（安裝包） | `【這次要做】第二批：A1。子集化工具要裝什麼先跟我說。做完每個畫面都要截圖確認圖示沒有變成文字。` |
| 第三批（啟動與記憶體） | `【這次要做】第三批：先重現 B2 並告訴我結果，然後依序做 B1、C1、C2、B3。B1 和 C1 共用「分頁可以沒有 WebView」這個設計，動手前先把設計和會改到的地方列給我看。` |
| 第四批（網頁與翻譯） | `【這次要做】第四批：D1、D2、E1、E2、E3。E2 先只換 Translator，其他連網的地方等我確認翻譯沒問題再換。` |
| 收尾 | `【這次要做】F2、C3、I3。I2（拆分 BrowserViewModel）先給我拆分方案，不要直接動手。` |

需要先決定方向的三項（A3 核心按需下載、G1 大陸預設值、H1 除錯開關），想做的時候直接跟 Claude 說想法，讓它先給方案。
