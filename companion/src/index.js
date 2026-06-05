// Companion main loop:
//   1. Subscribes to backend SSE /api/campsite/events (auto-reconnect with Last-Event-ID).
//   2. On `match` events, fetches the match, attempts to claim, runs Playwright
//      ATC, and reports the result. On success, kicks off a 5-min interval that
//      PATCHes the cart-expiry endpoint to keep the hold alive.
//   3. Posts a heartbeat every 30s so the backend knows we're alive.
//
// Usage: node --experimental-eventsource src/index.js [--id=companion-A]

import { setTimeout as sleep } from 'node:timers/promises'
import { seedFromEnv } from './store.js'
import { addToCart, extendCartHold } from './cart.js'
import { claimMatch, reportResult, heartbeat, getMatch, backendBase } from './backend.js'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/)
    return m ? [m[1], m[2] ?? true] : [a, true]
  })
)

const COMPANION_ID = args.id || process.env.COMPANION_ID || 'companion-A'
const HEARTBEAT_MS = parseInt(args['heartbeat-ms'] || '30000', 10)
const CART_EXTEND_MS = 5 * 60 * 1000

let lastId = 0
let stopRequested = false
let es

const cartHolds = new Map() // matchId → { page, intervalId }

function log (...xs) { console.log(new Date().toISOString(), '[' + COMPANION_ID + ']', ...xs) }

async function handleMatch (envelopeData) {
  let summary
  try { summary = JSON.parse(envelopeData) } catch (e) { return log('match parse error', e.message) }
  const matchId = summary.id
  if (!matchId) return log('match envelope missing id:', envelopeData)

  log('match', matchId, '— attempting claim')
  const claim = await claimMatch(matchId, COMPANION_ID)
  if (claim.status !== 200) {
    log('claim', matchId, 'lost →', claim.status, claim.body)
    return
  }
  log('claim', matchId, 'won; lease', claim.json?.leaseExpires)

  const fetched = await getMatch(matchId)
  if (fetched.status !== 200 || !fetched.json) {
    log('getMatch failed:', fetched.status, fetched.body)
    await reportResult(matchId, false)
    return
  }
  const m = fetched.json
  const matchForCart = {
    campground_id: m.campgroundId,
    campsite_id: m.campsiteId,
    first_date: m.firstDate,
    available_dates: m.availableDates || [],
    campsite_site: m.site || '',
  }

  let result
  try { result = await addToCart(matchForCart) } catch (e) {
    log('addToCart threw:', e.message)
    await reportResult(matchId, false)
    return
  }

  const ok = !!result?.ok
  const r = await reportResult(matchId, ok)
  log('result', matchId, ok ? 'cart_added=true' : 'cart_added=false', '→', r.status)

  if (ok && result.page) startCartHold(matchId, result.page)
  else if (result?.page) await result.page.close().catch(() => {})
}

function startCartHold (matchId, page) {
  if (cartHolds.has(matchId)) return
  log('cart hold extender started for match', matchId, `(every ${CART_EXTEND_MS / 1000}s)`)
  const intervalId = setInterval(async () => {
    try {
      await extendCartHold(page)
      log('cart hold extended for match', matchId)
    } catch (e) {
      log('extendCartHold error for match', matchId, e.message)
      stopCartHold(matchId)
    }
  }, CART_EXTEND_MS)
  cartHolds.set(matchId, { page, intervalId })
}

function stopCartHold (matchId) {
  const h = cartHolds.get(matchId)
  if (!h) return
  clearInterval(h.intervalId)
  h.page.close().catch(() => {})
  cartHolds.delete(matchId)
  log('cart hold extender stopped for match', matchId)
}

async function heartbeatLoop () {
  while (!stopRequested) {
    try {
      const r = await heartbeat(COMPANION_ID)
      if (r.status !== 200) log('heartbeat HTTP', r.status, r.body)
    } catch (e) { log('heartbeat error', e.message) }
    await sleep(HEARTBEAT_MS)
  }
}

function subscribe () {
  const url = lastId > 0
    ? `${backendBase()}/api/campsite/events?lastEventId=${lastId}`
    : `${backendBase()}/api/campsite/events`
  log('SSE connecting', url)
  es = new EventSource(url)

  es.addEventListener('open', () => log('SSE open'))
  es.addEventListener('error', (e) => {
    const parts = []
    if (e?.message) parts.push(e.message)
    if (e?.code) parts.push('code=' + e.code)
    if (e?.status) parts.push('status=' + e.status)
    if (e?.error) parts.push('error=' + (e.error.message || e.error.code || e.error))
    if (es?.readyState !== undefined) parts.push('readyState=' + es.readyState)
    log('SSE error', parts.length ? parts.join(' ') : '(no detail)', e)
  })

  for (const name of ['connected', 'tick', 'match', 'claimed', 'result', 'lease_expired', 'companion_offline', 'companion_online']) {
    es.addEventListener(name, (ev) => {
      if (ev.lastEventId) lastId = parseInt(ev.lastEventId, 10) || lastId
      if (name !== 'tick') log('event', name, 'id=' + (ev.lastEventId || '-'), ev.data)
      if (name === 'match') handleMatch(ev.data).catch((e) => log('handleMatch error', e.message))
    })
  }
}

async function main () {
  seedFromEnv()
  log('config:', { backend: backendBase(), companionId: COMPANION_ID })
  subscribe()
  heartbeatLoop().catch((e) => log('heartbeat loop crashed', e))

  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => {
      stopRequested = true
      log('shutting down')
      for (const id of cartHolds.keys()) stopCartHold(id)
      es?.close()
      setTimeout(() => process.exit(0), 100)
    })
  }
}

main()
