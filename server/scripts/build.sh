#!/usr/bin/env bash
# 編譯 Chromium Android（arm64、Release、不含除錯符號），輸出記在 ~/logs/build.log。
# 產物：~/chromium/src/out/Release/apks/ChromePublic.apk（開源版 Chrome，之後再改成 Orea）
set -eo pipefail
mkdir -p ~/logs
exec > >(tee -a ~/logs/build.log) 2>&1
if [ -f ~/proxy.env ]; then source ~/proxy.env; fi
export PATH="$HOME/depot_tools:$PATH"
cd ~/chromium/src
echo "=== $(date '+%F %T') build start, $(nproc) cores, $(free -g | awk '/Mem/{print $2}')GB RAM"
time autoninja -C out/Release ${JOBS:+-j $JOBS} chrome_public_apk
echo "=== $(date '+%F %T') build done"
ls -la out/Release/apks/*.apk
