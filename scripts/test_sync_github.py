#!/usr/bin/env python3
from __future__ import annotations

import json
import tempfile
import urllib.error
import unittest
from pathlib import Path
from unittest import mock

import sync_github


DIGEST = "a" * 64
OTHER_DIGEST = "b" * 64


def repo() -> dict:
    return {
        "full_name": "c0di-org/example",
        "name": "example",
        "private": False,
        "owner": {"login": "c0di-org"},
        "html_url": "https://github.com/c0di-org/example",
    }


def release(digest: str | None = DIGEST, *, extra_asset: bool = False) -> dict:
    assets = [
        {
            "name": "example.apk",
            "digest": f"sha256:{digest}" if digest else None,
            "size": 123,
            "url": "https://api.github.com/repos/c0di-org/example/releases/assets/1",
            "browser_download_url": "https://github.com/c0di-org/example/releases/download/v1/example.apk",
        }
    ]
    if extra_asset:
        assets.append(
            {
                "name": "split.apk",
                "digest": f"sha256:{OTHER_DIGEST}",
                "size": 42,
                "url": "https://api.github.com/repos/c0di-org/example/releases/assets/2",
                "browser_download_url": "https://github.com/c0di-org/example/releases/download/v1/split.apk",
            }
        )
    return {
        "tag_name": "v1",
        "published_at": "2026-09-18T00:00:00Z",
        "html_url": "https://github.com/c0di-org/example/releases/tag/v1",
        "body": "Fresh release notes",
        "draft": False,
        "prerelease": False,
        "assets": assets,
    }


def cached_release() -> dict:
    return {
        "tag": "v1",
        "versionName": "1.0",
        "versionCode": 1,
        "minSdk": 26,
        "targetSdk": 36,
        "publishedAt": "2026-09-17T00:00:00Z",
        "releaseUrl": "old-url",
        "signingCertSha256": "c" * 64,
        "artifacts": [
            {
                "name": "example.apk",
                "downloadUrl": "old-download-url",
                "apiUrl": "old-api-url",
                "sha256": DIGEST,
                "sizeBytes": 123,
                "abis": ["arm64-v8a"],
                "authRequired": False,
            }
        ],
        "changelog": ["Old notes"],
        "packageName": "com.example.app",
        "_icon": {"mimeType": "image/png", "dataBase64": "eA=="},
    }


class CatalogCacheTests(unittest.TestCase):
    def test_reuses_release_when_github_digest_matches(self):
        cache = {("c0di-org/example", "v1"): cached_release()}
        item = sync_github.cached_release_item(repo(), release(), cache)

        self.assertIsNotNone(item)
        self.assertEqual(item["packageName"], "com.example.app")
        self.assertEqual(item["versionCode"], 1)
        self.assertEqual(item["changelog"], ["Fresh release notes"])
        self.assertEqual(item["artifacts"][0]["sha256"], DIGEST)
        self.assertEqual(
            item["artifacts"][0]["apiUrl"],
            "https://api.github.com/repos/c0di-org/example/releases/assets/1",
        )

    def test_digest_change_forces_reinspection(self):
        cache = {("c0di-org/example", "v1"): cached_release()}
        self.assertIsNone(
            sync_github.cached_release_item(repo(), release(OTHER_DIGEST), cache)
        )

    def test_missing_github_digest_forces_reinspection(self):
        cache = {("c0di-org/example", "v1"): cached_release()}
        self.assertIsNone(sync_github.cached_release_item(repo(), release(None), cache))

    def test_asset_set_change_forces_reinspection(self):
        cache = {("c0di-org/example", "v1"): cached_release()}
        self.assertIsNone(
            sync_github.cached_release_item(
                repo(),
                release(extra_asset=True),
                cache,
            )
        )

    def test_load_previous_catalog_indexes_release_metadata(self):
        catalog = {
            "apps": [
                {
                    "repository": "c0di-org/example",
                    "packageName": "com.example.app",
                    "icon": {"mimeType": "image/png", "dataBase64": "eA=="},
                    "release": {"tag": "v1"},
                    "releases": [
                        {
                            key: value
                            for key, value in cached_release().items()
                            if key not in {"packageName", "_icon"}
                        }
                    ],
                }
            ]
        }
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "catalog.json"
            path.write_text(json.dumps(catalog))
            cache = sync_github.load_previous_catalog(path)

        item = cache[("c0di-org/example", "v1")]
        self.assertEqual(item["packageName"], "com.example.app")
        self.assertEqual(item["_icon"]["mimeType"], "image/png")

    def test_release_scan_pages_past_non_apk_catalog_releases(self):
        catalog_only = {
            "tag_name": "catalog-test",
            "draft": False,
            "prerelease": False,
            "assets": [{"name": "catalog.json"}],
        }
        apk_release = release()

        class FakeGitHub:
            def __init__(self):
                self.pages = []

            def release_json(self, _repo, url):
                page = int(url.rsplit("page=", 1)[1])
                self.pages.append(page)
                if page == 1:
                    return [catalog_only] * 100
                if page == 2:
                    return [apk_release]
                return []

        fake = FakeGitHub()
        inspected = cached_release()
        inspected["icon"] = None
        with mock.patch.object(sync_github, "inspect_release", return_value=inspected):
            items = sync_github.discover_releases(
                fake,
                repo(),
                {"releaseHistoryLimit": 1},
                "aapt2",
                "apksigner",
                cache={},
            )

        self.assertEqual(fake.pages, [1, 2])
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["tag"], "v1")


    def test_json_retries_transient_network_failure(self):
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return b'{"ok": true}'

        gh = sync_github.GitHub(None)
        with (
            mock.patch.object(
                sync_github.urllib.request,
                "urlopen",
                side_effect=[urllib.error.URLError("timed out"), Response()],
            ) as urlopen,
            mock.patch.object(sync_github.time, "sleep") as sleep,
        ):
            result = gh.json("https://api.github.com/example", authenticated=False)

        self.assertEqual(result, {"ok": True})
        self.assertEqual(urlopen.call_count, 2)
        sleep.assert_called_once_with(1)

    def test_json_does_not_retry_non_transient_http_error(self):
        gh = sync_github.GitHub(None)
        error = urllib.error.HTTPError(
            "https://api.github.com/example",
            404,
            "Not Found",
            {},
            None,
        )
        with (
            mock.patch.object(
                sync_github.urllib.request,
                "urlopen",
                side_effect=error,
            ) as urlopen,
            mock.patch.object(sync_github.time, "sleep") as sleep,
        ):
            with self.assertRaises(urllib.error.HTTPError):
                gh.json("https://api.github.com/example", authenticated=False)

        self.assertEqual(urlopen.call_count, 1)
        sleep.assert_not_called()



if __name__ == "__main__":
    unittest.main()
