# Personal Gallery Sync - Run On Another Machine

This repo is only source code. Nothing needs to be installed on this machine.

Use another machine for building/running:

## 1. What You Need On The Other Machine

- Python 3.12+
- Android Studio with Android SDK 35
- JDK 17+
- Gradle, or use Android Studio's Gradle support
- A phone and laptop on the same Wi-Fi network

## 2. Change APK Name And Icon

The APK title and launcher icon are controlled by:

- `branding/app-branding.properties`
- `tools/configure_apk.py`

Edit `branding/app-branding.properties`:

```properties
appName=My Gallery App
iconPath=C:/path/to/my_icon.png
```

Then run this on the other machine:

```bash
python tools/configure_apk.py
```

Or pass values directly:

```bash
python tools/configure_apk.py --name "My Gallery App" --icon "C:/path/to/my_icon.png"
```

Icon file support is intentionally simple: use PNG, JPG, JPEG, or WEBP. The script copies your image into the Android resources as the APK launcher icon. It does not resize or redesign it.

## 3. Start The Laptop Backend

From the project root on the other machine:

```bash
cd backend
python -m venv .venv
```

Windows:

```bash
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

macOS/Linux:

```bash
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Open the dashboard on the laptop:

```text
http://localhost:8000
```

Find the laptop Wi-Fi IP address, for example:

```text
http://192.168.1.23:8000
```

That is the URL you will type into the Android app.

## 4. Build The APK

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

## 5. Install And Use

1. Install `app-debug.apk` on your phone.
2. Open the app.
3. Enter the laptop backend URL, for example:

```text
http://192.168.1.23:8000
```

4. Tap **Grant Media Permission**.
5. Tap **Start Sync**.
6. Open `http://localhost:8000` on the laptop.
7. View thumbnails in the browser.
8. Click **Download** on a file. If the file is not already on the laptop, the backend asks the phone for it. Keep the Android app open until the download is ready.

## 6. Important Notes

- This is a simple local tool, not production software.
- Use it only on your own phone and your own laptop.
- Keep phone and laptop on the same trusted Wi-Fi.
- The backend stores data under `backend/media/`.
- Thumbnails are uploaded first. Original files are uploaded only when you request a download.
- If Android shows a limited photo access option, choose full access if you want the full gallery.
- HTTP is enabled for local Wi-Fi convenience.

## 7. Reset Local Data

Stop the backend, then delete this folder on the other machine:

```text
backend/media
```

Start the backend again and sync from the phone.
