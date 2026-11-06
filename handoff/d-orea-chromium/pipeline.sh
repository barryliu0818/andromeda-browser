#!/usr/bin/env bash
# 無人值守：等 setup.sh 完成 → 套用 Orea 品牌（名字、圖示、套件名）→ 編譯；品牌版編譯失敗就還原、改編原版保底。
# 進度寫在 ~/logs/status（一行）：WAIT_SETUP / SETUP_FAIL / BRANDING / BUILDING / BUILD_OK <apk> / BUILD_FAIL
set -o pipefail
mkdir -p ~/logs
exec >> ~/logs/pipeline.log 2>&1
st() { echo "$*" > ~/logs/status; echo "=== $(date '+%F %T') $*"; }
export PATH="$HOME/depot_tools:$PATH"
if [ -f ~/proxy.env ]; then source ~/proxy.env; fi

st WAIT_SETUP
while ! grep -q '完成：可以執行 build.sh' ~/logs/setup.log 2>/dev/null; do
  if ! tmux has-session -t orea 2>/dev/null; then
    sleep 20; grep -q '完成：可以執行 build.sh' ~/logs/setup.log && break
    # 多半是網路斷線：自動重跑 setup（做完的步驟會跳過），最多 2 次
    tries=$((${tries:-0}+1))
    if [ $tries -le 2 ]; then st "SETUP_RETRY $tries"; tmux new -d -s orea 'bash ~/setup.sh'; st WAIT_SETUP
    else st SETUP_FAIL; exit 1; fi
  fi
  sleep 60
done

cd ~/chromium/src
st BRANDING
bash ~/brand.sh || echo "brand.sh 有錯，略過品牌"

# 失敗先原樣重試（ninja 接著編、網路抖動），再用 16 並行重試（防記憶體不足）
try_build() { bash ~/build.sh || { st BUILD_RETRY; sleep 30; bash ~/build.sh; } || { st BUILD_RETRY_J16; JOBS=16 bash ~/build.sh; }; }
st BUILDING
if try_build; then
  st "BUILD_OK $(ls -t out/Release/apks/ChromePublic.apk)"
  exit 0
fi
echo "品牌版編譯失敗，還原後改編原版"
git -C ~/chromium/src checkout -- chrome/android 2>/dev/null; git -C ~/chromium/src clean -fdq chrome/android/java/res_chromium_base 2>/dev/null
sed -i '/chrome_public_manifest_package/d' out/Release/args.gn; gn gen out/Release
if try_build; then st "BUILD_OK $(ls -t out/Release/apks/ChromePublic.apk) (vanilla)"; else st BUILD_FAIL; fi
