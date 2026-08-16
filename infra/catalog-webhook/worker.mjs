const API = 'https://api.github.com';
const API_VERSION = '2022-11-28';
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
    if (event !== 'release') {
      return json({ ok: true, ignored: `event:${event || 'unknown'}` }, 202);
    }

    let payload;
    try {
      payload = JSON.parse(body);
    } catch {
      return json({ error: 'invalid JSON payload' }, 400);
    }

    const decision = shouldDispatchRelease(payload, env);
    if (!decision.dispatch) {
      return json({ ok: true, ignored: decision.reason }, 202);
    }

    try {
      const appJwt = await createAppJwt(env.GITHUB_APP_ID, env.GITHUB_APP_PRIVATE_KEY);
      const installationId = await findLibraryInstallation(appJwt, env);
      const installationToken = await createLibraryInstallationToken(appJwt, installationId, env);
      await dispatchCatalogWorkflow(installationToken, payload, delivery, env);
      return json({
        ok: true,
        dispatched: true,
        repository: payload.repository.full_name,
        tag: payload.release?.tag_name || null,
        delivery,
      }, 202);
    } catch (error) {
      console.error('catalog dispatch failed', error);
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
      permissions: { actions: 'write' },
    }),
  });
  const data = await response.json();
  if (!response.ok || !data.token) {
    throw new Error(`could not mint Library installation token (${response.status})`);
  }
  return data.token;
}

async function dispatchCatalogWorkflow(token, payload, delivery, env) {
  const owner = env.LIBRARY_OWNER || 'garfbargle';
  const repo = env.LIBRARY_REPO || 'library';
  const workflow = env.LIBRARY_WORKFLOW || 'catalog.yml';
  const ref = env.LIBRARY_REF || 'main';
  const response = await githubFetch(
    `${API}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/actions/workflows/${encodeURIComponent(workflow)}/dispatches`,
    token,
    {
      method: 'POST',
      body: JSON.stringify({
        ref,
        inputs: {
          source_repository: payload.repository.full_name,
          release_id: String(payload.release?.id || ''),
          release_tag: String(payload.release?.tag_name || ''),
          delivery_id: delivery,
        },
      }),
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
