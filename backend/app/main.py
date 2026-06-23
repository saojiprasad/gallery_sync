import json
import os
import secrets
import shutil
import time
import zipfile
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel


BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
MEDIA_DIR = BASE_DIR.parent / "media"
THUMBNAILS_DIR = MEDIA_DIR / "thumbnails"
UPLOADS_DIR = MEDIA_DIR / "uploads"
DOWNLOADS_DIR = MEDIA_DIR / "downloads"
METADATA_FILE = MEDIA_DIR / "metadata.json"
REQUESTS_FILE = MEDIA_DIR / "download_requests.json"
SYNC_TOKEN = os.environ.get("GALLERY_SYNC_TOKEN", "change-me")

for folder in (MEDIA_DIR, THUMBNAILS_DIR, UPLOADS_DIR, DOWNLOADS_DIR):
    folder.mkdir(parents=True, exist_ok=True)


app = FastAPI(title="Personal Gallery Sync")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


class DeviceRegistration(BaseModel):
    device_id: str
    device_name: str | None = None
    app_name: str | None = None


class MediaItem(BaseModel):
    id: str
    name: str
    uri: str | None = None
    type: str
    size: int = 0
    album: str | None = None
    mimeType: str | None = None
    dateCreated: int | None = None
    dateModified: int | None = None
    width: int | None = None
    height: int | None = None
    duration: int | None = None


class SyncPayload(BaseModel):
    device_id: str
    items: list[MediaItem]


class SocketHub:
    def __init__(self) -> None:
        self.connections: set[WebSocket] = set()

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self.connections.add(websocket)

    def disconnect(self, websocket: WebSocket) -> None:
        self.connections.discard(websocket)

    async def broadcast(self, event: str, payload: dict[str, Any] | None = None) -> None:
        message = {"event": event, "payload": payload or {}, "time": int(time.time())}
        dead: list[WebSocket] = []
        for websocket in self.connections:
            try:
                await websocket.send_json(message)
            except Exception:
                dead.append(websocket)
        for websocket in dead:
            self.disconnect(websocket)


hub = SocketHub()


def model_to_dict(model: BaseModel) -> dict[str, Any]:
    if hasattr(model, "model_dump"):
        return model.model_dump()
    return model.dict()


def read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return default


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_suffix(path.suffix + ".tmp")
    temp_path.write_text(json.dumps(value, indent=2, sort_keys=True), encoding="utf-8")
    temp_path.replace(path)


def is_valid_token(token: str | None) -> bool:
    return bool(token) and secrets.compare_digest(token, SYNC_TOKEN)


def require_token(request: Request) -> None:
    token = request.headers.get("X-Gallery-Sync-Token") or request.query_params.get("token")
    if not is_valid_token(token):
        raise HTTPException(status_code=401, detail="Invalid or missing sync token")


def load_store() -> dict[str, Any]:
    return read_json(METADATA_FILE, {"devices": {}, "media": {}, "last_sync": None})


def save_store(store: dict[str, Any]) -> None:
    write_json(METADATA_FILE, store)


def load_requests() -> dict[str, Any]:
    return read_json(REQUESTS_FILE, {"requests": {}})


def save_requests(requests: dict[str, Any]) -> None:
    write_json(REQUESTS_FILE, requests)


def safe_id(value: str) -> str:
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.")
    cleaned = "".join(char if char in allowed else "_" for char in value)
    return cleaned[:180] or "media"


def safe_folder_name(value: str | None) -> str:
    raw = (value or "Unknown").strip() or "Unknown"
    blocked = '<>:"/\\|?*'
    cleaned = "".join("_" if char in blocked or ord(char) < 32 else char for char in raw)
    return cleaned.strip(" .")[:120] or "Unknown"


def active_media_items() -> list[dict[str, Any]]:
    store = load_store()
    items = [item for item in store.get("media", {}).values() if item.get("status") != "removed"]
    return sorted(items, key=lambda item: item.get("dateModified") or 0, reverse=True)


def add_urls(item: dict[str, Any]) -> dict[str, Any]:
    item = dict(item)
    media_id = item["id"]
    thumb = THUMBNAILS_DIR / f"{safe_id(media_id)}.jpg"
    upload = UPLOADS_DIR / safe_id(media_id)
    item["thumbnailUrl"] = f"/thumbnail/{thumb.name}" if thumb.exists() else None
    item["downloadReady"] = upload.exists()
    return item


def queue_download(media_id: str) -> dict[str, Any]:
    store = load_store()
    item = store.get("media", {}).get(media_id)
    if not item or item.get("status") == "removed":
        raise HTTPException(status_code=404, detail="Media item not found")

    requests = load_requests()
    requests.setdefault("requests", {})[media_id] = {
        "media_id": media_id,
        "device_id": item["device_id"],
        "name": item.get("name") or media_id,
        "status": "pending",
        "requested_at": int(time.time()),
    }
    save_requests(requests)
    return requests["requests"][media_id]


