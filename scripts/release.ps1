# 發佈到 GitHub Releases：編譯 share 版（不含任何金鑰）、建立 release、上傳 APK，手機上直接下載安裝。
#   powershell -File scripts\release.ps1 [-Notes "這版改了什麼"]
# 需要：gh 已登入、keystore.properties（正式簽章，不進 git）、local.properties 的 OREA_CORE_DIR（內建核心，不進 git）。
param([string]$Notes = "")
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (git status --porcelain) { throw '有未提交的改動：先 commit 再發佈（版本號取自提交數）' }
if (-not (Test-Path keystore.properties)) { throw '找不到 keystore.properties：沒有正式簽章，手機上的舊版無法直接更新' }

& .\gradlew.bat :app:assembleShareRelease -q
if ($LASTEXITCODE -ne 0) { throw '編譯失敗' }

$count = (git rev-list --count HEAD).Trim()
$version = "0.5.$count"
$tag = "v$version"
$src = 'app\build\outputs\apk\share\release\app-share-release.apk'
$apk = Join-Path $env:TEMP "Orea-$version-arm64.apk"
Copy-Item $src $apk -Force
$mb = [math]::Round((Get-Item $apk).Length / 1MB, 1)

$log = (git log -1 --pretty=%s)
$body = @"
Orea $version（arm64，$mb MB）

手機上用瀏覽器打開這一頁，下載下面的 APK 安裝即可；已安裝的 Orea 會直接更新，資料保留。

最新改動：$log
$Notes
"@
gh release create $tag $apk --title "Orea $version" --notes $body --latest
if ($LASTEXITCODE -ne 0) { throw '建立 release 失敗' }
Remove-Item $apk
"已發佈 $tag"
