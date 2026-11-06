#!/bin/bash
# 等手機接上就裝最新版；Orea 在前景（使用者正在用）就等它離開前景再裝
APK=/c/Users/barry/Developer/andromeda/app/build/outputs/apk/personal/release/app-personal-release.apk
for i in $(seq 1 1440); do
  if adb devices | grep -q "^491d3cc5\s*device"; then
    f=$(adb -s 491d3cc5 shell dumpsys window | grep mCurrentFocus)
    if ! echo "$f" | grep -qi liquid; then
      adb -s 491d3cc5 install -r "$APK" && echo "INSTALLED $(date +%H:%M) focus=$f" && exit 0
    fi
  fi
  sleep 10
done
echo "TIMEOUT: 4 小時內手機沒接上"
