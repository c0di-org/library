#!/usr/bin/env python3
"""Sign the exact unsigned APK artifact selected by Library's automation GitHub App."""
from __future__ import annotations

import argparse
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
DEFAULT_CONFIG = ROOT / "config" / "managed-apps.json"
API = "https://api.github.com"
PACKAGE = re.compile(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'(?:.* split='([^']+)')?")
SIGNER = re.compile(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", re.IGNORECASE)
DEFAULT_ARTIFACT = "library-unsigned-apk"


class NoAuthCrossHostRedirect(urllib.request.HTTPRedirectHandler):
    """Do not forward GitHub credentials to temporary download hosts."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is None:
            return None
        source = urllib.parse.urlsplit(req.full_url)
        target = urllib.parse.urlsplit(newurl)
        if (source.scheme, source.netloc) != (target.scheme, target.netloc):
            redirected.remove_header("Authorization")
            redirected.remove_header("X-GitHub-Api-Version")
        return redirected


class GitHub:
    def __init__(self, token: str):
        self.token = token.strip()
        if not self.token:
            raise SystemExit("LIBRARY_GITHUB_TOKEN is required for managed signing")
        self.download_opener = urllib.request.build_opener(NoAuthCrossHostRedirect())

    def request(
        self,
        url: str,
        accept: str = "application/vnd.github+json",
        method: str = "GET",
        data: bytes | None = None,
    ):
        return urllib.request.Request(
            url,
            data=data,
            method=method,
            headers={
                "Accept": accept,
                "Authorization": f"Bearer {self.token}",
                "User-Agent": "c0di-org/library-managed-signing",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )

    def json(self, url: str):
        try:
            with urllib.request.urlopen(self.request(url), timeout=60) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:300]
            raise RuntimeError(f"GitHub API {exc.code} for {url}: {detail}") from exc

    def repo(self, full_name: str):
        owner, name = split_repo(full_name)
        return self.json(f"{API}/repos/{urllib.parse.quote(owner)}/{urllib.parse.quote(name)}")

    def download(self, url: str, path: Path):
        api_path = urllib.parse.urlsplit(url).path
        accept = "application/vnd.github+json" if "/actions/artifacts/" in api_path else "application/octet-stream"
        request = self.request(url, accept=accept)
        with self.download_opener.open(request, timeout=180) as response, path.open("wb") as output:
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
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:300]
            raise RuntimeError(
                f"could not publish {repo['full_name']} release {tag} ({exc.code}): {detail}"
            ) from exc


def split_repo(full_name: str):
    parts = full_name.split("/")
    if len(parts) != 2 or not all(parts):
        raise ValueError(f"repository must be owner/name: {full_name!r}")
    return parts[0], parts[1]


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


def load_config(path: Path):
    root = json.loads(path.read_text())
    if root.get("schemaVersion") != 1:
        raise ValueError("managed-apps schemaVersion must be 1")
    apps = root.get("apps")
    if not isinstance(apps, list):
        raise ValueError("managed-apps apps must be an array")

    repositories: set[str] = set()
    packages: set[str] = set()
    for app in apps:
        if not isinstance(app, dict):
            raise ValueError("each managed app must be an object")
        repository = app.get("repository", "")
        package = app.get("packageName", "")
        split_repo(repository)
        if not package:
            raise ValueError(f"{repository}: packageName is required")
        if repository in repositories:
            raise ValueError(f"duplicate managed repository: {repository}")
        if package in packages:
            raise ValueError(f"duplicate managed package: {package}")
        repositories.add(repository)
        packages.add(package)
    return apps


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


def required_source_context() -> tuple[str, int, int, str]:
    repository = os.environ.get("SOURCE_REPOSITORY", "").strip()
    run_raw = os.environ.get("SOURCE_RUN_ID", "").strip()
    artifact_raw = os.environ.get("SOURCE_ARTIFACT_ID", "").strip()
    head_sha = os.environ.get("SOURCE_HEAD_SHA", "").strip()

    missing = [
        name
        for name, value in (
            ("SOURCE_REPOSITORY", repository),
            ("SOURCE_RUN_ID", run_raw),
            ("SOURCE_ARTIFACT_ID", artifact_raw),
            ("SOURCE_HEAD_SHA", head_sha),
        )
        if not value
    ]
    if missing:
        raise SystemExit(
            "managed signing requires exact webhook context; missing: " + ", ".join(missing)
        )
    split_repo(repository)
    if not run_raw.isdigit() or int(run_raw) <= 0:
        raise SystemExit("SOURCE_RUN_ID must be a positive integer")
    if not artifact_raw.isdigit() or int(artifact_raw) <= 0:
        raise SystemExit("SOURCE_ARTIFACT_ID must be a positive integer")
    if not re.fullmatch(r"[0-9A-Fa-f]{40}", head_sha):
        raise SystemExit("SOURCE_HEAD_SHA must be a 40-character commit SHA")
    return repository, int(run_raw), int(artifact_raw), head_sha.lower()


def requested_run_and_artifact(
    gh: GitHub,
    repo: dict,
    app: dict,
    run_id: int,
    artifact_id: int,
    head_sha: str,
):
    expected_branch = app.get("branch") or repo.get("default_branch")
    expected_artifact = app.get("artifact") or DEFAULT_ARTIFACT

    run = gh.json(f"{API}/repos/{repo['full_name']}/actions/runs/{run_id}")
    if int(run.get("id", 0)) != run_id:
        raise ValueError(f"workflow run mismatch: expected {run_id}")
    if run.get("conclusion") != "success":
        raise ValueError(f"workflow run {run_id} did not complete successfully")
    if run.get("event") in {"pull_request", "pull_request_target"}:
        raise ValueError(f"workflow run {run_id} came from a pull-request event")
    if run.get("head_branch") != expected_branch:
        raise ValueError(e
            f"workflow run {run_id} is on {run.get('head_branch')}r; expected branch {expected_branch!r}"
         )
    actual_head = str(run.get("head_sha") or "").lower()
    if actual_head != head_sha:
        raise ValueError(f"workflow run {run_id} head SHA does not match webhook source commit")

    artifact_page = gh.json(f"{API}/repos/{repo['full_name']}/actions/runs/{run_id}/artifacts?per_page=100")
    artifact = next(
        (item for item in artifact_page.get("artifacts", []) if int(item.get("id", 0)) == artifact_id),
        None,
    )
    if artifact is None:
        raise ValueError(f"artifact {artifact_id} does not belong to workflow run {run_id}")
    if artifact.get("expired"):
        raise ValueError(f"artifact {artifact_id} is expired")
    if artifact.get("name") != expected_artifact:
        raise ValueError(
            f"artifact {artifact_id} is named {artifact.get('name')!r}; expected {expected_artifact!r}"
        )
    if not artifact.get("archive_download_url"):
        raise ValueError(f"artifact {artifact_id} has no archive download URL")

    return run, artifact


def sign_one(gh: GitHub, repo: dict, app: dict, run: dict, artifact: dict, tools: dict, keystore: Path, args):
    expected_package = app["packageName"]
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
            print(
                f"- {repo['full_name']}: versionCode {version_code} is not newer than "
                f"published {highest_version_code}; skipping"
            )
            return False

        tag = app.get("tagPrefix", "v") + version_name
        if any(release.get("tag_name") == tag for release in releases):
            raise ValueError(f"release tag {tag} already exists but was not created from artifact {artifact['id']}")

        unsigned_sha = sha256(unsigned)
        aligned = temp / "aligned.apk"
        safe_version = re.sub(r"[^A-Za-z0-9._-]+", "-", version_name).strip("-") or str(version_code)
        signed = temp / f"{repo['name']}-{safe_version}.apk"
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
                f"Library source workflow run: {run['id']}",
                marker,
                f"Unsigned SHA-256: {unsigned_sha}",
                f"Signed SHA-256: {signed_sha}",
                f"Signing certificate SHA-256: {signer_sha}",
            ]
        )

        if args.dry_run:
            print(
                f"+ dry-run {repo['full_name']}: {package} {version_name} ({version_code}) "
                f"<- run {run['id']} artifact {artifact['id']}"
            )
            return True

        # Pin the release tag to the exact commit that produced the validated artifact.
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
                    "sourceWorkflowRunId": run["id"],
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
        print(
            f"+ {repo['full_name']}: published {tag} from run {run['id']} artifact {artifact['id']}"
        )
        return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--token", default=os.environ.get("LIBRARY_GITHUB_TOKEN"))
    parser.add_argument("--keystore", default=os.environ.get("LIBRARY_DISTRIBUTION_KEYSTORE_FILE"))
    parser.add_argument("--store-password", default=os.environ.get("LIBRARY_DISTRIBUTION_STORE_PASSWORD"))
    parser.add_argument("--key-alias", default=os.environ.get("LIBRARY_DISTRIBUTION_KEY_ALIAS"))
    parser.add_argument("--key-password", default=os.environ.get("LIBRARY_DISTRIBUTION_KEY_PASSWORD"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    source_repository, run_id, artifact_id, head_sha = required_source_context()

    for name in ("token", "keystore", "store_password", "key_alias", "key_password"):
        if not getattr(args, name):
            raise SystemExit(f"missing required managed-signing setting: {name}")

    keystore = Path(args.keystore)
    if not keystore.is_file():
        raise SystemExit(f"keystore does not exist: {keystore}")

    apps = load_config(args.config)
    if len(apps) != 1:
        raise SystemExit(
            "managed signing requires exactly one resolved app; "
            "the polling/batch signing path is no longer supported"
        )
    app = apps[0]
    if str(app.get("repository") or "").lower() != source_repository.lower():
        raise SystemExit(
            f"resolved app {app.get('repository')!r} does not match SOURCE_REPOSITORY {source_repository!r}"
        )

    tools = {name: tool(name) for name in ("aapt2", "apksigner", "zipalign")}
    gh = GitHub(args.token)
    repo = gh.repo(source_repository)
    if repo.get("archived") or repo.get("fork"):
        raise SystemExit(f"{source_repository}: managed repository is archived or a fork")

    run, artifact = requested_run_and_artifact(gh, repo, app, run_id, artifact_id, head_sha)
    published = sign_one(gh, repo, app, run, artifact, tools, keystore, args)
    print(f"managed signing complete: {'published' if published else 'no new release'}")


if __name__ == "__main__":
    main()
