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

## 4. Cut a Library release

Push a semantic-version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

CI derives a monotonic Android version code using `major * 1,000,000 + minor * 1,000 + patch`, so `1.2.3` becomes `1002003`.

The release workflow builds and signature-verifies the APK, then publishes:

```text
library-1.0.0.apk
SHA256SUMS.txt
catalog.json
```

It then refreshes the rolling `catalog` GitHub Release so the just-published Library version is immediately visible to installed copies.

## 5. Download and install

Open the matching GitHub Release and download `library-<version>.apk`. The first install requires Android to authorize Library as an unknown-app source. Future updates can be initiated inside Library; Android may still require confirmation depending on OS version, installer ownership, target SDK, and platform policy.

## 6. Publish another app to Library

Publish a stable GitHub Release in that repository containing a standalone `.apk`. No Library catalog edit is required. The scheduled catalog job scans every six hours and can also be run manually.

For polished storefront metadata, add a `.library.json` file to the app repository; `.library.example.json` in this repository shows the supported fields.
