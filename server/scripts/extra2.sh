#!/usr/bin/env bash
# 等 AwShell（arm64）編完，再編一份 x86_64 版（電腦上的 Android 模擬器用）：ChromePublic 與 AwShell。
exec >> ~/logs/extra2.log 2>&1
export PATH="$HOME/depot_tools:$PATH"; [ -f ~/proxy.env ] && source ~/proxy.env
until grep -qE '^AWSHELL_' ~/logs/extra_status 2>/dev/null; do sleep 60; done
cd ~/chromium/src
mkdir -p out/ReleaseX64
sed 's/target_cpu = "arm64"/target_cpu = "x64"/' out/Release/args.gn > out/ReleaseX64/args.gn
gn gen out/ReleaseX64
echo "=== $(date '+%F %T') build x64"
if autoninja -C out/ReleaseX64 chrome_public_apk webview_instrumentation_apk || autoninja -C out/ReleaseX64 -j 16 chrome_public_apk webview_instrumentation_apk; then echo X64_OK > ~/logs/extra2_status; else echo X64_FAIL > ~/logs/extra2_status; fi
ls -la out/ReleaseX64/apks/ 2>/dev/null
