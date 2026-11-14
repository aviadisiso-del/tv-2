<#
.SYNOPSIS
    Downloads the latest GlassLauncher APK built by GitHub Actions and installs it on an
    Android TV / streamer over ADB. Works on Windows 10/11 (Windows PowerShell 5.1+).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install.ps1 -Device 192.168.1.50

.EXAMPLE
    .\install.ps1 -Device 192.168.1.50 -SetHome      # also make it the default home screen

.EXAMPLE
    .\install.ps1 -ApkPath .\GlassLauncher-12.apk     # install a local APK, skip GitHub
#>
[CmdletBinding()]
param(
    # TV IP address (port 5555 is assumed) or ip:port. Omit if the TV is on USB / already connected.
    [string]$Device,
    [string]$Repo = 'aviadisiso-del/tv-2',
    [string]$Branch = 'main',
    [string]$Workflow = 'build.yml',
    # GitHub token with Actions read access. Falls back to $env:GITHUB_TOKEN, then `gh auth token`, then a prompt.
    [string]$Token = $env:GITHUB_TOKEN,
    [string]$ApkPath,
    [switch]$SetHome
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$PackageName = 'com.glasslauncher.tv'
$WorkDir = Join-Path $env:LOCALAPPDATA 'GlassLauncher'
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message)   { Write-Host "    $Message" -ForegroundColor Green }
function Write-Warn2([string]$Message) { Write-Host "    $Message" -ForegroundColor Yellow }

# ---------------------------------------------------------------- download helper (with progress)
function Save-Url {
    param([string]$Url, [string]$OutFile, [string]$Auth)
    $req = [System.Net.HttpWebRequest]::Create($Url)
    $req.UserAgent = 'GlassLauncher-Installer'
    $req.AllowAutoRedirect = $false   # follow manually so the token never goes to the storage host
    if ($Auth) { $req.Headers['Authorization'] = $Auth }
    $resp = $req.GetResponse()
    $code = [int]$resp.StatusCode
    if ($code -ge 300 -and $code -lt 400) {
        $next = $resp.Headers['Location']
        $resp.Close()
        Save-Url -Url $next -OutFile $OutFile -Auth $null
        return
    }
    $total = $resp.ContentLength
    $in = $resp.GetResponseStream()
    $out = [System.IO.File]::Create($OutFile)
    try {
        $buffer = New-Object byte[] 81920
        $done = 0L
        $lastPct = -1
        while (($n = $in.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $out.Write($buffer, 0, $n)
            $done += $n
            if ($total -gt 0) {
                $pct = [int](100 * $done / $total)
                if ($pct -ne $lastPct) {
                    Write-Progress -Activity 'Downloading' -Status ('{0:N1} / {1:N1} MB' -f ($done / 1MB), ($total / 1MB)) -PercentComplete $pct
                    $lastPct = $pct
                }
            }
        }
    }
    finally {
        $out.Close(); $in.Close(); $resp.Close()
        Write-Progress -Activity 'Downloading' -Completed
    }
}

# ---------------------------------------------------------------- ADB
function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $WorkDir 'platform-tools\adb.exe'),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    )
    if ($PSScriptRoot) { $candidates += (Join-Path $PSScriptRoot 'platform-tools\adb.exe') }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    return $null
}

function Install-Adb {
    Write-Warn2 'ADB was not found on this PC.'
    $answer = Read-Host '    Download Google Android platform-tools (~7 MB) now? [Y/n]'
    if ($answer -and $answer -notmatch '^(y|yes)$') { throw 'ADB is required. Aborting.' }
    $zip = Join-Path $WorkDir 'platform-tools.zip'
    Write-Step 'Downloading platform-tools from dl.google.com'
    Save-Url -Url 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $WorkDir -Force
    Remove-Item $zip -Force
    $adbPath = Join-Path $WorkDir 'platform-tools\adb.exe'
    if (-not (Test-Path $adbPath)) { throw 'platform-tools download did not contain adb.exe' }
    Write-Ok "ADB installed to $adbPath"
    return $adbPath
}

