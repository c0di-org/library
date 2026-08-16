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

`LIBRARY_GITHUB_TOKEN` is optional for read-only catalog discovery, but required for Library-managed signing. For managed apps, grant it only the enrolled repositories with:

```text
Actions: read
Contents: write
Metadata: read
```

Public repositories can still be cataloged anonymously when they are not using managed signing.

## 4. Publish Library

`gradle.properties` contains the single Library release version. Every push to `main` compares that version with the highest stable `vX.Y.Z` GitHub Release and publishes only when the version increases.

The Library release contains its signed APK, `SHA256SUMS.txt`, and `catalog.json`.

## 5. Enroll an app in Library-managed signing

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

Add `.library.json` to that app repository:

```json
{
  "name": "My App",
  "packageName": "com.garfbargle.myapp",
  "provenance": "library-managed",
  "distribution": {
    "mode": "library-managed",
    "packageName": "com.garfbargle.myapp",
    "branch": "main",
    "artifact": "library-unsigned-apk"
  }
}
```

The explicit package name is the signing allowlist. Library will not sign an artifact whose manifest package differs.

## 6. Create the managed-app distribution key once

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

The hourly `Managed APK Signing` workflow looks for the latest successful artifact from each enrolled repository's configured branch. New artifacts are validated, aligned, signed, verified, and published back to that app repository as a stable GitHub Release containing:

```text
<repo>-<versionName>.apk
SHA256SUMS.txt
provenance.json
```

The release notes record the source commit and source Actions artifact ID. Already-published source artifacts are skipped.

## 7. Catalog refresh

The normal catalog job continues to discover stable GitHub Releases. Developer-signed releases and Library-managed signed releases therefore converge into the same downstream catalog and install path.

For existing packages, do not switch signing identities casually: Android normally requires updates to use the existing signing identity unless a supported signing-key rotation path is configured.
