# התקנה וניקוי לסטרימר GOtv+ GO700FR (אנדרואיד TV 12) מ-Windows.
# הרצה:  powershell -ExecutionPolicy Bypass -File .\install-tv.ps1 -Apk .\app-release.apk
# מומלץ להריץ ב-Windows Terminal (תצוגת עברית תקינה יותר).
param(
    [string]$TvIp = "10.100.102.20",
    [string]$Apk = "",
    [switch]$SkipClean
)

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ErrorActionPreference = "Continue"
$Pkg = "com.siso.glasslauncher"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------- מציאת adb ----------
$adb = $null
foreach ($c in @("$here\platform-tools\adb.exe", "$here\..\platform-tools\adb.exe")) {
    if (Test-Path $c) { $adb = (Resolve-Path $c).Path; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    Write-Host "לא נמצא adb. הורד 'SDK Platform-Tools for Windows' מ-developer.android.com/tools/releases/platform-tools," -ForegroundColor Red
    Write-Host "חלץ את התיקייה platform-tools לצד הסקריפט הזה והרץ שוב." -ForegroundColor Red
    exit 1
}

function Adb { & $adb -s $script:dev @args }
function Sh([string]$c) { (& $adb -s $script:dev shell $c 2>&1 | Out-String).Trim() }
function Ask([string]$q, [bool]$default = $false) {
    $hint = if ($default) { "[Y/n]" } else { "[y/N]" }
    $a = Read-Host "$q $hint"
    if ([string]::IsNullOrWhiteSpace($a)) { return $default }
    return $a.Trim().ToLower().StartsWith("y")
}

# ---------- חיבור ----------
$script:dev = "${TvIp}:5555"
Write-Host "מתחבר ל-$script:dev ..." -ForegroundColor Cyan
& $adb connect $script:dev | Out-Host
Write-Host "אם מופיע חלון 'לאפשר ניפוי באגים' בטלוויזיה - אשר אותו (ותסמן 'תמיד')." -ForegroundColor Yellow
Start-Sleep -Seconds 2
$state = (& $adb -s $script:dev get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") {
    Write-Host "המכשיר לא מחובר ($state). בדוק: אפשרויות מפתחים -> ניפוי באגים ברשת (או USB debugging) פעיל, והמחשב באותה רשת." -ForegroundColor Red
    exit 1
}
Write-Host "מחובר: $(Sh 'getprop ro.product.model') / אנדרואיד $(Sh 'getprop ro.build.version.release')" -ForegroundColor Green

# ---------- גיבוי ----------
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$bk = Join-Path $here "backup-$stamp"
New-Item -ItemType Directory -Path $bk | Out-Null
Sh "pm list packages -f"      | Set-Content -Encoding UTF8 "$bk\all-packages.txt"
Sh "pm list packages -d"      | Set-Content -Encoding UTF8 "$bk\already-disabled.txt"
Sh "pm list packages -3"      | Set-Content -Encoding UTF8 "$bk\third-party.txt"
Sh "settings list secure"     | Set-Content -Encoding UTF8 "$bk\settings-secure.txt"
Sh "settings list global"     | Set-Content -Encoding UTF8 "$bk\settings-global.txt"
Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" |
    Set-Content -Encoding UTF8 "$bk\original-home.txt"
Write-Host "גיבוי נשמר ב: $bk" -ForegroundColor Green
$disabledLog = "$bk\disabled-by-script.txt"
New-Item -ItemType File -Path $disabledLog | Out-Null

function Show-Space {
    $df = Sh "df /data"
    Write-Host $df
}
Write-Host "`nמקום פנוי לפני הניקוי:" -ForegroundColor Cyan
Show-Space

$installed = @{}
(Get-Content "$bk\all-packages.txt") | ForEach-Object {
    if ($_ -match "^package:(.+)=(.+)$") { $installed[$Matches[2].Trim()] = $true }
}

function Disable-Pkg([string]$p) {
    if (-not $installed.ContainsKey($p)) { return }
    $r = Sh "pm disable-user --user 0 $p"
    if ($r -match "new state: disabled") {
        Add-Content -Encoding UTF8 $disabledLog $p
        Write-Host "  הושבת: $p" -ForegroundColor DarkGray
    } else {
        Write-Host "  לא הצלחתי להשבית $p ($r)" -ForegroundColor DarkYellow
    }
}

# ---------- ניקוי ----------
if (-not $SkipClean) {
    Write-Host "`n=== שאלות: מה להשאיר ומה להשבית ===" -ForegroundColor Cyan
    Write-Host "הכול הפיך: ההשבתה לא מוחקת כלום, ואפשר להחזיר עם restore-tv.ps1." -ForegroundColor Gray

    # [שם, ברירת מחדל למחיקה (להשבית), חבילות]
    $groups = @(
        @{ Name = "המלצות ופרסומות במסך הבית של גוגל (לא צריך אחרי שהמסך שלנו הוא ברירת המחדל)"; Disable = $true;
           Pkgs = @("com.google.android.leanbacklauncher.recommendations","com.google.android.tvrecommendations","com.google.android.leanbacklauncher") },
        @{ Name = "שומר מסך / Ambient mode"; Disable = $true; Pkgs = @("com.google.android.backdrop") },
        @{ Name = "שליחת משוב ואיסוף נתונים (feedback, onetimeinitializer, partnersetup)"; Disable = $true;
           Pkgs = @("com.google.android.feedback","com.google.android.onetimeinitializer","com.google.android.partnersetup") },
        @{ Name = "אפליקציות גוגל מיותרות (Play Games, Play Movies, Play Music, YouTube Music)"; Disable = $true;
           Pkgs = @("com.google.android.play.games","com.google.android.videos","com.google.android.music","com.google.android.youtube.tvmusic") },
        @{ Name = "עוזר קולי (Google Assistant / חיפוש קולי בשלט)"; Disable = $false;
           Pkgs = @("com.google.android.katniss","com.google.android.speech.pumpkin") },
        @{ Name = "טלוויזיה חיה / ערוצי טיונר (Live Channels)"; Disable = $false; Pkgs = @("com.google.android.tv") },
        @{ Name = "Chromecast מובנה (שידור מהטלפון לסטרימר)"; Disable = $false; Pkgs = @("com.google.android.apps.mediashell") }
    )
    foreach ($g in $groups) {
        $present = @($g.Pkgs | Where-Object { $installed.ContainsKey($_) })
        if ($present.Count -eq 0) { continue }
        Write-Host "`n$($g.Name)" -ForegroundColor White
        Write-Host "  נמצאו: $($present -join ', ')" -ForegroundColor Gray
        $q = if ($g.Disable) { "להשבית?" } else { "להשבית? (ברירת מחדל: להשאיר)" }
        if (Ask $q $g.Disable) { foreach ($p in $present) { Disable-Pkg $p } }
    }

    # אפליקציות של המשתמש / של היצרן שהותקנו
    $third = @((Get-Content "$bk\third-party.txt") | ForEach-Object { $_ -replace "^package:", "" } |
        Where-Object { $_ -and $_ -ne $Pkg })
    if ($third.Count -gt 0) {
        Write-Host "`nאפליקציות שהותקנו (לא של המערכת). הקלד מספרים מופרדים בפסיק כדי להשבית, או Enter כדי לדלג:" -ForegroundColor White
        for ($i = 0; $i -lt $third.Count; $i++) { Write-Host ("  {0,2}. {1}" -f ($i + 1), $third[$i]) }
        $pick = Read-Host "מספרים"
        foreach ($n in ($pick -split "[, ]+" | Where-Object { $_ -match "^\d+$" })) {
            $idx = [int]$n - 1
            if ($idx -ge 0 -and $idx -lt $third.Count) {
                $p = $third[$idx]
                $installed[$p] = $true
                Disable-Pkg $p
            }
        }
    }

    # שחרור מקום
    Write-Host "`n=== שחרור מקום ===" -ForegroundColor Cyan
    if (Ask "לנקות מטמון (cache) של כל האפליקציות?" $true) {
        Sh "pm trim-caches 999999999999" | Out-Null
        Write-Host "  המטמון נוקה." -ForegroundColor Green
    }
    $bigs = (Sh "dumpsys diskstats")
    $line = ($bigs -split "`n" | Where-Object { $_ -match "^(App Sizes|Package Names|Cache Sizes)" })
    if ($line) { Write-Host "  (נתוני גודל אפליקציות נשמרו בקובץ diskstats.txt)"; $bigs | Set-Content -Encoding UTF8 "$bk\diskstats.txt" }
    Write-Host "`nמקום פנוי אחרי הניקוי:" -ForegroundColor Cyan
    Show-Space
}

# ---------- התקנת המסך ----------
if (-not $Apk) {
    foreach ($c in @("$here\app-release.apk", "$here\..\app-release.apk", "$here\..\app\build\outputs\apk\release\app-release.apk")) {
        if (Test-Path $c) { $Apk = (Resolve-Path $c).Path; break }
    }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Host "`nלא נמצא קובץ APK. הוסף -Apk .\app-release.apk (ראה README)." -ForegroundColor Red
    exit 1
}
Write-Host "`nמתקין את $Apk ..." -ForegroundColor Cyan
Adb install -r $Apk | Out-Host

# הרשאות ושירותים
Write-Host "מגדיר הרשאות..." -ForegroundColor Cyan
Sh "cmd notification allow_listener $Pkg/$Pkg.MediaNotificationListener" | Out-Null
$cur = Sh "settings get secure enabled_accessibility_services"
$svc = "$Pkg/$Pkg.KeyService"
if ($cur -notmatch [regex]::Escape($svc)) {
    $new = if ($cur -and $cur -ne "null") { "${cur}:$svc" } else { $svc }
    Sh "settings put secure enabled_accessibility_services $new" | Out-Null
}
Sh "settings put secure accessibility_enabled 1" | Out-Null
$g = Sh "pm grant $Pkg com.android.providers.tv.permission.READ_EPG_DATA"
if ($g) { Write-Host "  'המשך צפייה': לא ניתן להעניק הרשאה אוטומטית ($g). המסך יעבוד בלי השורה הזו." -ForegroundColor DarkYellow }
Sh "appops set $Pkg SYSTEM_ALERT_WINDOW allow" | Out-Null

# קביעה כמסך הבית
$role = Sh "cmd role add-role-holder --user 0 android.app.role.HOME $Pkg"
Write-Host "הגדרת מסך בית: $role"
Sh "input keyevent KEYCODE_HOME" | Out-Null
Start-Sleep -Seconds 2
$homeNow = Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
Sh "cmd package set-home-activity $Pkg/$Pkg.MainActivity" | Out-Null
if ($homeNow -match $Pkg) {
    Write-Host "`nהמסך החדש הוא מסך הבית." -ForegroundColor Green
    # מסך הבית המקורי של גוגל לפעמים "קופץ" חזרה. אחרי שוידאנו שהמסך שלנו פעיל, אפשר להשבית אותו (הפיך).
    $stockHomes = @("com.google.android.tvlauncher","com.google.android.leanbacklauncher","com.google.android.apps.tv.launcherx") |
        Where-Object { $installed.ContainsKey($_) }
    if ($stockHomes.Count -gt 0) {
        Write-Host "נמצאו מסכי בית מקוריים: $($stockHomes -join ', ')" -ForegroundColor White
        if (Ask "להשבית אותם כדי שהטלוויזיה לא תחזור אליהם? (מומלץ. הפיך עם restore-tv.ps1)" $true) {
            foreach ($p in $stockHomes) { Disable-Pkg $p }
            Sh "input keyevent KEYCODE_HOME" | Out-Null
            Write-Host "בוצע. אם אי פעם המסך שלנו לא עולה: .\restore-tv.ps1" -ForegroundColor Green
        }
    }
} else {
    Write-Host "`nהמסך עדיין לא נקבע כברירת מחדל. לחץ HOME בשלט ובחר 'מסך בית זכוכית' -> 'תמיד'." -ForegroundColor Yellow
}
Write-Host "לחזרה אחורה: .\restore-tv.ps1 -TvIp $TvIp  (גיבוי: $bk)" -ForegroundColor Gray
