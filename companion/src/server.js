// HTTP executor for backend-owned one-shot companion work.
// The backend posts an ATC payload here; this process owns the browser profile
// and returns the same JSON result as the recgov:atc CLI.

import http from 'node:http'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { IS_HEADLESS } from './browser.js'
import {
  recgovAuthenticationFailure,
  testChromium,
} from './cart.js'
import {
  RECGOV_DIAGNOSTIC_DIR,
  getRecgovSessionStatus,
  recgovLoginCredentialsFromInput,
} from './recgovSession.js'
import { runAtcOnce } from './runAtcOnce.js'

const DEFAULT_HOST = '0.0.0.0'
const DEFAULT_PORT = 8770
const MAX_BODY_BYTES = 64 * 1024
const HTTP_OK = 200
const HTTP_BAD_REQUEST = 400
const HTTP_UNAUTHORIZED = 401
const HTTP_NOT_FOUND = 404
const HTTP_CONFLICT = 409
const HTTP_PAYLOAD_TOO_LARGE = 413
const HTTP_UNPROCESSABLE_ENTITY = 422
const HTTP_INTERNAL_ERROR = 500
const EXIT_SUCCESS = 0
const EXIT_USAGE = 2
const LOG_DETAIL_MAX_CHARS = 160
const AUTH_CHECK_EXCEPTION_ERROR = 'recgov_auth_check_exception'
const AUTH_CHECK_EXCEPTION_ACTION =
  'Check the companion logs, then open the companion /login page or run make recgov-login from the host profile.'
const LOGIN_FORM_TITLE = 'Recreation.gov Login'
const DIAGNOSTIC_ROUTE_PREFIX = '/diagnostics/'
const PNG_CONTENT_TYPE = 'image/png'

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

function htmlResponse (res, status, body) {
  res.writeHead(status, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

function imageResponse (res, status, body, contentType) {
  res.writeHead(status, {
    'content-type': contentType,
    'content-length': body.length,
  })
  res.end(body)
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

export async function runRecgovAuthCheck ({
  operation = 'check',
  options = {},
  testChromiumFn = testChromium,
  authFailureFn = recgovAuthenticationFailure,
  logger = log,
} = {}) {
  recgovAuthStatus = {
    state: 'checking',
    operation,
    checked_at: new Date().toISOString(),
  }
  logger('recgov auth', operation, 'start')

  try {
    const result = await testChromiumFn(null, options)
    if (result?.loggedIn === true) {
      recgovAuthStatus = {
        state: 'ok',
        logged_in: true,
        operation,
        checked_at: new Date().toISOString(),
        diagnostic: null,
      }
      logger('recgov auth', operation, 'ok')
      return recgovAuthStatus
    }

    const failure = authFailureFn()
    const diagnostic = result?.diagnostic || getRecgovSessionStatus().last_login_diagnostic || null
    recgovAuthStatus = {
      state: 'failed',
      logged_in: false,
      operation,
      checked_at: new Date().toISOString(),
      diagnostic,
      ...failure,
    }
    logger('recgov auth', operation, 'fail', ...authLogFields(recgovAuthStatus))
    return recgovAuthStatus
  } catch (error) {
    recgovAuthStatus = {
      state: 'failed',
      logged_in: false,
      operation,
      checked_at: new Date().toISOString(),
      error: AUTH_CHECK_EXCEPTION_ERROR,
      detail: error.message,
      corrective_action: AUTH_CHECK_EXCEPTION_ACTION,
    }
    logger('recgov auth', operation, 'exception', `detail="${truncateLogField(error.message, LOG_DETAIL_MAX_CHARS)}"`)
    return recgovAuthStatus
  }
}

export async function runStartupAuthCheck (options = {}) {
  return runRecgovAuthCheck({
    operation: 'startup check',
    ...options,
  })
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

async function handleRefresh (req, res, deps) {
  if (busy) {
    jsonResponse(res, HTTP_CONFLICT, {
      ok: false,
      error: 'companion_busy',
      detail: 'companion is already running work',
    })
    return
  }

  busy = true
  const startedAt = Date.now()
  try {
    log('recgov auth refresh request start')
    await waitForStartupAuthCheck()
    const status = await runRecgovAuthCheck({
      operation: 'refresh',
      testChromiumFn: deps.testChromiumFn,
      authFailureFn: () => recgovAuthenticationFailure({ attemptedRefresh: true }),
      options: {
        forceRefresh: true,
        allowManualLogin: false,
      },
    })
    log('recgov auth refresh request result', status.state, `duration_ms=${Date.now() - startedAt}`)
    respondAuthResult(req, res, authHttpStatus(status), authResponseBody(status))
  } catch (error) {
    log('recgov auth refresh request exception', error.message)
    jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      error: AUTH_CHECK_EXCEPTION_ERROR,
      detail: error.message,
    })
  } finally {
    busy = false
  }
}

async function handleLoginPost (req, res, deps) {
  if (busy) {
    jsonResponse(res, HTTP_CONFLICT, {
      ok: false,
      error: 'companion_busy',
      detail: 'companion is already running work',
    })
    return
  }

  let raw
  try {
    raw = await readBody(req)
  } catch (error) {
    respondAuthResult(req, res, error.status || HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_request',
      detail: error.message,
    })
    return
  }

  let credentials
  try {
    credentials = loginCredentialsFromRequestBody(raw, req.headers['content-type'] || '')
  } catch (error) {
    respondAuthResult(req, res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_request',
      detail: error.message,
    })
    return
  }

  const credentialState = recgovLoginCredentialsFromInput(credentials)
  if (!credentialState.configured) {
    respondAuthResult(req, res, HTTP_BAD_REQUEST, {
      ok: false,
      error: credentialState.reason,
      detail: 'username/email and password are required',
    })
    return
  }

  busy = true
  const startedAt = Date.now()
  try {
    log('recgov auth login request start', `user=${maskLoginUsername(credentialState.email)}`, `mfa=${credentialState.mfaConfigured}`)
    await waitForStartupAuthCheck()
    const status = await runRecgovAuthCheck({
      operation: 'login',
      testChromiumFn: deps.testChromiumFn,
      authFailureFn: () => recgovAuthenticationFailure({ attemptedLogin: true }),
      options: {
        credentials,
        allowManualLogin: false,
      },
    })
    log('recgov auth login request result', status.state, `duration_ms=${Date.now() - startedAt}`)
    respondAuthResult(req, res, authHttpStatus(status), authResponseBody(status))
  } catch (error) {
    log('recgov auth login request exception', error.message)
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, {
      ok: false,
      error: AUTH_CHECK_EXCEPTION_ERROR,
      detail: error.message,
    })
  } finally {
    busy = false
  }
}

