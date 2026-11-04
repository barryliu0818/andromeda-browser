#!/usr/bin/env bash
# 國內編譯機的代理：用 mihomo（Clash.Meta 核心）跑你的訂閱，讓 git、npm、gclient 連得到 Google 與 GitHub。
# 需要 ~/mihomo/config.yaml（你的訂閱設定，由你自己放上來）；mihomo 執行檔與 Geo 資料由 go.ps1 從本機上傳到 ~/mihomo。
# 完成後寫出 ~/proxy.env，setup.sh／build.sh 會自動套用。阿里雲自己的套件鏡像不走代理。
set -eo pipefail
SUDO=; [ "$(id -u)" -eq 0 ] || SUDO=sudo
M=~/mihomo
mkdir -p ~/logs
exec > >(tee -a ~/logs/proxy.log) 2>&1
[ -f $M/config.yaml ] || { echo "缺少 $M/config.yaml：先把訂閱設定放上來"; exit 1; }

if [ -f $M/mihomo-linux-amd64.gz ]; then gunzip -f $M/mihomo-linux-amd64.gz; fi
chmod +x $M/mihomo-linux-amd64
$SUDO ln -sf $M/mihomo-linux-amd64 /usr/local/bin/mihomo

# 代理埠：沿用設定檔裡的 mixed-port／port；都沒有就加上 mixed-port: 7890
port=$(grep -E '^(mixed-port|port):' $M/config.yaml | head -1 | awk '{print $2}' | tr -d '"\r')
if [ -z "$port" ]; then echo 'mixed-port: 7890' >> $M/config.yaml; port=7890; fi

$SUDO tee /etc/systemd/system/mihomo.service >/dev/null <<EOF
[Unit]
Description=mihomo proxy
After=network-online.target
[Service]
User=$(id -un)
ExecStart=/usr/local/bin/mihomo -d $M
Restart=always
RestartSec=3
[Install]
WantedBy=multi-user.target
EOF
$SUDO systemctl daemon-reload
$SUDO systemctl enable --now mihomo
$SUDO systemctl restart mihomo
sleep 4

p="http://127.0.0.1:$port"
cat > ~/proxy.env <<EOF
export HTTPS_PROXY=$p HTTP_PROXY=$p ALL_PROXY=$p https_proxy=$p http_proxy=$p all_proxy=$p
export NO_PROXY=localhost,127.0.0.1,::1,.aliyuncs.com,.aliyun.com no_proxy=localhost,127.0.0.1,::1,.aliyuncs.com,.aliyun.com
EOF

echo "== 代理埠 $port，測試連線（200/301/302 都算通）"
for u in https://chromium.googlesource.com https://github.com https://storage.googleapis.com https://registry.npmjs.org; do
  printf '%-40s ' "$u"; curl -s -o /dev/null -x "$p" --max-time 15 -w '%{http_code}  %{time_total}s\n' "$u" || echo FAIL
done
echo "== 下載速度（從 Google 下載 depot_tools 壓縮檔）"
curl -s -o /dev/null -x "$p" --max-time 60 -w '%{speed_download} bytes/s\n' \
  https://storage.googleapis.com/chrome-infra/depot_tools.zip || echo FAIL
systemctl is-active mihomo
