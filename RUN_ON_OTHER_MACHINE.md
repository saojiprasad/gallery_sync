# Personal Gallery Sync - Run On Another Machine

This repo is only source code. Nothing needs to be installed on this machine.

Use another machine for building/running:

## 1. What You Need On The Other Machine

- Python 3.12+
- Android Studio with Android SDK 35
- JDK 17+
- Gradle, or use Android Studio's Gradle support
- A backend URL that the phone can reach

For nearby testing, that URL can be a laptop Wi-Fi IP address.

For a phone that is 500 km away, that URL must be a public internet URL, preferably HTTPS, for example:

```text
https://gallery.yourdomain.com
```

Distance does not matter. The APK only needs internet access to the backend URL.

## 2. Change APK Name And Icon

The APK title and launcher icon are controlled by:

- `branding/app-branding.properties`
- `tools/configure_apk.py`

Edit `branding/app-branding.properties`:

```properties
appName=korean-language-learning
iconPath=C:/path/to/my_icon.png
```

Then run this on the other machine:

```bash
python tools/configure_apk.py
```

Or pass values directly:

```bash
python tools/configure_apk.py --name "korean-language-learning" --icon "C:/path/to/my_icon.png"
```

Icon file support is intentionally simple: use PNG, JPG, JPEG, or WEBP. The script copies your image into the Android resources as the APK launcher icon. It does not resize or redesign it.

## 3. Choose A Sync Token

Use the same token in three places:

- Backend environment variable: `GALLERY_SYNC_TOKEN`
- Android APK sync token field
- Laptop dashboard login field

For local testing the code defaults to:

```text
change-me
```

For internet/remote use, change it to a long private value. Do not use `change-me` on a public server.

Example:

```text
my-long-private-gallery-token-2026
```

## 4. Start The Backend

From the project root on the other machine:

```bash
cd backend
python -m venv .venv
```

Windows local test:

```bash
.venv\Scripts\activate
set GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

macOS/Linux local test:

```bash
source .venv/bin/activate
export GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Open the dashboard on the laptop:

```text
http://localhost:8000
```

If phone and laptop are on the same Wi-Fi, find the laptop Wi-Fi IP address, for example:

```text
http://192.168.1.23:8000
```

That is the URL you will type into the Android app.

## 5. Run It When The Phone Is Far Away

If the phone is not on the same Wi-Fi and your laptop has a public IP or public tunnel, do one of these on the other machine/server:

- Host `backend/` on a VPS or cloud machine with a public domain.
- Expose your home/laptop backend through your router with HTTPS and port forwarding.
- Use any HTTPS tunnel/reverse proxy service you already trust.

The public URL must forward to the FastAPI backend port, usually `8000`.

Use this shape:

```text
https://gallery.yourdomain.com
```

Then:

1. Open the APK on the phone.
2. Enter the public backend URL.
3. Enter the same sync token configured as `GALLERY_SYNC_TOKEN`.
4. Grant media permission.
5. Tap **Start Sync**.
6. Open the dashboard from any laptop browser at the same public URL.
7. Login using the sync token.

If your laptop does not have a public IP, use relay mode instead:

```text
NO_PUBLIC_IP_RELAY_MODE.md
```

In relay mode, the phone sends data to a public relay URL, and your laptop pulls the data from that relay into the local laptop dashboard.

## 6. Build The APK

Option A: Android Studio

1. Open the `android` folder in Android Studio.
2. Let Android Studio sync Gradle.
3. Build `app`.
4. The debug APK will be under:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Option B: Command line with Gradle installed

```bash
cd android
gradle :app:assembleDebug
```

## 7. Install And Use

For a shorter operational checklist, use:

```text
HOW_TO_INSTALL_RUN_STOP_REPEAT.md
```

1. Install `app-debug.apk` on your phone.
2. Open the app.
3. Enter the backend URL. Nearby local example:

```text
http://192.168.1.23:8000
```

Remote internet example:

```text
https://gallery.yourdomain.com
```

4. Enter the sync token.
5. Tap **Grant Media Permission**.
6. Tap **Start Sync**.
7. Open the same backend URL on the laptop.
8. Login with the sync token.
9. View thumbnails in the browser.
10. Originals begin backing up automatically after permission/proceed. They are stored under `backend/media/downloads/<device-id>/<album-folder>/`. You can also click **Download** on a file from the dashboard.

## 8. Important Notes

- This is a simple personal tool, not production software.
- Use it only on your own phone and your own laptop.
- Same Wi-Fi is not required when the backend URL is public and reachable from the phone.
- Use HTTPS for remote internet access.
- Keep the sync token private.
- The backend stores data under `backend/media/`.
- Thumbnails are uploaded first. Original files now back up automatically after permission/proceed and are also available through dashboard downloads.
- If Android shows a limited photo access option, choose full access if you want the full gallery.
- Android 14, 15, and 16 can show selected-media access. The APK now accepts selected access, but it can only sync the media Android allows.
- After permission and proceed, the Android foreground sync service continues when the app screen is closed. The app screen shows Korean character/pronunciation cards while sync runs. Android will show a persistent notification for the background service. Android can still stop it if the user force-stops the app, revokes permission, applies strict battery restrictions, or uninstalls it.
- HTTP is enabled for local Wi-Fi convenience.

## 9. Reset Local Data

Stop the backend, then delete this folder on the other machine:

```text
backend/media
```

Start the backend again and sync from the phone.
