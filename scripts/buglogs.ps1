param([int]$Last = 60)
# Pull Andromeda/Orea bug logs from the phone: event counts by kind, last N events, newest auto reports.
$adb = 'C:\Users\barry\Developer\tools\android-sdk\platform-tools\adb.exe'
$pkg = 'com.liquid.browser.next'
$remote = "/sdcard/Android/data/$pkg/files/bugs"
$out = Join-Path $env:TEMP 'andromeda_bugs'
New-Item -ItemType Directory -Force $out | Out-Null
& $adb pull "$remote/events.log" "$out\events.log" 2>&1 | Out-Null
$lines = Get-Content "$out\events.log" -Encoding UTF8
"== counts by kind"
$lines | ForEach-Object { if ($_ -match '\[(\w+)\]') { $Matches[1] } } | Group-Object | Sort-Object Count -Descending | ForEach-Object { "{0,6} {1}" -f $_.Count, $_.Name }
"== last $Last (jank omitted)"
$lines | Where-Object { $_ -notmatch '\[jank\]' } | Select-Object -Last $Last
"== newest reports"
& $adb shell ls -t $remote | Select-Object -First 10
