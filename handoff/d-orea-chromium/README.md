# Orea（原版 Chromium）編譯機

在本機用 `go.ps1` 透過 SSH 操作雲端編譯機。登入金鑰 `C:\Users\barry\.ssh\orea_build` 只留在本機；APK 的簽章金鑰也不上傳。

## 開機器（阿里雲，國內機房）

- 地域：華東或華南都可以；付費：按量付費；關機時選「停機不收費」
- 規格：`ecs.c7a.8xlarge`（32 vCPU、64GiB）或 `g7a.8xlarge`（32 vCPU、128GiB）
- 鏡像：Ubuntu 22.04 64 位（x86_64）
- 系統盤：ESSD 300GB
- 公網 IP：按使用流量，頻寬峰值 100Mbps（阿里雲下載方向的流量不收費）
- 安全組：只開 22 埠
- 登入：密鑰對 → 匯入 `C:\Users\barry\.ssh\orea_build.pub` 的內容

## 放上代理訂閱（你自己做，訂閱內含帳號資訊）

在本機 PowerShell 執行（換成你的 IP 與訂閱網址）：

```powershell
ssh -i C:\Users\barry\.ssh\orea_build root@<公網IP> "mkdir -p ~/mihomo && curl -fsSL -A clash.meta -o ~/mihomo/config.yaml '<訂閱網址>' && head -c 300 ~/mihomo/config.yaml"
```

訂閱的流量要夠：第一次下載大約 30～60GB 會走代理。

## 指令

```powershell
.\go.ps1 -Ip <公網IP> -Do proxy    # 上傳 mihomo、啟動代理、測試連線與速度
.\go.ps1 -Ip <公網IP> -Do setup    # 在 tmux 裡開始初始化（下載原始碼，幾個小時）
.\go.ps1 -Ip <公網IP> -Do status   # 看進度
.\go.ps1 -Ip <公網IP> -Do build    # 初始化完成後開始編譯
.\go.ps1 -Ip <公網IP> -Do pull     # 把 ChromePublic.apk 拉回 out\
```

不用時記得在阿里雲主控台**停機**，否則一直計費。
