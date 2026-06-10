// Companion main loop:
//   1. Subscribes to backend SSE /api/campsite/events for wakeup signals.
//   2. On any wakeup (match/result/lease_expired/companion_online), calls
//      backend's planner: GET /api/campsite/companion/work/next. If it returns a match,
//      the companion claims, ATCs, and reports the result. The DB is the
//      source of truth for ATC orchestration; SSE event payloads are ignored.
//   3. Strict serial: only one ATC at a time across the whole companion.
//   4. 30s safety-net interval covers companion restarts and dropped events.
//   5. Posts a heartbeat every 30s so the backend knows we're alive.
//
// Usage: node --experimental-eventsource src/index.js [--id=companion-A]

import { setTimeout as sleep } from 'node:timers/promises'
import { addToCart } from './cart.js'
import { claimMatch, reportResult, heartbeat, getNextWork, backendBase } from './backend.js'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/)
    return m ? [m[1], m[2] ?? true] : [a, true]
  })
)

const COMPANION_ID = args.id || process.env.COMPANION_ID || 'companion-A'
const HEARTBEAT_MS = parseInt(args['heartbeat-ms'] || '30000', 10)
const WORK_POLL_MS = parseInt(args['work-poll-ms'] || '30000', 10)

let lastId = 0
let stopRequested = false
let es
let busy = false // strict serial: one ATC at a time across the whole companion

function log (...xs) { console.log(new Date().toISOString(), '[' + COMPANION_ID + ']', ...xs) }

// Ask the backend "is there work for me?" and run it. The backend's
// /companion/work/next endpoint is the single source of truth for what's pickable —
// SSE events are just wakeup hints. Loops until the backend says null
// (nothing more to do) or a claim/ATC fails.
async function pickAndRunWork () {
  if (busy) return
  busy = true
  try {
    while (!stopRequested) {
      const r = await getNextWork()
      if (r.status !== 200) {
        log('work/next HTTP', r.status, r.body)
        return
      }
      const m = r.json?.match
      if (!m) return // nothing to do; back to sleep until next wakeup

      log('work/next picked match', m.id, 'alert', m.alertId)
      const claim = await claimMatch(m.id, COMPANION_ID)
      if (claim.status !== 200) {
        // Lost the race to another worker (or stale planner result). Loop and
        // ask the backend for the next pick — its NOT EXISTS subquery will
        // skip this alert if our claim winner is still in flight.
        log('claim', m.id, 'lost →', claim.status, claim.body)
        continue
      }
      log('claim', m.id, 'won; lease', claim.json?.leaseExpires)

      const matchForCart = {
        campground_id: m.campgroundId,
        campsite_id: m.campsiteId,
        first_date: m.firstDate,
        available_dates: m.availableDates || [],
        campsite_site: m.site || '',
      }

      let result
      try {
        result = await addToCart(matchForCart)
      } catch (e) {
        log('addToCart threw:', e.message)
        await reportResult(m.id, false)
        continue // backend will publish WorkMaybeAvailable; loop tries the next match
      }

      const ok = !!result?.ok
      const reported = await reportResult(m.id, ok)
      log('result', m.id, ok ? 'cart_added=true' : 'cart_added=false', '→', reported.status)

      // Backend keeps the rec.gov cart hold alive on its own 5-min PATCH loop —
      // close the Playwright page now that ATC is done.
      if (result?.page) await result.page.close().catch(() => {})

      // Loop continues — if there's another pickable match (e.g. ATC failed
      // here, or this was a different alert), the next /companion/work/next call
      // returns it. If not, we exit cleanly.
    }
  } finally {
    busy = false
  }
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

// Safety net for missed wakeups (companion restart, transient SSE drop).
// The actual planner runs on every wakeup; this just guarantees we don't
// stall indefinitely if every wakeup signal got lost.
async function workPollLoop () {
  while (!stopRequested) {
    await sleep(WORK_POLL_MS)
    pickAndRunWork().catch((e) => log('pickAndRunWork error', e.message))
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

  // Wakeup signals: any of these mean "the set of pickable matches may have
  // changed; re-query /companion/work/next." Match payload is ignored — backend decides.
  const wakeupEvents = new Set(['match', 'result', 'lease_expired', 'companion_online'])
  const allEvents = ['connected', 'tick', 'match', 'claimed', 'result', 'lease_expired', 'companion_offline', 'companion_online']
  for (const name of allEvents) {
    es.addEventListener(name, (ev) => {
      if (ev.lastEventId) lastId = parseInt(ev.lastEventId, 10) || lastId
      if (name !== 'tick') log('event', name, 'id=' + (ev.lastEventId || '-'), ev.data)
      if (wakeupEvents.has(name)) {
        pickAndRunWork().catch((e) => log('pickAndRunWork error', e.message))
      }
    })
  }
}

async function main () {
  log('config:', { backend: backendBase(), companionId: COMPANION_ID })
  subscribe()
  heartbeatLoop().catch((e) => log('heartbeat loop crashed', e))
  workPollLoop().catch((e) => log('work poll loop crashed', e))
  // Kick off an initial pick in case there's pending work from before we connected.
  pickAndRunWork().catch((e) => log('pickAndRunWork error', e.message))

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
