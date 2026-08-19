#!/usr/bin/env python3
"""Run catalog discovery with GitHub App installation-aware repository enumeration.

`actions/create-github-app-token` returns an installation access token. The legacy
catalog enumerator also probes `/user/repos`, which is a user-token endpoint. Keep
its public-repository discovery, then merge in every repository exposed by the
current app installation via `/installation/repositories`.
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
    merged = {repo["full_name"]: repo for repo in ORIGINAL_REPOS(gh, owner)}
    if not gh.token:
        return list(merged.values())

    try:
        installed = installation_repositories(gh)
    except urllib.error.HTTPError as exc:
        raise SystemExit(
            f"GitHub App installation repository enumeration failed: HTTP {exc.code}"
        ) from exc

    owner_lower = owner.lower()
    for repo in installed:
        if repo.get("owner", {}).get("login", "").lower() == owner_lower:
            merged[repo["full_name"]] = repo

    print(f"GitHub App installation exposes {len(installed)} repositories")
    return list(merged.values())


ORIGINAL_REPOS = sync_github.repos
sync_github.repos = repos


if __name__ == "__main__":
    sync_github.main()
