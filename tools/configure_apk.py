import argparse
import shutil
from pathlib import Path
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[1]
BRANDING_FILE = ROOT / "branding" / "app-branding.properties"
STRINGS_FILE = ROOT / "android" / "app" / "src" / "main" / "res" / "values" / "strings.xml"
DRAWABLE_DIR = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"
SUPPORTED_ICON_EXTS = {".png", ".jpg", ".jpeg", ".webp"}


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def write_app_name(app_name: str) -> None:
    STRINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    STRINGS_FILE.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <string name="app_name">{escape(app_name)}</string>\n'
        "</resources>\n",
        encoding="utf-8",
    )


def apply_icon(icon_path: str) -> None:
    if not icon_path:
        return

    source = Path(icon_path).expanduser()
    if not source.is_absolute():
        source = (ROOT / source).resolve()

    if not source.exists():
        raise FileNotFoundError(f"Icon file not found: {source}")

    ext = source.suffix.lower()
    if ext not in SUPPORTED_ICON_EXTS:
        supported = ", ".join(sorted(SUPPORTED_ICON_EXTS))
        raise ValueError(f"Unsupported icon type {ext}. Use one of: {supported}")

    if ext == ".jpeg":
        ext = ".jpg"

    DRAWABLE_DIR.mkdir(parents=True, exist_ok=True)
    for existing in DRAWABLE_DIR.glob("app_icon.*"):
        existing.unlink()

    shutil.copyfile(source, DRAWABLE_DIR / f"app_icon{ext}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Configure Android APK name and launcher icon.")
    parser.add_argument("--name", help="APK display name")
    parser.add_argument("--icon", help="Path to PNG/JPG/WEBP launcher icon")
    args = parser.parse_args()

    props = read_properties(BRANDING_FILE)
    app_name = args.name or props.get("appName") or "Personal Gallery Sync"
    icon_path = args.icon if args.icon is not None else props.get("iconPath", "")

    write_app_name(app_name)
    apply_icon(icon_path)

    print(f"APK name set to: {app_name}")
    if icon_path:
        print(f"APK icon set from: {icon_path}")
    else:
        print("APK icon unchanged.")


if __name__ == "__main__":
    main()
