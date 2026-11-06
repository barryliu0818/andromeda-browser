#!/bin/bash
# 用法：frames.sh <serial> <名稱> "<adb shell 動作指令>"
# 清掉 gfxinfo → 執行動作 → 印出總幀數、卡頓比例、幀時間分位數
S=$1; NAME=$2; ACT=$3; P=com.liquid.browser.next
export MSYS_NO_PATHCONV=1
adb -s $S shell dumpsys gfxinfo $P reset >/dev/null
adb -s $S shell "$ACT"
sleep 1
adb -s $S shell dumpsys gfxinfo $P | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile" | tr -s ' ' | paste -sd'|' | sed "s/^/[$NAME] /"
