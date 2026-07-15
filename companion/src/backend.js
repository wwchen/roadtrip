// HTTP client for the Kotlin backend. The companion never touches Postgres;
// every state change goes through these endpoints.

const BASE = process.env.BACKEND_URL || 'http://127.0.0.1:8765'

async function postJson (path, body, options = {}) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: dispatchHeaders(),
    body: JSON.stringify(body),
    signal: options.signal,
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch {}
  return { status: res.status, body: text, json }
}

function dispatchHeaders () {
  const headers = { 'content-type': 'application/json' }
  const token = process.env.DISPATCH_COMPANION_TOKEN || process.env.COMPANION_DISPATCH_TOKEN
  if (token) headers.authorization = `Bearer ${token}`
  return headers
}

export async function claimDispatch ({
  kind,
  kinds = [],
  vendors,
  payloadVersions = [],
  waitSec = 30,
  leaseSec = 30,
  signal,
}) {
  const selector = {
    vendors,
    payload_versions: payloadVersions,
    wait_sec: waitSec,
    lease_sec: leaseSec,
  }
  if (kinds.length > 0) {
    selector.kinds = kinds
  } else if (kind) {
    selector.kind = kind
  }
  return postJson('/api/dispatches/claim', selector, { signal })
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
