# usage: tap.sh "text"  — taps first node whose text/content-desc contains text
export MSYS_NO_PATHCONV=1
S="-s emulator-5554"
adb $S shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb $S exec-out cat /sdcard/ui.xml > /d/Android/ui.xml
py -I - "$1" <<'P'
import re,sys,subprocess
x=open(r'D:\Android\ui.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    t=re.search(r' text="([^"]*)"',n).group(1); d=re.search(r'content-desc="([^"]*)"',n).group(1)
    if sys.argv[1] in t or sys.argv[1] in d:
        a,b,c,e=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups())
        subprocess.run(['adb','-s','emulator-5554','shell','input','tap',str((a+c)//2),str((b+e)//2)]); print('tapped',t or d); break
else: print('NOT FOUND')
P
