date
for u in https://storage.googleapis.com/chrome-infra/depot_tools.zip \
         https://chromium.googlesource.com/chromium/tools/depot_tools.git/+archive/refs/heads/main.tar.gz \
         https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-linux-amd64-compatible-v1.19.33.gz \
         https://mirrors.aliyun.com/ubuntu/ls-lR.gz; do
  printf '%-60s ' "$(echo $u | cut -d/ -f3)"; curl -s -o /dev/null --max-time 20 -w '%{http_code} %{speed_download}B/s %{size_download}B\n' "$u" || echo FAIL
done
env | grep -i _proxy || echo "no proxy env"
ps aux | grep -c '[g]it'
