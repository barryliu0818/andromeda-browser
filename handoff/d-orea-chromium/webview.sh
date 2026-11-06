#!/usr/bin/env bash
# 編 Orea 專用的系統 WebView（套件 com.liquid.orea.webview）：Orea 執行時把 WebView 底層換成它，原有功能不用改。
# arm64 馬上編（out/Release 空著）；x64 等 extra2 的 x64 編譯結束再編。結果寫到 ~/logs/webview_status。
exec >> ~/logs/webview.log 2>&1
export PATH="$HOME/depot_tools:$PATH"; [ -f ~/proxy.env ] && source ~/proxy.env
cd ~/chromium/src
arg='system_webview_package_name = "com.liquid.orea.webview"'
r=""
for out in Release ReleaseX64; do
  if [ $out = ReleaseX64 ]; then until [ -s ~/logs/extra2_status ]; do sleep 60; done; fi
  grep -q system_webview_package_name out/$out/args.gn || echo "$arg" >> out/$out/args.gn
  echo "=== $(date '+%F %T') $out system_webview_apk"
  if autoninja -C out/$out system_webview_apk || autoninja -C out/$out -j 16 system_webview_apk; then r="$r ${out}_OK"; else r="$r ${out}_FAIL"; fi
done
echo "WEBVIEW$r" | tr ' ' '_' > ~/logs/webview_status
ls -la out/*/apks/SystemWebView*.apk
