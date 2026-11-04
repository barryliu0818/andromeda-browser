#!/usr/bin/env bash
exec >> ~/logs/build64.log 2>&1
export PATH="$HOME/depot_tools:$PATH"; [ -f ~/proxy.env ] && source ~/proxy.env
cd ~/chromium/src
echo "=== $(date "+%F %T") 64 位元核心"
if autoninja -C out/Official system_webview_64_apk; then echo OK > ~/logs/build64_status; else echo FAIL > ~/logs/build64_status; fi
ls -la out/Official/apks/
