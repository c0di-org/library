# GitHub App authentication

Library uses a GitHub App instead of asking Android users to create personal access tokens.

## Android sign-in

The Android app uses the GitHub App OAuth **Device Flow**. The APK contains only the GitHub App client ID, which is public information. It never contains the App private key or a client secret.

Enable **Device Flow** in the Library Catalog GitHub App settings, then set the repository variable:

- `LIBRARY_CATALOG_APP_CLIENT_ID` — the GitHub App client ID (not the App ID)

A user access token can reach only repositories that **both** the signed-in user and the GitHub App installation can access. Library stores the access/refresh session with Android Keystore and renews device-flow sessions without shipping a client secret.

For the Android/private-release use case, keep the GitHub App's repository access minimal. `Contents: Read-only` is sufficient for reading private release metadata, README content, and release assets.

## Catalog workflow

The catalog workflow mints a one-hour GitHub App installation token instead of using a long-lived PAT. Configure:

- repository variable `LIBRARY_CATALOG_APP_CLIENT_ID`
- repository or environment secret `LIBRARY_CATALOG_APP_PRIVATE_KEY`

Install the App on the repositories Library should discover. The catalog workflow asks GitHub for a token scoped to `Contents: read` and uses the normal repository `GITHUB_TOKEN` only to publish the public rolling catalog in `garfbargle/library`.

## Managed signing

Managed signing is intentionally separate because it needs broader capabilities (`Actions: read` to retrieve build artifacts and `Contents: write` to publish a signed release). Do not broaden the read-only Catalog App just to support signing; use a dedicated signing GitHub App for that automation before removing the existing managed-signing credential.

This separation keeps the user-authorized Catalog App incapable of writing repository contents.
