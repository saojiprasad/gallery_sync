# No Public IP Relay Mode

If your laptop has no public IP, the phone cannot directly connect to it from far away.

This project now supports relay mode:

```text
Android phone -> public relay URL -> laptop sync worker -> local laptop dashboard
```

Your laptop makes outbound requests to the relay, so it does not need port forwarding or a public IP.

## What You Still Need

You still need one public URL for the relay. This can be:

- A small VPS
- A cloud app host
- A trusted HTTPS tunnel/reverse proxy service
- Any machine/server that has a public HTTPS URL

You do not need your laptop to have a public IP.

## 1. Run The Public Relay

Put this project on the public relay machine/host.

From the project root:

```bash
cd backend
python -m venv .venv
```

Windows:

```bash
.venv\Scripts\activate
set GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

macOS/Linux:

```bash
source .venv/bin/activate
export GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Expose this backend as an HTTPS URL, for example:

```text
https://gallery-relay.yourdomain.com
```

## 2. Run The Local Laptop Dashboard

On your laptop, run a separate local copy of the backend.

From the project root on the laptop:

```bash
cd backend
python -m venv .venv
```

Windows:

```bash
.venv\Scripts\activate
set GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

macOS/Linux:

```bash
source .venv/bin/activate
export GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Open the laptop dashboard:

```text
http://localhost:8000
```

Login with the sync token.

## 3. Run The Laptop Relay Sync Worker

Open a second terminal on the laptop from the project root.

Windows:

```bash
set GALLERY_RELAY_URL=https://gallery-relay.yourdomain.com
set GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
python tools/laptop_relay_sync.py
```

macOS/Linux:

```bash
export GALLERY_RELAY_URL=https://gallery-relay.yourdomain.com
export GALLERY_SYNC_TOKEN=my-long-private-gallery-token-2026
python tools/laptop_relay_sync.py
```

This worker copies relay data into:

```text
backend/media
```

The local laptop dashboard reads from that folder.
If the dashboard is already open, click **Refresh** after the worker prints a new sync line.

## 4. Configure The Android APK

In the Android app:

- Backend URL: `https://gallery-relay.yourdomain.com`
- Sync token: `my-long-private-gallery-token-2026`

Then grant media permission and tap **Start Sync**.

## 5. Download Flow

1. Phone uploads metadata and thumbnails to the public relay.
2. Laptop sync worker copies metadata and thumbnails to the laptop.
3. You open `http://localhost:8000` on the laptop.
4. You click **Request Download**.
5. The sync worker sends that request to the relay.
6. The phone sees the request and uploads the original file to the relay.
7. The sync worker downloads that original file to the laptop.
8. Click **Download** again if the browser did not automatically save it yet.

## Notes

- The relay stores metadata, thumbnails, and requested originals while it is running.
- Keep the sync token private.
- Use HTTPS for the relay URL.
- If you do not want any third-party server to ever hold files, use a trusted tunnel or VPN instead of a hosted relay.
- Without a relay, tunnel, VPN, or public backend URL, a far-away phone cannot reach a laptop behind NAT.
