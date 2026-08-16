# Release operations

## 1. Create the Library release key once

Run on a trusted machine:

```bash
bash scripts/create_release_key.sh library-release.jks
```

Back up the keystore offline before publishing anything.

## 2. Configure GitHub Actions signing secrets

Base64-encode the keystore without line wrapping.

Linux:

```bash
base64 -w 0 library-release.jks > library-release.jks.b64
```

macOS:

```bash
base64 < library-release.jks | tr -d '\n' > library-release.jks.b64
```

Add these repository secrets:

```text
LIBRARY_KEYSTORE_BASE64       contents of library-release.jks.b64
LIBRARY_SIGNING_STORE_PASSWORD
LIBRARY_SIGNING_KEY_ALIAS
LIBRARY_SIGNING_KEY_PASSWORD
```

Delete the temporary `.b64` file after storing the secret.

## 3. Optional private-repository discovery

Add `LIBRARY_GITHUB_TOKEN` as a fine-grained token with read-only access to the private repositories that should appear in Library. Public repositories are still scanned without it.

## 4. Publish Library

`gradle.properties` contains the single release version:

```properties
LIBRARY_VERSION=1.0.0
```

Every push to `main` runs the release workflow. It compares `LIBRARY_VERSION` with the highest stable `vX.Y.Z` GitHub Release:

- if the version is equal to or lower than the latest published version, the release job exits successfully without publishing anything;
- if the version is higher, CI requires the permanent signing secrets, builds and verifies a signed release APK, creates `v<version>`, and publishes the artifacts.

To publish the next version, change only the version and merge it to `main`, for example:

```properties
LIBRARY_VERSION=1.1.0
```

Android `versionCode` is derived automatically using `major * 1,000,000 + minor * 1,000 + patch`, so `1.2.3` becomes `1002003`.

The release contains:

```text
library-1.0.0.apk
SHA256SUMS.txt
catalog.json
```

After publishing, the workflow refreshes the rolling `catalog` GitHub Release so the just-published Library version is immediately visible to installed copies.

## 5. Download and install

Open the matching GitHub Release and download `library-<version>.apk`. The first install requires Android to authorize Library as an unknown-app source. Future updates can be initiated inside Library; Android may still require confirmation depending on OS version, installer ownership, target SDK, and platform policy.

## 6. Publish another app to Library

Publish a stable GitHub Release in that repository containing a standalone `.apk`. No Library catalog edit is required. The scheduled catalog job scans every six hours and can also be run manually.

For polished storefront metadata, add a `.library.json` file to the app repository; `.library.example.json` in this repository shows the supported fields.
