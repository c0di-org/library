#!/usr/bin/env python3
"""Run catalog discovery with GitHub App installation-aware repository enumeration.

`actions/create-github-app-token` returns an installation access token. Installation
access tokens must enumerate private/selected repositories through
`/installation/repositories`, not the user-token-only `/user/repos` endpoint.
Public repositories are still discovered independently so public apps do not need
to be selected in the GitHub App installation.
"""
from __future__ import annotations

import urllib.error

import sync_github


def installation_repositories(gh: sync_github.GitHub) -> list[dict]:
    page = 1
    repositories: list[dict] = []
    while True:
        data = gh.json(
            f"{sync_github.API}/installation/repositories?per_page=100&page={page}",
            authenticated=True,
        )
        batch = data.get("repositories", [])
        repositories.extend(batch)
        if len(batch) < 100:
            return repositories
        page += 1


def repos(gh: sync_github.GitHub, owner: str) -> list[dict]:
    merged = {
        repo["full_name"]: repo
        for repo in sync_github.public_repositories(gh, owner)
    }
    if not gh.token:
        return list(merged.values())

    try:
        installed = installation_repositories(gh)
    except urllib.error.HTTPError as exc:
        raise SystemExit(
            f"GitHub App installation repository enumeration failed: HTTP {exc.code}"
        ) from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        reason = getattr(exc, "reason", exc)
        raise SystemExit(
            "GitHub App installation repository enumeration failed after retries: "
            f"{reason}"
        ) from exc

    owner_lower = owner.lower()
    for repo in installed:
        if repo.get("owner", {}).get("login", "").lower() == owner_lower:
            merged[repo["full_name"]] = repo

    print(
        f"GitHub App installation exposes {len(installed)} repositories",
        flush=True,
    )
    return list(merged.values())


def main() -> None:
    original_repos = sync_github.repos
    sync_github.repos = repos
    try:
        sync_github.main()
    finally:
        sync_github.repos = original_repos


if __name__ == "__main__":
    main()