function loginCredentialsFromRequestBody (raw, contentType) {
  if (contentType.includes('application/json')) {
    const body = raw.trim() ? JSON.parse(raw) : {}
    return {
      email: body.email || body.username,
      password: body.password,
      mfaCode: body.mfaCode || body.mfa_code,
    }
  }

  const params = new URLSearchParams(raw)
  return {
    email: params.get('email') || params.get('username'),
    password: params.get('password'),
    mfaCode: params.get('mfa_code') || params.get('mfaCode'),
  }
}

function authHttpStatus (status) {
  return status.logged_in === true ? HTTP_OK : HTTP_UNAUTHORIZED
}

function authResponseBody (status) {
  return {
    ok: status.logged_in === true,
    recgov_auth: status,
  }
}

function respondAuthResult (req, res, status, body) {
  if (wantsHtml(req)) {
    htmlResponse(res, status, renderLoginPage({ result: body }))
    return
  }
  jsonResponse(res, status, body)
}

function wantsHtml (req) {
  const accept = String(req.headers.accept || '')
  return accept.includes('text/html') && !accept.includes('application/json')
}

function renderLoginPage ({ result = null } = {}) {
  const status = result?.recgov_auth
  const ok = result?.ok === true
  const error = result && !ok ? result.detail || status?.detail || result.error || status?.error : null
  const operation = status?.operation === 'refresh' ? 'Refresh' : 'Login'
  const diagnostic = status?.diagnostic || status?.last_login_diagnostic || null
  const statusHtml = result
    ? `<p class="${ok ? 'ok' : 'error'}">${escapeHtml(ok ? `${operation} succeeded.` : `${operation} failed: ${error}`)}</p>`
    : ''
  const healthHtml = status
    ? `<pre>${escapeHtml(JSON.stringify(status, null, 2))}</pre>`
    : ''
  const diagnosticHtml = diagnostic?.screenshot_url
    ? `<section>
        <h2>Last login screenshot</h2>
        <p>Reason: ${escapeHtml(diagnostic.reason || 'unknown')}</p>
        <img class="diagnostic" src="${escapeHtml(diagnostic.screenshot_url)}" alt="Recreation.gov login diagnostic screenshot">
      </section>`
    : ''

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${LOGIN_FORM_TITLE}</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 36rem; margin: 3rem auto; padding: 0 1rem; color: #172033; }
    label { display: block; font-weight: 650; margin-top: 1rem; }
    input { box-sizing: border-box; width: 100%; padding: .7rem; margin-top: .35rem; border: 1px solid #c8d0dc; border-radius: 6px; font: inherit; }
    button { margin-top: 1.25rem; padding: .75rem 1rem; border: 0; border-radius: 6px; background: #24579a; color: white; font: inherit; font-weight: 700; cursor: pointer; }
    .button-link { display: inline-flex; align-items: center; justify-content: center; margin-top: 1.25rem; padding: .75rem 1rem; border-radius: 6px; font: inherit; font-weight: 700; text-decoration: none; cursor: pointer; }
    .row { display: flex; gap: .75rem; align-items: center; }
    .row form { margin: 0; }
    .secondary { background: #eef2f7; color: #172033; }
    .secondary:visited { color: #172033; }
    .ok { color: #166534; font-weight: 700; }
    .error { color: #b42318; font-weight: 700; }
    .diagnostic { display: block; max-width: 100%; border: 1px solid #c8d0dc; border-radius: 6px; }
    pre { overflow: auto; background: #f6f8fb; padding: .75rem; border-radius: 6px; }
  </style>
</head>
<body>
  <h1>${LOGIN_FORM_TITLE}</h1>
  ${statusHtml}
  <form method="post" action="/login">
    <label for="username">Username or email</label>
    <input id="username" name="username" type="email" autocomplete="username" required>
    <label for="password">Password</label>
    <input id="password" name="password" type="password" autocomplete="current-password" required>
    <label for="mfa_code">MFA code</label>
    <input id="mfa_code" name="mfa_code" inputmode="numeric" autocomplete="one-time-code">
    <button type="submit">Log in</button>
  </form>
  <div class="row">
    <form method="post" action="/refresh"><button class="secondary" type="submit">Refresh session</button></form>
    <a class="secondary button-link" href="/health">Health JSON</a>
  </div>
  ${diagnosticHtml}
  ${healthHtml}
</body>
</html>`
}

function renderRefreshPage () {
  return `<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Refresh Recreation.gov Session</title></head>
<body>
  <h1>Refresh Recreation.gov Session</h1>
  <form method="post" action="/refresh"><button type="submit">Refresh session</button></form>
  <p><a href="/login">Login</a> <a href="/health">Health JSON</a></p>
</body>
</html>`
}

function escapeHtml (value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function maskLoginUsername (email) {
  const [name, domain] = String(email || '').split('@')
  if (!domain) return 'configured account'
  const visible = name.length <= 2 ? name[0] || '*' : `${name[0]}***${name.at(-1)}`
  return `${visible}@${domain}`
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
  }
  if (failure?.diagnostic?.reason) {
    fields.push(`diagnostic_reason=${failure.diagnostic.reason}`)
  }
  if (failure?.diagnostic?.screenshot_url) {
    fields.push(`screenshot=${failure.diagnostic.screenshot_url}`)
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

export function createCompanionServer ({
  testChromiumFn = testChromium,
} = {}) {
  const deps = { testChromiumFn }
  return http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://companion.local')
    if (req.method === 'GET' && url.pathname.startsWith(DIAGNOSTIC_ROUTE_PREFIX)) {
      await handleDiagnosticImage(url, res)
      return
    }
    if (req.method === 'GET' && url.pathname === '/health') {
      jsonResponse(res, HTTP_OK, { ok: true, busy, recgov_auth: getRecgovHealthStatus() })
      return
    }
    if (req.method === 'GET' && url.pathname === '/login') {
      htmlResponse(res, HTTP_OK, renderLoginPage())
      return
    }
    if (req.method === 'POST' && url.pathname === '/login') {
      await handleLoginPost(req, res, deps)
      return
    }
    if (req.method === 'GET' && url.pathname === '/refresh') {
      htmlResponse(res, HTTP_OK, renderRefreshPage())
      return
    }
    if (req.method === 'POST' && url.pathname === '/refresh') {
      await handleRefresh(req, res, deps)
      return
    }
    if (req.method === 'POST' && url.pathname === '/recgov/atc') {
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

async function handleDiagnosticImage (url, res) {
  const filename = path.basename(url.pathname)
  if (!filename || filename !== decodeURIComponent(url.pathname.slice(DIAGNOSTIC_ROUTE_PREFIX.length)) || !filename.endsWith('.png')) {
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_diagnostic_path',
    })
    return
  }

  try {
    const image = await readFile(path.join(RECGOV_DIAGNOSTIC_DIR, filename))
    imageResponse(res, HTTP_OK, image, PNG_CONTENT_TYPE)
  } catch {
    jsonResponse(res, HTTP_NOT_FOUND, {
      ok: false,
      error: 'diagnostic_not_found',
    })
  }
}

export const server = createCompanionServer()

export function startServer () {
  server.listen(PORT, HOST, () => {
    log('listening', `http://${HOST}:${PORT}`, `headless=${IS_HEADLESS}`)
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
