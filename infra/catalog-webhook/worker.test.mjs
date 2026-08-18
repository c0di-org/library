import assert from 'node:assert/strict';
import { createHmac, createVerify, generateKeyPairSync } from 'node:crypto';
import {
  createAppJwt,
  findManagedApp,
  shouldDispatchRelease,
  shouldInspectWorkflowRun,
  verifyWebhookSignature,
} from './worker.mjs';

const env = {
  SOURCE_OWNER: 'c0di-org',
  LIBRARY_OWNER: 'c0di-org',
  LIBRARY_REPO: 'library',
};

const payload = {
  action: 'published',
  repository: {
    full_name: 'c0di-org/example',
    name: 'example',
    default_branch: 'main',
    owner: { login: 'c0di-org' },
  },
  release: {
    id: 7,
    tag_name: 'v1.2.3',
    draft: false,
    prerelease: false,
    assets: [{ name: 'app.apk' }],
  },
};

assert.equal(shouldDispatchRelease(payload, env).dispatch, true);
assert.equal(
  shouldDispatchRelease({ ...payload, release: { ...payload.release, draft: true } }, env).dispatch,
  false,
);
assert.equal(
  shouldDispatchRelease({ ...payload, release: { ...payload.release, assets: [] } }, env).dispatch,
  true,
);
assert.equal(
  shouldDispatchRelease(
    {
      ...payload,
      action: 'deleted',
      release: { ...payload.release, draft: true, assets: [] },
    },
    env,
  ).dispatch,
  true,
);
assert.equal(
  shouldDispatchRelease(
    {
      ...payload,
      repository: { ...payload.repository, full_name: 'elsewhere/example', owner: { login: 'elsewhere' } },
    },
    env,
  ).dispatch,
  false,
);
assert.equal(
  shouldDispatchRelease(
    {
      ...payload,
      repository: { ...payload.repository, full_name: 'c0di-org/library', name: 'library' },
      release: { ...payload.release, tag_name: 'catalog' },
    },
    env,
  ).dispatch,
  false,
);

const workflowRunPayload = {
  action: 'completed',
  installation: { id: 99 },
  repository: {
    full_name: 'c0di-org/papyrus',
    name: 'papyrus',
    default_branch: 'main',
    owner: { login: 'c0di-org' },
  },
  workflow_run: {
    id: 123,
    event: 'push',
    head_branch: 'main',
    head_sha: 'abc123',
    conclusion: 'success',
  },
};
assert.equal(shouldInspectWorkflowRun(workflowRunPayload, env).inspect, true);
assert.equal(
  shouldInspectWorkflowRun(
    { ...workflowRunPayload, workflow_run: { ...workflowRunPayload.workflow_run, conclusion: 'failure' } },
    env,
  ).inspect,
  false,
);
assert.equal(
  shouldInspectWorkflowRun(
    { ...workflowRunPayload, workflow_run: { ...workflowRunPayload.workflow_run, event: 'pull_request' } },
    env,
  ).inspect,
  false,
);
assert.equal(
  shouldInspectWorkflowRun(
    {
      ...workflowRunPayload,
      repository: { ...workflowRunPayload.repository, owner: { login: 'elsewhere' } },
    },
    env,
  ).inspect,
  false,
);

const managedApps = [
  { repository: 'c0di-org/papyrus', branch: 'main', artifact: 'library-unsigned-apk' },
  { repository: 'c0di-org/Fiddler', branch: 'main', artifact: 'library-unsigned-apk' },
];
assert.equal(findManagedApp(managedApps, 'c0di-org/PAPYRUS')?.branch, 'main');
assert.equal(findManagedApp(managedApps, 'c0di-org/not-managed'), null);

const secret = 'webhook-secret';
const body = JSON.stringify(payload);
const signature = `sha256=${createHmac('sha256', secret).update(body).digest('hex')}`;
assert.equal(await verifyWebhookSignature(secret, body, signature), true);
assert.equal(await verifyWebhookSignature(secret, `${body}x`, signature), false);

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const pkcs1 = privateKey.export({ format: 'pem', type: 'pkcs1' }).toString();
const jwt = await createAppJwt('12345', pkcs1);
const [header, claims, signed] = jwt.split('.');
assert.equal(JSON.parse(Buffer.from(header, 'base64url')).alg, 'RS256');
assert.equal(JSON.parse(Buffer.from(claims, 'base64url')).iss, '12345');
const verifier = createVerify('RSA-SHA256');
verifier.update(`${header}.${claims}`);
verifier.end();
assert.equal(verifier.verify(publicKey, Buffer.from(signed, 'base64url')), true);

console.log('catalog webhook tests OK');
