param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [string]$User = 'root',
    [int]$Port = 22,
    # proxy：上傳 mihomo 並啟動代理（國內機房，要先放好 ~/mihomo/config.yaml）；setup：上傳腳本並在 tmux 裡開始初始化；
    # build：開始編譯；status：看進度；pull：把 APK 拉回本機
    [ValidateSet('proxy', 'setup', 'build', 'status', 'pull')][string]$Do = 'setup'
)
# 在本機執行，透過 SSH 操作編譯機。金鑰：C:\Users\barry\.ssh\orea_build（只在本機，不上傳）。
$key = 'C:\Users\barry\.ssh\orea_build'
$here = Split-Path $MyInvocation.MyCommand.Path
$ssh = @('-i', $key, '-p', $Port, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ServerAliveInterval=30', "$User@$Ip")
$scp = @('-i', $key, '-P', $Port, '-o', 'StrictHostKeyChecking=accept-new')

switch ($Do) {
    'proxy' {
        ssh @ssh 'mkdir -p ~/mihomo; ls -la ~/mihomo/config.yaml'
        scp @scp "$here\bin\mihomo-linux-amd64.gz" "$here\bin\Country.mmdb" "$here\bin\GeoSite.dat" "$here\bin\GeoIP.dat" "${User}@${Ip}:~/mihomo/"
        scp @scp "$here\proxy.sh" "${User}@${Ip}:~/"
        ssh @ssh 'bash ~/proxy.sh'
    }
    'setup' {
        scp @scp "$here\setup.sh" "$here\build.sh" "${User}@${Ip}:~/"
        ssh @ssh 'command -v tmux >/dev/null || { S=; [ $(id -u) -eq 0 ] || S=sudo; $S apt-get update -qq && $S apt-get install -y -qq tmux >/dev/null; }; chmod +x ~/setup.sh ~/build.sh; nproc; free -g | head -2; df -h / | tail -1; tmux new-session -d -s orea "bash ~/setup.sh; exec bash" && echo started'
    }
    'build' { ssh @ssh 'tmux new-session -d -s build "bash ~/build.sh; exec bash" && echo started' }
    'status' {
        ssh @ssh 'uptime; df -h / | tail -1; echo; for f in ~/logs/setup.log ~/logs/build.log; do [ -f $f ] && { echo "== $f"; grep "^===" $f | tail -3; tail -5 $f; }; done'
    }
    'pull' {
        New-Item -ItemType Directory -Force "$here\out" | Out-Null
        $apk = ssh @ssh "ls -t ~/chromium/src/out/Release/apks/ChromePublic.apk 2>/dev/null | head -1"
        if (-not $apk) { throw 'no apk yet' }
        scp @scp "${User}@${Ip}:$apk" "$here\out\"
        Get-ChildItem "$here\out" | Sort-Object LastWriteTime | Select-Object -Last 1
    }
}
