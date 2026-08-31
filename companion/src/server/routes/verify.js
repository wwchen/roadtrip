import { verifyRecgovSession } from '../../recgovVerify.js'
import {
  HTTP_BAD_REQUEST,
  HTTP_INTERNAL_ERROR,
  HTTP_OK,
  HTTP_UNAUTHORIZED,
} from '../constants.js'
import { jsonResponse } from '../http.js'
import {
  ERROR_INVALID_REQUEST,
  badRequest,
  profileBusyResponse,
  readRequestFields,
  requireProfileId,
} from '../requestInput.js'

const OPERATION_VERIFY = 'verify'
const VERIFY_EXCEPTION_ERROR = 'recgov_verify_exception'

export async function handleVerify (req, res, { runtime, pool, verifyRecgovSessionFn = verifyRecgovSession }) {
  let input
  try {
    input = await readRequestFields(req, new URL(req.url || '/', 'http://companion.local'))
  } catch (error) {
    jsonResponse(res, error.status || HTTP_BAD_REQUEST, {
      ok: false,
      error: ERROR_INVALID_REQUEST,
      detail: error.message,
    })
    return
  }

  const profile = requireProfileId(input.fields)
  if (!profile.ok) {
    const rejection = badRequest(profile.error, 'profile_id identifies the browser profile to verify')
    jsonResponse(res, rejection.status, rejection.body)
    return
  }

  const profileId = profile.profileId
  const lock = pool.acquire(profileId, OPERATION_VERIFY)
  if (!lock) {
    const rejection = profileBusyResponse(profileId, pool.busyOperation(profileId))
    jsonResponse(res, rejection.status, rejection.body)
    return
  }

  const startedAt = Date.now()
  try {
    runtime.logger('recgov verify start', `profile=${profileId}`)
    const verify = await verifyRecgovSessionFn({ getContextFn: () => pool.context(profileId) })
    pool.setAuthStatus(profileId, verifyAuthStatus(verify))
    runtime.logger(
      'recgov verify result',
      verify.ok ? 'ok' : 'failed',
      `profile=${profileId}`,
      `logged_in=${verify.logged_in}`,
      `cart_status=${verify.cart_status ?? '?'}`,
      `duration_ms=${Date.now() - startedAt}`,
    )
    jsonResponse(res, verify.ok ? HTTP_OK : HTTP_UNAUTHORIZED, {
      ok: verify.ok === true,
      profile_id: profileId,
      verify,
      recgov_auth: pool.getAuthStatus(profileId),
    })
  } catch (error) {
    runtime.logger('recgov verify exception', `profile=${profileId}`, error.message)
    jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      profile_id: profileId,
      error: VERIFY_EXCEPTION_ERROR,
      detail: error.message,
    })
  } finally {
    lock.release()
  }
}

function verifyAuthStatus (verify) {
  return {
    state: verify.ok ? 'ok' : 'failed',
    logged_in: verify.logged_in === true,
    operation: OPERATION_VERIFY,
    checked_at: verify.checked_at,
    ...(verify.ok ? {} : { error: verify.error, detail: verify.detail, corrective_action: verify.corrective_action }),
  }
}
