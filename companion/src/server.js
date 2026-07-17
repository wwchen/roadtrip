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
  logoutRecgovBrowserSession,
  recgovLoginCredentialsFromInput,
} from './recgovSession.js'
import {
  COMPANION_OPENAPI_SPEC,
} from './openapi.js'
import { matchCompanionRoute } from './apiContract.js'
import {
  SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX,
  captureRecgovScreenshot,
  createRecgovScreenshotDeps,
  recgovScreenshotTargetUrl,
} from './recgovScreenshot.js'
import { runAtcOnce } from './runAtcOnce.js'
import {
  renderLoginPage,
  renderSwaggerPage,
} from './templates.js'

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
  'Check the companion logs, then open the companion root page or run make recgov-login from the host profile.'
const LOGOUT_EXCEPTION_ERROR = 'recgov_logout_exception'
const LOGOUT_CORRECTIVE_ACTION = 'Open the companion root page and verify the Recreation.gov session screenshot.'
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
    const body = payload?.payload || payload
    const openings = body?.openings || []
    const first = openings[0] || body
    return [
      `vendor=${body?.vendor ?? first?.vendor ?? '?'}`,
      `start=${body?.start_date ?? first?.date ?? '?'}`,
      `end=${body?.end_date ?? first?.checkout_date ?? '?'}`,
      `campground=${first?.campground_id ?? body?.campground_id ?? '?'}`,
      `campsite=${first?.vendor_id ?? first?.campsite_id ?? body?.campsite_id ?? '?'}`,
      `booking_url="${truncateLogField(first?.booking_url ?? body?.booking_url ?? '', LOG_DETAIL_MAX_CHARS)}"`,
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
      const diagnostic = result?.diagnostic || getRecgovSessionStatus().last_login_diagnostic || null
      recgovAuthStatus = {
        state: 'ok',
        logged_in: true,
        operation,
        checked_at: new Date().toISOString(),
        diagnostic,
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
  const {
    last_login_diagnostic: _lastLoginDiagnostic,
    ...sessionStatus
  } = getRecgovSessionStatus()
  const {
    diagnostic: _diagnostic,
    ...authStatus
  } = recgovAuthStatus
  return {
    login_status: authStatus.state,
    ...sessionStatus,
    ...authStatus,
  }
}

async function waitForStartupAuthCheck () {
  if (!startupAuthCheck) return
  await startupAuthCheck.catch(() => {})
}

async function handleAtc (req, res, deps) {
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
    const code = await deps.runAtcOnceFn({
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

async function handleLogout (req, res, deps) {
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
    log('recgov auth logout request start')
    await waitForStartupAuthCheck()
    const result = await deps.logoutRecgovSessionFn()
    recgovAuthStatus = recgovLogoutStatus(result)
    const status = result.ok ? HTTP_OK : HTTP_INTERNAL_ERROR
    log(
      'recgov auth logout request result',
      result.ok ? 'ok' : 'failed',
      ...(result.ok ? logoutLogFields(result) : authLogFields(recgovAuthStatus)),
      `duration_ms=${Date.now() - startedAt}`,
    )
    respondAuthResult(req, res, status, {
      ...authActionResponseBody(recgovAuthStatus, result.ok === true),
    })
  } catch (error) {
    recgovAuthStatus = {
      state: 'failed',
      logged_in: null,
      operation: 'logout',
      checked_at: new Date().toISOString(),
      error: LOGOUT_EXCEPTION_ERROR,
      detail: error.message,
      corrective_action: LOGOUT_CORRECTIVE_ACTION,
    }
    log('recgov auth logout request exception', error.message)
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(recgovAuthStatus, false))
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
    recgovAuthStatus = authExceptionStatus('refresh', error)
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(recgovAuthStatus, false))
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
    recgovAuthStatus = authExceptionStatus('login', error)
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(recgovAuthStatus, false))
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
  return authActionResponseBody(status, status.logged_in === true)
}

function authActionResponseBody (status, ok) {
  const {
    diagnostic,
    ...recgovAuth
  } = status
  return {
    ok,
    recgov_auth: recgovAuth,
    diagnostics: diagnostic || null,
  }
}

function authExceptionStatus (operation, error) {
  return {
    state: 'failed',
    logged_in: false,
    operation,
    checked_at: new Date().toISOString(),
    error: AUTH_CHECK_EXCEPTION_ERROR,
    detail: error.message,
    corrective_action: AUTH_CHECK_EXCEPTION_ACTION,
  }
}

function recgovLogoutStatus (result) {
  const base = {
    state: result.ok ? 'logged_out' : 'failed',
    logged_in: result.ok ? false : (result.logged_in ?? null),
    operation: 'logout',
    checked_at: new Date().toISOString(),
    logout: {
      clicked: result.clicked === true,
      reason: result.reason || null,
      selector: result.selector || null,
      menu_selector: result.menu_selector || null,
      page_url: result.page_url || null,
    },
  }
  if (result.ok) return base
  return {
    ...base,
    error: result.error || 'recgov_logout_failed',
    detail: result.detail,
    corrective_action: LOGOUT_CORRECTIVE_ACTION,
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

function logoutLogFields (result) {
  return [
    `clicked=${result.clicked === true}`,
    `reason=${result.reason || '?'}`,
    `selector="${truncateLogField(result.selector || '', LOG_DETAIL_MAX_CHARS)}"`,
  ]
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
  runAtcOnceFn = runAtcOnce,
  logoutRecgovSessionFn = logoutRecgovBrowserSession,
  ...screenshotOverrides
} = {}) {
  const deps = {
    testChromiumFn,
    runAtcOnceFn,
    logoutRecgovSessionFn: () => logoutRecgovSessionFn({
      getContextFn: screenshotOverrides.getContextFn,
      isSpaLoggedInFn: screenshotOverrides.isSpaLoggedInFn,
    }),
    recgovScreenshotDeps: createRecgovScreenshotDeps(screenshotOverrides),
  }
  return http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://companion.local')
    const route = matchCompanionRoute(req.method, url.pathname)
    if (route) {
      await handleContractRoute(route, req, res, url, deps)
      return
    }
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'unsupported_route',
      detail: `${req.method} ${req.url}`,
    })
  })
}

