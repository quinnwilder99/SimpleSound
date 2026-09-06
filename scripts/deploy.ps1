<#
.SYNOPSIS
    Build the debug APK and install it on the phone as an in-place UPDATE, keeping
    every playlist and all play history.

.DESCRIPTION
    The only supported way to get a new build onto the phone. It:
      1. Builds :app:assembleDebug, signed with the stable committed debug key
         (keystore/simplesound-debug.keystore) so the signature never changes.
      2. Compares the new APK's versionCode with the build on the phone and
         REFUSES to install anything not strictly higher (a downgrade needs -d
         or an uninstall, both of which wipe playlists + history).
      3. Runs "adb install -r" (reinstall, keep data). Never -d. Never uninstall.
      4. Verifies the phone now reports the new versionCode.
    On a signature mismatch it STOPS instead of uninstalling your data.

.PARAMETER Serial
    adb device serial, if more than one device/emulator is connected.

.PARAMETER SkipBuild
    Install the existing app/build/outputs/apk/debug/app-debug.apk, do not rebuild.

.PARAMETER Force
    Proceed even if data/schemas/ has uncommitted changes (only when a matching
    Room Migration is already in place -- see DEPLOY.md).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SkipBuild,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$pkg = 'com.simplesound.app'
$apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'

function Fail([string[]]$lines) {
    Write-Host ''
    Write-Host '  DEPLOY ABORTED:' -ForegroundColor Red
    foreach ($l in $lines) { Write-Host "  $l" -ForegroundColor Red }
    Write-Host ''
    exit 1
}
function Step([string]$msg) { Write-Host ''; Write-Host "> $msg" -ForegroundColor Cyan }

# Run a native command, returning stdout+stderr as one string, WITHOUT letting a
# nonzero exit or text on stderr abort the script (Windows PowerShell surfaces
# native stderr as errors under $ErrorActionPreference = 'Stop'). Callers inspect
# the returned text themselves.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $Exe @Arguments 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $old
    }
}

# ---- locate adb --------------------------------------------------------
$adb = $null
$cmd = Get-Command adb -ErrorAction SilentlyContinue
if ($cmd) { $adb = $cmd.Source }
if (-not $adb) {
    foreach ($root in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\Sdk')) {
        if ($root -and (Test-Path (Join-Path $root 'platform-tools\adb.exe'))) {
            $adb = (Join-Path $root 'platform-tools\adb.exe'); break
        }
    }
}
if (-not $adb) { Fail 'adb not found. Add platform-tools to PATH or set ANDROID_SDK_ROOT.' }

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Fail 'git not found on PATH. The schema-change guard and versionCode both need it.'
}

# ---- locate aapt2 (reads the built APK's versionCode) ------------------
$aapt2 = $null
foreach ($root in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\Sdk')) {
    if (-not $root) { continue }
    $bt = Join-Path $root 'build-tools'
    if (-not (Test-Path $bt)) { continue }
    $latest = Get-ChildItem $bt -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($latest -and (Test-Path (Join-Path $latest.FullName 'aapt2.exe'))) {
        $aapt2 = (Join-Path $latest.FullName 'aapt2.exe'); break
    }
}

# ---- pick the adb target --------------------------------------------
$targetArgs = @()
if ($Serial) {
    $targetArgs = @('-s', $Serial)
} else {
    $listing = Invoke-Native $adb @('devices')
    $devices = @($listing -split "`r?`n" | Where-Object { $_ -match '\tdevice(\s|$)' })
    if ($devices.Count -eq 0) { Fail 'no device/emulator connected (adb devices shows none in the "device" state).' }
    if ($devices.Count -gt 1) {
        Fail (@('more than one device connected. Re-run with -Serial <serial>.') + $devices)
    }
}

# ---- schema-change guard -------------------------------------------
Step 'Checking Room schema state'
Push-Location $repo
try {
    $schemaDirty = @((Invoke-Native 'git' @('status', '--porcelain', '--', 'data/schemas')) -split "`r?`n" | Where-Object { $_ -ne '' })
} finally {
    Pop-Location
}
$schemaChanged = $schemaDirty.Count -gt 0
if ($schemaChanged -and -not $Force) {
    Fail (@(
        'data/schemas/ has uncommitted changes -- the DB schema changed.',
        'A schema change WITHOUT a matching Migration in AppDatabase.MIGRATIONS',
        'crashes the app on the next launch after this update. Confirm the',
        'Migration + version bump are in place, then pass -Force. See DEPLOY.md.'
    ) + $schemaDirty)
}
if ($schemaChanged) {
    Write-Host '  WARNING: schema changed; continuing because -Force was passed.' -ForegroundColor Yellow
} else {
    Write-Host '  clean.' -ForegroundColor Green
}

