import { recgovAuthenticationFailure } from '../../cart.js'
import {
  recgovLoginCredentialsFromInput,
} from '../../recgovSession.js'
import { renderLoginPage } from '../../templates.js'
import {
  ERROR_MFA_CHALLENGE_EXPIRED,
  ERROR_MFA_CHALLENGE_UNKNOWN,
} from '../../profilePool.js'
import {
  HTTP_BAD_REQUEST,
  HTTP_INTERNAL_ERROR,
  HTTP_OK,
  HTTP_TOO_MANY_REQUESTS,
  HTTP_UNAUTHORIZED,
} from '../constants.js'
import {
  htmlResponse,
  jsonResponse,
  wantsHtml,
} from '../http.js'
import {
  badRequest,
  invalidJsonRejection,
  profileBusyResponse,
  readRequestFields,
  requireProfileId,
  resolveProfileContext,
} from '../requestInput.js'
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
} from '../authStatus.js'

const LOGIN_STATE_OK = 'ok'
const LOGIN_STATE_MFA_REQUIRED = 'mfa_required'
export const ERROR_MFA_REQUIRED = 'mfa_required'
export const ERROR_MFA_INVALID = 'mfa_invalid'
export const ERROR_LOGIN_BACKOFF = 'login_backoff'

const OPERATION_LOGIN = 'login'
const OPERATION_LOGOUT = 'logout'
const OPERATION_REFRESH = 'refresh'

export async function handleLogout (req, res, { runtime, pool, logoutRecgovSessionFn }) {
  const request = await profileRequest(req, res, url(req), pool, OPERATION_LOGOUT)
  if (!request) return

  const { profileId, lock } = request
  const startedAt = Date.now()
  try {
    runtime.logger('recgov auth logout request start', `profile=${profileId}`)
    await runtime.waitForStartupAuthCheck()
    const resolved = await resolveProfileContext(pool, profileId)
    if (!resolved.ok) {
      respondRejection(req, res, resolved.rejection)
      return
    }
    const result = await logoutRecgovSessionFn({ getContextFn: async () => resolved.context, profileId })
    const statusBody = pool.setAuthStatus(profileId, recgovLogoutStatus(result))
    const status = result.ok ? HTTP_OK : HTTP_INTERNAL_ERROR
    runtime.logger(
      'recgov auth logout request result',
      result.ok ? 'ok' : 'failed',
      `profile=${profileId}`,
      ...(result.ok ? logoutLogFields(result) : authLogFields(statusBody)),
      `duration_ms=${Date.now() - startedAt}`,
    )
    respondAuthResult(req, res, status, authActionResponseBody(statusBody, result.ok === true))
  } catch (error) {
    const statusBody = pool.setAuthStatus(profileId, logoutExceptionStatus(error))
    runtime.logger('recgov auth logout request exception', `profile=${profileId}`, error.message)
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(statusBody, false))
  } finally {
    lock.release()
  }
}

export async function handleRefresh (req, res, { runtime, pool, testChromiumFn }) {
  const request = await profileRequest(req, res, url(req), pool, OPERATION_REFRESH)
  if (!request) return

  const { profileId, lock } = request
  const startedAt = Date.now()
  try {
    runtime.logger('recgov auth refresh request start', `profile=${profileId}`)
    await runtime.waitForStartupAuthCheck()
    const resolved = await resolveProfileContext(pool, profileId)
    if (!resolved.ok) {
      respondRejection(req, res, resolved.rejection)
      return
    }
    const status = await runRecgovAuthCheck({
      operation: OPERATION_REFRESH,
      testChromiumFn,
      authFailureFn: () => recgovAuthenticationFailure({ attemptedRefresh: true }),
      statusStore: pool.authStatusStore(profileId),
      options: {
        forceRefresh: true,
        allowManualLogin: false,
        profileId,
        getContextFn: async () => resolved.context,
      },
    })
    runtime.logger('recgov auth refresh request result', status.state, `profile=${profileId}`, `duration_ms=${Date.now() - startedAt}`)
    respondAuthResult(req, res, authHttpStatus(status), authResponseBody(status))
  } catch (error) {
    runtime.logger('recgov auth refresh request exception', `profile=${profileId}`, error.message)
    const status = pool.setAuthStatus(profileId, authExceptionStatus(OPERATION_REFRESH, error))
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(status, false))
  } finally {
    lock.release()
  }
}

