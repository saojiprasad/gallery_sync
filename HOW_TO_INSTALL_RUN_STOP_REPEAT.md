# Install, Run, Stop, Repeat

Nothing in this guide needs to run on this coding machine. Use the other machine where you want to build/run.

## What Changed

After the user grants media permission and proceeds, Android starts a foreground sync service.

- Sync continues if the app screen is closed.
- Android shows a persistent notification while sync is running.
- The app screen shows rotating Korean character cards with English pronunciation while sync runs.
- If the user force-stops the app from Android settings, sync stops.
- If Android kills the service, it is marked sticky and can restart.
- After phone reboot, the boot receiver tries to restart sync if it was previously enabled and permission still exists.

## Build The APK

On the Android build machine:

```bash
cd android
gradle :app:assembleDebug
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

If using Android Studio:

1. Open the `android` folder.
2. Let Gradle sync.
3. Build the debug APK.

## Install The APK On Phone

Option 1: USB with ADB:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Option 2: Manual:

1. Copy `app-debug.apk` to the phone.
2. Open it on the phone.
3. Allow install from unknown sources if Android asks.
4. Install.

## Start Backend

On the backend machine:

```bash
cd backend
python -m venv .venv
```

Windows:

```bash
.venv\Scripts\activate
set GALLERY_SYNC_TOKEN=change-me
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

macOS/Linux:

```bash
source .venv/bin/activate
export GALLERY_SYNC_TOKEN=change-me
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Open dashboard:

```text
http://localhost:8000
```

Login token:

```text
change-me
```

For real use, replace `change-me` with a private token and enter the same token in the APK.

## Start Sync From APK

1. Open `korean-language-learning`.
2. Enter backend URL.
3. Enter sync token.
4. Tap **Grant Media Permission**.
5. Allow media access.
6. Tap **Proceed** if it does not continue automatically.
7. A persistent sync notification appears.
8. You may close the app screen. Sync should continue.

## Download From Laptop

1. Open the dashboard.
2. Login with the token.
3. Click **Request Download** or **Request All**.
4. Keep the phone powered on and connected to internet.
5. The background sync service uploads requested originals.
6. Click **Download** again when ready, or use the ready ZIP.

## Stop Backend

In the terminal where `uvicorn` is running:

```text
Ctrl+C
```

## Stop APK Sync

The app no longer includes an in-app stop button. It is designed to keep the foreground sync service running after permission and proceed.

Android can still stop it in these cases:

1. Pull down notifications.
2. Open app info/settings for `korean-language-learning`.
3. Force stop the app.
4. Revoke media permission.
5. Apply strict battery restrictions.
6. Uninstall the app.

Force stop is stronger: Android will not restart sync until you open the app again.

## Run Again Later

Backend:

```bash
cd backend
```

Windows:

```bash
.venv\Scripts\activate
set GALLERY_SYNC_TOKEN=change-me
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

macOS/Linux:

```bash
source .venv/bin/activate
export GALLERY_SYNC_TOKEN=change-me
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Phone:

1. Open `korean-language-learning`.
2. Confirm backend URL/token.
3. Proceed again.

## Reset Everything

Stop backend, then delete:

```text
backend/media
```

Then start backend again and proceed from the APK.
