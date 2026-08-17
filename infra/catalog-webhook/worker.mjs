const API = 'https://api.github.com';
const API_VERSION = '2022-11-28';
const DEFAULT_MANAGED_ARTIFACT = 'library-unsigned-apk';
const encoder = new TextEncoder();

export default {
  async fetch(request, env) {
    if (request.method !== 'POST') {
      return json({ ok: true, service: 'library-catalog-webhook' }, 200);
    }

    const event = request.headers.get('x-github-event');
    const delivery = request.headers.get('x-github-delivery') || '';
    const signature = request.headers.get('x-hub-signature-256');
    const body = await request.text();

    if (!env.GITHUB_WEBHOOK_SECRET || !env.GITHUB_APP_ID || !env.GITHUB_APP_PRIVATE_KEY) {
      return json({ error: 'worker is missing required GitHub App secrets' }, 500);
    }

    if (!(await verifyWebhookSignature(env.GITHUB_WEBHOOK_SECRET, body, signature))) {
      return json({ error: 'invalid webhook signature' }, 401);
    }

    if (event === 'ping') {
      return json({ ok: true, pong: true }, 200);
    }
    if (event !== 'release' && event !== 'workflow_run') {
      return json({ ok: true, ignored: `event:${event || 'unknown'}` }, 202);
    }

    let payload;
    try {
      payload = JSON.parse(body);
    } catch {
      return json({ error: 'invalid JSON payload' }, 400);
    }

    if (event === 'release') {
      const decision = shouldDispatchRelease(payload, env);
      if (!decision.dispatch) {
        return json({ ok: true, ignored: decision.reason }, 202);
      }

      try {
        const appJwt = await createAppJwt(env.GITHUB_APP_ID, env.GITHUB_APP_PRIVATE_KEY);
        const installationId = await findLibraryInstallation(appJwt, env);
        const libraryToken = await createLibraryInstallationToken(appJwt, installationId, env);
        await dispatchCatalogWorkflow(libraryToken, payload, delivery, env);
        return json({
          ok: true,
          dispatched: true,
          workflow: env.LIBRARY_WORKFLOW || 'catalog.yml',
          repository: payload.repository.full_name,
          tag: payload.release?.tag_name || null,
          delivery,
        }, 202);
      } catch (error) {
        console.error('catalog dispatch failed', error);
        return json({ error: error instanceof Error ? error.message : String(error) }, 502);
      }
    }

    const candidate = shouldInspectWorkflowRun(payload, env);
    if (!candidate.inspect) {
      return json({ ok: true, ignored: candidate.reason }, 202);
    }

    try {
      const appJwt = await createAppJwt(env.GITHUB_APP_ID, env.GITHUB_APP_PRIVATE_KEY);
      const libraryInstallationId = await findLibraryInstallation(appJwt, env);
      const libraryToken = await createLibraryInstallationToken(appJwt, libraryInstallationId, env);
      const managedApps = await loadManagedApps(libraryToken, env);
      const managedApp = findManagedApp(managedApps, payload.repository.full_name);
      if (!managedApp) {
        return json({ ok: true, ignored: 'repository-not-managed' }, 202);
      }

      const expectedBranch = managedApp.branch || payload.repository.default_branch;
      if (payload.workflow_run?.head_branch !== expectedBranch) {
        return json({ ok: true, ignored: 'managed-build-wrong-branch' }, 202);
      }

      const sourceInstallationId = payload.installation?.id;
      if (!sourceInstallationId) {
        throw new Error('workflow_run webhook is missing GitHub App installation context');
      }
      const sourceToken = await createSourceInstallationToken(
        appJwt,
        sourceInstallationId,
        payload.repository.name,
      );
      const artifactName = managedApp.artifact || DEFAULT_MANAGED_ARTIFACT;
      const artifact = await findRunArtifact(
        sourceToken,
        payload.repository.full_name,
        payload.workflow_run.id,
        artifactName,
      );
      if (!artifact) {
        return json({ ok: true, ignored: `workflow-run-without-${artifactName}` }, 202);
      }

      await dispatchManagedSigningWorkflow(libraryToken, payload, artifact, delivery, env);
      return json({
        ok: true,
        dispatched: true,
        workflow: env.MANAGED_SIGNING_WORKFLOW || 'managed-signing.yml',
        repository: payload.repository.full_name,
        runId: payload.workflow_run.id,
        artifactId: artifact.id,
        delivery,
      }, 202);
    } catch (error) {
      console.error('managed signing dispatch failed', error);
      return json({ error: error instanceof Error ? error.message : String(error) }, 502);
    }
  },
};

