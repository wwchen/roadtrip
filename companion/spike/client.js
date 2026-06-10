// SSE spike client. Subscribes to backend /api/campsite/events, logs every event with
// its id+type+data, posts heartbeats every 30s while alive, auto-reconnects
// with Last-Event-ID on drop, and on a `match` event auto-claims after a
// configurable delay (so claim races vs lease expiry can be tested).
//
// Usage: node --experimental-eventsource spike/client.js [--id=companion-A] \
//          [--auto-claim] [--claim-delay-ms=0] [--no-result] [--cart-fail]

import { setTimeout as sleep } from 'node:timers/promises'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/)
    return m ? [m[1], m[2] ?? true] : [a, true]
  })
)

const BASE = process.env.SPIKE_BASE || 'http://127.0.0.1:8765'
const COMPANION_ID = args.id || 'companion-A'
const AUTO_CLAIM = !!args['auto-claim']
const CLAIM_DELAY = parseInt(args['claim-delay-ms'] || '0', 10)
const SEND_RESULT = !args['no-result']
const CART_RESULT = !args['cart-fail']
const HEARTBEAT_MS = parseInt(args['heartbeat-ms'] || '30000', 10)

let lastId = 0
let stopRequested = false
let es

function log (...xs) {
  console.log(new Date().toISOString(), '[' + COMPANION_ID + ']', ...xs)
}

async function postJson (path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: res.status, body: await res.text() }
}

async function claim (matchId) {
  if (CLAIM_DELAY > 0) await sleep(CLAIM_DELAY)
  const r = await postJson(`/api/campsite/companion/matches/${matchId}/claim`, { companion_id: COMPANION_ID })
  log('claim', matchId, '→', r.status, r.body)
  if (r.status !== 200) return false
  if (!SEND_RESULT) {
    log('NO_RESULT mode: leaving match claimed; lease will expire')
    return true
  }
  // tiny delay to simulate ATC work
  await sleep(500)
  const r2 = await postJson(`/api/campsite/companion/matches/${matchId}/result`, { cart_added: CART_RESULT })
  log('result', matchId, '→', r2.status, r2.body)
  return true
}

async function heartbeatLoop () {
  while (!stopRequested) {
    try {
      const r = await postJson('/api/campsite/companion/heartbeat', { companion_id: COMPANION_ID })
      if (r.status !== 200) log('heartbeat HTTP', r.status, r.body)
    } catch (e) {
      log('heartbeat error', e.message)
    }
    await sleep(HEARTBEAT_MS)
  }
}

function subscribe () {
  // EventSource only adds Last-Event-ID after a server-disconnect reconnect.
  // For an initial reconnect from a previous process death, set it ourselves
  // by passing the header on a separate fetch first — but native EventSource
  // doesn't expose headers. So we emulate by passing it as a query param the
  // server falls back to.
  const url = lastId > 0 ? `${BASE}/api/campsite/events?lastEventId=${lastId}` : `${BASE}/api/campsite/events`
  log('connecting', url)
  es = new EventSource(url)

  es.addEventListener('open', () => log('open'))
  es.addEventListener('error', (e) => log('error', e?.message || '(no message)'))

  // Generic envelope handler: capture every named event.
  for (const name of ['connected', 'tick', 'match', 'claimed', 'result', 'lease_expired', 'companion_offline', 'companion_online']) {
    es.addEventListener(name, (ev) => {
      if (ev.lastEventId) lastId = parseInt(ev.lastEventId, 10) || lastId
      log('event', name, 'id=' + (ev.lastEventId || '-'), ev.data)
      if (name === 'match' && AUTO_CLAIM) {
        try {
          const m = JSON.parse(ev.data)
          claim(m.id).catch((err) => log('claim error', err.message))
        } catch (err) {
          log('match parse error', err.message)
        }
      }
    })
  }
}

async function main () {
  log('config:', { base: BASE, autoClaim: AUTO_CLAIM, claimDelay: CLAIM_DELAY, sendResult: SEND_RESULT, cartResult: CART_RESULT })
  subscribe()
  heartbeatLoop().catch((e) => log('heartbeat loop crashed', e))

  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => {
      stopRequested = true
      log('shutting down')
      es?.close()
      setTimeout(() => process.exit(0), 100)
    })
  }
}

main()
