# ============================================================
#  DTV design guard  (tools/design-guard.ps1)
# ============================================================
#  Goal: keep the UI style from degrading as the project grows.
#        Docs + good intentions alone always degrade (this repo
#        already regressed three times).
#
#  Strategy: baseline + incremental guard
#        - do NOT rewrite all legacy code at once (baseline records current counts)
#        - fail only when NEW violations appear (count > baseline)
#        - fixing old ones lets you lower the baseline (only down, never up)
#
#  Usage:
#        ./tools/design-guard.ps1                  # check (CI: exit 1 on new violations)
#        ./tools/design-guard.ps1 -UpdateBaseline  # tighten baseline after fixing
#
#  NOTE: keep this file ASCII-only. PS 5.1 reads .ps1 as ANSI unless it has a
#        UTF-8 BOM, so non-ASCII text here gets mangled and breaks parsing.
# ============================================================
param([switch]$UpdateBaseline)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$baselineFile = Join-Path $PSScriptRoot 'design-baseline.json'

$rules = @(
    @{ name = 'system_toast';        desc = 'Toast.makeText -> use CustomToast';                 pattern = 'Toast\.makeText';                       glob = '*.kt' },
    @{ name = 'system_dialog';       desc = 'AlertDialog.Builder -> use GlassDialog';            pattern = 'AlertDialog\.Builder';                  glob = '*.kt' },
    @{ name = 'system_progress';     desc = 'ProgressBar/Dialog -> use BrandLoading';            pattern = 'ProgressBar|ProgressDialog';            glob = '*.kt' },
    @{ name = 'emoji_icon';          desc = 'emoji as icon -> use vector drawables';             pattern = '[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF]';  glob = '*.kt' },
    @{ name = 'hardcoded_color';     desc = 'hardcoded color -> use tokens';                     pattern = 'Color\.parseColor\("#|setBackgroundColor\(0x|setTextColor\(0x';  glob = '*.kt' },
    @{ name = 'hardcoded_color_xml'; desc = 'XML hardcoded color -> use colors.xml';             pattern = 'android:(textColor|background)="#[0-9A-Fa-f]{6,8}"';  glob = '*.xml' },
    @{ name = 'hardcoded_size_xml';  desc = 'XML hardcoded textSize -> use dimens';              pattern = 'android:textSize="\d+sp"';              glob = '*.xml' }
)

$targets = @(
    (Join-Path $root 'app/src/main'),
    (Join-Path $root 'remote/app/src/main')
)

function Scan {
    $result = @{}
    foreach ($r in $rules) { $result[$r.name] = 0 }
    foreach ($t in $targets) {
        if (-not (Test-Path $t)) { continue }
        foreach ($r in $rules) {
            $files = Get-ChildItem $t -Recurse -File -Include $r.glob -ErrorAction SilentlyContinue
            foreach ($f in $files) {
                $txt = Get-Content $f.FullName -Raw -ErrorAction SilentlyContinue
                if (-not $txt) { continue }
                $c = ([regex]::Matches($txt, $r.pattern)).Count
                if ($c -gt 0) { $result[$r.name] += $c }
            }
        }
    }
    return $result
}

$now = Scan

if ($UpdateBaseline) {
    $now | ConvertTo-Json | Set-Content -Path $baselineFile -Encoding UTF8
    Write-Host "[design-guard] baseline written -> $baselineFile" -ForegroundColor Green
    $now.GetEnumerator() | Sort-Object Name | ForEach-Object { "  {0,-22} {1}" -f $_.Key, $_.Value }
    exit 0
}

$baseline = @{}
if (Test-Path $baselineFile) {
    (Get-Content $baselineFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $baseline[$_.Name] = [int]$_.Value }
}

Write-Host ""
Write-Host "==== DTV design guard ====" -ForegroundColor Cyan
$failed = $false
foreach ($r in $rules) {
    $cur = [int]$now[$r.name]
    $base = if ($baseline.ContainsKey($r.name)) { [int]$baseline[$r.name] } else { $cur }
    $delta = $cur - $base
    if ($delta -gt 0) { $mark = 'FAIL ' + $delta; $color = 'Red'; $failed = $true }
    elseif ($delta -lt 0) { $mark = 'BETTER ' + $delta; $color = 'Green' }
    else { $mark = 'same'; $color = 'DarkGray' }
    Write-Host ("  {0,-22} now {1,3} / base {2,3}   {3,-10} {4}" -f $r.name, $cur, $base, $mark, $r.desc) -ForegroundColor $color
}
Write-Host ""
if ($failed) {
    Write-Host "RESULT: new violations -> use self-drawn components / tokens (docs/DESIGN.md)" -ForegroundColor Red
    Write-Host "        legit exception? explain in PR, then run -UpdateBaseline" -ForegroundColor Red
    exit 1
} else {
    Write-Host "RESULT: no new violations - style not degraded" -ForegroundColor Green
    exit 0
}
