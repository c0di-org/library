# GitHub App authentication

Library uses a GitHub App instead of asking Android users to create personal access tokens.

## Android sign-in

The Android app uses the GitHub App OAuth **Device Flow**. The APK contains only the GitHub App client ID, which is public information. It never contains the App private key or a client secret.

Enable **Device Flow** in the Library Catalog GitHub App settings, then set the repository variable:

- `LIBRARY_CATALOG_APP_CLIENT_ID` — the GitHub App client ID (not the App ID)

A user access token can reach only repositories that **both** the signed-in user and the GitHub App installation can access. Library stores the access/refresh session with Android Keystore and renews device-flow sessions without shipping a client secret.

For the Android/private-release use case, keep the GitHub App's repository access minimal. `Contents: Read-only` is sufficient for reading private release metadata, README content, and release assets.

## Catalog workflow

The catalog workflow mints a one-hour Catalog App installation token for repository discovery. Configure:

- repository variable `LIBRARY_CATALOG_APP_CLIENT_ID`
- repository or environment secret `LIBRARY_CATALOG_APP_PRIVATE_KEY`

Install the App on the repositories Library should discover. The catalog workflow asks GitHub for a token scoped to `Contents: read` and uses the Library workflow `GITHUB_TOKEN` only to create its own versioned `catalog-*` release. Catalog releases intentionally do not need to trigger further automation.

## Release-to-catalog chaining

Managed app releases are published by the protected managed-signing automation rather than by a source repository's built-in `GITHUB_TOKEN`. Their Release webhooks therefore enter the normal GitHub App → `catalog.yml` path.

Library's own Android release is a special case: it is safely published with Library's built-in `GITHUB_TOKEN`, but GitHub does not allow events created with that token to start downstream workflows. After publishing `vX.Y.Z`, `release.yml` therefore explicitly invokes the same `catalog.yml` `workflow_dispatch` entry point with the new release ID/tag. `workflow_dispatch` is the documented exception that is allowed to start a workflow from `GITHUB_TOKEN`.

This exception is local to Library's self-release workflow. It does not replace the GitHub App event path used for managed source repositories.

## Managed signing

Managed signing is intentionally separate because it needs broader capabilities (`Actions: read` to retrieve build artifacts and `Contents: write` to publish a signed release). Do not broaden the read-only Catalog App just to support signing or release publication; keep write-capable automation credentials protected in the Library production environment.

This separation keeps the user-authorized Catalog App incapable of writing repository contents while allowing trusted managed-signing automation to publish source-repo releases that the webhook router can consume.
