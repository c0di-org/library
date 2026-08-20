# Architecture

Library separates four security roles:

1. **Library app identity** — the stable Android signing key for the Library client itself.
2. **Managed-app distribution identity** — the separate signing key used for apps that explicitly opt into Library-managed signing. Managed apps currently share this distribution identity.
3. **Developer app identities** — developer-signed apps keep their own signing certificates; Library never re-signs those APK bytes.
4. **Automation/distribution identity** — the GitHub App, protected workflows, GitHub Releases, and catalog releases that move verified bytes and metadata between repositories and devices.

The Library application key and the managed-app distribution key are never interchangeable.

## Product ownership

The automation GitHub App owns **event routing**, not APK or catalog generation. GitHub Actions workflows remain the protected executors for signing, validation, and release publication.

The two Library products have independent release lifecycles:

- **Library Android app** — versioned `vX.Y.Z` releases containing only the signed Library APK and checksum. Its build embeds the newest already-published catalog as an offline fallback, but it does not rebuild or publish catalog data.
- **Library catalog** — immutable versioned `catalog-<run-id>-<attempt>` releases containing only `catalog.json`. `catalog.yml` is the only workflow that publishes this product.

Normal updates are event-driven through the GitHub App. The catalog's six-hour schedule exists only as recovery reconciliation if a Release webhook is missed.

## Event-driven managed signing

```text
source app workflow completes successfully
        │
        ▼
GitHub workflow_run webhook
        │
        ▼
automation GitHub App / Worker
        ├─ verify webhook signature
        ├─ require configured source owner
        ├─ reject PR/failed/wrong-branch runs
        ├─ resolve central config or repo-side candidate
        └─ require expected unexpired Actions artifact
        │
        ▼
dispatch managed-signing.yml
(repo + run ID + artifact ID + source SHA)
        │
        ▼
protected enrollment resolution
        ├─ central hard pin, or
        └─ .library.json read from exact source SHA
        │
        ▼
manage_unsigned_apks.py
        ├─ re-fetch exact workflow run
        ├─ re-fetch exact artifact
        ├─ require matching branch/SHA/name
        ├─ require unsigned standalone APK
        ├─ verify package + increasing versionCode
        ├─ align + sign with managed distribution key
        └─ verify final signature and hashes
        │
        ▼
stable GitHub Release in source repository
(targeted at the exact source SHA)
```

The signer does not enumerate recent workflow runs and does not choose a "latest" artifact. The source run/artifact/SHA tuple selected by the webhook is part of the protected signing boundary.

## Release-driven catalog reconciliation

Both signing models converge on GitHub Releases:

```text
developer-signed APK Release ────────┐
                                     │
Library-managed signed APK Release ──┤
                                     │
Library Android APK Release ─────────┤
                                     ▼
                              release webhook
                                     │
                                     ▼
                         automation GitHub App
                                     │
                                     ▼
                              catalog.yml
                                     │
                                     ▼
                 installation-aware release discovery
                         ├─ download exact APK
                         ├─ package/version/sdk/ABI
                         ├─ signing certificate
                         ├─ SHA-256 exact bytes
                         └─ optional .library.json
                                     │
                                     ▼
                         catalog/apps/generated/*.json
                                     │
                                     ▼
                         scripts/build_catalog.py
                         ├─ catalog/library.json
                         └─ app/src/main/assets/catalog.json
                                     │
                                     ▼
                 versioned catalog GitHub Release
                 tag: catalog-<run-id>-<attempt>
                         asset: catalog.json
                                     │
                                     ▼
                         Android Library client
```

Release webhooks are the normal trigger. The Worker entrypoint ignores Library's own `catalog` and `catalog-*` releases so catalog publication cannot recursively trigger another refresh. `catalog.yml` also contains a self-release guard as defense in depth.

Each catalog workflow run publishes a new release rather than mutating an existing one. Catalog releases use `--latest=false`, so Library `vX.Y.Z` releases remain the repository's normal Latest release. The Android client lists published releases, selects the newest stable `catalog-*` release by publish time, and falls back to the legacy `catalog` release only during migration.

`catalog.yml` also runs every six hours as a recovery reconciliation in case a Release webhook was missed; that schedule never signs workflow artifacts.

## Public and private apps

Public releases work anonymously. Catalog discovery uses a short-lived GitHub App installation token for repositories visible to the configured Catalog App and merges those repositories with public discovery. Library-managed signing uses the protected signing credential only inside the Library production environment to read the selected artifact and publish the resulting release.

On Android, GitHub App Device Flow gives a signed-in user access only to repositories that both the user and the App installation can reach. Library stores the resulting session using Android Keystore. Authorization is sent only to `api.github.com` and is not forwarded to release-CDN redirects.

The bundled catalog lets the client start offline. Normal live refresh reads the newest published `catalog-*` GitHub Release.

## Release selection

Discovery ignores drafts, prereleases by default, repositories with no qualifying APK release, and split APK fragments. All accepted APKs in one release must agree on package name, versionCode, signer, and version name. Version metadata comes from the APK manifest rather than tag naming.

## Update safety

Library compares catalog `versionCode` with the installed package. Before staging an update it checks the installed signer against the pinned signer, then verifies the downloaded SHA-256, package ID, versionCode, and certificate. Android performs its own signature/update verification again when the PackageInstaller session commits.
