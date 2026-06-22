import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MEDIA_DIR = ROOT / "backend" / "media"


def safe_id(value: str) -> str:
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.")
    cleaned = "".join(char if char in allowed else "_" for char in value)
    return cleaned[:180] or "media"


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


def relay_url(base_url: str, path: str) -> str:
    base = base_url.rstrip("/") + "/"
    return urllib.parse.urljoin(base, path.lstrip("/"))


def token_request(url: str, token: str, method: str = "GET", data: bytes | None = None) -> urllib.request.Request:
    headers = {"X-Gallery-Sync-Token": token}
    return urllib.request.Request(url, data=data, headers=headers, method=method)


def request_json(base_url: str, token: str, path: str, method: str = "GET") -> dict[str, Any]:
    data = b"" if method.upper() == "POST" else None
    request = token_request(relay_url(base_url, path), token, method=method, data=data)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def request_bytes(base_url: str, token: str, path: str) -> tuple[int, bytes]:
    request = token_request(relay_url(base_url, path), token)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def media_paths(media_dir: Path) -> dict[str, Path]:
    return {
        "metadata": media_dir / "metadata.json",
        "requests": media_dir / "download_requests.json",
        "thumbnails": media_dir / "thumbnails",
        "uploads": media_dir / "uploads",
    }


def local_upload_path(paths: dict[str, Path], media_id: str) -> Path:
    return paths["uploads"] / safe_id(media_id)


def local_thumbnail_path(paths: dict[str, Path], media_id: str) -> Path:
    return paths["thumbnails"] / f"{safe_id(media_id)}.jpg"


def mirror_metadata(export: dict[str, Any], media_dir: Path) -> None:
    paths = media_paths(media_dir)
    local_store = read_json(paths["metadata"], {"devices": {}, "media": {}, "last_sync": None})
    remote_media = export.get("media", {})
    mirrored_media: dict[str, Any] = {}

    for media_id, item in remote_media.items():
        local_item = dict(item)
        local_item["thumbnail_uploaded"] = local_thumbnail_path(paths, media_id).exists()
        local_item["original_uploaded"] = local_upload_path(paths, media_id).exists()
        mirrored_media[media_id] = local_item

    local_store["devices"] = export.get("devices", {})
    local_store["media"] = mirrored_media
    local_store["last_sync"] = export.get("last_sync")
    write_json(paths["metadata"], local_store)


def download_thumbnails(base_url: str, token: str, export: dict[str, Any], media_dir: Path) -> int:
    paths = media_paths(media_dir)
    paths["thumbnails"].mkdir(parents=True, exist_ok=True)
    count = 0

    for media_id, item in export.get("media", {}).items():
        if item.get("status") == "removed" or not item.get("thumbnailUrl"):
            continue
        destination = local_thumbnail_path(paths, media_id)
        if destination.exists():
            continue
        status, body = request_bytes(base_url, token, item["thumbnailUrl"])
        if status == 200:
            destination.write_bytes(body)
            count += 1

    return count


def pending_local_requests(media_dir: Path) -> dict[str, Any]:
    requests_file = media_paths(media_dir)["requests"]
    requests = read_json(requests_file, {"requests": {}})
    return {
        media_id: request
        for media_id, request in requests.get("requests", {}).items()
        if request.get("status") == "pending"
    }


def mirror_download_requests(base_url: str, token: str, media_dir: Path) -> int:
    count = 0
    for media_id in pending_local_requests(media_dir):
        encoded_id = urllib.parse.quote(media_id, safe="")
        try:
            request_json(base_url, token, f"/request-download/{encoded_id}", method="POST")
            count += 1
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    return count


def pull_requested_originals(base_url: str, token: str, media_dir: Path) -> int:
    paths = media_paths(media_dir)
    paths["uploads"].mkdir(parents=True, exist_ok=True)
    requests_file = paths["requests"]
    requests = read_json(requests_file, {"requests": {}})
    local_store = read_json(paths["metadata"], {"devices": {}, "media": {}, "last_sync": None})
    count = 0

    for media_id, request in list(requests.get("requests", {}).items()):
        if request.get("status") != "pending":
            continue

        destination = local_upload_path(paths, media_id)
        if destination.exists():
            request["status"] = "ready"
            request["ready_at"] = int(time.time())
            continue

        encoded_id = urllib.parse.quote(media_id, safe="")
        status, body = request_bytes(base_url, token, f"/download/{encoded_id}")
        if status == 200:
            destination.write_bytes(body)
            request["status"] = "ready"
            request["ready_at"] = int(time.time())
            if media_id in local_store.get("media", {}):
                local_store["media"][media_id]["original_uploaded"] = True
            count += 1
        elif status in (202, 404):
            continue
        else:
            print(f"Original download skipped for {media_id}: HTTP {status}")

    write_json(requests_file, requests)
    write_json(paths["metadata"], local_store)
    return count


def sync_once(base_url: str, token: str, media_dir: Path) -> None:
    export = request_json(base_url, token, "/relay/export")
    mirror_metadata(export, media_dir)
    thumbnails = download_thumbnails(base_url, token, export, media_dir)
    mirrored_requests = mirror_download_requests(base_url, token, media_dir)
    originals = pull_requested_originals(base_url, token, media_dir)
    total_media = len(export.get("media", {}))
    print(
        f"Mirrored {total_media} items, "
        f"{thumbnails} thumbnails, "
        f"{mirrored_requests} download requests, "
        f"{originals} originals."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Mirror a public Gallery Sync relay into this laptop.")
    parser.add_argument("--relay-url", default=os.environ.get("GALLERY_RELAY_URL", ""), help="Public relay URL")
    parser.add_argument("--token", default=os.environ.get("GALLERY_SYNC_TOKEN", ""), help="Sync token")
    parser.add_argument("--media-dir", default=str(DEFAULT_MEDIA_DIR), help="Local backend media folder")
    parser.add_argument("--interval", type=int, default=20, help="Seconds between syncs")
    parser.add_argument("--once", action="store_true", help="Run one sync pass and exit")
    args = parser.parse_args()

    if not args.relay_url:
        raise SystemExit("Missing --relay-url or GALLERY_RELAY_URL")
    if not args.token:
        raise SystemExit("Missing --token or GALLERY_SYNC_TOKEN")

    media_dir = Path(args.media_dir).resolve()
    for folder in media_paths(media_dir).values():
        if folder.suffix:
            folder.parent.mkdir(parents=True, exist_ok=True)
        else:
            folder.mkdir(parents=True, exist_ok=True)

    while True:
        try:
            sync_once(args.relay_url, args.token, media_dir)
        except Exception as error:
            print(f"Relay sync error: {error}")

        if args.once:
            break
        time.sleep(max(5, args.interval))


if __name__ == "__main__":
    main()
