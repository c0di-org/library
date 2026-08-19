#!/usr/bin/env python3
"""Resolve one event-driven managed-signing request from central config or source .library.json."""
from __future__ import annotations

import base64
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config" / "managed-apps.json"
API = "https://api.github.com"
DEFAULT_ARTIFACT = "library-unsigned-apk"
DEFAULT_TAG_PREFIX = "android-v"
PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")


def split_repo(full_name: str) -> tuple[str, str]:
    parts = full_name.split("/")
    if len(parts) != 2 or not all(parts):
        raise ValueError(f"repository must be owner/name: {full_name!r}")
    return parts[0], parts[1]


def request_json(token: str, url: str) -> dict:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "c0di-org/library-managed-enrollment",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.loads(response.read())


def source_metadata(token: str, repository: str, ref: str) -> dict:
    owner, name = split_repo(repository)
    url = (
        f"{API}/repos/{urllib.parse.quote(owner)}/{urllib.parse.quote(name)}"
        f"/contents/.library.json?ref={urllib.parse.quote(ref)}"
    )
    try:
        data = request_json(token, url)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            raise ValueError(f"{repository}: .library.json is required for repo-side managed signing") from exc
        raise
    if data.get("encoding") != "base64":
        raise ValueError(f"{repository}: .library.json could not be decoded")
    try:
        return json.loads(base64.b64decode(data["content"]).decode())
    except Exception as exc:
        raise ValueError(f"{repository}: .library.json is not valid JSON") from exc


def repo_side_app(token: str, repository: str, ref: str) -> dict:
    owner, name = split_repo(repository)
    expected_owner = os.environ.get("LIBRARY_GITHUB_OWNER", "c0di-org")
    if owner.lower() != expected_owner.lower():
        raise ValueError(f"{repository}: repo-side managed signing is limited to owner {expected_owner}")

    repo = request_json(token, f"{API}/repos/{urllib.parse.quote(owner)}/{urllib.parse.quote(name)}")
    if repo.get("archived") or repo.get("fork"):
        raise ValueError(f"{repository}: archived/fork repositories cannot self-enroll")

    metadata = source_metadata(token, repository, ref)
    if metadata.get("provenance") != "library-managed":
        raise ValueError(f"{repository}: provenance must be library-managed for managed signing")
    signing = metadata.get("managedSigning")
    if not isinstance(signing, dict) or signing.get("enabled") is False:
        raise ValueError(f"{repository}: managedSigning object is required for repo-side enrollment")

    package = str(signing.get("packageName") or "").strip()
    if not PACKAGE.fullmatch(package):
        raise ValueError(f"{repository}: managedSigning.packageName is invalid")

    branch = str(repo.get("default_branch") or "").strip()
    if not branch:
        raise ValueError(f"{repository}: repository has no default branch")
    requested_branch = str(signing.get("branch") or branch).strip()
    if requested_branch != branch:
        raise ValueError(f"{repository}: repo-side managed signing must use default branch {branch}; use config/managed-apps.json for a custom branch")

    requested_artifact = str(signing.get("artifact") or DEFAULT_ARTIFACT).strip()
    if requested_artifact != DEFAULT_ARTIFACT:
        raise ValueError(f"{repository}: repo-side managed signing must use artifact {DEFAULT_ARTIFACT}; use config/managed-apps.json for a custom artifact")

    tag_prefix = str(signing.get("tagPrefix") or DEFAULT_TAG_PREFIX).strip()
    if not tag_prefix:
        raise ValueError(f"{repository}: managedSigning.tagPrefix cannot be empty")

    return {
        "repository": repository,
        "packageName": package,
        "branch": branch,
        "artifact": DEFAULT_ARTIFACT,
        "tagPrefix": tag_prefix,
    }


def required_source_context() -> tuple[str, str, str, str]:
    values = {
        "SOURCE_REPOSITORY": os.environ.get("SOURCE_REPOSITORY", "").strip(),
        "SOURCE_RUN_ID": os.environ.get("SOURCE_RUN_ID", "").strip(),
        "SOURCE_ARTIFACT_ID": os.environ.get("SOURCE_ARTIFACT_ID", "").strip(),
        "SOURCE_HEAD_SHA": os.environ.get("SOURCE_HEAD_SHA", "").strip(),
    }
    missing = [name for name, value in values.items() if not value]
    if missing:
        raise SystemExit("managed signing is event-driven; missing required webhook context: " + ", ".join(missing))
    if not values["SOURCE_RUN_ID"].isdigit() or int(values["SOURCE_RUN_ID"]) <= 0:
        raise SystemExit("SOURCE_RUN_ID must be a positive integer")
    if not values["SOURCE_ARTIFACT_ID"].isdigit() or int(values["SOURCE_ARTIFACT_ID"]) <= 0:
        raise SystemExit("SOURCE_ARTIFACT_ID must be a positive integer")
    if not re.fullmatch(r"[0-9A-Fa-f]{40}", values["SOURCE_HEAD_SHA"]):
        raise SystemExit("SOURCE_HEAD_SHA must be a 40-character commit SHA")
    return (
        values["SOURCE_REPOSITORY"],
        values["SOURCE_RUN_ID"],
        values["SOURCE_ARTIFACT_ID"],
        values["SOURCE_HEAD_SHA"],
    )


def main() -> None:
    target, run_id, artifact_id, head_sha = required_source_context()
    root = json.loads(CONFIG.read_text())
    apps = root.get("apps", [])

    selected = [app for app in apps if str(app.get("repository", "")).lower() == target.lower()]
    enrollment = "central"
    if not selected:
        token = os.environ.get("LIBRARY_GITHUB_TOKEN", "").strip()
        if not token:
            raise SystemExit("LIBRARY_GITHUB_TOKEN is required for repo-side managed signing")
        selected = [repo_side_app(token, target, head_sha)]
        enrollment = "repository"

    if len(selected) != 1:
        raise SystemExit(f"{target}: managed-signing enrollment must resolve to exactly one app")

    root["apps"] = selected
    CONFIG.write_text(json.dumps(root, indent=2) + "\n")
    print(f"managed signing requested by {target} via {enrollment} enrollment (run {run_id}, artifact {artifact_id}, commit {head_sha})")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"enrollment={enrollment}\n")


if __name__ == "__main__":
    main()
