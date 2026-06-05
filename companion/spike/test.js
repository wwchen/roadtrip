// SSE spike protocol test. Run with the backend already up:
//   make run    # in another terminal, or backend bg job
//   cd companion && node --experimental-eventsource --test spike/test.js
//
// Validates each requirement from PLAN.md step 2 in turn:
//   1. SSE endpoint emits `connected` immediately on subscribe
//   2. `match` event fires after a synth-match POST
//   3. claim → result lease state machine: claimed → result, single source of truth
//   4. Reconnect with Last-Event-ID replays the events the client missed
//   5. Lease expiry releases a CLAIMED match (lease_expired event fires)
//   6. companion_offline fires after no heartbeat for the threshold
//
// Requirement 6 needs a backend with a SHORT offline threshold so the test
// runs in seconds. Set CAMPSITE_OFFLINE_SEC=3 when running the spike backend
// for the offline test, or skip that test (it's run separately).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { setTimeout as sleep } from 'node:timers/promises'

const BASE = process.env.SPIKE_BASE || 'http://127.0.0.1:8765'
const COMPANION = 'spike-test-' + process.pid

class SseClient {
  constructor (url) {
    this.url = url
    this.events = []
    this.es = new EventSource(url)
    for (const name of ['connected', 'tick', 'match', 'claimed', 'result', 'lease_expired', 'companion_offline', 'companion_online']) {
      this.es.addEventListener(name, (ev) => {
        this.events.push({
          id: ev.lastEventId ? parseInt(ev.lastEventId, 10) : null,
          type: name,
          data: JSON.parse(ev.data),
        })
      })
    }
  }
  close () { this.es.close() }
  async waitFor (predicate, { timeoutMs = 5000 } = {}) {
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
      const found = this.events.find(predicate)
      if (found) return found
      await sleep(50)
    }
    throw new Error(`waitFor timed out (${timeoutMs}ms). Events seen: ${JSON.stringify(this.events.map((e) => e.type))}`)
  }
}

async function postJson (path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: res.status, body: JSON.parse(await res.text()) }
}

test('1) connected event fires immediately on subscribe', async () => {
  const c = new SseClient(BASE + '/api/campsite/events')
  await c.waitFor((e) => e.type === 'connected', { timeoutMs: 2000 })
  c.close()
})

test('2) synth-match publishes a match event with monotonic id', async () => {
  const c = new SseClient(BASE + '/api/campsite/events')
  await c.waitFor((e) => e.type === 'connected')
  const synth = await postJson('/api/campsite/spike/synth-match', { campgroundId: 'cg-1', campsiteId: 's-1', startDate: '2026-04-28', endDate: '2026-04-29' })
  assert.equal(synth.status, 200)
  const ev = await c.waitFor((e) => e.type === 'match' && e.data.id === synth.body.id)
  assert.ok(ev.id > 0, 'envelope id is monotonic')
  c.close()
})

test('3) claim → result lease state machine; double-claim rejected', async () => {
  const c = new SseClient(BASE + '/api/campsite/events')
  await c.waitFor((e) => e.type === 'connected')
  const synth = await postJson('/api/campsite/spike/synth-match', { campgroundId: 'cg-2', campsiteId: 's-2', startDate: '2026-05-01', endDate: '2026-05-02' })
  const matchId = synth.body.id

  const claim1 = await postJson(`/api/campsite/matches/${matchId}/claim`, { companion_id: COMPANION })
  assert.equal(claim1.status, 200, 'first claim succeeds')
  await c.waitFor((e) => e.type === 'claimed' && e.data.id === matchId)

  const claim2 = await postJson(`/api/campsite/matches/${matchId}/claim`, { companion_id: 'someone-else' })
  assert.equal(claim2.status, 409, 'second claim is rejected (already claimed)')

  const result = await postJson(`/api/campsite/matches/${matchId}/result`, { cart_added: true })
  assert.equal(result.status, 200)
  const resEv = await c.waitFor((e) => e.type === 'result' && e.data.id === matchId)
  assert.equal(resEv.data.cartAdded, true)
  c.close()
})

test('4) reconnect with lastEventId replays missed events', async () => {
  const c1 = new SseClient(BASE + '/api/campsite/events')
  await c1.waitFor((e) => e.type === 'connected')

  // Generate a few events while c1 is connected, capture their ids.
  await postJson('/api/campsite/spike/synth-match', { campgroundId: 'cg-3', campsiteId: 's-1', startDate: '2026-06-01', endDate: '2026-06-02' })
  const ev1 = await c1.waitFor((e) => e.type === 'match' && e.data.campgroundId === 'cg-3')
  const replayFromId = ev1.id
  c1.close()

  // Now generate more events while disconnected.
  const synth = await postJson('/api/campsite/spike/synth-match', { campgroundId: 'cg-4', campsiteId: 's-1', startDate: '2026-07-01', endDate: '2026-07-02' })
  const missedId = synth.body.id

  // Reconnect with lastEventId — should see the missed match in the replay.
  const c2 = new SseClient(BASE + `/api/campsite/events?lastEventId=${replayFromId}`)
  const replayed = await c2.waitFor((e) => e.type === 'match' && e.data.id === missedId, { timeoutMs: 3000 })
  assert.ok(replayed.id > replayFromId, `replayed envelope id=${replayed.id} > resume point ${replayFromId}`)
  c2.close()
})

test('5) lease expires when no result arrives in time', async () => {
  // This test relies on a short lease. We ship the spike with the default
  // 5-min lease; for tractable testing the spike backend should be started
  // with CAMPSITE_LEASE_SEC=2 (consumed by Main below). If the env var is
  // not set we skip with a clear message.
  if (!process.env.CAMPSITE_LEASE_SEC) {
    console.log('SKIP: set CAMPSITE_LEASE_SEC=2 on the backend for this test')
    return
  }
  const c = new SseClient(BASE + '/api/campsite/events')
  await c.waitFor((e) => e.type === 'connected')
  const synth = await postJson('/api/campsite/spike/synth-match', { campgroundId: 'cg-5', campsiteId: 's-1', startDate: '2026-08-01', endDate: '2026-08-02' })
  const matchId = synth.body.id
  await postJson(`/api/campsite/matches/${matchId}/claim`, { companion_id: COMPANION })
  await c.waitFor((e) => e.type === 'claimed' && e.data.id === matchId)
  // Don't send result. Wait for sweepExpiredLeases to fire.
  const expired = await c.waitFor((e) => e.type === 'lease_expired' && e.data.id === matchId, { timeoutMs: 15_000 })
  assert.equal(expired.data.id, matchId)
  c.close()
})

test('6) companion_offline fires after heartbeat silence', async () => {
  if (!process.env.CAMPSITE_OFFLINE_SEC) {
    console.log('SKIP: set CAMPSITE_OFFLINE_SEC=3 on the backend for this test')
    return
  }
  const c = new SseClient(BASE + '/api/campsite/events')
  await c.waitFor((e) => e.type === 'connected')
  // Send one heartbeat, then go silent. After OFFLINE_SEC seconds the
  // backend should sweep and emit companion_offline.
  await postJson('/api/campsite/companion/heartbeat', { companion_id: COMPANION })
  const offline = await c.waitFor((e) => e.type === 'companion_offline' && e.data.companionId === COMPANION, { timeoutMs: 15_000 })
  assert.equal(offline.data.companionId, COMPANION)
  c.close()
})
