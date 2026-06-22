#!/usr/bin/env pwsh
# ============================================================
# Gallery Sync – Cloudflare Tunnel Launcher
# ============================================================
# Usage:  .\tools\start_tunnel.ps1
#
# What this script does:
#   1. Starts your FastAPI backend (if not already running)
#   2. Starts a Cloudflare Tunnel and captures the public URL
#   3. Writes the URL to tools\current_tunnel_url.txt
#   4. Optionally patches MainActivity.kt with the new URL
# ============================================================

$ROOT       = Split-Path $PSScriptRoot -Parent
$BACKEND    = "$ROOT\backend"
$VENV_PYTHON = "$BACKEND\.venv\Scripts\python.exe"
$CLOUDFLARED = "$PSScriptRoot\cloudflared.exe"
$URL_FILE    = "$PSScriptRoot\current_tunnel_url.txt"
$MAIN_KT     = "$ROOT\android\app\src\main\java\com\gallerysync\app\MainActivity.kt"
$PORT        = 8000

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Gallery Sync – Cloudflare Tunnel Setup  " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# ── 1. Verify cloudflared exists ─────────────────────────────
if (-not (Test-Path $CLOUDFLARED)) {
    Write-Host "[ERROR] cloudflared.exe not found at: $CLOUDFLARED" -ForegroundColor Red
    Write-Host "  Run this first:" -ForegroundColor Yellow
    Write-Host "  Invoke-WebRequest -Uri https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe -OutFile $CLOUDFLARED" -ForegroundColor Yellow
    exit 1
}

$cfVer = & $CLOUDFLARED --version 2>&1 | Select-Object -First 1
Write-Host "[OK] cloudflared found: $cfVer" -ForegroundColor Green

# ── 2. Start FastAPI backend ──────────────────────────────────
$backendRunning = $false
try {
    $resp = Invoke-WebRequest -Uri "http://localhost:$PORT/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
    if ($resp.StatusCode -eq 200) {
        $backendRunning = $true
        Write-Host "[OK] FastAPI already running on port $PORT" -ForegroundColor Green
    }
} catch {}

if (-not $backendRunning) {
    Write-Host "[..] Starting FastAPI backend on port $PORT..." -ForegroundColor Cyan

    if (-not (Test-Path $VENV_PYTHON)) {
        Write-Host "[ERROR] Virtual environment not found: $VENV_PYTHON" -ForegroundColor Red
        Write-Host "  Create it with:  python -m venv $BACKEND\.venv" -ForegroundColor Yellow
        Write-Host "  Then install:    $VENV_PYTHON -m pip install -r $BACKEND\requirements.txt" -ForegroundColor Yellow
        exit 1
    }

    Start-Process -FilePath $VENV_PYTHON `
        -ArgumentList "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "$PORT" `
        -WorkingDirectory $BACKEND `
        -WindowStyle Normal

    Write-Host "[..] Waiting for backend to be ready..." -ForegroundColor Cyan
    $waited = 0
    while ($waited -lt 20) {
        Start-Sleep -Seconds 1
        $waited++
        try {
            $resp = Invoke-WebRequest -Uri "http://localhost:$PORT/health" -TimeoutSec 1 -UseBasicParsing -ErrorAction Stop
            if ($resp.StatusCode -eq 200) {
                Write-Host "[OK] Backend is ready!" -ForegroundColor Green
                $backendRunning = $true
                break
            }
        } catch {}
    }

    if (-not $backendRunning) {
        Write-Host "[ERROR] Backend did not start within 20 seconds." -ForegroundColor Red
        exit 1
    }
}

# ── 3. Start Cloudflare Tunnel ───────────────────────────────
Write-Host ""
Write-Host "[..] Starting Cloudflare Tunnel (this may take 10-20 seconds)..." -ForegroundColor Cyan

$logFile = "$env:TEMP\cloudflared_gallery_sync.log"
$cfProcess = Start-Process -FilePath $CLOUDFLARED `
    -ArgumentList "tunnel", "--url", "http://localhost:$PORT" `
    -RedirectStandardError $logFile `
    -PassThru `
    -WindowStyle Hidden

Write-Host "[..] Waiting for tunnel URL..." -ForegroundColor Cyan
$tunnelUrl = $null
$elapsed = 0

while ($elapsed -lt 60) {
    Start-Sleep -Seconds 2
    $elapsed += 2

    if (Test-Path $logFile) {
        $content = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
        if ($content -match "https://[a-z0-9\-]+\.trycloudflare\.com") {
            $tunnelUrl = $matches[0]
            break
        }
    }

    Write-Host "  ... still waiting ($elapsed s)" -ForegroundColor DarkGray
}

if (-not $tunnelUrl) {
    Write-Host "[ERROR] Could not capture tunnel URL. Check log: $logFile" -ForegroundColor Red
    $cfProcess | Stop-Process -ErrorAction SilentlyContinue
    exit 1
}

# ── 4. Save URL to file ───────────────────────────────────────
$tunnelUrl | Out-File -FilePath $URL_FILE -Encoding UTF8 -NoNewline
Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  Tunnel URL:" -ForegroundColor Green
Write-Host "  $tunnelUrl" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""
Write-Host "[OK] URL saved to: $URL_FILE" -ForegroundColor Green
Write-Host "[OK] FastAPI docs: ${tunnelUrl}/docs" -ForegroundColor Green
Write-Host ""

# ── 5. Prompt to update the Android app ─────────────────────
$update = Read-Host "Update MainActivity.kt with this URL? (y/N)"
if ($update -match '^[Yy]') {
    if (Test-Path $MAIN_KT) {
        $kt = Get-Content $MAIN_KT -Raw -Encoding UTF8
        # Replace any existing backend URL in the hint/default
        $kt = $kt -replace '(hint\s*=\s*")https://[^"]+(")', "`${1}${tunnelUrl}`${2}"
        $kt = $kt -replace '(setText\(prefs\.getString\("backend_url",\s*")https://[^"]+(")', "`${1}${tunnelUrl}`${2}"
        Set-Content -Path $MAIN_KT -Value $kt -Encoding UTF8
        Write-Host "[OK] MainActivity.kt updated with new tunnel URL." -ForegroundColor Green
        Write-Host "     Rebuild APK:  .\tools\build_apk.ps1" -ForegroundColor Cyan
    } else {
        Write-Host "[WARN] MainActivity.kt not found at expected path." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "Press Ctrl+C to stop the tunnel. Backend continues running." -ForegroundColor DarkGray

# Keep script alive so tunnel stays open
try {
    while ($true) {
        Start-Sleep -Seconds 10
        if ($cfProcess.HasExited) {
            Write-Host "[WARN] Cloudflare tunnel process ended unexpectedly." -ForegroundColor Yellow
            break
        }
    }
} finally {
    Write-Host ""
    Write-Host "[..] Stopping tunnel..." -ForegroundColor Cyan
    $cfProcess | Stop-Process -ErrorAction SilentlyContinue
    Write-Host "[OK] Tunnel stopped." -ForegroundColor Green
}
