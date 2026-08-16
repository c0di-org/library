#!/usr/bin/env python3
"""Sign opted-in unsigned Android CI artifacts and publish stable GitHub Releases."""
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

API = "https://api.github.com"
PACKAGE = re.compile(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'(?:.* split='([^']+)')?")
SIGNER = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)")
DEFAULT_ARTIFACT = "library-unsigned-apk"


class GitHub:
    def __init__(self, token: str):
        self.token = token.strip()
        if not self.token:
            raise SystemExit("LIBRARY_GITHUB_TOKEN is required for managed signing")

    def request(self, url: str, accept: str = "application/vnd.github+json", method: str = "GET", data: bytes | None = None):
        return urllib.request.Request(
            url,
            data=data,
            method=method,
            headers={
                "Accept": accept,
                "Authorization": f"Bearer {self.token}",
                "User-Agent": "garfbargle/library-managed-signing",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )

    def json(self, url: str):
        with urllib.request.urlopen(self.request(url), timeout=60) as response:
            return json.loads(response.read())

    def file(self, repo: dict, path: str, ref: str | None = None):
        ref = ref or repo["default_branch"]
        url = f"{API}/repos/{repo['full_name']}/contents/{urllib.parse.quote(path, safe='/')}?ref={urllib.parse.quote(ref)}"
        try:
            data = self.json(url)
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            raise
        if data.get("encoding") == "base64":
            return base64.b64decode(data["content"]).decode()
        return None

    def download(self, url: str, path: Path):
        with urllib.request.urlopen(self.request(url), timeout=180) as response, path.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)

    def upload_release_asset(self, upload_url: str, name: str, path: Path, content_type: str):
        url = upload_url.replace("{?name,label}", "") + "?name=" + urllib.parse.quote(name)
        body = path.read_bytes()
        request = self.request(url, method="POST", data=body)
        request.add_header("Content-Type", content_type)
        request.add_header("Content-Length", str(len(body)))
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.loads(response.read())

    def create_release(self, repo: dict, tag: str, name: str, body: str, target: str):
        payload = json.dumps(
            {
                "tag_name": tag,
                "target_commitish": target,
                "name": name,
                "body": body,
                "draft": False,
                "prerelease": False,
            }
        ).encode()
        request = self.request(f"{API}/repos/{repo['full_name']}/releases", method="POST", data=payload)
        request.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read())


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


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


def repo_pages(gh: GitHub, owner: str):
    page = 1
    while True:
        batch = gh.json(f"{API}/user/repos?affiliation=owner&per_page=100&page={page}&sort=updated")
        yield from [repo for repo in batch if repo.get("owner", {}).get("login", "").lower() == owner.lower()]
        if len(batch) < 100:
            break
        page += 1


def inspect_unsigned_apk(path: Path, aapt2: str, apksigner: str):
    badging = subprocess.check_output([aapt2, "dump", "badging", str(path)], text=True)
    package_line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    match = PACKAGE.search(package_line)
    if not match:
        raise ValueError("unreadable APK metadata")
    package, code, name, split = match.groups()
    if split:
        raise ValueError("split APKs are not supported")
    verified = subprocess.run([apksigner, "verify", "--print-certs", str(path)], text=True, capture_output=True)
    if verified.returncode == 0:
        raise ValueError("artifact is already signed; managed signing accepts unsigned APKs only")
    return package, int(code), name or code


def inspect_release_apk(path: Path, aapt2: str):
    badging = subprocess.check_output([aapt2, "dump", "badging", str(path)], text=True)
    line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    match = PACKAGE.search(line)
    if not match:
        raise ValueError("published release APK metadata is unreadable")
    return match.group(1), int(match.group(2)), match.group(3) or match.group(2)


def latest_artifact(gh: GitHub, repo: dict, branch: str, artifact_name: str):
    runs = gh.json(
        f"{API}/repos/{repo['full_name']}/actions/runs?branch={urllib.parse.quote(branch)}&status=success&per_page=20"
    ).get("workflow_runs", [])
    for run in runs:
        if run.get("head_branch") != branch or run.get("event") in {"pull_request", "pull_request_target"}:
            continue
        artifacts = gh.json(f"{API}/repos/{repo['full_name']}/actions/runs/{run['id']}/artifacts?per_page=100").get("artifacts", [])
        for artifact in artifacts:
            if artifact.get("name") == artifact_name and not artifact.get("expired"):
                return run, artifact
    return None, None


