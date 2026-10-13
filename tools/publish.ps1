# ============================================================
#  DTV 一键发布脚本（GitHub 开源 + 更新渠道）
# ============================================================
#  用法：
#     .\tools\publish.ps1 -Notes "1. 修复xxx`n2. 新增yyy"
#     .\tools\publish.ps1 -Notes "..." -Release      # 同时发 GitHub Release（上传 APK）
#     .\tools\publish.ps1 -Notes "..." -NoBump       # 不升版本，只重新上传代码
#
#  前置：
#     · Token 放在  _tools\gh_token.txt   （该目录已 gitignore，不会进仓库）
#       或用环境变量  $env:GH_TOKEN
#     · Token 需要 repo 权限；用完请到 github.com/settings/tokens 吊销
#
#  说明：
#     · 你的网络 github.com:443 被墙，git push 不可用 → 本脚本全程走
#       api.github.com 的 Contents / Git Data / Releases 接口上传
#     · 版本号规则（十进制小进位）：2.17 → 2.18 … 2.19 → 2.20 … 2.99 → 3.00
# ============================================================
param(
    [Parameter(Mandatory = $true)][string]$Notes,
    [switch]$Release,
    [switch]$NoBump,
    [string]$Owner = 'Alone2235',
    [string]$Repo = 'DTV'
)

$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $PSScriptRoot          # 仓库根（脚本在 tools\ 下）
$api = "https://api.github.com/repos/$Owner/$Repo"

# ---------- 0) Token ----------
$tok = $env:GH_TOKEN
if (-not $tok) {
    $tf = Join-Path (Split-Path -Parent $proj) '_tools\gh_token.txt'
    if (Test-Path $tf) { $tok = (Get-Content $tf -Raw).Trim() }
}
if (-not $tok) { throw "缺少 Token：请写入 _tools\gh_token.txt 或设置 `$env:GH_TOKEN" }
$H = @{ Authorization = "token $tok"; 'User-Agent' = 'DTV-Publish' }

function Api($method, $uri, $body = $null, $timeout = 90) {
    $p = @{ Uri = $uri; Method = $method; Headers = $H; TimeoutSec = $timeout }
    if ($body -ne $null) { $p['Body'] = ($body | ConvertTo-Json -Depth 6 -Compress); $p['ContentType'] = 'application/json' }
    return Invoke-RestMethod @p
}

# ---------- 1) 版本号 ----------
$gradle = Join-Path $proj 'app\build.gradle'
$txt = [IO.File]::ReadAllText($gradle, [Text.Encoding]::UTF8)

