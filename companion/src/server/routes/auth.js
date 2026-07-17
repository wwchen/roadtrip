import { recgovAuthenticationFailure } from '../../cart.js'
import {
  recgovLoginCredentialsFromInput,
} from '../../recgovSession.js'
import { renderLoginPage } from '../../templates.js'
import {
  HTTP_BAD_REQUEST,
  HTTP_CONFLICT,
  HTTP_INTERNAL_ERROR,
  HTTP_OK,
} from '../constants.js'
import {
  htmlResponse,
  jsonResponse,
  readBody,
  wantsHtml,
} from '../http.js'
import {
  authActionResponseBody,
  authExceptionStatus,
  authHttpStatus,
  authLogFields,
  authResponseBody,
  logoutExceptionStatus,
  logoutLogFields,
  recgovLogoutStatus,
  runRecgovAuthCheck,
  setRecgovAuthStatus,
} from '../authStatus.js'

export async function handleLogout (req, res, { runtime, logoutRecgovSessionFn }) {
  if (!claimCompanionWork(res, runtime)) return

  const startedAt = Date.now()
  try {
    runtime.logger('recgov auth logout request start')
    await runtime.waitForStartupAuthCheck()
    const result = await logoutRecgovSessionFn()
    const statusBody = setRecgovAuthStatus(recgovLogoutStatus(result))
    const status = result.ok ? HTTP_OK : HTTP_INTERNAL_ERROR
    runtime.logger(
      'recgov auth logout request result',
      result.ok ? 'ok' : 'failed',
      ...(result.ok ? logoutLogFields(result) : authLogFields(statusBody)),
      `duration_ms=${Date.now() - startedAt}`,
    )
    respondAuthResult(req, res, status, authActionResponseBody(statusBody, result.ok === true))
  } catch (error) {
    const statusBody = setRecgovAuthStatus(logoutExceptionStatus(error))
    runtime.logger('recgov auth logout request exception', error.message)
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(statusBody, false))
  } finally {
    runtime.setBusy(false)
  }
}

export async function handleRefresh (req, res, { runtime, testChromiumFn }) {
  if (!claimCompanionWork(res, runtime)) return

  const startedAt = Date.now()
  try {
    runtime.logger('recgov auth refresh request start')
    await runtime.waitForStartupAuthCheck()
    const status = await runRecgovAuthCheck({
      operation: 'refresh',
      testChromiumFn,
      authFailureFn: () => recgovAuthenticationFailure({ attemptedRefresh: true }),
      options: {
        forceRefresh: true,
        allowManualLogin: false,
      },
    })
    runtime.logger('recgov auth refresh request result', status.state, `duration_ms=${Date.now() - startedAt}`)
    respondAuthResult(req, res, authHttpStatus(status), authResponseBody(status))
  } catch (error) {
    runtime.logger('recgov auth refresh request exception', error.message)
    const status = setRecgovAuthStatus(authExceptionStatus('refresh', error))
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(status, false))
  } finally {
    runtime.setBusy(false)
  }
}

export async function handleLoginPost (req, res, { runtime, testChromiumFn }) {
  if (rejectBusy(res, runtime)) return

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
  runtime.setBusy(true)

  const startedAt = Date.now()
  try {
    runtime.logger('recgov auth login request start', `user=${maskLoginUsername(credentialState.email)}`, `mfa=${credentialState.mfaConfigured}`)
    await runtime.waitForStartupAuthCheck()
    const status = await runRecgovAuthCheck({
      operation: 'login',
      testChromiumFn,
      authFailureFn: () => recgovAuthenticationFailure({ attemptedLogin: true }),
      options: {
        credentials,
        allowManualLogin: false,
      },
    })
    runtime.logger('recgov auth login request result', status.state, `duration_ms=${Date.now() - startedAt}`)
    respondAuthResult(req, res, authHttpStatus(status), authResponseBody(status))
  } catch (error) {
    runtime.logger('recgov auth login request exception', error.message)
    const status = setRecgovAuthStatus(authExceptionStatus('login', error))
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(status, false))
  } finally {
    runtime.setBusy(false)
  }
}

function claimCompanionWork (res, runtime) {
  if (rejectBusy(res, runtime)) return false
  runtime.setBusy(true)
  return true
}

function rejectBusy (res, runtime) {
  if (runtime.isBusy()) {
    jsonResponse(res, HTTP_CONFLICT, {
      ok: false,
      error: 'companion_busy',
      detail: 'companion is already running work',
    })
    return true
  }
  return false
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

function respondAuthResult (req, res, status, body) {
  if (wantsHtml(req)) {
    htmlResponse(res, status, renderLoginPage({ result: body }))
    return
  }
  jsonResponse(res, status, body)
}

function maskLoginUsername (email) {
  const [name, domain] = String(email || '').split('@')
  if (!domain) return 'configured account'
  const visible = name.length <= 2 ? name[0] || '*' : `${name[0]}***${name.at(-1)}`
  return `${visible}@${domain}`
}
