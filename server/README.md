# Orea Chromium 編譯伺服器備份（2026-11-05）

編譯伺服器（ai.bygpu.com）到期前的備份。只含我們自己的東西；Chromium 原始碼（約 125GB）照 `patches/chromium-HEAD.txt` 的版本重新抓。

- `scripts/`：環境安裝（setup.sh）、代理（proxy.sh）、品牌（brand*.sh）、編譯（build*.sh、official*.sh、webview.sh、pipeline.sh）
- `args/`：各輸出資料夾的 `args.gn`（Official＝正式最佳化版：PGO＋AFDO）
- `patches/chromium.diff`：對 Chromium 原始碼的修改；`chromium-new-files.tgz`：新增的圖示資源
- `orea_icon/`：App 圖示
- `*_status`：最後幾次編譯的結果

沒備份：`mihomo/config.yaml`（代理帳號，含金鑰）、編譯紀錄（logs，95MB）、編譯產物（APK 已在 GitHub Release 與 D:\orea-chromium）。

還原：抓 Chromium 到 HEAD 那個版本 → `git apply patches/chromium.diff` → 在 `src/` 解開 `chromium-new-files.tgz` → 把 `args/Official.args.gn` 放到 `out/Official/args.gn` → `autoninja -C out/Official system_webview_64_apk`
