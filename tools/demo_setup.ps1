#!/usr/bin/env pwsh
# ============================================================
# Gallery Sync — ONE-CLICK DEMO SETUP
# ============================================================
# Run ONCE before demo:   .\tools\demo_setup.ps1
#
# What it does:
#   1. Starts FastAPI backend (port 8000)
#   2. Opens Cloudflare Tunnel, captures public URL
#   3. Bakes URL + token into the APK source
#   4. Rebuilds APK in ~30 seconds
#
# After running, user just: Install APK -> Grant Permission -> Proceed
# ============================================================

$ROOT        = Split-Path $PSScriptRoot -Parent
$BACKEND     = "$ROOT\backend"
$VENV_PYTHON = "$BACKEND\.venv\Scripts\python.exe"
$CLOUDFLARED = "$PSScriptRoot\cloudflared.exe"
$MAIN_KT     = "$ROOT\android\app\src\main\java\com\gallerysync\app\MainActivity.kt"
$ANDROID_DIR = "$ROOT\android"
$OUT_APK     = "$ANDROID_DIR\app\build\outputs\apk\debug\app-debug.apk"
$DEST_APK    = "$PSScriptRoot\GallerySync.apk"
$URL_FILE    = "$PSScriptRoot\current_tunnel_url.txt"
$PORT        = 8000
$SYNC_TOKEN  = "change-me"

$env:JAVA_HOME        = "$env:USERPROFILE\.jdks\jbr-17.0.14"
$env:ANDROID_HOME     = "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH             = "$env:JAVA_HOME\bin;" + $env:PATH

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Korean Master - Demo Setup               " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ─────────────────────────────────────────────────────────────
# STEP 1: Start FastAPI backend
# ─────────────────────────────────────────────────────────────
Write-Host "[1/4] Starting FastAPI backend..." -ForegroundColor Cyan

$backendRunning = $false
try {
    $r = Invoke-WebRequest -Uri "http://localhost:$PORT/health" -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
    if ($r.StatusCode -eq 200) {
        $backendRunning = $true
        Write-Host "      Already running on port $PORT" -ForegroundColor Green
    }
} catch { }

if (-not $backendRunning) {
    if (-not (Test-Path $VENV_PYTHON)) {
        Write-Host "ERROR: Python venv not found: $VENV_PYTHON" -ForegroundColor Red
        exit 1
    }
    Start-Process -FilePath $VENV_PYTHON `
        -ArgumentList "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "$PORT", "--loop", "asyncio" `
        -WorkingDirectory $BACKEND -WindowStyle Normal

    Write-Host "      Waiting for backend to start (up to 30s)..." -ForegroundColor DarkGray
    Start-Sleep -Seconds 3
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$PORT/health" -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
            if ($r.StatusCode -eq 200) { $backendRunning = $true; break }
        } catch { }
    }

    if ($backendRunning) {
        Write-Host "      Backend is ready" -ForegroundColor Green
    } else {
        Write-Host "ERROR: Backend did not start in time." -ForegroundColor Red
        exit 1
    }
}

# ─────────────────────────────────────────────────────────────
# STEP 2: Start Cloudflare Tunnel and capture URL
# ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "[2/4] Starting Cloudflare Tunnel..." -ForegroundColor Cyan

if (-not (Test-Path $CLOUDFLARED)) {
    Write-Host "ERROR: cloudflared.exe not found at: $CLOUDFLARED" -ForegroundColor Red
    exit 1
}

$logFile = "$env:TEMP\cloudflared_demo.log"
if (Test-Path $logFile) { Remove-Item $logFile -Force }

$cfProcess = Start-Process -FilePath $CLOUDFLARED `
    -ArgumentList "tunnel", "--url", "http://localhost:$PORT" `
    -RedirectStandardError $logFile -PassThru -WindowStyle Hidden

$tunnelUrl = $null
Write-Host "      Waiting for public URL (up to 60s)..." -ForegroundColor DarkGray

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    if (Test-Path $logFile) {
        $content = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
        if ($content -match "https://[a-z0-9-]+\.trycloudflare\.com") {
            $tunnelUrl = $Matches[0]
            break
        }
    }
}

if (-not $tunnelUrl) {
    Write-Host "ERROR: Could not capture tunnel URL." -ForegroundColor Red
    $cfProcess | Stop-Process -ErrorAction SilentlyContinue
    exit 1
}

$tunnelUrl | Out-File -FilePath $URL_FILE -Encoding UTF8 -NoNewline

Write-Host ""
Write-Host "      Tunnel URL:" -ForegroundColor Green
Write-Host "      $tunnelUrl" -ForegroundColor Yellow
Write-Host ""

# ─────────────────────────────────────────────────────────────
# STEP 3: Patch URL + Token into MainActivity.kt
# ─────────────────────────────────────────────────────────────
Write-Host "[3/4] Baking URL and token into APK source..." -ForegroundColor Cyan

$ktContent = [System.IO.File]::ReadAllText($MAIN_KT, [System.Text.Encoding]::UTF8)

# Replace the default value for backend_url (the text after default = " for the backend field)
# Pattern: savedKey = "backend_url", ... default = "ANYTHING"
# We do a simpler line-by-line approach
$lines = $ktContent -split "`n"
$patchedLines = @()
$inBackendInput = $false
$inTokenInput = $false

