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

An app repository produces an unsigned release APK and uploads it as a GitHub Actions artifact named `library-unsigned-apk`. Library validates the artifact, aligns it, signs it with Library's managed-app distribution identity, verifies the final APK, and publishes a stable GitHub Release back to the source repository.

Managed enrollment has two forms:

1. **Repository-side enrollment (normal Tauri path).** An owned repository declares `provenance: "library-managed"` and `managedSigning.packageName` in `.library.json`. The webhook treats a successful default-branch `library-unsigned-apk` as a candidate. The protected signing workflow reads `.library.json` from the exact source commit and verifies the declared package against the APK before using the key.
2. **Central hard pin.** `config/managed-apps.json` pins repository/package/branch/artifact in Library. Central enrollment takes precedence over repo metadata and is the stronger option when the app repository must not be able to change its own package declaration.

Repo-side enrollment deliberately moves enrollment ownership into repositories controlled by the configured source owner. A compromise of such a repository can alter its own managed-signing declaration, so use the central hard-pin path for packages that need a separate authorization boundary.

The managed-app distribution key is **not** the Library application key. Never use `LIBRARY_KEYSTORE_*` to sign another package.

Library-managed apps currently share one distribution signing identity for operational simplicity. This increases blast radius: compromise or loss of that key affects every app enrolled in managed signing, and same-certificate apps can participate in Android signature-level trust relationships. Only enroll packages you intentionally want under this common identity.

The normal Tauri boundary is:

```text
owned app CI -> unsigned library-unsigned-apk -> protected .library.json resolution + validation -> managed distribution key -> signed GitHub Release
```

For a central hard pin it is:

```text
trusted app CI -> unsigned APK artifact -> Library central enrollment + validation -> managed distribution key -> signed GitHub Release
```

The signer refuses artifacts that are already signed, whose package name differs from the resolved enrollment, that come from the wrong branch, or whose `versionCode` does not increase when an existing APK release can be inspected.

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

`LIBRARY_GITHUB_TOKEN` needs `Actions: read`, `Contents: write`, and `Metadata: read` on repositories Library manages. Repo-side enrollment also uses that contents access to read `.library.json` from the exact source commit. Keep the token scoped to only the repositories Library intentionally manages.

## Backups

Keep at least two encrypted/offline backups of both stable signing keystores and their recovery information. Losing a key means losing the ability to ship normal updates under the same package identity.

Never commit `.jks`, `.keystore`, base64 key dumps, passwords, or secret-bearing environment files.

## Android developer verification

Android developer verification is separate from APK signing. Register the same package/signing identities that Library distributes when required by Android's rollout. Library's signer pinning is intentionally stable so the catalog and Android's developer identity checks refer to the same application identity.
