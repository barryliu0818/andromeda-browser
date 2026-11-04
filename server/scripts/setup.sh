#!/usr/bin/env bash
# Orea 編譯機初始化（原版 Chromium，Android arm64）：Ubuntu 22.04 x86_64、root。
# 系統套件 → depot_tools → 下載 Chromium 原始碼（不含歷史紀錄，約 30GB）→ Android 編譯相依 → runhooks → 產生編譯設定。
# 可以重複執行：做完的步驟會跳過；下載中斷時重跑會用 gclient sync 接著抓。全部輸出記在 ~/logs/setup.log。
# 國內機房：先跑 proxy.sh（寫出 ~/proxy.env），本腳本會自動套用。
set -eo pipefail
mkdir -p ~/logs
exec > >(tee -a ~/logs/setup.log) 2>&1
step() { echo; echo "=== $(date '+%F %T') $*"; }
# root 直接執行；一般使用者（例如租用的物理機）用 sudo（要先設定免密碼 sudo）
SUDO=; [ "$(id -u)" -eq 0 ] || SUDO=sudo

if [ -f ~/proxy.env ]; then
  step "套用代理 ~/proxy.env"
  # shellcheck disable=SC1090
  source ~/proxy.env
  git config --global http.proxy "$HTTPS_PROXY"
fi
git config --global core.autocrlf false
git config --global core.filemode false
git config --global http.postBuffer 524288000

step "系統套件"
export DEBIAN_FRONTEND=noninteractive
$SUDO -E apt-get update -y
$SUDO -E apt-get install -y git curl wget python3 python3-pip lsb-release sudo build-essential tmux htop unzip ca-certificates file

step "depot_tools"
[ -d ~/depot_tools ] || git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git ~/depot_tools
grep -q depot_tools ~/.bashrc || echo 'export PATH="$HOME/depot_tools:$PATH"' >> ~/.bashrc
export PATH="$HOME/depot_tools:$PATH"

step "下載 Chromium 原始碼（Android；第一次最久）"
mkdir -p ~/chromium && cd ~/chromium
if [ ! -f .gclient ]; then
  fetch --nohooks --no-history android
else
  # 上次中斷：接著抓
  gclient sync --nohooks --no-history -D
fi

step "Android 編譯相依"
cd ~/chromium/src
build/install-build-deps.sh --android --no-prompt || echo WARN-deps-partial

step "gclient runhooks（下載編譯器、Android SDK 等工具）"
gclient runhooks

step "產生編譯設定 out/Release"
mkdir -p out/Release
cat > out/Release/args.gn <<'EOF'
target_os = "android"
target_cpu = "arm64"
is_debug = false
is_component_build = false
# 不含除錯符號：編譯快、輸出小
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
# 播放 H.264／AAC（多數影片網站要用；個人自用）
proprietary_codecs = true
ffmpeg_branding = "Chrome"
treat_warnings_as_errors = false
EOF
gn gen out/Release

step "完成：可以執行 build.sh"
df -h / | tail -1
