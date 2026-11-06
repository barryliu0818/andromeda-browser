#!/usr/bin/env bash
# 等主流水線編好 ChromePublic → 編嵌入式 WebView 核心（AwShell，Orea 換核心用）→ 套 Orea 品牌並增量重編 ChromePublic。
exec >> ~/logs/extra.log 2>&1
export PATH="$HOME/depot_tools:$PATH"; [ -f ~/proxy.env ] && source ~/proxy.env
until grep -qE '^BUILD_(OK|FAIL)' ~/logs/status 2>/dev/null; do sleep 60; done
cd ~/chromium/src
echo "=== $(date '+%F %T') build webview_instrumentation_apk"
if autoninja -C out/Release webview_instrumentation_apk || autoninja -C out/Release -j 16 webview_instrumentation_apk; then aw=AWSHELL_OK; else aw=AWSHELL_FAIL; fi
echo "=== $(date '+%F %T') branding + rebuild chrome_public_apk"
if bash ~/brand.sh && autoninja -C out/Release chrome_public_apk; then br=BRAND_OK; else br=BRAND_FAIL; fi
echo "$aw $br" > ~/logs/extra_status
ls -la out/Release/apks/ 2>/dev/null
