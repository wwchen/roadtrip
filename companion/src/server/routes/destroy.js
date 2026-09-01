// Destroy one browser profile: the true-wipe half of "remove my credentials".
//
// `POST /logout` clicks through rec.gov's sign-out flow and leaves the
// user-data directory and the saved cookie jar on disk. That is the right
// behaviour for a logout and the wrong behaviour for a removal, so the two are
// separate routes rather than one route with a flag: logout drives a browser
// and needs one, destroy needs no browser at all and must work for a profile
// that was never launched.
//
// Takes the per-profile busy lock, so it can never race a login, verify or ATC
// mid-flight on the same profile.

import { HTTP_BAD_REQUEST, HTTP_INTERNAL_ERROR, HTTP_OK } from '../constants.js'
import { jsonResponse } from '../http.js'
import {
  badRequest,
  invalidJsonRejection,
  profileBusyResponse,
  readRequestFields,
  requireProfileId,
} from '../requestInput.js'

const OPERATION_DESTROY = 'destroy'

export async function handleDestroy (req, res, { runtime, pool }) {
  let input
  try {
    input = await readRequestFields(req, new URL(req.url || '/', 'http://companion.local'))
  } catch (error) {
    const rejection = invalidJsonRejection(error)
    return jsonResponse(res, rejection.status, rejection.body)
  }

  const profile = requireProfileId(input.fields)
  if (!profile.ok) {
    const rejection = badRequest(profile.error, 'profile_id identifies the browser profile to destroy')
    return jsonResponse(res, rejection.status, rejection.body)
  }

  const lock = pool.acquire(profile.profileId, OPERATION_DESTROY)
  if (!lock) {
    const rejection = profileBusyResponse(profile.profileId, pool.busyOperation(profile.profileId))
    return jsonResponse(res, rejection.status, rejection.body)
  }

  const startedAt = Date.now()
  try {
    runtime.logger('recgov profile destroy start', `profile=${profile.profileId}`)
    const result = await pool.destroyProfile(profile.profileId)
    if (!result.ok) {
      return jsonResponse(res, HTTP_BAD_REQUEST, { ok: false, profile_id: profile.profileId, error: result.error })
    }
    runtime.logger(
      'recgov profile destroy result',
      `profile=${profile.profileId}`,
      `dir_removed=${result.directory_removed}`,
      `duration_ms=${Date.now() - startedAt}`,
    )
    return jsonResponse(res, HTTP_OK, { ...result })
  } catch (error) {
    runtime.logger('recgov profile destroy exception', `profile=${profile.profileId}`, error.message)
    return jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      profile_id: profile.profileId,
      error: 'profile_destroy_failed',
      detail: error.message,
    })
  } finally {
    lock.release()
  }
}
