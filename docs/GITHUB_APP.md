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

## Automated releases that must feed the webhook

A release that is expected to enter the downstream GitHub App webhook path must **not** be published with the repository's built-in `GITHUB_TOKEN`. GitHub suppresses downstream workflow chaining for events created by that token.

Library's versioned Android release therefore uses the protected `LIBRARY_GITHUB_TOKEN` only for the final `gh release create` operation. Managed signing already uses the same protected automation credential when it publishes signed releases back to source repositories. Those releases can therefore enter the normal Release webhook → Catalog App → `catalog.yml` path.

## Managed signing

Managed signing is intentionally separate because it needs broader capabilities (`Actions: read` to retrieve build artifacts and `Contents: write` to publish a signed release). Do not broaden the read-only Catalog App just to support signing or release publication; keep write-capable automation credentials protected in the Library production environment.

This separation keeps the user-authorized Catalog App incapable of writing repository contents while allowing trusted release automation to create events that the webhook router can consume.
