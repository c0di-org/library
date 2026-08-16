# Signing

## Library itself

`com.garfbargle.library` has one stable Android application-signing identity. Keep that key for the lifetime of the package; ordinary Android updates require the same signer (or a valid Android signing-key rotation lineage).

The keystore is never committed. Release CI reconstructs it only on the ephemeral GitHub runner from encrypted repository secrets:

```text
LIBRARY_KEYSTORE_BASE64
LIBRARY_SIGNING_STORE_PASSWORD
LIBRARY_SIGNING_KEY_ALIAS
LIBRARY_SIGNING_KEY_PASSWORD
```

The release workflow runs `apksigner verify --verbose --print-certs` on the final APK before publishing it.

## Apps distributed by Library

Library supports two signing models.

### Developer-signed

The APK attached to the developer's GitHub Release is installed unchanged. Discovery records the exact file SHA-256 and APK signing-certificate SHA-256, and the Android client verifies both before installation.

### Library-managed

An app repository may be explicitly enrolled in `config/managed-apps.json`. Its own CI produces an unsigned release APK and uploads it as a GitHub Actions artifact. Library downloads the artifact from the allowlisted repository and branch, verifies the manifest package against the package name pinned in Library, confirms the APK is unsigned, checks version monotonicity, aligns it, signs it with Library's managed-app distribution identity, verifies the final APK, and publishes a normal GitHub Release back to the source repository.

The allowlist is intentionally stored in Library rather than in the app repository. A compromise of an enrolled app repository can change its source and CI output, but cannot authorize Library to sign a different Android package without a separate change to `config/managed-apps.json`.

The managed-app distribution key is **not** the Library application key. Never use `LIBRARY_KEYSTORE_*` to sign another package.

Library-managed apps currently share one distribution signing identity for operational simplicity. This increases blast radius: compromise or loss of that key affects every app enrolled in managed signing, and same-certificate apps can participate in Android signature-level trust relationships. Only enroll packages you intentionally want under this common identity.

The preferred security boundary is:

```text
trusted app CI -> unsigned APK artifact -> Library central allowlist + validation -> managed distribution key -> signed GitHub Release
```

The signer refuses artifacts that are already signed, whose package name differs from the central allowlist, that come from the wrong branch, or whose `versionCode` does not increase when an existing APK release can be inspected.

## Managed distribution secrets

Create the distribution key once with:

```bash
bash scripts/create_distribution_key.sh library-distribution.jks
```

Store these only in the Library repository/environment:

```text
LIBRARY_DISTRIBUTION_KEYSTORE_BASE64
LIBRARY_DISTRIBUTION_STORE_PASSWORD
LIBRARY_DISTRIBUTION_KEY_ALIAS
LIBRARY_DISTRIBUTION_KEY_PASSWORD
```

`LIBRARY_GITHUB_TOKEN` additionally needs `Actions: read`, `Contents: write`, and `Metadata: read` on each repository enrolled in `config/managed-apps.json` so Library can retrieve CI artifacts and publish signed Releases. Keep the token scoped to only the repositories Library actually manages.

## Backups

Keep at least two encrypted/offline backups of both stable signing keystores and their recovery information. Losing a key means losing the ability to ship normal updates under the same package identity.

Never commit `.jks`, `.keystore`, base64 key dumps, passwords, or secret-bearing environment files.

## Android developer verification

Android developer verification is separate from APK signing. Register the same package/signing identities that Library distributes when required by Android's rollout. Library's signer pinning is intentionally stable so the catalog and Android's developer identity checks refer to the same application identity.
