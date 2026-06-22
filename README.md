# Personal Gallery Sync

Simple phone-to-laptop sync viewer. The Android APK label is `korean-language-learning`. It can run locally on the same Wi-Fi or remotely over the internet when the backend has a public HTTPS URL or relay.

This source tree contains:

- `android/` - Kotlin Android APK source
- `backend/` - FastAPI backend plus browser dashboard
- `branding/` - editable APK name/icon settings
- `tools/configure_apk.py` - applies APK name/icon settings
- `tools/laptop_relay_sync.py` - pulls remote relay data into the laptop
- `RUN_ON_OTHER_MACHINE.md` - setup and run guide
- `NO_PUBLIC_IP_RELAY_MODE.md` - setup when the laptop has no public IP
- `HOW_TO_INSTALL_RUN_STOP_REPEAT.md` - APK/backend start, stop, and repeat steps

No dependency installation was performed while creating these files. Build and run it on the other machine using `RUN_ON_OTHER_MACHINE.md`.

For remote use, set `GALLERY_SYNC_TOKEN` on the backend and enter the same token in the Android app and dashboard login.
