// HTTP client for the Kotlin backend. The companion never touches Postgres;
// every state change goes through these endpoints.

const BASE = process.env.BACKEND_URL || 'http://127.0.0.1:8765'

async function postJson (path, body, options = {}) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: options.signal,
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch {}
  return { status: res.status, body: text, json }
}

async function getJson (path) {
  const res = await fetch(BASE + path)
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch {}
  return { status: res.status, body: text, json }
}

// Backend owns the recgov token lifecycle. Companion asks for a non-expired
// recaccount-shaped JSON every time it needs to inject auth into Playwright.
// Returns null when the backend has no token saved or the call fails.
export async function fetchFreshRecaccount () {
  try {
    const r = await getJson('/api/campsite/booking/session/fresh-token')
    if (r.status !== 200 || !r.json) return null
    return r.json
  } catch { return null }
}

export async function claimDispatch ({
  kind,
  vendors,
  payloadVersions = [],
  waitSec = 30,
  leaseSec = 30,
  signal,
}) {
  return postJson('/api/dispatches/claim', {
    kind,
    vendors,
    payload_versions: payloadVersions,
    wait_sec: waitSec,
    lease_sec: leaseSec,
  }, { signal })
}

export async function completeDispatch (dispatchId, leaseToken, result = {}) {
  return postJson(`/api/dispatches/${dispatchId}/complete`, {
    lease_token: leaseToken,
    result,
  })
}

export async function failDispatch (dispatchId, leaseToken, error, detail = null, result = {}) {
  return postJson(`/api/dispatches/${dispatchId}/fail`, {
    lease_token: leaseToken,
    error,
    detail,
    result,
  })
}

export function backendBase () { return BASE }
