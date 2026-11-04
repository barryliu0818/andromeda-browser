#!/usr/bin/env bash
# 品牌版重編（第一次 BUILD.gn 只登記一份清單而失敗）；結果寫回 extra_status 讓本機監看叫醒 Claude。
exec >> ~/logs/extra.log 2>&1
export PATH="$HOME/depot_tools:$PATH"; [ -f ~/proxy.env ] && source ~/proxy.env
cd ~/chromium/src
echo "=== $(date '+%F %T') branding retry"
if bash ~/brand.sh && autoninja -C out/Release chrome_public_apk; then echo "AWSHELL_OK BRAND_OK" > ~/logs/extra_status; else echo "AWSHELL_OK BRAND_FAIL2" > ~/logs/extra_status; fi
