#!/usr/bin/env python3
"""Discover installable Android GitHub Releases and generate Library manifests."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "catalog" / "apps" / "generated"
LIBRARY_MANIFEST = ROOT / "catalog" / "apps" / "library.json"
API = "https://api.github.com"
PACKAGE = re.compile(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'(?:.* split='([^']+)')?")
ICON = re.compile(r"application-icon-(\d+):'([^']+)'" )
ICON_FALLBACK = re.compile(r"application-icon:'([^']+)'" )
MAX_ICON_BYTES = 256 * 1024
DEFAULT_RELEASE_HISTORY_LIMIT = 10
MAX_RELEASE_HISTORY_LIMIT = 50


class GitHub:
    def __init__(self, token: str | None):
        self.token = token.strip() if token else None

    def request(self, url: str, accept: str = "application/vnd.github+json", authenticated: bool = True):
        headers = {
            "Accept": accept,
            "User-Agent": "garfbargle/library-catalog",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if authenticated and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return urllib.request.Request(url, headers=headers)

    def json(self, url: str, authenticated: bool = True):
        with urllib.request.urlopen(self.request(url, authenticated=authenticated), timeout=45) as response:
            return json.loads(response.read())

    def file(self, repo: dict, path: str):
        url = f"{API}/repos/{repo['full_name']}/contents/{urllib.parse.quote(path, safe='/')}?ref={urllib.parse.quote(repo['default_branch'])}"
        try:
            data = self.json(url, authenticated=bool(self.token))
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            raise
        if data.get("encoding") == "base64":
            return base64.b64decode(data["content"]).decode()
        return None

    def release_json(self, repo: dict, url: str):
        return self.json(url, authenticated=bool(self.token))

    def download(self, repo: dict, asset: dict, path: Path):
        authenticated = bool(self.token)
        request = self.request(asset["url"], "application/octet-stream", authenticated=authenticated)
        with urllib.request.urlopen(request, timeout=120) as response, path.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)


def tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        hits = sorted(Path(sdk).glob(f"build-tools/*/{name}"), reverse=True)
        if hits:
            return str(hits[0])
    hit = shutil.which(name)
    if hit:
        return hit
    raise SystemExit(f"{name} is required")


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _read_icon(apk: zipfile.ZipFile, resource: str, mime_types: dict[str, str]):
    mime_type = mime_types.get(Path(resource).suffix.lower())
    if not mime_type:
        return None
    try:
        with apk.open(resource) as source:
            data = source.read(MAX_ICON_BYTES + 1)
    except KeyError:
        return None
    if not data or len(data) > MAX_ICON_BYTES:
        return None
    return {
        "mimeType": mime_type,
        "dataBase64": base64.b64encode(data).decode("ascii"),
    }


def _density_score(resource: str) -> int:
    scores = {
        "xxxhdpi": 6,
        "xxhdpi": 5,
        "xhdpi": 4,
        "hdpi": 3,
        "mdpi": 2,
        "ldpi": 1,
    }
    return next((score for density, score in scores.items() if density in resource), 0)


def extract_icon(path: Path, badging: str):
    candidates = [(int(size), resource) for size, resource in ICON.findall(badging)]
    fallback = ICON_FALLBACK.search(badging)
    if fallback:
        candidates.append((0, fallback.group(1)))

    mime_types = {
        ".png": "image/png",
        ".webp": "image/webp",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
    }
    with zipfile.ZipFile(path) as apk:
        for _, resource in sorted(candidates, reverse=True):
            icon = _read_icon(apk, resource, mime_types)
            if icon:
                return icon

        stems = {Path(resource).stem for _, resource in candidates}
        stems.update({"ic_launcher", "ic_launcher_round"})
        raster_fallbacks = [
            name
            for name in apk.namelist()
            if Path(name).suffix.lower() in mime_types
            and Path(name).stem in stems
            and name.startswith("res/")
        ]
        raster_fallbacks.sort(
            key=lambda name: (_density_score(name), -len(name)),
            reverse=True,
        )
        for resource in raster_fallbacks:
            icon = _read_icon(apk, resource, mime_types)
            if icon:
                return icon
    return None


def inspect(path: Path, aapt2: str, apksigner: str):
    badging = subprocess.check_output([aapt2, "dump", "badging", str(path)], text=True)
    package_line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    match = PACKAGE.search(package_line)
    if not match:
        raise ValueError("unreadable APK metadata")
    package, code, name, split = match.groups()
    if split:
        raise ValueError("split APK is not a standalone artifact")
    sdk = re.search(r"sdkVersion:'([^']+)'", badging)
    target = re.search(r"targetSdkVersion:'([^']+)'", badging)
    native = next((line for line in badging.splitlines() if line.startswith("native-code:")), "")
    certs = subprocess.check_output(
        [apksigner, "verify", "--verbose", "--print-certs", str(path)],
        text=True,
        stderr=subprocess.STDOUT,
    )
    signer_digest = None
    marker = "certificate SHA-256 digest:"
    for line in certs.splitlines():
        if marker not in line:
            continue
        candidate = line.split(marker, 1)[1].strip().replace(":", "").lower()
        if re.fullmatch(r"[0-9a-f]{64}", candidate):
            signer_digest = candidate
            break
    if not signer_digest:
        raise ValueError("unreadable signing certificate")
    return {
        "package": package,
        "code": int(code),
        "name": name or code,
        "minSdk": int(sdk.group(1)) if sdk else 21,
        "targetSdk": int(target.group(1)) if target else 21,
        "abis": re.findall(r"'([^']+)'", native),
        "signer": signer_digest,
        "icon": extract_icon(path, badging),
    }


def _paged(gh: GitHub, url_for_page, authenticated: bool):
    page, values = 1, []
    while True:
        batch = gh.json(url_for_page(page), authenticated=authenticated)
        values.extend(batch)
        if len(batch) < 100:
            return values
        page += 1


def repos(gh: GitHub, owner: str):
    public_url = lambda page: (
        f"{API}/users/{urllib.parse.quote(owner)}/repos?per_page=100&page={page}&sort=updated"
    )

    try:
        public = _paged(gh, public_url, authenticated=False)
    except urllib.error.HTTPError as exc:
        if exc.code != 403 or not gh.token:
            raise
        print("! anonymous public repository enumeration rate-limited; retrying authenticated")
        try:
            public = _paged(gh, public_url, authenticated=True)
        except urllib.error.HTTPError as authenticated_exc:
            print(
                "! public repository enumeration unavailable after authenticated retry: "
                f"HTTP {authenticated_exc.code}; continuing with token-visible repositories"
            )
            public = []

    merged = {repo["full_name"]: repo for repo in public}

    if gh.token:
        try:
            private_and_visible = _paged(
                gh,
                lambda page: f"{API}/user/repos?affiliation=owner&per_page=100&page={page}&sort=updated",
                authenticated=True,
            )
            for repo in private_and_visible:
                if repo.get("owner", {}).get("login", "").lower() == owner.lower():
                    merged[repo["full_name"]] = repo
        except urllib.error.HTTPError as exc:
            print(f"! authenticated repository enumeration unavailable: HTTP {exc.code}")

    return list(merged.values())


def metadata(gh: GitHub, repo: dict):
    raw = gh.file(repo, ".library.json")
    return json.loads(raw) if raw else {}


def write(path: Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def release_notes(body: str) -> list[str]:
    notes = []
    for raw in body.splitlines():
        line = raw.strip()
        line = re.sub(r"^#{1,6}\s*", "", line)
        line = re.sub(r"^[-*+]\s+", "", line)
        line = line.strip()
        if not line:
            continue
        lowered = line.lower().strip(":")
        if lowered in {"what's changed", "whats changed", "changes", "changelog"}:
            continue
        if lowered.startswith("**full changelog**") or lowered.startswith("full changelog"):
            continue
        notes.append(line)
        if len(notes) == 8:
            break
    return notes


def inspect_release(gh: GitHub, repo: dict, release: dict, aapt2: str, apksigner: str):
    assets = [asset for asset in release.get("assets", []) if asset.get("name", "").lower().endswith(".apk")]
    if not assets:
        raise ValueError("no APK assets")

    inspected = []
    with tempfile.TemporaryDirectory(prefix="library-") as temp:
        for asset in assets:
            path = Path(temp) / asset["name"]
            gh.download(repo, asset, path)
            try:
                info = inspect(path, aapt2, apksigner)
            except ValueError as exc:
                print(f"skip asset {repo['full_name']}:{asset['name']}: {exc}")
                continue
            inspected.append((asset, info, sha256(path), path.stat().st_size))

    if not inspected:
        raise ValueError("no standalone APK assets")
    packages = {item[1]["package"] for item in inspected}
    codes = {item[1]["code"] for item in inspected}
    signers = {item[1]["signer"] for item in inspected}
    names = {item[1]["name"] for item in inspected}
    if len(packages) != 1 or len(codes) != 1 or len(signers) != 1 or len(names) != 1:
        raise ValueError("release APKs disagree on package/version/signer")

    private = bool(repo.get("private"))
    changelog = release_notes(release.get("body") or "")
    changelog = changelog or [f"Release {release.get('tag_name', '')}"]
    return {
        "tag": release.get("tag_name"),
        "versionName": next(iter(names)),
        "versionCode": next(iter(codes)),
        "minSdk": min(item[1]["minSdk"] for item in inspected),
        "targetSdk": max(item[1]["targetSdk"] for item in inspected),
        "publishedAt": release.get("published_at"),
        "releaseUrl": release.get("html_url"),
        "signingCertSha256": next(iter(signers)),
        "artifacts": [
            {
                "name": item[0]["name"],
                "downloadUrl": item[0].get("browser_download_url") if not private else None,
                "apiUrl": item[0].get("url"),
                "sha256": item[2],
                "sizeBytes": item[3],
                "abis": item[1]["abis"],
                "authRequired": private,
            }
            for item in inspected
        ],
        "changelog": changelog,
        "packageName": next(iter(packages)),
        "icon": next((item[1].get("icon") for item in inspected if item[1].get("icon")), None),
    }


def release_history_limit(meta: dict) -> int:
    raw = meta.get("releaseHistoryLimit", DEFAULT_RELEASE_HISTORY_LIMIT)
    try:
        value = int(raw)
    except (TypeError, ValueError):
        value = DEFAULT_RELEASE_HISTORY_LIMIT
    return max(1, min(value, MAX_RELEASE_HISTORY_LIMIT))


def discover_releases(gh: GitHub, repo: dict, meta: dict, aapt2: str, apksigner: str):
    limit = release_history_limit(meta)
    include_prereleases = bool(meta.get("includePrereleases", False))
    fetch_count = min(100, max(20, limit * 3))
    releases = [
        release
        for release in gh.release_json(repo, f"{API}/repos/{repo['full_name']}/releases?per_page={fetch_count}")
        if not release.get("draft") and (include_prereleases or not release.get("prerelease"))
    ]

    resolved = []
    seen_versions = set()
    expected_package = None
    for release in releases:
        if len(resolved) >= limit:
            break
        if not any(asset.get("name", "").lower().endswith(".apk") for asset in release.get("assets", [])):
            continue
        try:
            item = inspect_release(gh, repo, release, aapt2, apksigner)
        except ValueError as exc:
            print(f"skip release {repo['full_name']}:{release.get('tag_name', '')}: {exc}")
            continue
        if expected_package is None:
            expected_package = item["packageName"]
        elif item["packageName"] != expected_package:
            print(
                f"skip release {repo['full_name']}:{release.get('tag_name', '')}: "
                f"package changed from {expected_package} to {item['packageName']}"
            )
            continue
        version_key = (item["versionCode"], item.get("tag"))
        if version_key in seen_versions:
            continue
        seen_versions.add(version_key)
        resolved.append(item)
    return resolved


def build(repo: dict, release_items: list[dict], meta: dict):
    if not release_items:
        raise ValueError("no installable releases")
    latest = release_items[0]
    private = bool(repo.get("private"))
    releases = [
        {
            "tag": item["tag"],
            "versionName": item["versionName"],
            "versionCode": item["versionCode"],
            "minSdk": item["minSdk"],
            "targetSdk": item["targetSdk"],
            "publishedAt": item["publishedAt"],
            "releaseUrl": item["releaseUrl"],
            "signingCertSha256": item["signingCertSha256"],
            "artifacts": item["artifacts"],
            "changelog": item["changelog"],
        }
        for item in release_items
    ]
    return {
        "id": meta.get("id") or re.sub(r"[^a-z0-9._-]+", "-", repo["name"].lower()).strip("-"),
        "name": meta.get("name") or repo["name"].replace("_", " ").replace("-", " ").title(),
        "packageName": latest["packageName"],
        "developer": meta.get("developer") or repo["owner"]["login"],
        "tagline": meta.get("tagline") or repo.get("description") or "Latest release from GitHub.",
        "description": meta.get("description") or repo.get("description") or f"Latest Android release from {repo['full_name']}.",
        "icon": latest.get("icon"),
        "category": meta.get("category") or "Apps",
        "accent": meta.get("accent") or "#A9FF68",
        "featured": bool(meta.get("featured", False)),
        "visibility": "private" if private else "public",
        "repository": repo["full_name"],
        "sourceUrl": repo["html_url"] if meta.get("sourceVisible", not private) else None,
        "release": {
            "tag": latest["tag"],
            "versionName": latest["versionName"],
            "versionCode": latest["versionCode"],
            "minSdk": latest["minSdk"],
            "targetSdk": latest["targetSdk"],
            "publishedAt": latest["publishedAt"],
            "releaseUrl": latest["releaseUrl"],
            "artifacts": latest["artifacts"],
        },
        "provenance": {
            "kind": meta.get("provenance", "developer-signed"),
            "signingCertSha256": latest["signingCertSha256"],
        },
        "changelog": latest["changelog"],
        "releases": releases,
        "history": [
            {
                "tag": item["tag"] or "",
                "versionName": item["versionName"],
                "publishedAt": item["publishedAt"],
                "releaseUrl": item["releaseUrl"],
                "notes": "\n".join(item["changelog"])[:600] or None,
            }
            for item in release_items[1:6]
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", default=os.environ.get("LIBRARY_GITHUB_OWNER", "garfbargle"))
    parser.add_argument("--token", default=os.environ.get("LIBRARY_GITHUB_TOKEN") or os.environ.get("GITHUB_TOKEN"))
    args = parser.parse_args()

    gh = GitHub(args.token)
    aapt2, apksigner = tool("aapt2"), tool("apksigner")
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    count = 0
    api_failures = []
    for repo in repos(gh, args.owner):
        if repo.get("archived") or repo.get("fork"):
            continue
        try:
            meta = metadata(gh, repo)
            release_items = discover_releases(gh, repo, meta, aapt2, apksigner)
            if not release_items:
                continue
            manifest = build(repo, release_items, meta)
            write(LIBRARY_MANIFEST if repo["name"].lower() == "library" else OUT / f"{manifest['id']}.json", manifest)
            count += 1
            print(
                f"+ {repo['full_name']} -> {manifest['packageName']} "
                f"{manifest['release']['versionName']} ({len(release_items)} installable releases)"
            )
        except urllib.error.HTTPError as exc:
            failure = f"{repo['full_name']}: HTTP {exc.code}"
            api_failures.append(failure)
            print(f"! API failure {failure}")
        except urllib.error.URLError as exc:
            failure = f"{repo['full_name']}: {exc.reason}"
            api_failures.append(failure)
            print(f"! API failure {failure}")
        except TimeoutError as exc:
            failure = f"{repo['full_name']}: {exc}"
            api_failures.append(failure)
            print(f"! API failure {failure}")
        except Exception as exc:
            print(f"! skip {repo['full_name']}: {exc}")

    if api_failures:
        details = "; ".join(api_failures)
        raise SystemExit(f"GitHub API failures encountered; refusing to publish a partial catalog: {details}")

    print(f"discovered {count} installable Android repositories")


if __name__ == "__main__":
    main()
