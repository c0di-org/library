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

Library does **not** re-sign developer releases. The APK attached to the developer's GitHub Release is the APK Library installs. Discovery records the exact file SHA-256 and the APK signing-certificate SHA-256, and the Android client verifies both before installation.

Never reuse Library's client signing key as a universal app key.

For a future Library source-build service, create a dedicated signing key per package:

```text
com.example.notes   -> dedicated key A
com.example.camera  -> dedicated key B
```

The preferred production boundary is:

```text
isolated builder -> unsigned artifact digest -> signing service -> HSM/KMS-backed package key -> signed APK
```

Build workers should not be able to export signing private keys.

## Backups

Keep at least two encrypted/offline backups of the Library release keystore and recovery information. Losing the key means losing the ability to ship normal updates under the same package identity.

Never commit `.jks`, `.keystore`, base64 key dumps, passwords, or secret-bearing environment files.

## Android developer verification

Android developer verification is separate from APK signing. Register the same package/signing identities that Library distributes when required by Android's rollout. Library's signer pinning is intentionally stable so the catalog and Android's developer identity checks refer to the same application identity.
