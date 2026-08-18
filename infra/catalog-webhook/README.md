# Library automation GitHub App

This Worker turns GitHub events into immediate Library automation. It is intentionally stateless: GitHub remains the source of truth.

It handles two paths:

- published release activity → refresh the Library catalog
- successful managed-app build with a `library-unsigned-apk` artifact → run managed APK signing for that app only

The catalog's existing six-hour schedule remains as a safety net. Managed APK signing is event-driven and no longer needs an hourly polling schedule.

## Event flow

### Catalog refresh

1. GitHub sends a `release` webhook for repositories where the App is installed.
2. The Worker verifies `X-Hub-Signature-256`.
3. Draft releases are ignored, and Library's rolling `catalog` release is ignored to prevent a feedback loop.
4. The Worker mints a short-lived installation token scoped to `c0di-org/library` and dispatches `.github/workflows/catalog.yml`.
5. `catalog.yml` performs the normal full reconciliation; `sync_github.py` remains the authoritative APK filter.

### Managed APK signing

1. GitHub sends a completed `workflow_run` webhook.
2. The Worker ignores failed runs, pull-request runs, repos outside `SOURCE_OWNER`, and repos not listed in `config/managed-apps.json`.
3. For an enrolled repo, the Worker verifies the run is on the configured branch.
4. It mints a short-lived token scoped to that source repo with `Actions: read` and checks that the completed run actually contains the configured `library-unsigned-apk` artifact.
5. Only then does it dispatch `.github/workflows/managed-signing.yml`, passing the source repository/run/artifact IDs.
6. The signing workflow narrows `config/managed-apps.json` to that one app, signs/publishes it, and exits. A manual workflow dispatch with no source repository still provides an explicit catch-up path across all enrolled apps.
7. Publishing the signed release naturally emits the Release webhook, which then refreshes the catalog.

This means normal successful workflows do not start signing jobs, and idle periods consume no managed-signing runners.

## GitHub App setup

Use the deployed Worker URL as the GitHub App webhook URL.

Repository permissions:

- **Contents: Read-only** — release events and reading `config/managed-apps.json`
- **Actions: Read and write** — reading source workflow artifacts and dispatching Library workflows

Subscribe to both repository events:

- **Release**
- **Workflow run**

Install the App for **All repositories** on the account so new repositories require no copied token, secret, or notification workflow. Repositories only participate in managed signing when they are explicitly enrolled in `config/managed-apps.json`.

If the App was originally configured only for Release events, simply enable **Workflow run** in the GitHub App settings. The existing permissions are already sufficient.

## Worker secrets

Required secrets:

- `GITHUB_WEBHOOK_SECRET`
- `GITHUB_APP_ID`
- `GITHUB_APP_PRIVATE_KEY`

The Worker accepts GitHub-generated `-----BEGIN RSA PRIVATE KEY-----` keys as well as PKCS#8 `-----BEGIN PRIVATE KEY-----` keys.

## Configuration

Non-secret settings live in `wrangler.toml`:

- `SOURCE_OWNER` — only events from this owner are considered
- `LIBRARY_OWNER` / `LIBRARY_REPO` — destination repository
- `LIBRARY_WORKFLOW` — catalog workflow filename
- `MANAGED_SIGNING_WORKFLOW` — managed-signing workflow filename
- `LIBRARY_REF` — ref used for `workflow_dispatch` and managed-app config lookup

## Verification

Expected webhook behavior:

- `ping` → HTTP 200
- unrelated event → HTTP 202, ignored
- draft release → HTTP 202, ignored
- non-draft release → HTTP 202 with a catalog dispatch
- unsuccessful or pull-request workflow run → HTTP 202, ignored
- successful workflow run in an unmanaged repo → HTTP 202, ignored
- successful managed workflow without the expected artifact → HTTP 202, ignored
- successful enrolled build with `library-unsigned-apk` → HTTP 202 with a managed-signing dispatch

No npm install is required for local validation:

```bash
node --check worker.mjs
node worker.test.mjs
```

CI runs the Worker tests alongside the repository's existing helper-script, catalog, lint, unit-test, and APK build validation.
