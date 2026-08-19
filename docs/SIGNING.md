# Signing

## Library itself

The Library Android client has one stable application-signing identity. Keep that key for the lifetime of the package; ordinary Android updates require the same signer (or a valid Android signing-key rotation lineage).

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

The APK attached to the developer's stable GitHub Release is installed unchanged. Discovery records the exact file SHA-256 and APK signing-certificate SHA-256, and the Android client verifies both before installation.

### Library-managed

A managed app's release workflow produces an unsigned standalone APK and uploads it as an Actions artifact named `library-unsigned-apk`. Library's automation GitHub App reacts to the successful `workflow_run`, verifies the candidate artifact exists, and dispatches the protected signing workflow with the exact repository, run ID, artifact ID, and source commit SHA.

The protected workflow then:

1. resolves enrollment from a central hard pin or `.library.json` at the exact source commit;
2. re-fetches the exact workflow run and artifact selected by the webhook;
3. requires the expected branch, source SHA, artifact name, successful non-PR run, and unexpired artifact;
4. requires exactly one unsigned standalone APK and verifies its package/version metadata;
5. refuses non-increasing `versionCode` values when existing releases can be inspected;
6. aligns, signs, and verifies the APK with Library's managed-app distribution key;
7. publishes a stable GitHub Release targeted at the exact source commit and records the source run/artifact IDs in release provenance.

There is no hourly signer, batch catch-up, or "find the latest artifact" fallback.

Managed enrollment has two forms:

1. **Repository-side enrollment (normal path).** A repository owned by the configured source owner declares `provenance: "library-managed"` and `managedSigning.packageName` in `.library.json`. Repo-side enrollment uses the repository default branch and the conventional `library-unsigned-apk` artifact name.
2. **Central hard pin.** `config/managed-apps.json` pins repository/package/branch/artifact in Library. Central enrollment takes precedence and is appropriate when the app repository must not be able to change its own package declaration or when a custom branch/artifact protocol is required.

Repo-side enrollment deliberately moves enrollment ownership into repositories controlled by c0di-org. A compromise of such a repository can alter its own managed-signing declaration, so use a central hard pin for packages that need a separate authorization boundary.

The managed-app distribution key is **not** the Library application key. Never use `LIBRARY_KEYSTORE_*` to sign another package.

Library-managed apps currently share one distribution signing identity for operational simplicity. This increases blast radius: compromise or loss of that key affects every app enrolled in managed signing, and same-certificate apps can participate in Android signature-level trust relationships. Only enroll packages intentionally placed under this common identity.

The event boundary is:

```text
app CI
  -> unsigned library-unsigned-apk
  -> workflow_run webhook
  -> automation GitHub App verifies exact candidate
  -> protected managed-signing workflow
  -> exact run/artifact re-validation
  -> managed distribution key
  -> signed stable GitHub Release
  -> release webhook
  -> catalog reconciliation
```

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

`LIBRARY_GITHUB_TOKEN` needs `Actions: read`, `Contents: write`, and `Metadata: read` on repositories Library manages. Repo-side enrollment also uses that contents access to read `.library.json` from the exact source commit. Keep the credential scoped only to repositories Library intentionally manages.

## Backups

Keep at least two encrypted/offline backups of both stable signing keystores and their recovery information. Losing a key means losing the ability to ship normal updates under the same package identity.

Never commit `.jks`, `.keystore`, base64 key dumps, passwords, or secret-bearing environment files.
