// HTTP client for the Kotlin backend. The companion never touches Postgres;
// every state change goes through these endpoints.

const BASE = process.env.BACKEND_URL || 'http://127.0.0.1:8765'

async function postJson (path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
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

export async function claimMatch (matchId, companionId) {
  return postJson(`/api/campsite/matches/${matchId}/claim`, { companion_id: companionId })
}

export async function reportResult (matchId, cartAdded) {
  return postJson(`/api/campsite/matches/${matchId}/result`, { cart_added: cartAdded })
}

export async function heartbeat (companionId) {
  return postJson('/api/campsite/companion/heartbeat', { companion_id: companionId })
}

export async function getMatch (matchId) {
  return getJson(`/api/campsite/matches/${matchId}`)
}

// Backend owns the recgov token lifecycle as of RFC 0001 / PR 3. Companion
// asks for a non-expired recaccount-shaped JSON every time it needs to inject
// auth into a Playwright session. Returns null when the backend has no token
// saved (paste hasn't happened) or the call fails — companion fails closed.
export async function fetchFreshRecaccount () {
  try {
    const r = await getJson('/api/campsite/recgov/fresh-token')
    if (r.status !== 200 || !r.json) return null
    return r.json
  } catch { return null }
}

export function backendBase () { return BASE }
