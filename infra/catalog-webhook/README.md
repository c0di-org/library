# Library Catalog GitHub App

This Worker turns GitHub release webhooks into immediate Library catalog refreshes. It is intentionally stateless: GitHub remains the source of truth, and the existing scheduled catalog refresh remains the fallback.

## What it does

1. GitHub sends a `release` webhook for repositories where the Library Catalog GitHub App is installed.
2. The Worker verifies `X-Hub-Signature-256` using the GitHub App webhook secret.
3. Draft releases and releases without an APK are ignored. Release removals still trigger a refresh so the catalog can fall back to the previous valid release.
4. The Worker signs a short-lived GitHub App JWT, finds the App installation for `garfbargle/library`, and mints an installation token scoped to the `library` repository with only `Actions: write`.
5. The Worker dispatches `.github/workflows/catalog.yml` with the source repository, release ID/tag, and webhook delivery ID.
6. `catalog.yml` performs the normal full reconciliation. Its six-hour schedule remains enabled as a safety net.

The rolling `catalog` release in `garfbargle/library` is explicitly ignored to prevent a feedback loop.

## One-time setup

### 1. Deploy the Worker once to get its URL

From this directory:

```bash
npx wrangler@latest deploy
```

Copy the resulting `workers.dev` URL. A normal `GET` returns a small health response even before secrets are configured.

### 2. Create the GitHub App

Create a new GitHub App named **Library Catalog** under the account that owns the participating repositories.

Use the Worker URL as the webhook URL and generate a strong webhook secret, for example:

```bash
openssl rand -hex 32
```

Repository permissions:

- **Contents: Read-only** — required for Release webhook events.
- **Actions: Read and write** — used only to dispatch the Library catalog workflow.

Subscribe to the **Release** repository event. The Worker additionally restricts source events to `SOURCE_OWNER` and mints the runtime installation token for the Library repository only.

Generate a private key for the App, then install the App for **All repositories** on the account. This is what removes per-repository secrets/workflows and automatically covers new repositories.

### 3. Add Worker secrets

Set the same webhook secret used in the GitHub App settings:

```bash
npx wrangler@latest secret put GITHUB_WEBHOOK_SECRET
```

Set the GitHub App ID:

```bash
npx wrangler@latest secret put GITHUB_APP_ID
```

Store the entire generated GitHub App private-key PEM:

```bash
npx wrangler@latest secret put GITHUB_APP_PRIVATE_KEY < path/to/github-app.private-key.pem
```

The Worker accepts GitHub-generated `-----BEGIN RSA PRIVATE KEY-----` keys as well as PKCS#8 `-----BEGIN PRIVATE KEY-----` keys.

### 4. Verify delivery

In the GitHub App settings, use the webhook delivery page to redeliver the `ping`, or publish an APK release in an installed repository.

Expected behavior:

- `ping` → HTTP 200
- unrelated event → HTTP 202, ignored
- draft/no-APK release → HTTP 202, ignored
- APK release → HTTP 202 with `"dispatched": true`, followed by a **Refresh Catalog** workflow run in `garfbargle/library`

## Configuration

Non-secret settings live in `wrangler.toml`:

- `SOURCE_OWNER` — only release events from this owner may trigger catalog refreshes.
- `LIBRARY_OWNER` / `LIBRARY_REPO` — destination repository.
- `LIBRARY_WORKFLOW` — workflow filename to dispatch.
- `LIBRARY_REF` — ref used for `workflow_dispatch`.

Required Worker secrets:

- `GITHUB_WEBHOOK_SECRET`
- `GITHUB_APP_ID`
- `GITHUB_APP_PRIVATE_KEY`

## Local validation

No npm install is required:

```bash
node --check worker.mjs
node worker.test.mjs
```

The tests cover release filtering, HMAC verification, GitHub-style RSA private-key handling, JWT signing, and signature verification.
