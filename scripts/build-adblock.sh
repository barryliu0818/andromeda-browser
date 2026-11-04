#!/usr/bin/env bash
# 編譯廣告攔截引擎 liboreaadblock.so（native/adblock，adblock-rust）→ app/src/main/jniLibs/{arm64-v8a,x86_64}
# 需要：D:\rust（Rust 工具鏈與 NDK 連結器設定，見 /d/rust/env.sh）
set -euo pipefail
source /d/rust/env.sh
cd "$(dirname "$0")/../native/adblock"
out=../../app/src/main/jniLibs
for pair in aarch64-linux-android:arm64-v8a x86_64-linux-android:x86_64; do
  target=${pair%%:*} abi=${pair##*:}
  cargo build --release --target "$target"
  mkdir -p "$out/$abi"
  cp "$CARGO_TARGET_DIR/$target/release/liboreaadblock.so" "$out/$abi/"
done
ls -la "$out"/*/liboreaadblock.so
