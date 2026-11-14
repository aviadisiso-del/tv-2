# החזרה אחורה: מפעיל מחדש את כל מה שהסקריפט השבית, ומחזיר את מסך הבית המקורי.
# הרצה:  powershell -ExecutionPolicy Bypass -File .\restore-tv.ps1
param(
    [string]$TvIp = "10.100.102.20",
    [switch]$Uninstall
)
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$Pkg = "com.siso.glasslauncher"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$adb = $null
foreach ($c in @("$here\platform-tools\adb.exe", "$here\..\platform-tools\adb.exe")) {
    if (Test-Path $c) { $adb = (Resolve-Path $c).Path; break }
}
if (-not $adb) { $cmd = Get-Command adb -ErrorAction SilentlyContinue; if ($cmd) { $adb = $cmd.Source } }
if (-not $adb) { Write-Host "לא נמצא adb (ראה README)." -ForegroundColor Red; exit 1 }

$dev = "${TvIp}:5555"
& $adb connect $dev | Out-Host
function Sh([string]$c) { (& $adb -s $dev shell $c 2>&1 | Out-String).Trim() }

# מחזיר את כל מה שהושבת בכל הרצות קודמות
$logs = Get-ChildItem -Path $here -Directory -Filter "backup-*" -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName "disabled-by-script.txt" } | Where-Object { Test-Path $_ }
foreach ($l in $logs) {
    foreach ($p in (Get-Content $l | Where-Object { $_.Trim() })) {
        $r = Sh "pm enable --user 0 $($p.Trim())"
        Write-Host "הופעל: $p  $r" -ForegroundColor DarkGray
    }
}

# מסיר את המסך שלנו כמסך בית => חוזר המסך המקורי
Sh "cmd role remove-role-holder --user 0 android.app.role.HOME $Pkg" | Out-Null
Sh "settings put secure enabled_accessibility_services null" | Out-Null
Sh "input keyevent KEYCODE_HOME" | Out-Null
if ($Uninstall) { & $adb -s $dev uninstall $Pkg | Out-Host }
Write-Host "בוצע. אם המסך המקורי לא חזר: לחץ HOME ובחר אותו -> 'תמיד'. (אם רוצים לגמרי להסיר: -Uninstall)" -ForegroundColor Green
