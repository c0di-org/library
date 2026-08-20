import { createPrivateKey, sign } from 'node:crypto';

const API = 'https://api.github.com';
const API_VERSION = '2022-11-28';

const query = (process.env.DELIVERY_QUERY || '').trim();
const since = process.env.DELIVERY_SINCE || '2026-08-20T18:00:00Z';
const until = process.env.DELIVERY_UNTIL || '2026-08-20T22:00:00Z';
const appId = process.env.GITHUB_APP_ID || process.env.LIBRARY_CATALOG_APP_CLIENT_ID;
const privateKey = process.env.GITHUB_APP_PRIVATE_KEY || process.env.LIBRARY_CATALOG_APP_PRIVATE_KEY;

if (!privateKey) {
  console.error('Missing GitHub App private key');
  process.exit(1);
}

const jwt = await mintWorkingJwt(privateKey, [appId, process.env.LIBRARY_CATALOG_APP_CLIENT_ID]);
const deliveries = await listDeliveries(jwt, since, until);
const interesting = deliveries.filter((delivery) => {
  if (delivery.event === 'workflow_run' || delivery.event === 'release' || delivery.event === 'ping') return true;
  return !query;
});
const details = [];
for (const delivery of interesting) {
  try {
    details.push(await getDelivery(jwt, delivery.id));
  } catch (error) {
    details.push({
      ...delivery,
      request: { payload: { _error: error instanceof Error ? error.message : String(error) } },
    });
  }
}
const matches = details.filter((delivery) => matchesQuery(delivery, query));

const summary = {
  query: query || null,
  since,
  until,
  scanned: deliveries.length,
  detailed: details.length,
  matched: matches.length,
  statusCounts: countBy(deliveries, (delivery) => String(delivery.status_code)),
  eventCounts: countBy(deliveries, (delivery) => `${delivery.event}:${delivery.action || ''}`),
  workflowRuns: deliveries.filter((delivery) => delivery.event === 'workflow_run').map(summarizeDelivery),
  matches: matches.map(summarizeDetailedDelivery),
};

console.log(JSON.stringify(summary, null, 2));

async function mintWorkingJwt(pem, issuers) {
  const uniqueIssuers = [...new Set(issuers.filter(Boolean).map((value) => String(value)))];
  const errors = [];
  for (const issuer of uniqueIssuers) {
    const token = createAppJwt(issuer, pem);
    const response = await githubFetch(`${API}/app`, token);
    if (response.ok) return token;
    const body = await response.text();
    errors.push(`${issuer}: ${response.status} ${body.slice(0, 180)}`);
  }
  throw new Error(`could not authenticate as GitHub App (${errors.join('; ') || 'no issuer configured'})`);
}

function createAppJwt(issuer, pem) {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({ iat: now - 60, exp: now + 9 * 60, iss: String(issuer) })).toString('base64url');
  const unsigned = `${header}.${payload}`;
  const key = createPrivateKey(String(pem).replace(/\\n/g, '\n'));
  const signature = sign('RSA-SHA256', Buffer.from(unsigned), key).toString('base64url');
  return `${unsigned}.${signature}`;
}

async function listDeliveries(token, sinceTime, untilTime) {
  const sinceMs = Date.parse(sinceTime);
  const untilMs = Date.parse(untilTime);
  const results = [];
  let cursor = null;
  for (let page = 0; page < 10; page += 1) {
    const url = new URL(`${API}/app/hook/deliveries`);
    url.searchParams.set('per_page', '100');
    if (cursor) url.searchParams.set('cursor', cursor);
    const response = await githubFetch(url, token);
    const batch = await parseGithubJson(await response.text());
    if (!response.ok || !Array.isArray(batch)) {
      throw new Error(`could not list webhook deliveries (${response.status}): ${JSON.stringify(batch).slice(0, 300)}`);
    }
    for (const delivery of batch) {
      const deliveredAt = Date.parse(delivery.delivered_at);
      if (deliveredAt > untilMs) continue;
      if (deliveredAt < sinceMs) return results;
      results.push(delivery);
    }
    if (batch.length === 0) return results;
    cursor = parseCursor(response.headers.get('link'));
    if (!cursor) return results;
  }
  return results;
}

async function getDelivery(token, id) {
  const response = await githubFetch(`${API}/app/hook/deliveries/${id}`, token);
  const data = await parseGithubJson(await response.text());
  if (!response.ok) {
    throw new Error(`could not read webhook delivery ${id} (${response.status})`);
  }
  return data;
}

function parseGithubJson(text) {
  return JSON.parse(text.replace(/:(0|[1-9][0-9]{15,})([,\]}])/g, ':"$1"$2'));
}

function githubFetch(url, token) {
  return fetch(url, {
    headers: {
      accept: 'application/vnd.github+json',
      authorization: `Bearer ${token}`,
      'user-agent': 'library-catalog-github-app',
      'x-github-api-version': API_VERSION,
    },
  });
}

function parseCursor(link) {
  const match = String(link || '').match(/<[^>]*[?&]cursor=([^&>]+)[^>]*>;\s*rel="next"/);
  return match ? decodeURIComponent(match[1]) : null;
}

function matchesQuery(delivery, needle) {
  if (!needle) return true;
  const requestPayload = delivery.request?.payload || {};
  const haystack = JSON.stringify({
    id: delivery.id,
    guid: delivery.guid,
    event: delivery.event,
    action: delivery.action,
    status: delivery.status,
    status_code: delivery.status_code,
    repository: requestPayload.repository?.full_name,
    runId: requestPayload.workflow_run?.id,
    headSha: requestPayload.workflow_run?.head_sha,
    tag: requestPayload.release?.tag_name,
  }).toLowerCase();
  return haystack.includes(String(needle).toLowerCase());
}

function summarizeDelivery(delivery) {
  return {
    id: delivery.id,
    guid: delivery.guid,
    delivered_at: delivery.delivered_at,
    event: delivery.event,
    action: delivery.action,
    status: delivery.status,
    status_code: delivery.status_code,
    duration: delivery.duration,
    redelivery: delivery.redelivery,
    installation_id: delivery.installation_id,
    repository_id: delivery.repository_id,
  };
}

function summarizeDetailedDelivery(delivery) {
  const requestPayload = delivery.request?.payload || {};
  const responsePayload = decodeMaybeJson(delivery.response?.payload);
  return {
    ...summarizeDelivery(delivery),
    repository: requestPayload.repository?.full_name || null,
    owner: requestPayload.repository?.owner?.login || null,
    runId: requestPayload.workflow_run?.id || null,
    runEvent: requestPayload.workflow_run?.event || null,
    conclusion: requestPayload.workflow_run?.conclusion || null,
    headBranch: requestPayload.workflow_run?.head_branch || null,
    headSha: requestPayload.workflow_run?.head_sha || null,
    tag: requestPayload.release?.tag_name || null,
    response: responsePayload,
  };
}

function decodeMaybeJson(value) {
  if (value == null || value === '') return null;
  if (typeof value === 'object') return value;
  try {
    return JSON.parse(value);
  } catch {
    return String(value).slice(0, 500);
  }
}

function countBy(items, fn) {
  const counts = {};
  for (const item of items) {
    const key = fn(item) || 'unknown';
    counts[key] = (counts[key] || 0) + 1;
  }
  return counts;
}
