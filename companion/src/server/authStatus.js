import {
  recgovAuthenticationFailure,
  testChromium,
} from '../cart.js'
import { getRecgovSessionStatus } from '../recgovSession.js'
import {
  HTTP_OK,
  HTTP_UNAUTHORIZED,
  LOG_DETAIL_MAX_CHARS,
} from './constants.js'
import {
  log,
  truncateLogField,
} from './logging.js'

const AUTH_CHECK_EXCEPTION_ERROR = 'recgov_auth_check_exception'
const AUTH_CHECK_EXCEPTION_ACTION =
  'Check the companion logs, then open the companion root page or run make recgov-login from the host profile.'
const LOGOUT_EXCEPTION_ERROR = 'recgov_logout_exception'
export const LOGOUT_CORRECTIVE_ACTION = 'Open the companion root page and verify the Recreation.gov session screenshot.'

let recgovAuthStatus = { state: 'unchecked' }

// The companion-wide slot, owned by the startup check. Profile-scoped callers
// pass the pool's per-profile store instead, so one user's login never lands
// on another user's row — or on the global one.
const globalAuthStatusStore = {
  get: () => recgovAuthStatus,
  set: (status) => {
    recgovAuthStatus = status
    return status
  },
}

export async function runRecgovAuthCheck ({
  operation = 'check',
  options = {},
  testChromiumFn = testChromium,
  authFailureFn = recgovAuthenticationFailure,
  logger = log,
  statusStore = globalAuthStatusStore,
} = {}) {
  statusStore.set({
    state: 'checking',
    operation,
    checked_at: new Date().toISOString(),
  })
  logger('recgov auth', operation, 'start')

  try {
    const result = await testChromiumFn(null, options)
    const profileDiagnostic = getRecgovSessionStatus(options.profileId ?? null).last_login_diagnostic
    if (result?.loggedIn === true) {
      const status = statusStore.set({
        state: 'ok',
        logged_in: true,
        operation,
        checked_at: new Date().toISOString(),
        diagnostic: result?.diagnostic || profileDiagnostic || null,
      })
      logger('recgov auth', operation, 'ok')
      return status
    }

    const status = statusStore.set({
      state: 'failed',
      logged_in: false,
      operation,
      checked_at: new Date().toISOString(),
      diagnostic: result?.diagnostic || profileDiagnostic || null,
      ...authFailureFn(),
    })
    logger('recgov auth', operation, 'fail', ...authLogFields(status))
    return status
  } catch (error) {
    const status = statusStore.set(authExceptionStatus(operation, error))
    logger('recgov auth', operation, 'exception', `detail="${truncateLogField(error.message, LOG_DETAIL_MAX_CHARS)}"`)
    return status
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

export function setRecgovAuthStatus (status) {
  recgovAuthStatus = status
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

export function authHttpStatus (status) {
  return status.logged_in === true ? HTTP_OK : HTTP_UNAUTHORIZED
}

export function authResponseBody (status) {
  return authActionResponseBody(status, status.logged_in === true)
}

export function authActionResponseBody (status, ok) {
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

export function authExceptionStatus (operation, error) {
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

export function logoutExceptionStatus (error) {
  return {
    state: 'failed',
    logged_in: null,
    operation: 'logout',
    checked_at: new Date().toISOString(),
    error: LOGOUT_EXCEPTION_ERROR,
    detail: error.message,
    corrective_action: LOGOUT_CORRECTIVE_ACTION,
  }
}

export function recgovLogoutStatus (result) {
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

export function authLogFields (failure) {
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

export function logoutLogFields (result) {
  return [
    `clicked=${result.clicked === true}`,
    `reason=${result.reason || '?'}`,
    `selector="${truncateLogField(result.selector || '', LOG_DETAIL_MAX_CHARS)}"`,
  ]
}