def upload_path_for(media_id: str) -> Path:
    return UPLOADS_DIR / safe_id(media_id)


def organized_download_path(device_id: str, item: dict[str, Any], fallback_name: str) -> Path:
    device_folder = safe_folder_name(device_id)
    album_folder = safe_folder_name(item.get("album"))
    filename = safe_folder_name(item.get("name") or fallback_name)
    return DOWNLOADS_DIR / device_folder / album_folder / filename


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True, "auth": "token", "remoteReady": True}


@app.get("/relay/export")
def relay_export(request: Request) -> dict[str, Any]:
    require_token(request)
    store = load_store()
    media = {
        media_id: add_urls(item)
        for media_id, item in store.get("media", {}).items()
    }
    return {
        "devices": store.get("devices", {}),
        "media": media,
        "last_sync": store.get("last_sync"),
        "generated_at": int(time.time()),
    }


@app.get("/thumbnail/{filename}")
def thumbnail(filename: str, request: Request) -> FileResponse:
    require_token(request)
    safe_name = Path(filename).name
    path = THUMBNAILS_DIR / safe_name
    if not path.exists() or not path.is_file():
        raise HTTPException(status_code=404, detail="Thumbnail not found")
    return FileResponse(path, media_type="image/jpeg")


@app.post("/register-device")
async def register_device(registration: DeviceRegistration, request: Request) -> dict[str, Any]:
    require_token(request)
    store = load_store()
    store.setdefault("devices", {})[registration.device_id] = {
        "device_id": registration.device_id,
        "device_name": registration.device_name or "Android device",
        "app_name": registration.app_name or "Personal Gallery Sync",
        "connected_at": int(time.time()),
        "last_seen": int(time.time()),
    }
    save_store(store)
    await hub.broadcast("DEVICE_CONNECTED", {"device_id": registration.device_id})
    return {"ok": True, "device_id": registration.device_id}


@app.post("/sync-metadata")
async def sync_metadata(payload: SyncPayload, request: Request) -> dict[str, Any]:
    require_token(request)
    store = load_store()
    now = int(time.time())
    store.setdefault("devices", {}).setdefault(payload.device_id, {"device_id": payload.device_id})
    store["devices"][payload.device_id]["last_seen"] = now
    store.setdefault("media", {})

    seen_ids: set[str] = set()
    for item_model in payload.items:
        item = model_to_dict(item_model)
        media_id = item["id"]
        seen_ids.add(media_id)
        previous = store["media"].get(media_id, {})
        item.update(
            {
                "device_id": payload.device_id,
                "status": "active",
                "synced_at": now,
                "thumbnail_uploaded": previous.get("thumbnail_uploaded", False),
                "original_uploaded": upload_path_for(media_id).exists(),
            }
        )
        store["media"][media_id] = item

    for media_id, item in store["media"].items():
        if item.get("device_id") == payload.device_id and media_id not in seen_ids:
            item["status"] = "removed"
            item["removed_at"] = now

    store["last_sync"] = now
    save_store(store)
    await hub.broadcast("SYNC_COMPLETED", {"device_id": payload.device_id, "count": len(payload.items)})
    return {"ok": True, "count": len(payload.items)}


@app.post("/upload-thumbnail")
async def upload_thumbnail(
    request: Request,
    device_id: str = Form(...),
    media_id: str = Form(...),
    file: UploadFile = File(...),
) -> dict[str, Any]:
    require_token(request)
    destination = THUMBNAILS_DIR / f"{safe_id(media_id)}.jpg"
    with destination.open("wb") as output:
        shutil.copyfileobj(file.file, output)

    store = load_store()
    if media_id in store.get("media", {}):
        store["media"][media_id]["thumbnail_uploaded"] = True
        store["media"][media_id]["thumbnail_path"] = str(destination)
        save_store(store)

    await hub.broadcast("MEDIA_UPDATED", {"device_id": device_id, "media_id": media_id})
    return {"ok": True, "thumbnailUrl": f"/thumbnail/{destination.name}"}


@app.get("/media")
def list_media(
    request: Request,
    q: str = "",
    album: str = "",
    type: str = "",
    limit: int = Query(120, ge=1, le=500),
    offset: int = Query(0, ge=0),
) -> dict[str, Any]:
    require_token(request)
    items = active_media_items()
    query = q.strip().lower()
    if query:
        items = [
            item
            for item in items
            if query in (item.get("name") or "").lower()
            or query in (item.get("album") or "").lower()
            or query in (item.get("mimeType") or "").lower()
        ]
    if album:
        items = [item for item in items if (item.get("album") or "") == album]
    if type:
        items = [item for item in items if item.get("type") == type]

    page = items[offset : offset + limit]
    return {"total": len(items), "items": [add_urls(item) for item in page]}


@app.get("/media/{media_id}")
def get_media(media_id: str, request: Request) -> dict[str, Any]:
    require_token(request)
    store = load_store()
    item = store.get("media", {}).get(media_id)
    if not item or item.get("status") == "removed":
        raise HTTPException(status_code=404, detail="Media item not found")
    return add_urls(item)


