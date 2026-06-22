# ============================================================
# Gallery Sync – Demo Day Workflow
# ============================================================
# Run these steps IN ORDER before your external evaluation
# ============================================================

# STEP 1: Start backend + open Cloudflare Tunnel
#   (Keep this window open throughout the demo)
.\tools\start_tunnel.ps1

# STEP 2 (separate window): Build APK with the tunnel URL
#   (After start_tunnel.ps1 shows the URL and writes it to current_tunnel_url.txt)
.\tools\build_apk.ps1

# OR: Build with a specific URL you paste directly
.\tools\build_apk.ps1 -Url "https://purple-cat-123.trycloudflare.com"

# STEP 3: Install APK on your phone via USB
adb install -r .\tools\GallerySync.apk

# STEP 4: Open the app – URL is pre-filled. Set token to: change-me
#         Tap "Grant Media Permission" then "Start Sync"

# STEP 5: Open the web dashboard in a browser
#   https://purple-cat-123.trycloudflare.com