def stable_releases(gh: GitHub, repo: dict):
    return [
        release
        for release in gh.json(f"{API}/repos/{repo['full_name']}/releases?per_page=100")
        if not release.get("draft") and not release.get("prerelease")
    ]


def extract_single_apk(zip_path: Path, out_dir: Path):
    with zipfile.ZipFile(zip_path) as archive:
        names = [name for name in archive.namelist() if name.lower().endswith(".apk") and not name.endswith("/")]
        if len(names) != 1:
            raise ValueError(f"artifact must contain exactly one APK, found {len(names)}")
        archive.extract(names[0], out_dir)
        return out_dir / names[0]


def source_artifact_already_published(releases: list[dict], artifact_id: int):
    marker = f"Library source artifact: {artifact_id}"
    return any(marker in (release.get("body") or "") for release in releases)


def sign_one(gh: GitHub, repo: dict, meta: dict, tools: dict, keystore: Path, args):
    distribution = meta.get("distribution") or {}
    if distribution.get("mode") != "library-managed":
        return False

    expected_package = distribution.get("packageName") or meta.get("packageName")
    if not expected_package:
        raise ValueError("library-managed repositories must declare distribution.packageName or packageName")
    branch = distribution.get("branch") or repo.get("default_branch")
    artifact_name = distribution.get("artifact") or DEFAULT_ARTIFACT
    run, artifact = latest_artifact(gh, repo, branch, artifact_name)
    if not artifact:
        print(f"- {repo['full_name']}: no successful {artifact_name} artifact on {branch}")
        return False

    releases = stable_releases(gh, repo)
    if source_artifact_already_published(releases, artifact["id"]):
        print(f"- {repo['full_name']}: artifact {artifact['id']} already published")
        return False

    with tempfile.TemporaryDirectory(prefix="library-sign-") as temp_name:
        temp = Path(temp_name)
        artifact_zip = temp / "artifact.zip"
        gh.download(artifact["archive_download_url"], artifact_zip)
        unsigned = extract_single_apk(artifact_zip, temp / "src")
        package, version_code, version_name = inspect_unsigned_apk(unsigned, tools["aapt2"], tools["apksigner"])
        if package != expected_package:
            raise ValueError(f"package mismatch: expected {expected_package}, found {package}")

        highest_version_code = -1
        for release in releases:
            for asset in release.get("assets", []):
                if not asset.get("name", "").lower().endswith(".apk"):
                    continue
                try:
                    current_apk = temp / f"release-{asset['id']}.apk"
                    gh.download(asset["url"], current_apk)
                    current_package, current_code, _ = inspect_release_apk(current_apk, tools["aapt2"])
                    if current_package == expected_package:
                        highest_version_code = max(highest_version_code, current_code)
                except Exception as exc:
                    print(f"! {repo['full_name']}: could not inspect existing release asset {asset.get('name')}: {exc}")
        if version_code <= highest_version_code:
            raise ValueError(f"versionCode {version_code} must be greater than published {highest_version_code}")

        tag = distribution.get("tagPrefix", "v") + version_name
        if any(release.get("tag_name") == tag for release in releases):
            raise ValueError(f"release tag {tag} already exists but was not created from artifact {artifact['id']}")

        unsigned_sha = sha256(unsigned)
        aligned = temp / "aligned.apk"
        signed = temp / f"{repo['name']}-{version_name}.apk"
        subprocess.check_call([tools["zipalign"], "-f", "-p", "4", str(unsigned), str(aligned)])
        subprocess.check_call(
            [
                tools["apksigner"],
                "sign",
                "--ks",
                str(keystore),
                "--ks-key-alias",
                args.key_alias,
                "--ks-pass",
                f"pass:{args.store_password}",
                "--key-pass",
                f"pass:{args.key_password}",
                "--out",
                str(signed),
                str(aligned),
            ]
        )
        subprocess.check_call([tools["apksigner"], "verify", "--verbose", "--print-certs", str(signed)])
        subprocess.check_call([tools["zipalign"], "-c", "-p", "4", str(signed)])

        certs = subprocess.check_output([tools["apksigner"], "verify", "--print-certs", str(signed)], text=True)
        signer = SIGNER.search(certs)
        if not signer:
            raise ValueError("signed APK certificate could not be read")
        signer_sha = signer.group(1).replace(":", "").lower()
        signed_sha = sha256(signed)
        marker = f"Library source artifact: {artifact['id']}"
        body = "\n".join(
            [
                "Signed and published by Library managed distribution.",
                "",
                f"Library source repository: {repo['full_name']}",
                f"Library source commit: {run['head_sha']}",
                marker,
                f"Unsigned SHA-256: {unsigned_sha}",
                f"Signed SHA-256: {signed_sha}",
                f"Signing certificate SHA-256: {signer_sha}",
            ]
        )

        if args.dry_run:
            print(f"+ dry-run {repo['full_name']}: {package} {version_name} ({version_code}) <- artifact {artifact['id']}")
            return True

        release = gh.create_release(repo, tag, f"{repo['name']} {version_name}", body, run["head_sha"])
        gh.upload_release_asset(release["upload_url"], signed.name, signed, "application/vnd.android.package-archive")
        sums = temp / "SHA256SUMS.txt"
        sums.write_text(f"{signed_sha}  {signed.name}\n")
        gh.upload_release_asset(release["upload_url"], sums.name, sums, "text/plain")
        provenance = temp / "provenance.json"
        provenance.write_text(
            json.dumps(
                {
                    "kind": "library-managed",
                    "sourceRepository": repo["full_name"],
                    "sourceCommit": run["head_sha"],
                    "sourceArtifactId": artifact["id"],
                    "unsignedSha256": unsigned_sha,
                    "signedSha256": signed_sha,
                    "signingCertSha256": signer_sha,
                    "packageName": package,
                    "versionName": version_name,
                    "versionCode": version_code,
                },
                indent=2,
            )
            + "\n"
        )
        gh.upload_release_asset(release["upload_url"], provenance.name, provenance, "application/json")
        print(f"+ {repo['full_name']}: published {tag} from artifact {artifact['id']}")
        return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", default=os.environ.get("LIBRARY_GITHUB_OWNER", "garfbargle"))
    parser.add_argument("--token", default=os.environ.get("LIBRARY_GITHUB_TOKEN"))
    parser.add_argument("--keystore", default=os.environ.get("LIBRARY_DISTRIBUTION_KEYSTORE_FILE"))
    parser.add_argument("--store-password", default=os.environ.get("LIBRARY_DISTRIBUTION_STORE_PASSWORD"))
    parser.add_argument("--key-alias", default=os.environ.get("LIBRARY_DISTRIBUTION_KEY_ALIAS"))
    parser.add_argument("--key-password", default=os.environ.get("LIBRARY_DISTRIBUTION_KEY_PASSWORD"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    for name in ("token", "keystore", "store_password", "key_alias", "key_password"):
        if not getattr(args, name):
            raise SystemExit(f"missing required managed-signing setting: {name}")

    keystore = Path(args.keystore)
    if not keystore.is_file():
        raise SystemExit(f"keystore does not exist: {keystore}")

    tools = {name: tool(name) for name in ("aapt2", "apksigner", "zipalign")}
    gh = GitHub(args.token)
    published = 0
    failures = 0
    for repo in repo_pages(gh, args.owner):
        if repo.get("archived") or repo.get("fork") or repo.get("name", "").lower() == "library":
            continue
        try:
            raw = gh.file(repo, ".library.json")
            if not raw:
                continue
            meta = json.loads(raw)
            if sign_one(gh, repo, meta, tools, keystore, args):
                published += 1
        except Exception as exc:
            failures += 1
            print(f"! {repo['full_name']}: {exc}")
    print(f"managed signing complete: {published} published, {failures} failed")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