export function shouldDispatchRelease(payload, env) {
  const repository = payload?.repository?.full_name;
  const owner = payload?.repository?.owner?.login;
  const release = payload?.release;

  if (!repository || !release) return { dispatch: false, reason: 'missing-release-context' };
  if (env.SOURCE_OWNER && owner?.toLowerCase() !== env.SOURCE_OWNER.toLowerCase()) {
    return { dispatch: false, reason: 'outside-source-owner' };
  }

  const libraryFullName = `${env.LIBRARY_OWNER || 'garfbargle'}/${env.LIBRARY_REPO || 'library'}`;
  if (repository.toLowerCase() === libraryFullName.toLowerCase() && release.tag_name === 'catalog') {
    return { dispatch: false, reason: 'rolling-catalog-release' };
  }

  if (payload.action === 'deleted' || payload.action === 'unpublished') {
    return { dispatch: true, reason: 'release-removed' };
  }
  if (release.draft) return { dispatch: false, reason: 'draft-release' };

  // Do not require an APK in the webhook payload. GitHub can emit the release event
  // before release assets finish uploading; sync_github.py is the authoritative APK filter.
  return { dispatch: true, reason: 'release-change' };
}

export function shouldInspectWorkflowRun(payload, env) {
  const repository = payload?.repository?.full_name;
  const owner = payload?.repository?.owner?.login;
  const run = payload?.workflow_run;
  if (!repository || !run) return { inspect: false, reason: 'missing-workflow-run-context' };
  if (env.SOURCE_OWNER && owner?.toLowerCase() !== env.SOURCE_OWNER.toLowerCase()) {
    return { inspect: false, reason: 'outside-source-owner' };
  }
  if (payload.action !== 'completed') return { inspect: false, reason: 'workflow-run-not-completed' };
  if (run.conclusion !== 'success') return { inspect: false, reason: 'workflow-run-not-successful' };
  if (run.event === 'pull_request' || run.event === 'pull_request_target') {
    return { inspect: false, reason: 'pull-request-workflow-run' };
  }
  return { inspect: true, reason: 'successful-workflow-run' };
}

export function findManagedApp(apps, repository) {
  if (!Array.isArray(apps) || !repository) return null;
  return apps.find((app) => String(app?.repository || '').toLowerCase() === repository.toLowerCase()) || null;
}

export async function verifyWebhookSignature(secret, body, signature) {
  if (!signature?.startsWith('sha256=')) return false;
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const digest = new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(body)));
  return timingSafeEqual(`sha256=${toHex(digest)}`, signature.toLowerCase());
}

export async function createAppJwt(appId, privateKeyPem) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlJson({ alg: 'RS256', typ: 'JWT' });
  const payload = base64UrlJson({ iat: now - 60, exp: now + 9 * 60, iss: String(appId) });
  const unsigned = `${header}.${payload}`;
  const key = await importPrivateKey(privateKeyPem);
  const signature = new Uint8Array(
    await crypto.subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, key, encoder.encode(unsigned)),
  );
  return `${unsigned}.${base64UrlBytes(signature)}`;
}

async function findLibraryInstallation(appJwt, env) {
  const owner = env.LIBRARY_OWNER || 'garfbargle';
  const repo = env.LIBRARY_REPO || 'library';
  const response = await githubFetch(`${API}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/installation`, appJwt);
  const data = await response.json();
  if (!response.ok || !data.id) {
    throw new Error(`could not resolve Library GitHub App installation (${response.status})`);
  }
  return data.id;
}

async function createLibraryInstallationToken(appJwt, installationId, env) {
  const repo = env.LIBRARY_REPO || 'library';
  const response = await githubFetch(`${API}/app/installations/${installationId}/access_tokens`, appJwt, {
    method: 'POST',
    body: JSON.stringify({
      repositories: [repo],
      permissions: { actions: 'write', contents: 'read' },
    }),
  });
  const data = await response.json();
  if (!response.ok || !data.token) {
    throw new Error(`could not mint Library installation token (${response.status})`);
  }
  return data.token;
}

async function createSourceInstallationToken(appJwt, installationId, repositoryName) {
  const response = await githubFetch(`${API}/app/installations/${installationId}/access_tokens`, appJwt, {
    method: 'POST',
    body: JSON.stringify({
      repositories: [repositoryName],
      permissions: { actions: 'read' },
    }),
  });
  const data = await response.json();
  if (!response.ok || !data.token) {
    throw new Error(`could not mint source repository installation token (${response.status})`);
  }
  return data.token;
}