@app.get("/albums")
def albums(request: Request) -> dict[str, Any]:
    require_token(request)
    counts: dict[str, int] = {}
    for item in active_media_items():
        album = item.get("album") or "Unknown"
        counts[album] = counts.get(album, 0) + 1
    return {"albums": [{"name": name, "count": count} for name, count in sorted(counts.items())]}


@app.get("/search")
def search(request: Request, q: str = "", album: str = "", type: str = "") -> dict[str, Any]:
    return list_media(request=request, q=q, album=album, type=type, limit=120, offset=0)


@app.get("/stats")
def stats(request: Request) -> dict[str, Any]:
    require_token(request)
    store = load_store()
    items = active_media_items()
    total_size = sum(int(item.get("size") or 0) for item in items)
    return {
        "devices": list(store.get("devices", {}).values()),
        "total": len(items),
        "photos": sum(1 for item in items if item.get("type") == "image"),
        "videos": sum(1 for item in items if item.get("type") == "video"),
        "storage": total_size,
        "lastSync": store.get("last_sync"),
    }


@app.post("/request-download/{media_id}")
async def request_download(media_id: str, request: Request) -> dict[str, Any]:
    require_token(request)
    download_request = queue_download(media_id)
    await hub.broadcast("DOWNLOAD_REQUESTED", {"media_id": media_id})
    return {"ok": True, "request": download_request}


@app.get("/download/{media_id}")
async def download(media_id: str, request: Request):
    require_token(request)
    store = load_store()
    item = store.get("media", {}).get(media_id)
    if not item or item.get("status") == "removed":
        raise HTTPException(status_code=404, detail="Media item not found")

    path = upload_path_for(media_id)
    if not path.exists():
        queue_download(media_id)
        await hub.broadcast("DOWNLOAD_REQUESTED", {"media_id": media_id})
        return JSONResponse(
            status_code=202,
            content={"ok": False, "status": "pending", "message": "Phone upload requested. Keep the app open."},
        )

    filename = item.get("name") or f"{media_id}.bin"
    return FileResponse(path, filename=filename, media_type=item.get("mimeType") or "application/octet-stream")


@app.post("/download-all/request")
async def request_all_downloads(request: Request) -> dict[str, Any]:
    require_token(request)
    count = 0
    for item in active_media_items():
        if not upload_path_for(item["id"]).exists():
            queue_download(item["id"])
            count += 1
    await hub.broadcast("DOWNLOAD_REQUESTED", {"count": count})
    return {"ok": True, "queued": count}


@app.get("/download-ready.zip")
def download_ready_zip(request: Request) -> FileResponse:
    require_token(request)
    archive = DOWNLOADS_DIR / "ready-gallery.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zip_file:
        for item in active_media_items():
            path = upload_path_for(item["id"])
            if path.exists():
                name = item.get("name") or path.name
                zip_file.write(path, arcname=name)
    return FileResponse(archive, filename="ready-gallery.zip", media_type="application/zip")


@app.get("/download-requests")
def download_requests(request: Request, device_id: str = "") -> dict[str, Any]:
    require_token(request)
    requests = load_requests()
    pending = []
    for request in requests.get("requests", {}).values():
        if request.get("status") != "pending":
            continue
        if device_id and request.get("device_id") != device_id:
            continue
        pending.append(request)
    return {"requests": pending}


@app.post("/upload-original")
async def upload_original(
    request: Request,
    device_id: str = Form(...),
    media_id: str = Form(...),
    file: UploadFile = File(...),
) -> dict[str, Any]:
    require_token(request)
    store = load_store()
    item = store.get("media", {}).get(media_id, {})
    destination = upload_path_for(media_id)
    organized_destination = organized_download_path(device_id, item, file.filename or media_id)
    organized_destination.parent.mkdir(parents=True, exist_ok=True)

    with destination.open("wb") as output:
        shutil.copyfileobj(file.file, output)
    shutil.copyfile(destination, organized_destination)

    requests = load_requests()
    if media_id in requests.get("requests", {}):
        requests["requests"][media_id]["status"] = "ready"
        requests["requests"][media_id]["ready_at"] = int(time.time())
        save_requests(requests)

    if media_id in store.get("media", {}):
        store["media"][media_id]["original_uploaded"] = True
        store["media"][media_id]["download_path"] = str(organized_destination)
        save_store(store)

    await hub.broadcast("MEDIA_UPDATED", {"device_id": device_id, "media_id": media_id, "downloadReady": True})
    return {"ok": True, "media_id": media_id, "path": str(organized_destination)}


@app.websocket("/ws/gallery")
async def gallery_socket(websocket: WebSocket) -> None:
    if not is_valid_token(websocket.query_params.get("token")):
        await websocket.close(code=1008)
        return
    await hub.connect(websocket)
    try:
        await websocket.send_json({"event": "CONNECTED", "payload": {}, "time": int(time.time())})
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        hub.disconnect(websocket)
