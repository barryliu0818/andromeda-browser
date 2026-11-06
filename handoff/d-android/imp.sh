# imp.sh X Y : open settings → import, pick file at X,Y, print toast
S="-s emulator-5554"; export MSYS_NO_PATHCONV=1
adb $S shell am start -n com.liquid.browser.next/com.liquid.browser.MainActivity >/dev/null; sleep 3
adb $S shell input tap 242 2253; sleep 1.5; bash /d/Android/tap.sh Settings >/dev/null; sleep 1.5
adb $S shell input swipe 540 2000 540 400 300; sleep 0.6; adb $S shell input swipe 540 2000 540 900 300; sleep 1; bash /d/Android/tap.sh "Import bookmarks"
sleep 2.5; adb $S shell input tap $1 $2
sleep 1.2; adb $S exec-out screencap -p > /d/Android/toast_$3.png; sleep 3
adb $S shell input keyevent BACK
