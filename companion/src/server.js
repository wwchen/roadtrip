// HTTP executor for backend-owned one-shot companion work.
// The backend posts an ATC payload here; this process owns the browser profile
// and returns the same JSON result as the recgov:atc CLI.

import http from 'node:http'
import { pathToFileURL } from 'node:url'
import {
  recgovAuthenticationFailure,
  testChromium,
} from './cart.js'
import { getRecgovSessionStatus } from './recgovSession.js'
import { runAtcOnce } from './runAtcOnce.js'

const DEFAULT_HOST = '0.0.0.0'
const DEFAULT_PORT = 8770
const MAX_BODY_BYTES = 64 * 1024
const HTTP_OK = 200
const HTTP_BAD_REQUEST = 400
const HTTP_CONFLICT = 409
const HTTP_PAYLOAD_TOO_LARGE = 413
const HTTP_UNPROCESSABLE_ENTITY = 422
const HTTP_INTERNAL_ERROR = 500
const EXIT_SUCCESS = 0
const EXIT_USAGE = 2
const LOG_DETAIL_MAX_CHARS = 160
const AUTH_CHECK_EXCEPTION_ERROR = 'recgov_auth_check_exception'
const AUTH_CHECK_EXCEPTION_ACTION =
  'Check the companion startup logs, then run make recgov-login or set RECGOV_EMAIL and RECGOV_PASSWORD before restarting.'

const HOST = process.env.COMPANION_HOST || DEFAULT_HOST
const PORT = Number.parseInt(process.env.COMPANION_PORT || String(DEFAULT_PORT), 10)
const COMPANION_ID = process.env.COMPANION_ID || 'recgov-companion'

let busy = false
let recgovAuthStatus = { state: 'unchecked' }
let startupAuthCheck = null

function log (...items) {
  console.log(new Date().toISOString(), `[${COMPANION_ID}]`, ...items)
}

function jsonResponse (res, status, body) {
  const rendered = JSON.stringify(body)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(rendered),
  })
  res.end(rendered)
}

async function readBody (req) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > MAX_BODY_BYTES) {
      throw Object.assign(new Error('request body too large'), { status: HTTP_PAYLOAD_TOO_LARGE })
    }
    chunks.push(chunk)
  }
  return Buffer.concat(chunks).toString('utf8')
}

function payloadSummary (raw) {
  try {
    const payload = JSON.parse(raw)
    const openings = payload?.payload?.openings || payload?.openings || []
    const first = openings[0] || payload
    return [
      `watch=${payload?.payload?.watch_id ?? payload?.watch_id ?? '?'}`,
      `start=${payload?.payload?.start_date ?? payload?.start_date ?? first?.date ?? '?'}`,
      `end=${payload?.payload?.end_date ?? payload?.end_date ?? '?'}`,
      `site="${first?.label ?? first?.campsite_site ?? first?.vendor_id ?? '?'}"`,
    ].join(' ')
  } catch {
    return 'invalid-json'
  }
}

export async function runStartupAuthCheck ({
  testChromiumFn = testChromium,
  authFailureFn = recgovAuthenticationFailure,
  logger = log,
} = {}) {
  recgovAuthStatus = {
    state: 'checking',
    checked_at: new Date().toISOString(),
  }
  logger('recgov auth startup check start')

  try {
    const result = await testChromiumFn()
    if (result?.loggedIn === true) {
      recgovAuthStatus = {
        state: 'ok',
        logged_in: true,
        checked_at: new Date().toISOString(),
      }
      logger('recgov auth startup check ok')
      return recgovAuthStatus
    }

    const failure = authFailureFn()
    recgovAuthStatus = {
      state: 'failed',
      logged_in: false,
      checked_at: new Date().toISOString(),
      ...failure,
    }
    logger('recgov auth startup check fail', ...authLogFields(failure))
    return recgovAuthStatus
  } catch (error) {
    recgovAuthStatus = {
      state: 'failed',
      logged_in: false,
      checked_at: new Date().toISOString(),
      error: AUTH_CHECK_EXCEPTION_ERROR,
      detail: error.message,
      corrective_action: AUTH_CHECK_EXCEPTION_ACTION,
    }
    logger('recgov auth startup check exception', `detail="${truncateLogField(error.message, LOG_DETAIL_MAX_CHARS)}"`)
    return recgovAuthStatus
  }
}

export function getRecgovAuthStatus () {
  return recgovAuthStatus
}

export function getRecgovHealthStatus () {
  return {
    login_status: recgovAuthStatus.state,
    ...recgovAuthStatus,
    ...getRecgovSessionStatus(),
  }
}

