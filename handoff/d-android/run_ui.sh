P="adb -s 491d3cc5"; out=$1
$P shell am start -a android.intent.action.VIEW -d "https://m.sohu.com/" com.liquid.browser.next >/dev/null; sleep 9
($P shell "cat /data/local/tmp/jank.pftxt | perfetto --txt -c - -o /data/misc/perfetto-traces/$out.pftrace" > /dev/null 2>&1 &); sleep 2
$P shell input tap 1293 3001; sleep 2.2; $P shell input tap 720 1500; sleep 2.2
$P shell input tap 343 3001; sleep 1.8; $P shell input keyevent BACK; sleep 1.5
$P shell input tap 343 3001; sleep 1.5; $P shell input tap 300 2966; sleep 2; $P shell input keyevent BACK; sleep 1.8
$P shell input swipe 900 3001 300 3001 250; sleep 2; $P shell input swipe 300 3001 900 3001 250; sleep 2
for i in 1 2; do $P shell input swipe 720 2300 720 900 200; sleep 1.2; done; $P shell input swipe 720 900 720 2300 200; sleep 6
$P pull /data/misc/perfetto-traces/$out.pftrace D:/Android/$out.pftrace >/dev/null; pid=$($P shell pidof com.liquid.browser.next)
export USERPROFILE='D:\perfetto-home' HOME=/d/perfetto-home
sed "s/pid=22906/pid=$pid/" D:/Android/u5.sql > D:/Android/q_$out.sql
py C:/Users/barry/.claude/skills/android-profiler/references/perfetto/bin/trace_processor D:/Android/$out.pftrace -q D:/Android/q_$out.sql 2>/dev/null | tail -6