async function loadManagedApps(token, env) {
  const owner = env.LIBRARY_OWNER || 'garfbargle';
  const repo = env.LIBRARY_REPO || 'library';
  const ref = env.LIBRARY_REF || 'main';
  const response = await githubFetch(
    `${API}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/contents/config/managed-apps.json?ref=${encodeURIComponent(ref)}`,
    token,
    { headers: { accept: 'application/vnd.github.raw+json' } },
  );
  if (!response.ok) {
    throw new Error(`could not load managed app configuration (${response.status})`);
  }
  const config = JSON.parse(await response.text());
  return Array.isArray(config.apps) ? config.apps : [];
}

async function findRunArtifact(token, repository, runId, artifactName) {
  const [owner, repo] = repository.split('/');
  const response = await githubFetch(
    `${API}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/actions/runs/${encodeURIComponent(String(runId))}/artifacts?per_page=100`,
    token,
  );
  const data = await response.json();
  if (!response.ok) {
    throw new Error(`could not inspect workflow artifacts (${response.status})`);
  }
  return (data.artifacts || []).find((artifact) => artifact.name === artifactName && !artifact.expired) || null;
}

async function dispatchCatalogWorkflow(token, payload, delivery, env) {
  await dispatchLibraryWorkflow(
    token,
    env.LIBRARY_WORKFLOW || 'catalog.yml',
    {
      source_repository: payload.repository.full_name,
      release_id: String(payload.release?.id || ''),
      release_tag: String(payload.release?.tag_name || ''),
      delivery_id: delivery,
    },
    env,
  );
}

async function dispatchManagedSigningWorkflow(token, payload, artifact, delivery, env) {
  await dispatchLibraryWorkflow(
    token,
    env.MANAGED_SIGNING_WORKFLOW || 'managed-signing.yml',
    {
      source_repository: payload.repository.full_name,
      source_run_id: String(payload.workflow_run?.id || ''),
      source_artifact_id: String(artifact.id || ''),
      delivery_id: delivery,
    },
    env,
  );
}

async function dispatchLibraryWorkflow(token, workflow, inputs, env) {
  const owner = env.LIBRARY_OWNER || 'garfbargle';
  const repo = env.LIBRARY_REPO || 'library';
  const ref = env.LIBRARY_REF || 'main';
  const response = await githubFetch(
    `${API}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/actions/workflows/${encodeURIComponent(workflow)}/dispatches`,
    token,
    {
      method: 'POST',
      body: JSON.stringify({ ref, inputs }),
    },
  );
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`workflow dispatch failed (${response.status}): ${detail.slice(0, 300)}`);
  }
}

function githubFetch(url, token, init = {}) {
  return fetch(url, {
    ...init,
    headers: {
      accept: 'application/vnd.github+json',
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      'user-agent': 'library-catalog-github-app',
      'x-github-api-version': API_VERSION,
      ...(init.headers || {}),
    },
  });
}

async function importPrivateKey(pem) {
  const normalized = pem.replace(/\\n/g, '\n').trim();
  const match = normalized.match(/-----BEGIN (RSA )?PRIVATE KEY-----([\s\S]+?)-----END (RSA )?PRIVATE KEY-----/);
  if (!match) throw new Error('GitHub App private key is not valid PEM');
  let der = base64ToBytes(match[2].replace(/\s+/g, ''));
  if (match[1]) der = wrapPkcs1AsPkcs8(der);
  return crypto.subtle.importKey(
    'pkcs8',
    der,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

function wrapPkcs1AsPkcs8(pkcs1) {
  const version = Uint8Array.from([0x02, 0x01, 0x00]);
  const rsaAlgorithm = Uint8Array.from([
    0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86,
    0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
  ]);
  const privateKey = derValue(0x04, pkcs1);
  return derValue(0x30, concatBytes(version, rsaAlgorithm, privateKey));
}

function derValue(tag, bytes) {
  return concatBytes(Uint8Array.from([tag]), derLength(bytes.length), bytes);
}

function derLength(length) {
  if (length < 0x80) return Uint8Array.from([length]);
  const bytes = [];
  for (let value = length; value > 0; value >>>= 8) bytes.unshift(value & 0xff);
  return Uint8Array.from([0x80 | bytes.length, ...bytes]);
}

function concatBytes(...arrays) {
  const size = arrays.reduce((sum, array) => sum + array.length, 0);
  const result = new Uint8Array(size);
  let offset = 0;
  for (const array of arrays) {
    result.set(array, offset);
    offset += array.length;
  }
  return result;
}

function base64ToBytes(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function base64UrlJson(value) {
  return base64UrlBytes(encoder.encode(JSON.stringify(value)));
}

function base64UrlBytes(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function toHex(bytes) {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function timingSafeEqual(left, right) {
  if (left.length !== right.length) return false;
  let mismatch = 0;
  for (let index = 0; index < left.length; index += 1) {
    mismatch |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return mismatch === 0;
}

function json(value, status) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}