# ---------------------------------------------------------------- GitHub
function Get-GitHubToken {
    if ($Token) { return $Token }
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($gh) {
        try {
            $t = (& $gh.Source auth token 2>$null)
            if ($LASTEXITCODE -eq 0 -and $t) { return ([string]$t).Trim() }
        } catch { }
    }
    Write-Warn2 'GitHub only serves Actions artifacts to signed-in users, even for public repos.'
    Write-Warn2 "Create a fine-grained token: https://github.com/settings/personal-access-tokens"
    Write-Warn2 "  Repository access: $Repo   Permissions: Actions = Read-only"
    Write-Warn2 'Tip: set $env:GITHUB_TOKEN to skip this prompt next time.'
    $secure = Read-Host '    Paste token' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Get-LatestApk {
    $tok = Get-GitHubToken
    $headers = @{
        Authorization          = "Bearer $tok"
        Accept                 = 'application/vnd.github+json'
        'X-GitHub-Api-Version' = '2022-11-28'
        'User-Agent'           = 'GlassLauncher-Installer'
    }
    $api = "https://api.github.com/repos/$Repo"

    Write-Step "Finding the latest successful build of $Repo ($Branch)"
    $runs = Invoke-RestMethod -Headers $headers -Uri "$api/actions/workflows/$Workflow/runs?branch=$Branch&status=success&per_page=1"
    if (-not $runs.workflow_runs -or $runs.workflow_runs.Count -eq 0) {
        throw "No successful '$Workflow' run found on '$Branch'. Push to main and wait for the build."
    }
    $run = $runs.workflow_runs[0]
    Write-Ok ("Run #{0}  commit {1}  ({2})" -f $run.run_number, $run.head_sha.Substring(0, 7), $run.created_at)

    $arts = Invoke-RestMethod -Headers $headers -Uri "$api/actions/runs/$($run.id)/artifacts"
    $artifact = $arts.artifacts | Where-Object { -not $_.expired } | Select-Object -First 1
    if (-not $artifact) { throw "Run #$($run.run_number) has no (unexpired) artifacts." }

    $dir = Join-Path $WorkDir "run-$($run.run_number)"
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $zip = Join-Path $dir 'artifact.zip'

    Write-Step ("Downloading artifact '{0}' ({1:N1} MB)" -f $artifact.name, ($artifact.size_in_bytes / 1MB))
    Save-Url -Url $artifact.archive_download_url -OutFile $zip -Auth "Bearer $tok"
    Expand-Archive -Path $zip -DestinationPath $dir -Force
    $apk = Get-ChildItem -Path $dir -Filter *.apk -Recurse | Select-Object -First 1
    if (-not $apk) { throw 'The artifact did not contain an APK.' }
    Write-Ok "Saved $($apk.FullName)"
    return $apk.FullName
}

# ---------------------------------------------------------------- device
function Select-Device([string]$Adb) {
    & $Adb start-server | Out-Null
    if ($Device) {
        if ($Device -notmatch ':\d+$') { $script:Device = "${Device}:5555" }
        Write-Step "Connecting to $script:Device"
        & $Adb connect $script:Device | ForEach-Object { Write-Host "    $_" }
    }

    $lines = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
    $ready = @($lines | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
    $unauthorized = @($lines | Where-Object { $_ -match '\tunauthorized$' })

    if ($ready.Count -eq 0) {
        if ($unauthorized.Count -gt 0) {
            throw 'The TV is connected but not authorized. Accept the "Allow USB debugging?" prompt on the TV and run again.'
        }
        $ip = Read-Host '    No device connected. Enter the TV IP address'
        if (-not $ip) { throw 'No device.' }
        if ($ip -notmatch ':\d+$') { $ip = "${ip}:5555" }
        & $Adb connect $ip | ForEach-Object { Write-Host "    $_" }
        Start-Sleep -Seconds 1
        $state = $null
        try { $state = (& $Adb -s $ip get-state 2>$null) } catch { }
        if ("$state".Trim() -ne 'device') {
            throw "Could not connect to $ip. Check that Developer options > USB/Network debugging is on, then accept the prompt on the TV."
        }
        return $ip
    }
    if ($script:Device -and ($ready -contains $script:Device)) { return $script:Device }
    if ($ready.Count -eq 1) { return $ready[0] }

    Write-Host '    Several devices are connected:'
    for ($i = 0; $i -lt $ready.Count; $i++) { Write-Host "      [$i] $($ready[$i])" }
    $pick = Read-Host '    Choose a number'
    return $ready[[int]$pick]
}

# ================================================================= main
try {
    $adb = Find-Adb
    if (-not $adb) { $adb = Install-Adb }
    Write-Ok "Using ADB: $adb"

    if ($ApkPath) {
        if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
        $apk = (Resolve-Path $ApkPath).Path
    } else {
        $apk = Get-LatestApk
    }

    $serial = Select-Device $adb
    $sizeMb = (Get-Item $apk).Length / 1MB
    Write-Step ("Installing on {0} ({1:N1} MB)..." -f $serial, $sizeMb)
    $output = & $adb -s $serial install -r $apk
    $output | ForEach-Object { Write-Host "    $_" }
    if ($LASTEXITCODE -ne 0 -or -not ($output -match 'Success')) {
        Write-Warn2 'If the error above says INSTALL_FAILED_UPDATE_INCOMPATIBLE, the installed copy'
        Write-Warn2 'was signed with a different key. Uninstall it once, then run this script again:'
        Write-Warn2 "  adb -s $serial uninstall $PackageName"
        throw 'adb install failed.'
    }
    Write-Ok 'Installed.'

    # Optional data access for Continue Watching / Recommended. Many devices refuse one or
    # both (that's fine: the launcher falls back to its own launch history).
    Write-Step 'Granting optional data access (music control, Continue Watching)'
    $grants = [ordered]@{
        'Media control (music player)'      = "cmd notification allow_listener $PackageName/$PackageName.media.MediaListenerService"
        'Usage access (recently used apps)' = "appops set $PackageName GET_USAGE_STATS allow"
        'TV provider (Watch Next items)'    = "pm grant $PackageName com.android.providers.tv.permission.READ_EPG_DATA"
    }
    foreach ($name in $grants.Keys) {
        $ok = $false
        try {
            $out = & $adb -s $serial shell $grants[$name]
            $ok = ($LASTEXITCODE -eq 0) -and -not ("$out" -match 'Exception|Error|not')
        } catch { }
        if ($ok) { Write-Ok "$name - granted" } else { Write-Warn2 "$name - not allowed on this device (fallback will be used)" }
    }

    if ($SetHome) {
        Write-Step 'Setting GlassLauncher as the default home screen'
        & $adb -s $serial shell cmd package set-home-activity "$PackageName/.MainActivity"
    }

    Write-Step 'Opening GlassLauncher'
    & $adb -s $serial shell am start -n "$PackageName/.MainActivity" | Out-Null
    Write-Host ''
    Write-Host 'Done.' -ForegroundColor Green
}
catch {
    Write-Host ''
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
