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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "catalog" / "apps" / "generated"
LIBRARY_MANIFEST = ROOT / "catalog" / "apps" / "library.json"
API = "https://api.github.com"
PACKAGE = re.compile(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'(?:.* split='([^']+)')?")
SIGNER = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)")


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
            data = self.json(url, authenticated=bool(repo.get("private")))
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            raise
        if data.get("encoding") == "base64":
            return base64.b64decode(data["content"]).decode()
        return None

    def release_json(self, repo: dict, url: str):
        return self.json(url, authenticated=bool(repo.get("private")))

    def download(self, repo: dict, asset: dict, path: Path):
        authenticated = bool(repo.get("private"))
        request = self.request(asset["url"], "application/octet-stream", authenticated=authenticated)
        with urllib.request.urlopen(request, timeout=120) as response, path.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)


def tool(name: str) -> str:
    hit = shutil.which(name)
    if hit:
        return hit
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        hits = sorted(Path(sdk).glob(f"build-tools/*/{name}"), reverse=True)
        if hits:
            return str(hits[0])
    raise SystemExit(f"{name} is required")


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


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
        [apksigner, "verify", "--print-certs", str(path)], text=True, stderr=subprocess.STDOUT
    )
    signer = SIGNER.search(certs)
    if not signer:
        raise ValueError("unreadable signing certificate")
    return {
        "package": package,
        "code": int(code),
        "name": name or code,
        "minSdk": int(sdk.group(1)) if sdk else 21,
        "targetSdk": int(target.group(1)) if target else 21,
        "abis": re.findall(r"'([^']+)'", native),
        "signer": signer.group(1).replace(":", "").lower(),
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
    # Always enumerate public repos anonymously so a repo-scoped Actions token cannot
    # accidentally hide the rest of the owner's public Android releases.
    public = _paged(
        gh,
        lambda page: f"{API}/users/{urllib.parse.quote(owner)}/repos?per_page=100&page={page}&sort=updated",
        authenticated=False,
    )
    merged = {repo["full_name"]: repo for repo in public}

    # A fine-grained PAT can add private repos. The built-in GITHUB_TOKEN normally
    # contributes only this repository; that's still useful for Library self-updates.
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


def build(gh: GitHub, repo: dict, release: dict, history: list, meta: dict, aapt2: str, apksigner: str):
    assets = [asset for asset in release.get("assets", []) if asset.get("name", "").lower().endswith(".apk")]
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
    if len(packages) != 1 or len(codes) != 1 or len(signers) != 1:
        raise ValueError("release APKs disagree on package/version/signer")

    private = bool(repo.get("private"))
    changelog = [line.strip(" -*\t") for line in (release.get("body") or "").splitlines() if line.strip()][:8]
    changelog = changelog or [f"Release {release.get('tag_name', '')}"]
    return {
        "id": meta.get("id") or re.sub(r"[^a-z0-9._-]+", "-", repo["name"].lower()).strip("-"),
        "name": meta.get("name") or repo["name"].replace("_", " ").replace("-", " ").title(),
        "packageName": next(iter(packages)),
        "developer": meta.get("developer") or repo["owner"]["login"],
        "tagline": meta.get("tagline") or repo.get("description") or "Latest release from GitHub.",
        "description": meta.get("description") or repo.get("description") or f"Latest Android release from {repo['full_name']}.",
        "category": meta.get("category") or "Apps",
        "accent": meta.get("accent") or "#A9FF68",
        "featured": bool(meta.get("featured", False)),
        "visibility": "private" if private else "public",
        "repository": repo["full_name"],
        "sourceUrl": repo["html_url"] if meta.get("sourceVisible", not private) else None,
        "release": {
            "tag": release.get("tag_name"),
            "versionName": next(iter(names)),
            "versionCode": next(iter(codes)),
            "minSdk": min(item[1]["minSdk"] for item in inspected),
            "targetSdk": max(item[1]["targetSdk"] for item in inspected),
            "publishedAt": release.get("published_at"),
            "releaseUrl": release.get("html_url"),
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
        },
        "provenance": {"kind": meta.get("provenance", "developer-signed"), "signingCertSha256": next(iter(signers))},
        "changelog": changelog,
        "history": [
            {
                "tag": item.get("tag_name", ""),
                "versionName": None,
                "publishedAt": item.get("published_at"),
                "releaseUrl": item.get("html_url"),
                "notes": (item.get("body") or "")[:600] or None,
            }
            for item in history[:5]
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
    for repo in repos(gh, args.owner):
        if repo.get("archived") or repo.get("fork"):
            continue
        try:
            releases = [
                release
                for release in gh.release_json(repo, f"{API}/repos/{repo['full_name']}/releases?per_page=5")
                if not release.get("draft") and not release.get("prerelease")
            ]
            release = next(
                (item for item in releases if any(asset.get("name", "").lower().endswith(".apk") for asset in item.get("assets", []))),
                None,
            )
            if not release:
                continue
            manifest = build(gh, repo, release, releases, metadata(gh, repo), aapt2, apksigner)
            write(LIBRARY_MANIFEST if repo["name"].lower() == "library" else OUT / f"{manifest['id']}.json", manifest)
            count += 1
            print(f"+ {repo['full_name']} -> {manifest['packageName']} {manifest['release']['versionName']}")
        except Exception as exc:
            print(f"! skip {repo['full_name']}: {exc}")
    print(f"discovered {count} installable Android repositories")


if __name__ == "__main__":
    main()
