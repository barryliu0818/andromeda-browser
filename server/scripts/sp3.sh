p=http://127.0.0.1:7890
for i in 1 2; do printf 'googlesource try%s ' $i; curl -s -o /dev/null -x $p --max-time 20 -w '%{http_code} %{time_total}s\n' https://chromium.googlesource.com/ || echo FAIL; done
printf 'googlesource 20s download: '; curl -s -o /dev/null -x $p --max-time 20 -w '%{speed_download}B/s %{size_download}B\n' https://chromium.googlesource.com/chromium/src/+archive/refs/heads/main/third_party/blink/renderer/core.tar.gz || echo FAIL
printf 'gcs 20s download: '; curl -s -o /dev/null -x $p --max-time 20 -w '%{speed_download}B/s\n' https://storage.googleapis.com/chrome-infra/depot_tools.zip || echo FAIL
