#!/usr/bin/env bash
exec >> ~/logs/official.log 2>&1
export PATH="$HOME/depot_tools:$PATH"; [ -f ~/proxy.env ] && source ~/proxy.env
cd ~/chromium/src
echo "=== $(date "+%F %T") 正式版開始編譯（ThinLTO＋PGO＋AFDO）"
if autoninja -C out/Official system_webview_apk || autoninja -C out/Official -j 16 system_webview_apk; then echo OFFICIAL_OK > ~/logs/official_status; else echo OFFICIAL_FAIL > ~/logs/official_status; fi
echo "=== $(date "+%F %T") 結束"; ls -la out/Official/apks/
