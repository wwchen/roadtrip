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

export async function getCompanionRecgov () {
  return getJson('/api/campsite/companion/recgov')
}

export function backendBase () { return BASE }