if (-not $NoBump) {
    $m = [regex]::Match($txt, 'versionName\s+"([\d.]+)"')
    if (-not $m.Success) { throw "读不到 versionName" }
    $cur = $m.Groups[1].Value
    $parts = $cur.Split('.')
    $major = [int]$parts[0]; $minor = [int]$parts[1] + 1
    if ($minor -ge 100) { $major++; $minor = 0 }      # 十进制小进位
    $new = "$major.{0:D2}" -f $minor
    $vc = [int]([regex]::Match($txt, 'versionCode\s+(\d+)').Groups[1].Value) + 1
    $txt = $txt -replace 'versionCode\s+\d+', "versionCode $vc"
    $txt = $txt -replace 'versionName\s+"[\d.]+"', "versionName `"$new`""
    # ★ 必须以无 BOM UTF-8 写回，否则中文注释损坏 + Gradle 报 unexpected character
    [IO.File]::WriteAllText($gradle, $txt, (New-Object Text.UTF8Encoding($false)))
    Write-Host "[版本] $cur -> $new  (versionCode $vc)" -ForegroundColor Green
} else {
    $new = [regex]::Match($txt, 'versionName\s+"([\d.]+)"').Groups[1].Value
    $vc = [int]([regex]::Match($txt, 'versionCode\s+(\d+)').Groups[1].Value)
    Write-Host "[版本] 保持不变 $new (versionCode $vc)" -ForegroundColor Yellow
}

# ---------- 2) 编译 ----------
Write-Host "[编译] assembleDebug ..." -ForegroundColor Cyan
$env:GRADLE_OPTS = "-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
Push-Location $proj
& .\gradlew.bat :app:assembleDebug --offline --console=plain 2>&1 | Select-String -Pattern '^e: |BUILD SUCCESSFUL|BUILD FAILED' | Select-Object -Last 5
Pop-Location
$apk = Get-ChildItem (Join-Path $proj 'app\build\outputs\apk\debug\*.apk') |
    Where-Object { $_.Name -notmatch 'unaligned' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "编译产物不存在，检查上面的编译错误" }
Write-Host ("[编译] {0}  {1:N2} MB" -f $apk.Name, ($apk.Length / 1MB)) -ForegroundColor Green

# ---------- 3) 上传代码（Contents API，逐个文件；已存在则带 sha 覆盖） ----------
$skip = '\\build\\|\\\.gradle|gradle-user-home|\\\.git\\|\\__pycache__|\\\.kotlin|\\\.workbuddy|local\.properties|\.apk$|^hs_err_|^replay_pid|gh_token'
$files = Get-ChildItem $proj -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch $skip }
Write-Host "[上传] $($files.Count) 个文件 ..." -ForegroundColor Cyan
$ok = 0; $fail = 0
foreach ($f in $files) {
    $rel = $f.FullName.Replace("$proj\", '').Replace('\', '/')
    $uri = "$api/contents/$rel"
    $sha = $null
    try { $sha = (Api 'GET' $uri).sha } catch { }
    $body = @{ message = "release: v$new"; content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($f.FullName)); branch = 'main' }
    if ($sha) { $body['sha'] = $sha }
    try { Api 'PUT' $uri $body | Out-Null; $ok++ } catch { $fail++; Write-Host "   ! $rel : $($_.Exception.Message.Split([char]10)[0])" -ForegroundColor DarkYellow }
}
Write-Host "[上传] 成功 $ok / 失败 $fail" -ForegroundColor Green

# ---------- 4) 更新清单 version.json ----------
$apkUrl = "https://github.com/$Owner/$Repo/releases/download/v$new/$($apk.Name)"
$manifest = [ordered]@{
    versionCode = $vc
    versionName = $new
    apkUrl      = $apkUrl
    notes       = $Notes
    force       = $false
}
$json = $manifest | ConvertTo-Json -Depth 3
$uri = "$api/contents/version.json"
$sha = (Api 'GET' $uri).sha
Api 'PUT' $uri @{ message = "release: v$new 清单"; content = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)); branch = 'main'; sha = $sha } | Out-Null
Write-Host "[清单] version.json -> v$new" -ForegroundColor Green

# ---------- 5) GitHub Release + 上传 APK ----------
if ($Release) {
    Write-Host "[发布] 创建 Release v$new ..." -ForegroundColor Cyan
    $rel = Api 'POST' "$api/releases" @{
        tag_name = "v$new"; name = "DTV $new"; body = $Notes; draft = $false; prerelease = $false
    }
    $up = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($rel.id)/assets?name=$($apk.Name)"
    Invoke-RestMethod -Uri $up -Method Post -Headers (@{
        Authorization = "token $tok"; 'User-Agent' = 'DTV-Publish'
        'Content-Type' = 'application/vnd.android.package-archive'
    }) -InFile $apk.FullName -TimeoutSec 900 | Out-Null
    Write-Host "[发布] APK 已上传到 Release：$apkUrl" -ForegroundColor Green
}

Write-Host ""
Write-Host "完成 ✅  仓库：https://github.com/$Owner/$Repo" -ForegroundColor Green
Write-Host "更新清单：https://raw.githubusercontent.com/$Owner/$Repo/main/version.json"
if ($Release) { Write-Host "电视端启动 3 秒后即可收到 v$new 更新提示" -ForegroundColor Green }
