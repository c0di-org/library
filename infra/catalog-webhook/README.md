# Library automation GitHub App

This Worker turns GitHub events into immediate Library automation. It is intentionally stateless: GitHub remains the source of truth.

It handles two event paths:

- published release activity → reconcile the Library catalog;
- successful app workflow with the expected unsigned APK artifact → sign and publish that exact artifact.

Managed APK signing is fully event-driven. There is no hourly signing poll and no batch/latest-artifact catch-up path.

## Event flow

### Managed APK signing

1. GitHub sends a completed `workflow_run` webhook.
2. The Worker verifies `X-Hub-Signature-256` and ignores failed runs, pull-request runs, and repositories outside `SOURCE_OWNER`.
3. It loads `config/managed-apps.json`. A centrally enrolled repository may pin its branch and artifact name; otherwise the repository is treated as a repo-side enrollment candidate using its default branch and `library-unsigned-apk`.
4. The Worker rejects runs on the wrong branch.
5. It mints a short-lived installation token scoped to the source repository with `Actions: read` and verifies that the completed run actually contains the expected, unexpired artifact.
6. Only then does it dispatch `.github/workflows/managed-signing.yml`, passing the exact source repository, workflow run ID, artifact ID, source commit SHA, and webhook delivery ID.
7. The protected signing workflow resolves central enrollment or reads `.library.json` from that exact source commit.
8. `manage_unsigned_apks.py` re-fetches and validates the exact workflow run and artifact. It never searches recent runs for a newer/latest artifact.
9. The signer validates package/version state, requires the APK to be unsigned, signs and verifies it, and publishes a stable GitHub Release targeted at the source commit.
10. Publishing that release naturally emits the Release webhook, entering the catalog path below.

Duplicate deliveries are safe: releases record the source Actions artifact ID, and an already-published artifact is not published again.

### Catalog reconciliation

1. GitHub sends a `release` webhook for repositories where the App is installed.
2. The Worker verifies the webhook signature, ignores draft releases, and ignores Library's rolling `catalog` release to prevent a feedback loop.
3. It mints a short-lived installation token scoped to `c0di-org/library` and dispatches `.github/workflows/catalog.yml` with release context.
4. `catalog.yml` performs a full reconciliation. `scripts/sync_github.py` remains the authoritative APK filter; the webhook does not patch catalog JSON directly.
5. The workflow publishes the rebuilt `catalog.json` as the rolling `catalog` Release asset.

`catalog.yml` also runs every six hours as a recovery safety net for a missed Release webhook. That schedule only reconciles already-published releases; it does not discover or sign unsigned workflow artifacts.

## Why there is no signing poll

The previous managed-signing workflow ran hourly and searched recent successful workflow runs for the newest `library-unsigned-apk`. That model has been removed. The source workflow run and artifact are now selected before the protected signing workflow starts, and the signer is required to consume exactly that pair.

This avoids idle runners, eliminates "latest artifact" races, and makes the source commit/run/artifact tuple part of the signing trust boundary.

## GitHub App setup

Use the deployed Worker URL as the GitHub App webhook URL.

Repository permissions:

- **Contents: Read-only** — release events and reading `config/managed-apps.json`;
- **Actions: Read and write** — reading source workflow artifacts and dispatching Library workflows.

Subscribe to:

- **Release**
- **Workflow run**

Install the App for **All repositories** on the c0di-org account so new repositories require no copied notification workflow. A repository participates in managed signing only when central configuration or its protected `.library.json` resolves it as `library-managed`.

## Worker secrets

Required secrets:

- `GITHUB_WEBHOOK_SECRET`
- `GITHUB_APP_ID`
- `GITHUB_APP_PRIVATE_KEY`

The Worker accepts GitHub-generated `-----BEGIN RSA PRIVATE KEY-----` keys as well as PKCS#8 `-----BEGIN PRIVATE KEY-----` keys.

## Configuration

Non-secret settings live in `wrangler.toml`:

- `SOURCE_OWNER` — only events from this owner are considered;
- `LIBRARY_OWNER` / `LIBRARY_REPO` — destination repository;
- `LIBRARY_WORKFLOW` — catalog workflow filename;
- `MANAGED_SIGNING_WORKFLOW` — managed-signing workflow filename;
- `LIBRARY_REF` — ref used for workflow dispatch and managed-app config lookup.

The checked-in configuration targets `c0di-org/library`.

## Verification

Expected webhook behavior:

- `ping` → HTTP 200;
- unrelated event → HTTP 202, ignored;
- draft release → HTTP 202, ignored;
- non-draft release → HTTP 202 with a catalog dispatch;
- unsuccessful or pull-request workflow run → HTTP 202, ignored;
- successful workflow on the wrong branch → HTTP 202, ignored;
- successful workflow without the expected artifact → HTTP 202, ignored;
- successful enrolled/candidate workflow with the expected artifact → HTTP 202 with a managed-signing dispatch containing exact source IDs.

No npm install is required for local validation:

```bash
node --check worker.mjs
node worker.test.mjs
```

CI runs the Worker tests alongside helper-script, catalog, Android lint/test, and APK build validation.
