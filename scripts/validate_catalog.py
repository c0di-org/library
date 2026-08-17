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


def validate_release(app_id: str, release: dict, signer: str | None, label: str) -> None:
    version_code = int(release.get("versionCode", -1))
    if version_code < 0:
        fail(f"{app_id}: {label} has invalid versionCode")
    artifacts = release.get("artifacts", [])
    for artifact in artifacts:
        digest = artifact.get("sha256", "")
        if len(digest) != 64:
            fail(f"{app_id}: {label} artifact {artifact.get('name')} has invalid SHA-256")
        if not artifact.get("apiUrl") and not artifact.get("downloadUrl"):
            fail(f"{app_id}: {label} artifact {artifact.get('name')} has no download URL")
    if artifacts and (not signer or len(signer.replace(":", "")) != 64):
        fail(f"{app_id}: {label} must pin a signing certificate")


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

        current_signer = app.get("provenance", {}).get("signingCertSha256")
        current = app.get("release", {})
        validate_release(app_id, current, current_signer, "current release")

        seen_versions: set[tuple[int, str | None]] = set()
        releases = app.get("releases", [])
        for index, release in enumerate(releases):
            signer = release.get("signingCertSha256") or current_signer
            validate_release(app_id, release, signer, f"release #{index + 1}")
            key = (int(release.get("versionCode", -1)), release.get("tag"))
            if key in seen_versions:
                fail(f"{app_id}: duplicate installable release {key[1] or key[0]}")
            seen_versions.add(key)

        if releases:
            current_key = (int(current.get("versionCode", -1)), current.get("tag"))
            if current_key not in seen_versions:
                fail(f"{app_id}: releases must include the current release")

    print(f"catalog OK: {len(ids)} apps")


if __name__ == "__main__":
    main()
