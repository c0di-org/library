# Architecture

Library separates four security roles:

1. **Library app identity** — the stable Android signing key for the Library client itself.
2. **Managed-app distribution identity** — the separate signing key used for apps that explicitly opt into Library-managed signing. Managed apps currently share this distribution identity.
3. **Developer app identities** — developer-signed apps keep their own signing certificates; Library never re-signs those APK bytes.
4. **Automation/distribution identity** — the GitHub App, protected workflows, GitHub Releases, and rolling catalog that move verified bytes and metadata between repositories and devices.

The Library application key and the managed-app distribution key are never interchangeable.

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
                         scripts/sync_github.py
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
                         rolling Release: catalog.json
                                     │
                                     ▼
                         Android Library client
```

Release webhooks are the normal trigger. `catalog.yml` also runs every six hours as a recovery reconciliation in case a Release webhook was missed; that schedule never signs workflow artifacts.

## Public and private apps

Public releases work anonymously. Catalog discovery can use a short-lived GitHub App installation token for repositories visible to the configured Catalog App. Library-managed signing uses the protected signing credential only inside the Library production environment to read the selected artifact and publish the resulting release.

On Android, GitHub App Device Flow gives a signed-in user access only to repositories that both the user and the App installation can reach. Library stores the resulting session using Android Keystore. Authorization is sent only to `api.github.com` and is not forwarded to release-CDN redirects.

The bundled catalog lets the client start offline. Normal live refresh reads the rolling `catalog` GitHub Release.

## Release selection

Discovery ignores drafts, prereleases by default, repositories with no qualifying APK release, and split APK fragments. All accepted APKs in one release must agree on package name, versionCode, signer, and version name. Version metadata comes from the APK manifest rather than tag naming.

## Update safety

Library compares catalog `versionCode` with the installed package. Before staging an update it checks the installed signer against the pinned signer, then verifies the downloaded SHA-256, package ID, versionCode, and certificate. Android performs its own signature/update verification again when the PackageInstaller session commits.