async function waitForStartupAuthCheck () {
  if (!startupAuthCheck) return
  await startupAuthCheck.catch(() => {})
}

async function handleAtc (req, res) {
  if (busy) {
    jsonResponse(res, HTTP_CONFLICT, {
      ok: false,
      cart_added: false,
      error: 'companion_busy',
      detail: 'companion is already running an ATC request',
    })
    return
  }

  busy = true
  const stdout = captureStdout()
  const startedAt = Date.now()
  try {
    let raw
    try {
      raw = await readBody(req)
    } catch (error) {
      jsonResponse(res, error.status || HTTP_BAD_REQUEST, {
        ok: false,
        cart_added: false,
        error: 'invalid_request',
        detail: error.message,
      })
      return
    }

    log('recgov atc start', payloadSummary(raw))
    await waitForStartupAuthCheck()
    const code = await runAtcOnce({
      argv: ['--payload-json', raw],
      stdout,
      stderr: process.stderr,
    })
    const result = parseRunResult(stdout.value())
    const status =
      code === EXIT_SUCCESS && result.ok
        ? HTTP_OK
        : code === EXIT_USAGE
          ? HTTP_UNPROCESSABLE_ENTITY
          : HTTP_INTERNAL_ERROR
    log('recgov atc result', ...resultLogFields(result), `code=${code}`, `duration_ms=${Date.now() - startedAt}`)
    jsonResponse(res, status, result)
  } catch (error) {
    log('recgov atc exception', error.message)
    jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      cart_added: false,
      error: 'add_to_cart_exception',
      detail: error.message,
    })
  } finally {
    busy = false
  }
}

function authLogFields (failure) {
  const fields = [
    `error=${failure?.error || '?'}`,
    `detail="${truncateLogField(failure?.detail || '', LOG_DETAIL_MAX_CHARS)}"`,
  ]
  if (failure?.corrective_action) {
    fields.push(`corrective_action="${truncateLogField(failure.corrective_action, LOG_DETAIL_MAX_CHARS)}"`)
  }
  if (failure?.auth) {
    fields.push(`headless=${failure.auth.headless}`)
    fields.push(`credentials=${failure.auth.credentials_reason || '?'}`)
  }
  return fields
}

function captureStdout () {
  let data = ''
  return {
    write (chunk) {
      data += chunk
    },
    value () {
      return data
    },
  }
}

function parseRunResult (raw) {
  try {
    return JSON.parse(raw.trim())
  } catch (error) {
    return {
      ok: false,
      cart_added: false,
      error: 'invalid_companion_result',
      detail: `companion returned non-json stdout: ${error.message}`,
    }
  }
}

function resultLogFields (result) {
  const fields = [result.ok ? 'success' : 'fail']
  if (!result.ok) {
    fields.push(`error=${result.error || '?'}`)
    fields.push(`detail="${truncateLogField(result.detail || '', LOG_DETAIL_MAX_CHARS)}"`)
  }
  if (result.cart_check?.reason) {
    fields.push(`cart_reason=${result.cart_check.reason}`)
    fields.push(`cart_status=${result.cart_check.status ?? '?'}`)
    fields.push(`cart_reservations=${result.cart_check.reservation_count ?? '?'}`)
    fields.push(`cart_response_signal=${result.cart_check.response_signal ?? '?'}`)
    fields.push(`cart_best_score=${result.cart_check.best_match?.score ?? '?'}`)
  }
  return fields
}

function truncateLogField (value, maxLength) {
  const rendered = String(value).replace(/\s+/g, ' ').trim()
  return rendered.length <= maxLength ? rendered : `${rendered.slice(0, maxLength)}...`
}

export function createCompanionServer () {
  return http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
      jsonResponse(res, HTTP_OK, { ok: true, busy, recgov_auth: getRecgovHealthStatus() })
      return
    }
    if (req.method === 'POST' && req.url === '/recgov/atc') {
      await handleAtc(req, res)
      return
    }
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'unsupported_route',
      detail: `${req.method} ${req.url}`,
    })
  })
}

export const server = createCompanionServer()

export function startServer () {
  server.listen(PORT, HOST, () => {
    log('listening', `http://${HOST}:${PORT}`)
    startupAuthCheck = runStartupAuthCheck()
  })
  return server
}

function installShutdownHandlers (runningServer) {
  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => {
      log('shutting down')
      runningServer.close(() => process.exit(0))
      setTimeout(() => process.exit(0), 1000)
    })
  }
}

const entrypointUrl = process.argv[1] ? pathToFileURL(process.argv[1]).href : null
if (entrypointUrl && import.meta.url === entrypointUrl) {
  installShutdownHandlers(startServer())
}
