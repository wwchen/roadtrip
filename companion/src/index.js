// Companion main loop:
//   1. Opens a backend long-poll for dispatch work.
//   2. Claims dispatches through /api/dispatches/claim.
//   3. Runs test dispatches by simulation flag and real ATC dispatches through
//      Playwright, then reports complete/fail to the backend.
//   4. Strict serial: one dispatch at a time across the whole companion.
//
// Usage: npm start -- [--id=companion-A]

import { setTimeout as sleep } from 'node:timers/promises'
import { addToCart } from './cart.js'
import { claimDispatch, completeDispatch, failDispatch, backendBase } from './backend.js'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/)
    return m ? [m[1], m[2] ?? true] : [a, true]
  })
)

const COMPANION_ID = args.id || process.env.COMPANION_ID || 'companion-A'
const CLAIM_WAIT_SEC = intArg('dispatch-wait-sec', 'DISPATCH_WAIT_SEC', 30)
const LEASE_SEC = intArg('dispatch-lease-sec', 'DISPATCH_LEASE_SEC', 30)
const IDLE_SLEEP_MS = intArg('idle-sleep-ms', 'DISPATCH_IDLE_SLEEP_MS', 250)
const CLAIM_ERROR_SLEEP_MS = intArg('claim-error-sleep-ms', 'DISPATCH_CLAIM_ERROR_SLEEP_MS', 1000)
const DISPATCH_KINDS = csvArg('dispatch-kinds', 'DISPATCH_KINDS', 'test,atc')
const DISPATCH_VENDORS = csvArg('dispatch-vendors', 'DISPATCH_VENDORS', 'recgov')
const PAYLOAD_VERSIONS = csvArg('dispatch-payload-versions', 'DISPATCH_PAYLOAD_VERSIONS', '')
const SIMULATE_SUCCESS = 'success'
const SIMULATE_FAILURE = 'failure'

let stopRequested = false
let busy = false
let activeClaims = []

function log (...xs) { console.log(new Date().toISOString(), '[' + COMPANION_ID + ']', ...xs) }

function intArg (argName, envName, fallback) {
  const raw = args[argName] ?? process.env[envName]
  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) ? parsed : fallback
}

function csvArg (argName, envName, fallback) {
  const raw = String(args[argName] ?? process.env[envName] ?? fallback)
  return raw.split(',').map((v) => v.trim()).filter(Boolean)
}

function abortActiveClaims () {
  for (const controller of activeClaims) controller.abort()
  activeClaims = []
}

function errorDetail (error) {
  const parts = [error.message]
  if (error.cause?.code) parts.push(error.cause.code)
  if (error.cause?.message) parts.push(error.cause.message)
  return parts.filter(Boolean).join(' / ')
}

function responseDetail (response) {
  if (!response?.body) return `http=${response?.status ?? 'unknown'}`
  return `http=${response.status} body=${response.body.slice(0, 300)}`
}

function dispatchSummary (dispatch) {
  const payload = dispatch.payload || {}
  const openings = payload.openings || []
  const first = openings[0] || {}
  const fields = [
    `id=${dispatch.id}`,
    `kind=${dispatch.kind}`,
    `vendor=${dispatch.vendor}`,
    `payload=${dispatch.payload_version}`,
  ]
  if (payload.simulate_result) fields.push(`simulate=${payload.simulate_result}`)
  if (payload.watch_id !== undefined) fields.push(`watch=${payload.watch_id}`)
  if (payload.start_date || payload.end_date) fields.push(`window=${payload.start_date ?? '?'}..${payload.end_date ?? '?'}`)
  fields.push(`openings=${openings.length}`)
  if (first.label) fields.push(`first_site="${first.label}"`)
  if (first.date) fields.push(`first_date=${first.date}`)
  if (first.booking_url) fields.push(`booking_url=${first.booking_url}`)
  return fields.join(' ')
}

async function claimAnyDispatch () {
  const controller = new AbortController()
  activeClaims = [controller]
  try {
    const response = await claimDispatch({
      kinds: DISPATCH_KINDS,
      vendors: DISPATCH_VENDORS,
      payloadVersions: PAYLOAD_VERSIONS,
      waitSec: CLAIM_WAIT_SEC,
      leaseSec: LEASE_SEC,
      signal: controller.signal,
    })
    if (response.status === 204) return null
    if (response.status !== 200) {
      log('dispatch claim HTTP', DISPATCH_KINDS.join(','), response.status, response.body)
      return null
    }
    const dispatch = response.json?.dispatch
    if (!dispatch) {
      log('dispatch claim missing dispatch body', DISPATCH_KINDS.join(','), response.body)
      return null
    }
    return dispatch
  } catch (error) {
    if (error.name !== 'AbortError') log('dispatch claim error', errorDetail(error))
    return null
  } finally {
    activeClaims = []
  }
}

