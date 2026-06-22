#!/usr/bin/env pwsh
# ============================================================
# Gallery Sync – APK Builder
# ============================================================
# Usage:  .\tools\build_apk.ps1 [-Url "https://...trycloudflare.com"]
#
# What this script does:
#   1. (Optional) Patches MainActivity.kt with a fresh tunnel URL
#   2. Runs gradlew assembleDebug
#   3. Copies the APK to tools\GallerySync.apk for easy access
# ============================================================
param(
    [string]$Url = ""
)

$ROOT        = Split-Path $PSScriptRoot -Parent
$ANDROID_DIR = "$ROOT\android"
$GRADLEW     = "$ANDROID_DIR\gradlew.bat"
$MAIN_KT     = "$ANDROID_DIR\app\src\main\java\com\gallerysync\app\MainActivity.kt"
$URL_FILE    = "$PSScriptRoot\current_tunnel_url.txt"
$OUT_APK     = "$ANDROID_DIR\app\build\outputs\apk\debug\app-debug.apk"
$DEST_APK    = "$PSScriptRoot\GallerySync.apk"

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Gallery Sync – APK Builder              " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# ── Resolve URL ───────────────────────────────────────────────
if (-not $Url -and (Test-Path $URL_FILE)) {
    $Url = (Get-Content $URL_FILE -Raw).Trim()
    Write-Host "[OK] Using saved tunnel URL: $Url" -ForegroundColor Green
}

if ($Url) {
    Write-Host "[..] Patching MainActivity.kt with URL: $Url" -ForegroundColor Cyan
    $kt = Get-Content $MAIN_KT -Raw -Encoding UTF8

    # Patch hint and default text for backend URL field
    $kt = $kt -replace '(hint\s*=\s*")https://[^"]+(")', "`${1}${Url}`${2}"
    $kt = $kt -replace '(setText\(prefs\.getString\("backend_url",\s*")https://[^"]+(")', "`${1}${Url}`${2}"

    Set-Content -Path $MAIN_KT -Value $kt -Encoding UTF8
    Write-Host "[OK] MainActivity.kt patched." -ForegroundColor Green
} else {
    Write-Host "[INFO] No URL provided – building with current MainActivity.kt defaults." -ForegroundColor Yellow
}

# ── Verify Gradle wrapper ──────────────────────────────────────
if (-not (Test-Path $GRADLEW)) {
    Write-Host "[ERROR] gradlew.bat not found at: $GRADLEW" -ForegroundColor Red
    Write-Host "  Make sure the android project is properly initialized." -ForegroundColor Yellow
    exit 1
}

# ── Set JAVA_HOME to Android Studio JBR (Java 21) ─────────────
$studioJbr = "E:\android\jbr"
if (Test-Path "$studioJbr\bin\java.exe") {
    $env:JAVA_HOME = $studioJbr
    Write-Host "[OK] JAVA_HOME set to Android Studio JBR: $studioJbr" -ForegroundColor Green
} elseif (Test-Path "C:\Program Files\Java\jdk-19\bin\java.exe") {
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-19"
    Write-Host "[OK] JAVA_HOME set to JDK 19" -ForegroundColor Green
} else {
    Write-Host "[WARN] JAVA_HOME not set automatically – using system default" -ForegroundColor Yellow
}

# ── Set ANDROID_HOME ───────────────────────────────────────────
$sdkPath = "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk"
if (Test-Path $sdkPath) {
    $env:ANDROID_HOME = $sdkPath
    $env:ANDROID_SDK_ROOT = $sdkPath
    Write-Host "[OK] ANDROID_HOME set to: $sdkPath" -ForegroundColor Green
} else {
    Write-Host "[WARN] Android SDK not found at: $sdkPath" -ForegroundColor Yellow
    Write-Host "       Open Android Studio and install SDK via SDK Manager first." -ForegroundColor Yellow
}

# ── Build APK ──────────────────────────────────────────────────
Write-Host ""
Write-Host "[..] Running: gradlew assembleDebug" -ForegroundColor Cyan
Write-Host "     (First build may take 5-10 min to download Gradle & Android SDK)" -ForegroundColor DarkGray
Write-Host ""

Push-Location $ANDROID_DIR
try {
    & cmd /c "gradlew.bat assembleDebug 2>&1"
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    Write-Host ""
    Write-Host "[ERROR] Build failed with exit code: $exitCode" -ForegroundColor Red
    exit $exitCode
}

# ── Copy APK ──────────────────────────────────────────────────
if (Test-Path $OUT_APK) {
    Copy-Item $OUT_APK $DEST_APK -Force
    $sizeMb = [math]::Round((Get-Item $DEST_APK).Length / 1MB, 2)
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Green
    Write-Host "  Build SUCCESS!" -ForegroundColor Green
    Write-Host "  APK:  $DEST_APK" -ForegroundColor Yellow
    Write-Host "  Size: ${sizeMb} MB" -ForegroundColor Yellow
    Write-Host "==========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Install on your phone:" -ForegroundColor Cyan
    Write-Host "  adb install -r $DEST_APK" -ForegroundColor White
    Write-Host ""
    Write-Host "Or copy GallerySync.apk to your phone and open it." -ForegroundColor DarkGray
} else {
    Write-Host "[WARN] APK not found at expected path: $OUT_APK" -ForegroundColor Yellow
    Write-Host "       Check build output above for errors." -ForegroundColor Yellow
}
