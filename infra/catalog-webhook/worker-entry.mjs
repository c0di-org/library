import worker, { verifyWebhookSignature } from './worker.mjs';

export function isCatalogRelease(payload, env) {
  const repository = payload?.repository?.full_name;
  const tag = payload?.release?.tag_name;
  if (!repository || !tag) return false;

  const libraryFullName = `${env.LIBRARY_OWNER || 'garfbargle'}/${env.LIBRARY_REPO || 'library'}`;
  return repository.toLowerCase() === libraryFullName.toLowerCase()
    && (tag === 'catalog' || tag.startsWith('catalog-'));
}

export default {
  async fetch(request, env) {
    if (
      request.method === 'POST'
      && request.headers.get('x-github-event') === 'release'
      && env.GITHUB_WEBHOOK_SECRET
    ) {
      const body = await request.clone().text();
      let payload = null;
      try {
        payload = JSON.parse(body);
      } catch {
        // Let the main handler return its normal invalid-JSON response.
      }

      if (payload && isCatalogRelease(payload, env)) {
        const signature = request.headers.get('x-hub-signature-256');
        if (await verifyWebhookSignature(env.GITHUB_WEBHOOK_SECRET, body, signature)) {
          return new Response(
            JSON.stringify({ ok: true, ignored: 'catalog-release' }),
            {
              status: 202,
              headers: { 'content-type': 'application/json; charset=utf-8' },
            },
          );
        }
      }
    }

    return worker.fetch(request, env);
  },
};