async function dispatchLoop () {
  while (!stopRequested) {
    if (busy) {
      await sleep(IDLE_SLEEP_MS)
      continue
    }
    const dispatch = await claimAnyDispatch()
    if (!dispatch) {
      await sleep(CLAIM_ERROR_SLEEP_MS)
      continue
    }
    busy = true
    try {
      await runDispatch(dispatch)
    } finally {
      busy = false
    }
  }
}

async function runDispatch (dispatch) {
  log('dispatch claimed', dispatchSummary(dispatch))
  if (dispatch.kind === 'test') {
    await runTestDispatch(dispatch)
    return
  }
  if (dispatch.kind === 'atc') {
    await runAtcDispatch(dispatch)
    return
  }
  const detail = `unsupported dispatch kind ${dispatch.kind}`
  log(detail)
  await failDispatch(dispatch.id, dispatch.lease_token, 'unsupported_kind', detail)
}

async function runTestDispatch (dispatch) {
  const simulateResult = dispatch.payload?.simulate_result
  log('test dispatch simulate_result', dispatch.id, simulateResult ?? '(missing)')
  if (simulateResult === SIMULATE_SUCCESS) {
    const reported = await completeDispatch(dispatch.id, dispatch.lease_token, {
      companion_id: COMPANION_ID,
      simulated: true,
      simulate_result: simulateResult,
    })
    log('test dispatch complete', dispatch.id, responseDetail(reported))
    return
  }
  if (simulateResult === SIMULATE_FAILURE) {
    const reported = await failDispatch(
      dispatch.id,
      dispatch.lease_token,
      'simulated_failure',
      'test dispatch requested failure',
      {
        companion_id: COMPANION_ID,
        simulated: true,
        simulate_result: simulateResult,
      }
    )
    log('test dispatch fail', dispatch.id, responseDetail(reported))
    return
  }
  const reported = await failDispatch(
    dispatch.id,
    dispatch.lease_token,
    'invalid_simulate_result',
    `unsupported simulate_result ${simulateResult ?? '(missing)'}`,
    { companion_id: COMPANION_ID, simulated: true }
  )
  log('test dispatch invalid simulate_result', dispatch.id, responseDetail(reported))
}

async function runAtcDispatch (dispatch) {
  let result
  const match = dispatchToCartMatch(dispatch)
  log('atc dispatch start', dispatch.id, `site="${match.campsite_site}"`, `date=${match.first_date}`, `url=${match.booking_url ?? '(derived)'}`)
  try {
    result = await addToCart(match)
  } catch (e) {
    log('addToCart threw:', e.message)
    const reported = await failDispatch(dispatch.id, dispatch.lease_token, 'add_to_cart_exception', e.message, { companion_id: COMPANION_ID })
    log('atc dispatch fail', dispatch.id, responseDetail(reported))
    return
  }

  const ok = !!result?.ok
  const reported = ok
    ? await completeDispatch(dispatch.id, dispatch.lease_token, { companion_id: COMPANION_ID, cart_added: true })
    : await failDispatch(dispatch.id, dispatch.lease_token, 'cart_not_added', 'cart automation did not confirm a cart hold', {
        companion_id: COMPANION_ID,
        cart_added: false,
      })
  log('atc dispatch result', dispatch.id, ok ? 'complete' : 'fail', responseDetail(reported))
  if (result?.page) await result.page.close().catch(() => {})
}

function dispatchToCartMatch (dispatch) {
  const payload = dispatch.payload || {}
  const opening = payload.openings?.[0] || {}
  const dates = [...new Set((payload.openings || []).map((o) => o.date).filter(Boolean))]
  const firstDate = opening.date || payload.start_date
  return {
    booking_url: opening.booking_url,
    campground_id: opening.campground_id,
    campsite_id: opening.campsite_id,
    first_date: firstDate,
    checkout_date: payload.end_date,
    available_dates: dates.length ? dates : (firstDate ? [firstDate] : []),
    campsite_site: opening.label || '',
  }
}

async function main () {
  log('config:', {
    backend: backendBase(),
    companionId: COMPANION_ID,
    dispatchKinds: DISPATCH_KINDS,
    dispatchVendors: DISPATCH_VENDORS,
    payloadVersions: PAYLOAD_VERSIONS,
    claimWaitSec: CLAIM_WAIT_SEC,
    leaseSec: LEASE_SEC,
  })

  dispatchLoop().catch((e) => log('dispatch loop crashed', e.message))

  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => {
      stopRequested = true
      abortActiveClaims()
      log('shutting down')
      setTimeout(() => process.exit(0), 100)
    })
  }
}

main()
