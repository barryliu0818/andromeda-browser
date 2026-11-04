#!/usr/bin/env bash
# 正式最佳化版的 Orea WebView 核心（is_official_build：ThinLTO＋PGO）。非正式版的網頁繪製約慢一倍（手機實測 46% vs 11% 掉幀）。
# 結果寫到 ~/logs/official_status
exec >> ~/logs/official.log 2>&1
export PATH="$HOME/depot_tools:$PATH"; [ -f ~/proxy.env ] && source ~/proxy.env
cd ~/chromium/src
echo "=== $(date '+%F %T') 下載 PGO 設定檔"
pgo=2
python3 tools/update_pgo_profiles.py --target=android-arm64 update --gs-url-base=chromium-optimization-profiles/pgo_profiles || { echo "PGO 下載失敗，改用沒有 PGO 的正式版"; pgo=0; }
mkdir -p out/Official
grep -v chrome_public_manifest_package out/Release/args.gn > out/Official/args.gn
printf 'is_official_build = true\nchrome_pgo_phase = %s\n' "$pgo" >> out/Official/args.gn
gn gen out/Official || { echo OFFICIAL_GN_FAIL > ~/logs/official_status; exit 1; }
echo "=== $(date '+%F %T') 開始編譯"
if autoninja -C out/Official system_webview_apk || autoninja -C out/Official -j 16 system_webview_apk; then
  echo "OFFICIAL_OK_pgo$pgo" > ~/logs/official_status
else
  echo "OFFICIAL_FAIL" > ~/logs/official_status
fi
echo "=== $(date '+%F %T') 結束"; ls -la out/Official/apks/