export async function handleLoginPost (req, res, { runtime, pool, credentialLoginFn }) {
  let input
  try {
    input = await readRequestFields(req, url(req))
  } catch (error) {
    respondRejection(req, res, invalidJsonRejection(error))
    return
  }

  const profile = requireProfileId(input.fields)
  if (!profile.ok) {
    respondRejection(req, res, badRequest(profile.error, 'profile_id identifies the browser profile to log in'))
    return
  }
  const profileId = profile.profileId
  const credentials = loginCredentialsFromFields(input.fields)

  if (input.fields.challenge_id) {
    await completeMfaChallenge(req, res, {
      runtime,
      pool,
      profileId,
      challengeId: String(input.fields.challenge_id),
      mfaCode: credentials.mfaCode,
    })
    return
  }

  const credentialState = recgovLoginCredentialsFromInput(credentials)
  if (!credentialState.configured) {
    respondRejection(req, res, badRequest(credentialState.reason, 'username/email and password are required'))
    return
  }

  const backoff = pool.loginBackoff(profileId)
  if (backoff.blocked) {
    respondRejection(req, res, {
      status: HTTP_TOO_MANY_REQUESTS,
      body: {
        ok: false,
        error: ERROR_LOGIN_BACKOFF,
        detail: 'a recent login for this profile failed; wait before retrying',
        retry_after_ms: backoff.retry_after_ms,
      },
    })
    return
  }

  const lock = pool.acquire(profileId, OPERATION_LOGIN)
  if (!lock) {
    respondRejection(req, res, profileBusyResponse(profileId, pool.busyOperation(profileId)))
    return
  }

  const startedAt = Date.now()
  let challengeHoldsLock = false
  try {
    runtime.logger(
      'recgov auth login request start',
      `profile=${profileId}`,
      `user=${maskLoginUsername(credentialState.email)}`,
      `mfa=${credentialState.mfaConfigured}`,
    )
    await runtime.waitForStartupAuthCheck()
    const resolved = await resolveProfileContext(pool, profileId)
    if (!resolved.ok) {
      respondRejection(req, res, resolved.rejection)
      return
    }

    const outcome = await credentialLoginFn({
      getContextFn: async () => resolved.context,
      credentials,
      profileId,
    })
    runtime.logger('recgov auth login request result', outcome.state, `profile=${profileId}`, `duration_ms=${Date.now() - startedAt}`)

    if (outcome.state === LOGIN_STATE_MFA_REQUIRED) {
      const challenge = pool.openMfaChallenge(profileId, {
        lock,
        // The resume closure owns the page Rec.gov is showing the prompt on.
        complete: (mfaCode) => outcome.resume(mfaCode),
      })
      challengeHoldsLock = true
      runtime.logger('recgov auth login mfa challenge', `profile=${profileId}`, `expires_at=${challenge.expires_at}`)
      respondAuthResult(req, res, HTTP_UNAUTHORIZED, {
        ...authActionResponseBody(loginStatus(pool, profileId, outcome), false),
        error: ERROR_MFA_REQUIRED,
        ...challenge,
      })
      return
    }

    const status = loginStatus(pool, profileId, outcome)
    if (status.logged_in !== true) pool.recordLoginFailure(profileId)
    else pool.clearLoginFailure(profileId)
    respondAuthResult(req, res, authHttpStatus(status), authResponseBody(status))
  } catch (error) {
    runtime.logger('recgov auth login request exception', `profile=${profileId}`, error.message)
    const status = pool.setAuthStatus(profileId, authExceptionStatus(OPERATION_LOGIN, error))
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(status, false))
  } finally {
    if (!challengeHoldsLock) lock.release()
  }
}

