import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import workerEntry, { isCatalogRelease } from './worker-entry.mjs';

const env = {
  LIBRARY_OWNER: 'c0di-org',
  LIBRARY_REPO: 'library',
  GITHUB_WEBHOOK_SECRET: 'webhook-secret',
};

const catalogPayload = {
  action: 'published',
  repository: { full_name: 'c0di-org/library' },
  release: { tag_name: 'catalog-12345-1' },
};

assert.equal(isCatalogRelease(catalogPayload, env), true);
assert.equal(
  isCatalogRelease({ ...catalogPayload, release: { tag_name: 'catalog' } }, env),
  true,
);
assert.equal(
  isCatalogRelease({ ...catalogPayload, release: { tag_name: 'v1.0.18' } }, env),
  false,
);
assert.equal(
  isCatalogRelease(
    { ...catalogPayload, repository: { full_name: 'c0di-org/example' } },
    env,
  ),
  false,
);

const body = JSON.stringify(catalogPayload);
const signature = `sha256=${createHmac('sha256', env.GITHUB_WEBHOOK_SECRET).update(body).digest('hex')}`;
const response = await workerEntry.fetch(
  new Request('https://example.invalid/', {
    method: 'POST',
    headers: {
      'x-github-event': 'release',
      'x-hub-signature-256': signature,
    },
    body,
  }),
  env,
);
assert.equal(response.status, 202);
assert.deepEqual(await response.json(), { ok: true, ignored: 'catalog-release' });

console.log('catalog webhook entry tests OK');