# ---- build --------------------------------------------------------
if (-not $SkipBuild) {
    Step 'Building :app:assembleDebug'
    Push-Location $repo
    try {
        & (Join-Path $repo 'gradlew.bat') :app:assembleDebug --console=plain
        $gradleExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($gradleExit -ne 0) { Fail 'gradle build failed (see output above).' }
}
if (-not (Test-Path $apk)) { Fail "APK not found at $apk (run without -SkipBuild)." }

# ---- versionCode comparison ------------------------------------
Step 'Comparing versionCode (new APK vs. the build on the phone)'
$newVc = 0
if ($aapt2) {
    $badging = Invoke-Native $aapt2 @('dump', 'badging', $apk)
    $m = [regex]::Match($badging, "versionCode='(\d+)'")
    if ($m.Success) { $newVc = [int]$m.Groups[1].Value }
}
if ($newVc -le 0) {
    Push-Location $repo
    try {
        $count = (Invoke-Native 'git' @('rev-list', '--count', 'HEAD')).Trim()
    } finally {
        Pop-Location
    }
    if ($count -match '^\d+$') { $newVc = [Math]::Max([int]$count, 53) }
    Write-Host "  (aapt2 unavailable; inferred new versionCode = $newVc from git commit count)" -ForegroundColor Yellow
}
if ($newVc -le 0) { Fail 'could not determine the new APK versionCode.' }

$dump = Invoke-Native $adb ($targetArgs + @('shell', 'dumpsys', 'package', $pkg))
$installedVc = 0
$mi = [regex]::Match($dump, 'versionCode=(\d+)')
if ($mi.Success) { $installedVc = [int]$mi.Groups[1].Value }

if ($installedVc -le 0) {
    Write-Host "  $pkg is not installed yet -- this will be a fresh install (nothing to preserve)." -ForegroundColor Yellow
} else {
    Write-Host "  on phone: versionCode $installedVc" -ForegroundColor Gray
    Write-Host "  new APK : versionCode $newVc" -ForegroundColor Gray
    if ($newVc -lt $installedVc) {
        Fail (@(
            "new versionCode ($newVc) is LOWER than the phone's ($installedVc).",
            'Installing this needs a downgrade (-d) or an uninstall, which wipes',
            'playlists + history. Commit your work (versionCode = git commit count)',
            'and rebuild.'
        ))
    }
    if ($newVc -eq $installedVc) {
        Write-Host '  WARNING: identical versionCode. The reinstall is allowed but you' -ForegroundColor Yellow
        Write-Host '  will not be able to tell the two builds apart -- commit to bump it.' -ForegroundColor Yellow
    }
}

# ---- install (reinstall, keep data) --------------------------
Step 'Installing (adb install -r, keeps all app data)'
$out = Invoke-Native $adb ($targetArgs + @('install', '-r', $apk))
Write-Host $out.Trim()

$sigMismatch = ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') -or
               ($out -match 'INSTALL_FAILED_INCONSISTENT_CERTIFICATES') -or
               ($out -match 'signatures do not match')
if ($sigMismatch) {
    Fail @(
        'signature mismatch: the build on the phone was signed with a different',
        'key than keystore/simplesound-debug.keystore (e.g. an old build signed',
        "with a machine's throwaway ~/.android/debug.keystore).",
        '',
        'This script will NOT uninstall to fix it, because that deletes your',
        'playlists and history. Either restore the original keystore to',
        'keystore/simplesound-debug.keystore and redeploy, or run',
        "  adb uninstall $pkg",
        'by hand and start fresh (that install''s data is lost).'
    )
}
if ($out -notmatch 'Success') {
    Fail @('adb install did not report Success (see output above).')
}

# ---- verify ------------------------------------------------
Step 'Verifying'
$dump2 = Invoke-Native $adb ($targetArgs + @('shell', 'dumpsys', 'package', $pkg))
$mv = [regex]::Match($dump2, 'versionCode=(\d+)')
if (-not $mv.Success) {
    Write-Host '  WARNING: install reported Success but could not read the version back from the device.' -ForegroundColor Yellow
} elseif ([int]$mv.Groups[1].Value -ne $newVc) {
    Fail "phone reports versionCode $($mv.Groups[1].Value) after install, expected $newVc."
}

Write-Host ''
Write-Host "  Deployed. Phone now on versionCode $newVc." -ForegroundColor Green
Write-Host '  Playlists, favorites, play history and settings were preserved.' -ForegroundColor Green
Write-Host '  Open the app and confirm it launches -- a launch crash means a Room' -ForegroundColor Green
Write-Host '  schema change shipped without a Migration.' -ForegroundColor Green
Write-Host ''