async function completeMfaChallenge (req, res, { runtime, pool, profileId, challengeId, mfaCode }) {
  // Validate before consuming: an empty code must leave the challenge (and
  // the held page behind it) intact for the user's next attempt.
  if (!mfaCode) {
    respondRejection(req, res, badRequest(ERROR_MFA_REQUIRED, 'mfa_code is required to complete the challenge'))
    return
  }

  const taken = pool.takeMfaChallenge(profileId, challengeId)
  if (!taken.ok) {
    respondRejection(req, res, badRequest(taken.error, mfaChallengeDetail(taken.error)))
    return
  }

  const startedAt = Date.now()
  try {
    runtime.logger('recgov auth mfa completion start', `profile=${profileId}`)
    const outcome = await taken.challenge.complete(mfaCode)
    const status = loginStatus(pool, profileId, outcome)
    runtime.logger('recgov auth mfa completion result', status.state, `profile=${profileId}`, `duration_ms=${Date.now() - startedAt}`)
    if (status.logged_in === true) {
      pool.clearLoginFailure(profileId)
      respondAuthResult(req, res, authHttpStatus(status), authResponseBody(status))
      return
    }
    // A wrong code is not a failed credential login: arming the credential
    // backoff here would lock the user out of retrying over a typo.
    respondAuthResult(req, res, HTTP_UNAUTHORIZED, {
      ...authActionResponseBody(status, false),
      error: ERROR_MFA_INVALID,
      detail: outcome?.detail || outcome?.reason || 'Recreation.gov rejected the code',
    })
  } catch (error) {
    runtime.logger('recgov auth mfa completion exception', `profile=${profileId}`, error.message)
    const status = pool.setAuthStatus(profileId, authExceptionStatus(OPERATION_LOGIN, error))
    respondAuthResult(req, res, HTTP_INTERNAL_ERROR, authActionResponseBody(status, false))
  } finally {
    taken.challenge.release()
  }
}

// Maps a credential-login outcome onto the auth status shape the rest of the
// companion (health, responses, logs) already speaks, and records it against
// the profile.
function loginStatus (pool, profileId, outcome) {
  const base = {
    state: outcome?.state === LOGIN_STATE_OK ? 'ok' : 'failed',
    logged_in: outcome?.logged_in === true,
    operation: OPERATION_LOGIN,
    checked_at: new Date().toISOString(),
    diagnostic: outcome?.diagnostic || null,
  }
  if (base.logged_in) return pool.setAuthStatus(profileId, base)
  const failure = recgovAuthenticationFailure({ attemptedLogin: true })
  return pool.setAuthStatus(profileId, {
    ...base,
    ...failure,
    ...(outcome?.reason ? { error: outcome.reason } : {}),
    ...(outcome?.detail ? { detail: outcome.detail } : {}),
  })
}

function mfaChallengeDetail (error) {
  if (error === ERROR_MFA_CHALLENGE_EXPIRED) return 'the MFA challenge expired; start the login again'
  if (error === ERROR_MFA_CHALLENGE_UNKNOWN) return 'no pending MFA challenge matches challenge_id'
  return null
}

// Shared prologue for the mutating profile routes that carry no other input.
async function profileRequest (req, res, requestUrl, pool, operation) {
  let input
  try {
    input = await readRequestFields(req, requestUrl)
  } catch (error) {
    respondRejection(req, res, invalidJsonRejection(error))
    return null
  }

  const profile = requireProfileId(input.fields)
  if (!profile.ok) {
    respondRejection(req, res, badRequest(profile.error, `profile_id identifies the browser profile to ${operation}`))
    return null
  }

  const lock = pool.acquire(profile.profileId, operation)
  if (!lock) {
    respondRejection(req, res, profileBusyResponse(profile.profileId, pool.busyOperation(profile.profileId)))
    return null
  }
  return { profileId: profile.profileId, lock, fields: input.fields }
}

function url (req) {
  return new URL(req.url || '/', 'http://companion.local')
}

function loginCredentialsFromFields (fields) {
  return {
    email: fields.email || fields.username,
    password: fields.password,
    mfaCode: fields.mfaCode || fields.mfa_code,
  }
}

function respondRejection (req, res, rejection) {
  respondAuthResult(req, res, rejection.status, rejection.body)
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
