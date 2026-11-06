#!/usr/bin/env bash
# 本機監看：每 5 分鐘檢查編譯機，有事件或異常就印出並退出（叫醒 Claude）；處理過的事件記在 seen，重跑不會重複通知。
# 防護：SSH 連不上、進度卡住（log 40 分鐘沒動）、tmux 工作意外消失、硬碟不足、代理掛掉（先自動重啟）、機器快到期。
K=/c/Users/barry/.ssh/orea_build; S="-i $K -p 32117 -o BatchMode=yes -o ConnectTimeout=20 bygpu@ai.bygpu.com"
D=/d/orea-chromium; SEEN=$D/seen; touch $SEEN
EXPIRE_WARN="2026-11-04 22:30"   # 機器 23:40 到期會被清空，提前叫醒把產物拉回來
seen() { grep -qxF "$1" $SEEN; }
mark() { echo "$1" >> $SEEN; }
wake() {
  echo "$(date '+%F %T') $*"
  ssh $S 'tail -8 ~/logs/pipeline.log; p=$(ls -t ~/logs/*.log | head -1); echo "--- $p"; tail -30 $p' 2>/dev/null | cut -c1-220
  exit 1
}
fails=0
while true; do
  r=$(ssh $S 'p=$(ls -t ~/logs/*.log | head -1); echo "$(cat ~/logs/status)|$(cat ~/logs/extra_status 2>/dev/null)|$(cat ~/logs/extra2_status 2>/dev/null)|$(cat ~/logs/webview_status 2>/dev/null)|$(( $(date +%s) - $(stat -c %Y $p) ))|$(df -BG --output=avail / | tail -1 | tr -dc 0-9)|$(systemctl is-active mihomo)|$(tmux ls -F "#S" 2>/dev/null | tr "\n" ,)"' 2>/dev/null)
  if [ -z "$r" ]; then
    fails=$((fails+1)); [ $fails -ge 3 ] && wake "SSH_DOWN 連續 3 次連不上編譯機"
    sleep 300; continue
  fi
  fails=0
  IFS='|' read -r s x1 x2 x3 age disk px tm <<< "$r"
  [ -n "$DEBUG" ] && echo "status=$s extra=$x1 extra2=$x2 webview=$x3 log_age=${age}s disk=${disk}G proxy=$px tmux=$tm"

  if [ "$px" != active ]; then
    echo "$(date '+%F %T') 代理沒在跑，重啟 mihomo"
    ssh $S 'sudo systemctl restart mihomo; sleep 5; systemctl is-active mihomo' | grep -qx active || wake "PROXY_DOWN 代理重啟失敗"
  fi
  [ "${disk:-0}" -lt 30 ] && wake "DISK_LOW 剩 ${disk}G"

  case "$s" in
    BUILD_OK*) if ! seen BUILD_OK; then
        apk=$(echo "$s" | awk '{print $2}'); tag="orea-chromium-$(date +%Y%m%d-%H%M)"
        case "$apk" in /*) ;; *) apk="chromium/src/$apk" ;; esac   # status 裡是相對 ~/chromium/src 的路徑
        mkdir -p $D/out; scp -q -i $K -P 32117 "bygpu@ai.bygpu.com:$apk" "$D/out/Orea-chromium.apk" || wake "PULL_FAIL $s"
        ls -la $D/out/Orea-chromium.apk
        (cd /c/Users/barry/Developer/andromeda && gh release create "$tag" "$D/out/Orea-chromium.apk" --prerelease \
          --title "Orea (Chromium) $tag" --notes "自己編譯的 Chromium（Android arm64）改成 Orea。$s") && echo "RELEASED $tag"
        mark BUILD_OK; echo "DONE $s"; exit 0
      fi ;;
    SETUP_FAIL*|BUILD_FAIL*) seen "$s" || { mark "$s"; wake "FAILED $s"; } ;;
  esac
  for e in "$x1" "$x2" "$x3"; do [ -n "$e" ] && ! seen "$e" && { mark "$e"; echo "EVENT $e"; exit 0; }; done
  [ -n "$x2" ] && [ -n "$x3" ] && { echo "ALL_DONE"; exit 0; }

  # 工作意外消失（之前就是這樣卡了 7 小時）
  case "$s" in BUILD_OK*|BUILD_FAIL*|SETUP_FAIL*) ;; *) [[ ",$tm" == *",pipe,"* ]] || wake "PIPE_DEAD 主流程不見了（$s）" ;; esac
  [ -z "$x1" ] && [[ ",$tm" != *",extra,"* ]] && wake "EXTRA_DEAD WebView 核心的編譯工作不見了"
  [ -z "$x2" ] && [[ ",$tm" != *",extra2,"* ]] && wake "EXTRA2_DEAD x64 編譯工作不見了"
  [ -z "$x3" ] && [[ ",$tm" != *",webview,"* ]] && wake "WEBVIEW_DEAD Orea WebView 編譯工作不見了"
  # 卡住：所有 log 40 分鐘都沒動
  [ "${age:-0}" -gt 2400 ] && wake "STALL log ${age}s 沒更新（$s）"
  if [[ ! "$(date '+%F %H:%M')" < "$EXPIRE_WARN" ]] && ! seen EXPIRY; then mark EXPIRY; wake "EXPIRY_SOON 機器 23:40 到期"; fi
  sleep 300
done
