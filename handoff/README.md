# 換電腦交接（2026-11-06）

## 新電腦要手動搬的（不進 git）
- `local.properties`（sdk.dir、OREA_CORE_DIR）——用隨身碟或密碼管理器搬，sdk.dir 改成新電腦的路徑
- `~/.ssh/orea_build`、`orea_build.pub`（編譯伺服器 `ssh -i ~/.ssh/orea_build -p 32117 bygpu@ai.bygpu.com`）
- release 簽章檔（若有放在 repo 外）
- `~/.android/debug.keystore`：release 目前用它簽章，換了就不能覆蓋安裝手機上的 Orea（要先移除舊版）
- Orea 核心（`D:\orea-chromium\bin`、`core-assets`、`vendor`、`seen`）已放在 GitHub Release `handoff-2026-11-06` 的 `orea-core-files.tar.gz`：
  `gh release download handoff-2026-11-06 --repo barryliu0818/andromeda-browser`，再到 `D:\orea-chromium` 解壓 `tar -xzf orea-core-files.tar.gz`

## 還原
- `claude-memory/*.md` → 新電腦的 `~/.claude/projects/<專案資料夾>/memory/`
- `d-android/*.sh` → `D:\Android\`（模擬器腳本：tap.sh 依文字點擊、imp.sh、run_ui.sh、frames.sh、wait_install.sh 等手機接上自動安裝）
- `d-orea-chromium/*` → `D:\orea-chromium\`（Chromium/Orea 核心編譯腳本）
- 模擬器：裝 Android SDK 到 D:\Android，PowerShell 設 ANDROID_SDK_ROOT / ANDROID_HOME / ANDROID_AVD_HOME 再開

## 規則（使用者要求）
- 金鑰、簽章檔、local.properties 永遠不進 git
- 每次改動都 commit + push；回覆用繁體中文
- 功能照抄 Brave 的行為，換 Safari 風格前端，不要自行發揮
- 操作手機前先看 mCurrentFocus，使用者在用就不動；不要在真機留測試資料
- 一定要測 AI 翻譯
- 所有東西放 D 槽

## 待辦（最新 commit 845eb00 還沒裝上手機 491d3cc5）
1. 安裝：`adb -s 491d3cc5 install -r app/build/outputs/apk/personal/release/app-personal-release.apk`
2. 手機上驗證：
   - 無痕模式不再閃退（Android 17 updateServiceBindings 修正，commit 3e75716）
   - 無痕起始頁是黑色
   - 登入 Google 時跳出「儲存密碼」（使用者自己登入，不可代填密碼）
   - 「最近關閉的分頁」已移除、「降低透明度」已移除
   - AI 翻譯（8 種語言、切換目標語言整頁重翻）仍正常
3. 未重現：曾有一次第一次點翻譯卻打開設定
4. 手機上約 6 個測試分頁請使用者自行關閉