foreach ($line in $lines) {
    # Detect which makeInput block we are in
    if ($line -match 'savedKey\s*=\s*"backend_url"') { $inBackendInput = $true; $inTokenInput = $false }
    if ($line -match 'savedKey\s*=\s*"sync_token"')  { $inTokenInput = $true; $inBackendInput = $false }
    # When we reach the closing paren of makeInput, reset
    if ($line -match '^\s*\)' -and ($inBackendInput -or $inTokenInput)) {
        $inBackendInput = $false; $inTokenInput = $false
    }

    if ($inBackendInput -and $line -match '(\s*default\s*=\s*")[^"]*(")')   {
        $line = $line -replace '(\s*default\s*=\s*")[^"]*(")', "`${1}$tunnelUrl`${2}"
    }
    if ($inTokenInput -and $line -match '(\s*default\s*=\s*")[^"]*(")')      {
        $line = $line -replace '(\s*default\s*=\s*")[^"]*(")', "`${1}$SYNC_TOKEN`${2}"
    }
    # Also patch the hint line for the URL input
    if ($line -match '(\s*hint\s*=\s*")[^"]*(your-server-url|trycloudflare|example)[^"]*(")')        {
        $line = $line -replace '(\s*hint\s*=\s*")[^"]*(")', "`${1}$tunnelUrl`${2}"
    }
    $patchedLines += $line
}

$patchedContent = $patchedLines -join "`n"
[System.IO.File]::WriteAllText($MAIN_KT, $patchedContent, [System.Text.Encoding]::UTF8)

Write-Host "      Patched: backend_url default = $tunnelUrl" -ForegroundColor Green
Write-Host "      Patched: sync_token default  = $SYNC_TOKEN" -ForegroundColor Green

# ─────────────────────────────────────────────────────────────
# STEP 4: Rebuild APK
# ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "[4/4] Rebuilding APK..." -ForegroundColor Cyan

Push-Location $ANDROID_DIR
$buildOutput = & cmd /c "gradlew.bat assembleDebug 2>&1"
$buildCode   = $LASTEXITCODE
Pop-Location

# Show only key lines
$buildOutput | Where-Object { $_ -match "BUILD |FAILURE|error:|Exception" } | ForEach-Object {
    $color = if ($_ -match "SUCCESSFUL") { "Green" } else { "Red" }
    Write-Host "      $_" -ForegroundColor $color
}

if ($buildCode -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Build failed (exit $buildCode)." -ForegroundColor Red
    Write-Host "       Run gradlew.bat assembleDebug to see full output." -ForegroundColor Yellow
    $cfProcess | Stop-Process -ErrorAction SilentlyContinue
    exit 1
}

if (Test-Path $OUT_APK) {
    Copy-Item $OUT_APK $DEST_APK -Force
    $sizeMb = [math]::Round((Get-Item $DEST_APK).Length / 1MB, 2)

    Write-Host ""
    Write-Host "============================================" -ForegroundColor Green
    Write-Host "  DEMO READY!                              " -ForegroundColor Green
    Write-Host "============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  APK  : $DEST_APK ($sizeMb MB)" -ForegroundColor Yellow
    Write-Host "  URL  : $tunnelUrl" -ForegroundColor Yellow
    Write-Host "  Token: $SYNC_TOKEN (already in APK)" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  --- Install the APK ---" -ForegroundColor Cyan
    Write-Host "  Via ADB:  adb install -r `"$DEST_APK`"" -ForegroundColor White
    Write-Host "  Manual:   Copy GallerySync.apk to phone via USB" -ForegroundColor White
    Write-Host ""
    Write-Host "  --- On the phone ---" -ForegroundColor Cyan
    Write-Host "  1. Open app (Korean Master)" -ForegroundColor White
    Write-Host "  2. Tap 'Grant Storage Permission'" -ForegroundColor White
    Write-Host "  3. Tap 'Proceed' (URL + token already filled)" -ForegroundColor White
    Write-Host "  4. Gallery syncs automatically!" -ForegroundColor White
    Write-Host ""
    Write-Host "  --- Web Dashboard ---" -ForegroundColor Cyan
    Write-Host "  Open in browser: $tunnelUrl" -ForegroundColor White
    Write-Host ""
    Write-Host "  Keep this window OPEN during demo." -ForegroundColor DarkGray
    Write-Host "  Press Ctrl+C after demo to shut down." -ForegroundColor DarkGray
    Write-Host ""
}

# ─────────────────────────────────────────────────────────────
# Keep tunnel alive until Ctrl+C
# ─────────────────────────────────────────────────────────────
try {
    while ($true) {
        Start-Sleep -Seconds 15
        if ($cfProcess.HasExited) {
            Write-Host "WARN: Tunnel ended unexpectedly." -ForegroundColor Yellow
            break
        }
    }
} finally {
    Write-Host ""
    Write-Host "Shutting down tunnel..." -ForegroundColor Cyan
    $cfProcess | Stop-Process -ErrorAction SilentlyContinue
    Write-Host "Tunnel stopped." -ForegroundColor Green
}
