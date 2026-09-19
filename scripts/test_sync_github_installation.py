#!/usr/bin/env python3
from __future__ import annotations

import unittest

import sync_github_installation


def repo(name: str, *, private: bool = False) -> dict:
    return {
        "full_name": f"c0di-org/{name}",
        "name": name,
        "private": private,
        "owner": {"login": "c0di-org"},
    }


class FakeGitHub:
    def __init__(self, token: str | None = "installation-token"):
        self.token = token
        self.urls: list[tuple[str, bool]] = []

    def json(self, url: str, authenticated: bool = True):
        self.urls.append((url, authenticated))
        if "/users/c0di-org/repos?" in url:
            return [repo("public-app")]
        if "/installation/repositories?" in url:
            return {"repositories": [repo("private-app", private=True)]}
        if "/user/repos?" in url:
            raise AssertionError("installation token must never call /user/repos")
        raise AssertionError(f"unexpected URL: {url}")


class InstallationEnumerationTests(unittest.TestCase):
    def test_merges_public_and_installation_repositories_without_user_endpoint(self):
        gh = FakeGitHub()

        repositories = sync_github_installation.repos(gh, "c0di-org")

        self.assertEqual(
            {item["full_name"] for item in repositories},
            {"c0di-org/public-app", "c0di-org/private-app"},
        )
        self.assertFalse(any("/user/repos?" in url for url, _ in gh.urls))

    def test_without_token_only_public_repositories_are_used(self):
        gh = FakeGitHub(token=None)

        repositories = sync_github_installation.repos(gh, "c0di-org")

        self.assertEqual(
            {item["full_name"] for item in repositories},
            {"c0di-org/public-app"},
        )
        self.assertFalse(any("/installation/repositories?" in url for url, _ in gh.urls))


if __name__ == "__main__":
    unittest.main()
