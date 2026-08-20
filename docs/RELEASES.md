# Release operations

## 1. Create the Library release key once

Run on a trusted machine:

```bash
bash scripts/create_release_key.sh library-release.jks
```

Back up the keystore offline before publishing anything.

## 2. Configure Library release signing secrets

Base64-encode the keystore without line wrapping and configure:

```text
LIBRARY_KEYSTORE_BASE64
LIBRARY_SIGNING_STORE_PASSWORD
LIBRARY_SIGNING_KEY_ALIAS
LIBRARY_SIGNING_KEY_PASSWORD
```

Delete temporary base64 files after storing the secret.

## 3. Configure GitHub repository access

`LIBRARY_GITHUB_TOKEN` is optional for read-only catalog discovery, but required for Library-managed signing. For every managed app repository, grant only the access needed to read source metadata/artifacts and publish signed releases:

```text
Actions: read
Contents: write
Metadata: read
```

The Library GitHub App needs Actions read access to the source repositories so its webhook can locate a `library-unsigned-apk` candidate. It does not need source contents access for repo-side enrollment; `.library.json` is resolved inside the protected signing workflow with `LIBRARY_GITHUB_TOKEN`.

Public repositories can still be cataloged anonymously when they are not using managed signing.

## 4. Publish Library

`gradle.properties` contains the single Library release version. Every push to `main` compares that version with the highest stable `vX.Y.Z` GitHub Release and publishes only when the version increases.

The versioned Library release contains only its signed APK and `SHA256SUMS.txt`. It does **not** publish or update catalog releases.

Before building the APK, the release workflow finds the newest already-published stable `catalog-*` release and embeds its `catalog.json` as the app's offline fallback. During migration it can fall back to the legacy `catalog` release; if neither exists, it uses the checked-in fallback snapshot.

Publishing the Library APK emits the same Release webhook as any other app. The automation GitHub App then dispatches `catalog.yml`, which independently rebuilds the catalog and includes the newly published Library release.

## 5. Make an app produce an unsigned artifact

The app repository should build a release-mode APK **without a release signing configuration** and upload exactly one APK in an Actions artifact named `library-unsigned-apk`.

A minimal app-repository step is:

```yaml
- name: Build unsigned release APK
  run: ./gradlew assembleRelease

- name: Upload unsigned APK for Library
  uses: actions/upload-artifact@v4
  with:
    name: library-unsigned-apk
    path: app/build/outputs/apk/release/*.apk
    if-no-files-found: error
```

The app repository does not receive a JKS, signing password, or Library signing secret.

For the Tauri convention, this release workflow runs automatically from `main` but only uploads `library-unsigned-apk` when the committed stable version is newer than the latest stable `android-vX.Y.Z` release. Routine/manual CI must not use this artifact name.

## 6. Enroll the app from its repository

For repositories owned by the configured Library source owner, put the managed-signing declaration in `.library.json`:

```json
{
  "name": "My App",
  "provenance": "library-managed",
  "managedSigning": {
    "packageName": "com.garfbargle.myapp",
    "tagPrefix": "android-v"
  }
}
```

A successful default-branch workflow containing `library-unsigned-apk` becomes a signing candidate. The webhook passes its repository, run, artifact ID, and source commit to the protected signing workflow. That workflow reads `.library.json` from the exact source commit and requires:

- `provenance` to be `library-managed`;
- a `managedSigning` object;
- a valid `managedSigning.packageName` matching the APK;
- the source repository to belong to the configured Library owner.

Repo-side enrollment intentionally uses the repository's **default branch** and the exact artifact name `library-unsigned-apk`; `tagPrefix` defaults to `android-v`. If an app needs a different branch or artifact protocol, use a central hard pin instead so the webhook and signer share the same explicit configuration.

### Optional central hard pin

`config/managed-apps.json` remains supported. If a repository has a central entry, that entry wins over repo-side metadata. Use this when the app repository must not be able to change its own package declaration, when a custom branch/artifact is required, or for legacy/non-Tauri managed integrations.

```json
{
  "schemaVersion": 1,
  "apps": [
    {
      "repository": "garfbargle/myapp",
      "packageName": "com.garfbargle.myapp",
      "branch": "main",
      "artifact": "library-unsigned-apk",
      "tagPrefix": "android-v"
    }
  ]
}
```

## 7. Create the managed-app distribution key once

Run on a trusted machine:

```bash
bash scripts/create_distribution_key.sh library-distribution.jks
```

Back it up separately from the Library application key. Base64-encode it and configure these Library production secrets:

```text
LIBRARY_DISTRIBUTION_KEYSTORE_BASE64
LIBRARY_DISTRIBUTION_STORE_PASSWORD
LIBRARY_DISTRIBUTION_KEY_ALIAS
LIBRARY_DISTRIBUTION_KEY_PASSWORD
```

Managed signing is event-driven. A qualifying source workflow dispatches `Managed APK Signing`; the protected job resolves enrollment, validates the unsigned APK, aligns it, signs it, verifies it, and publishes back to the app repository as a stable GitHub Release containing:

```text
<repo>-<versionName>.apk
SHA256SUMS.txt
provenance.json
```

The release notes and provenance file record the source commit and source Actions artifact ID. Already-published source artifacts and non-increasing APK `versionCode` values are skipped/rejected.

## 8. Catalog refresh

The automation GitHub App owns the normal event path. Any qualifying app Release — developer-signed, Library-managed, or Library itself — causes the webhook Worker to dispatch `catalog.yml`.

`catalog.yml` is the **only** workflow that publishes the catalog product. It scans the GitHub Releases visible to the Catalog App, rebuilds and validates the catalog, then creates a new GitHub Release:

```text
c0di-org/library release tag: catalog-<workflow-run-id>-<attempt>
asset: catalog.json
```

Catalog releases are immutable-style snapshots: a workflow run never overwrites an earlier catalog release. They are created with `--latest=false`, so Library `vX.Y.Z` remains the repository's normal Latest release.

The deployed Worker entrypoint ignores Library's own `catalog` and `catalog-*` release webhooks, and `catalog.yml` independently skips self-triggered catalog release dispatches as defense in depth. This prevents catalog publication from causing a release loop.

The Android client lists stable GitHub Releases and chooses the newest `catalog-*` release by publish time. The old mutable `catalog` release remains only as a migration fallback for older clients.

The six-hour catalog schedule remains only as recovery reconciliation for a missed Release webhook; it is not the normal update path and never signs app artifacts.

For existing packages, do not switch signing identities casually: Android normally requires updates to use the existing signing identity unless a supported signing-key rotation path is configured.
