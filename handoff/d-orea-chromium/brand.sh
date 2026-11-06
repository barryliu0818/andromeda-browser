#!/usr/bin/env bash
# 把 Chromium 改成 Orea：App 名稱、桌面圖示（沿用 Orea 的 adaptive icon 向量）、套件名（可與原本的 App 同時安裝）。
# 每一項找不到對應的檔案或參數就略過，不影響編譯。圖示向量由本機上傳到 ~/orea_icon/。
set -o pipefail
cd ~/chromium/src
RES=chrome/android/java/res_chromium_base

# 套件名：com.liquid.orea
if grep -rqs 'chrome_public_manifest_package' chrome/android/*.gni chrome/android/BUILD.gn; then
  grep -q chrome_public_manifest_package out/Release/args.gn || echo 'chrome_public_manifest_package = "com.liquid.orea"' >> out/Release/args.gn
  echo "package: com.liquid.orea"
else echo "略過套件名（找不到 chrome_public_manifest_package）"; fi

# App 名稱
for f in $(grep -rls '>Chromium<' $RES/values* 2>/dev/null); do
  sed -i 's#\(name="app_name"[^>]*>\)Chromium<#\1Orea<#' "$f" && echo "app_name: $f"
done

# 圖示：adaptive icon 覆蓋 app_icon（API 26+ 優先使用 mipmap-anydpi-v26）
if [ -d ~/orea_icon ] && [ -d $RES ]; then
  mkdir -p $RES/drawable $RES/mipmap-anydpi-v26
  cp ~/orea_icon/ic_launcher_background.xml $RES/drawable/orea_icon_background.xml
  cp ~/orea_icon/ic_launcher_foreground.xml $RES/drawable/orea_icon_foreground.xml
  cp ~/orea_icon/ic_launcher_monochrome.xml $RES/drawable/orea_icon_monochrome.xml
  for name in app_icon layered_app_icon; do
    cat > $RES/mipmap-anydpi-v26/$name.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/orea_icon_background" />
    <foreground android:drawable="@drawable/orea_icon_foreground" />
    <monochrome android:drawable="@drawable/orea_icon_monochrome" />
</adaptive-icon>
EOF
  done
  # 新檔案要登記進 BUILD.gn 的資源清單，否則 prepare_resources 會擋下（第一次就是這樣失敗）
  python3 - <<'PY2'
p='chrome/android/BUILD.gn'; s=open(p).read()
anchor='"java/res_chromium_base/drawable/themed_app_icon.xml",'
new=[f'"java/res_chromium_base/{f}",' for f in ('drawable/orea_icon_background.xml','drawable/orea_icon_foreground.xml','drawable/orea_icon_monochrome.xml','mipmap-anydpi-v26/app_icon.xml','mipmap-anydpi-v26/layered_app_icon.xml')]

assert anchor in s, 'BUILD.gn 找不到 themed_app_icon.xml'
# 有兩份資源清單（chrome_base_module_resources、chrome_public_apk_base_module_resources）都列這個目錄，每份都要登記
for n in new: s=s.replace(chr(10)+'      '+n,'')
s=s.replace(anchor, anchor+''.join(chr(10)+'      '+n for n in new)); open(p,'w').write(s)
print('BUILD.gn: %d 份清單已登記' % s.count(anchor))
PY2
  echo "icon: $RES/mipmap-anydpi-v26"
fi
gn gen out/Release