async function handleContractRoute (route, req, res, url, deps) {
  const handler = CONTRACT_ROUTE_HANDLERS[route.operationId]
  if (handler) {
    await handler({ req, res, url, deps })
    return
  }
  jsonResponse(res, HTTP_INTERNAL_ERROR, {
    ok: false,
    error: 'unhandled_route',
    detail: `${route.method} ${route.path}`,
  })
}

const CONTRACT_ROUTE_HANDLERS = {
  getDiagnosticScreenshot: async ({ url, res }) => handleDiagnosticImage(url, res),
  getScreenshot: async ({ url, res, deps }) => handleLiveScreenshot(url, res, deps),
  getOpenApiJson: async ({ res }) => jsonResponse(res, HTTP_OK, COMPANION_OPENAPI_SPEC),
  getSwaggerDocs: async ({ res }) => htmlResponse(res, HTTP_OK, renderSwaggerPage()),
  getHealth: async ({ res }) => jsonResponse(res, HTTP_OK, { ok: true, busy, recgov_auth: getRecgovHealthStatus() }),
  getOperatorPage: async ({ res }) => htmlResponse(res, HTTP_OK, renderLoginPage()),
  postLogin: async ({ req, res, deps }) => handleLoginPost(req, res, deps),
  postLogout: async ({ req, res, deps }) => handleLogout(req, res, deps),
  postRefresh: async ({ req, res, deps }) => handleRefresh(req, res, deps),
  postAtc: async ({ req, res, deps }) => handleAtc(req, res, deps),
}

export const HANDLED_OPERATION_IDS = Object.freeze(Object.keys(CONTRACT_ROUTE_HANDLERS))

async function handleDiagnosticImage (url, res) {
  const filename = diagnosticFilename(url)
  if (!filename) {
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_diagnostic_path',
    })
    return
  }

  const imagePath = diagnosticImagePath(filename)
  if (!imagePath) {
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_diagnostic_path',
    })
    return
  }

  await serveScreenshotImage(imagePath, res, 'diagnostic_not_found')
}

async function handleLiveScreenshot (url, res, deps) {
  const target = recgovScreenshotTargetUrl(url)
  if (!target) {
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_screenshot_target',
      detail: 'screenshot target must be a recreation.gov URL or path',
    })
    return
  }

  log('recgov screenshot start', `target=${target.href}`)
  const startedAt = Date.now()
  try {
    const { image, recaccountPresent } = await captureRecgovScreenshot(target, deps.recgovScreenshotDeps)
    log('recgov screenshot result ok', `target=${target.href}`, `recaccount=${recaccountPresent}`, `duration_ms=${Date.now() - startedAt}`)
    imageResponse(res, HTTP_OK, image, PNG_CONTENT_TYPE)
  } catch (error) {
    log('recgov screenshot result fail', `target=${target.href}`, `detail="${truncateLogField(error.message, LOG_DETAIL_MAX_CHARS)}"`, `duration_ms=${Date.now() - startedAt}`)
    jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      error: 'screenshot_failed',
      detail: error.message,
      target_url: target.href,
    })
  }
}

async function serveScreenshotImage (imagePath, res, notFoundError) {
  try {
    const image = await readFile(imagePath)
    imageResponse(res, HTTP_OK, image, PNG_CONTENT_TYPE)
  } catch {
    jsonResponse(res, HTTP_NOT_FOUND, {
      ok: false,
      error: notFoundError,
    })
  }
}

function diagnosticFilename (url) {
  const filename = path.basename(url.pathname)
  const requested = decodeURIComponent(url.pathname.slice(`${SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX}/`.length))
  if (!filename || filename !== requested || !filename.endsWith('.png')) return null
  return filename
}

function diagnosticImagePath (filename) {
  return path.join(RECGOV_DIAGNOSTIC_DIR, filename)
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
