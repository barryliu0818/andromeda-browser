"""
把 Material Symbols Rounded 可變字型縮成 App 實際用到的圖示（res/font/material_symbols_rounded.ttf）。

  D:\\Tools\\python\\python.exe -I scripts\\subset-icons.py .

1. 從 app/src/main/java 所有 .kt 檔收集看起來像圖示名稱的字串（"arrow_back" 這種小寫加底線），
   跟字型裡的連字（ligature）名稱取交集，就是用到的圖示。新增圖示後重跑這支腳本即可。
2. 可變字型固定成 App 用的那一組（wght 300、FILL 0、GRAD 0、opsz 24，見 ui/Icons.kt），變成靜態字型。
3. 只留這些圖示的字形和組成名稱用的字母；連字規則只留用到的（不展開成全部圖示）。

原始的完整字型放在 fonts-src/（不打包進 App）。
"""
import pathlib
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
src = root / "fonts-src" / "material_symbols_rounded.ttf"
out = root / "app" / "src" / "main" / "res" / "font" / "material_symbols_rounded.ttf"

words = set()
for p in (root / "app" / "src" / "main" / "java").rglob("*.kt"):
    words |= set(re.findall(r'"([a-z][a-z0-9_]*)"', p.read_text(encoding="utf-8")))

font = TTFont(src)
rev = {g: chr(c) for c, g in font.getBestCmap().items()}

# 連字表：圖示名稱 → 字形
ligs = {}
for lookup in font["GSUB"].table.LookupList.Lookup:
    for st in lookup.SubTable:
        if lookup.LookupType == 7:
            st = st.ExtSubTable
        for first, ligset in getattr(st, "ligatures", {}).items():
            for lig in ligset:
                try:
                    name = "".join(rev[g] for g in [first] + lig.Component)
                except KeyError:
                    continue
                ligs[name] = lig.LigGlyph

used = sorted(w for w in words if w in ligs)
print(f"{len(used)} icons: {' '.join(used)}")

font = instancer.instantiateVariableFont(font, {"wght": 300, "FILL": 0, "GRAD": 0, "opsz": 24})

opts = subset.Options()
opts.layout_features = ["*"]
opts.layout_closure = False  # 不要從字母展開出所有連字（那樣會留下全部圖示）
opts.notdef_outline = True
opts.name_IDs = ["*"]
opts.glyph_names = False
s = subset.Subsetter(opts)
s.populate(text="".join(sorted(set("".join(used)))), glyphs=[ligs[n] for n in used])
s.subset(font)
font.save(out)
print(f"{src.stat().st_size} -> {out.stat().st_size} bytes")
