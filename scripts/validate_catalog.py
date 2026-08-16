#!/usr/bin/env python3
from __future__ import annotations

import base64
import binascii
import json
import sys
from pathlib import Path

MAX_ICON_BYTES = 256 * 1024
ICON_MIME_TYPES = {"image/png", "image/webp", "image/jpeg"}


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "catalog/library.json")
    root = json.loads(path.read_text())
    if root.get("schemaVersion") != 2:
        fail("catalog schemaVersion must be 2")
    ids: set[str] = set()
    packages: set[str] = set()
    for app in root.get("apps", []):
        app_id = app.get("id")
        package = app.get("packageName")
        if not app_id or not package:
            fail("every app needs id and packageName")
        if app_id in ids or package in packages:
            fail(f"duplicate catalog identity: {app_id} / {package}")
        ids.add(app_id)
        packages.add(package)

        icon = app.get("icon")
        if icon is not None:
            if icon.get("mimeType") not in ICON_MIME_TYPES:
                fail(f"{app_id}: unsupported icon MIME type")
            try:
                icon_bytes = base64.b64decode(icon.get("dataBase64", ""), validate=True)
            except (binascii.Error, ValueError):
                fail(f"{app_id}: invalid icon base64")
            if not icon_bytes or len(icon_bytes) > MAX_ICON_BYTES:
                fail(f"{app_id}: icon must be between 1 byte and {MAX_ICON_BYTES} bytes")

        release = app.get("release", {})
        if int(release.get("versionCode", -1)) < 0:
            fail(f"{app_id}: invalid versionCode")
        artifacts = release.get("artifacts", [])
        for artifact in artifacts:
            digest = artifact.get("sha256", "")
            if len(digest) != 64:
                fail(f"{app_id}: artifact {artifact.get('name')} has invalid SHA-256")
            if not artifact.get("apiUrl") and not artifact.get("downloadUrl"):
                fail(f"{app_id}: artifact {artifact.get('name')} has no download URL")
        signer = app.get("provenance", {}).get("signingCertSha256")
        if artifacts and (not signer or len(signer.replace(":", "")) != 64):
            fail(f"{app_id}: installable release must pin a signing certificate")
    print(f"catalog OK: {len(ids)} apps")


if __name__ == "__main__":
    main()
