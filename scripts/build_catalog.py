#!/usr/bin/env python3
"""Build Library's schema-v2 catalog from catalog/apps/**/*.json."""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APPS_DIR = ROOT / "catalog" / "apps"
DEFAULT_OUTPUT = ROOT / "catalog" / "library.json"
ASSET_OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "catalog.json"


def validate_app(app: dict, path: Path) -> None:
    for key in ("id", "name", "packageName", "release", "provenance"):
        if key not in app:
            raise SystemExit(f"{path}: missing required field {key}")
    release = app["release"]
    for key in ("versionName", "versionCode", "minSdk", "targetSdk"):
        if key not in release:
            raise SystemExit(f"{path}: release missing {key}")
    for artifact in release.get("artifacts", []):
        digest = artifact.get("sha256", "")
        if digest and (len(digest) != 64 or any(c not in "0123456789abcdefABCDEF" for c in digest)):
            raise SystemExit(f"{path}: invalid SHA-256 for {artifact.get('name', 'artifact')}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--updated-at", default=dt.date.today().isoformat())
    parser.add_argument("--generated-at", default=dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"))
    args = parser.parse_args()

    apps = []
    ids: set[str] = set()
    packages: set[str] = set()
    for path in sorted(APPS_DIR.rglob("*.json")):
        app = json.loads(path.read_text())
        validate_app(app, path)
        app_id = app["id"]
        package = app["packageName"]
        if app_id in ids:
            raise SystemExit(f"duplicate app id: {app_id} ({path})")
        if package in packages:
            raise SystemExit(f"duplicate package name: {package} ({path})")
        ids.add(app_id)
        packages.add(package)
        apps.append(app)

    apps.sort(key=lambda app: (not bool(app.get("featured")), app.get("name", "").lower()))
    catalog = {"schemaVersion": 2, "name": "Library", "updatedAt": args.updated_at, "generatedAt": args.generated_at, "apps": apps}
    payload = json.dumps(catalog, indent=2, ensure_ascii=False) + "\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(payload)
    ASSET_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    ASSET_OUTPUT.write_text(payload)
    print(f"wrote {len(apps)} apps to {args.output} and {ASSET_OUTPUT}")


if __name__ == "__main__":
    main()
