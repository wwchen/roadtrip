// The armed (keep-warm) profile set, pushed by the backend's keepalive job.
//
// The companion cannot know which profiles back an active `atc` watch — that
// lives in the backend's database — so it is told, wholesale, on every sweep.
// Replacing rather than merging is what lets a watch being paused or deleted
// actually disarm its profile.
//
// Deliberately lock-free and profile-agnostic: this marks profiles, it never
// drives a browser, so it must answer while any number of them are mid-login.

import { HTTP_OK } from '../constants.js'
import { jsonResponse } from '../http.js'
import {
  ERROR_INVALID_REQUEST,
  badRequest,
  invalidJsonRejection,
  readRequestFields,
} from '../requestInput.js'

const PROFILE_IDS_FIELD = 'profile_ids'

export async function handleKeepWarm (req, res, { runtime, pool }) {
  let input
  try {
    input = await readRequestFields(req, new URL(req.url || '/', 'http://companion.local'))
  } catch (error) {
    const rejection = invalidJsonRejection(error)
    return jsonResponse(res, rejection.status, rejection.body)
  }

  const ids = input.fields?.[PROFILE_IDS_FIELD]
  if (!Array.isArray(ids)) {
    const rejection = badRequest(ERROR_INVALID_REQUEST, `${PROFILE_IDS_FIELD} must be an array of profile ids`)
    return jsonResponse(res, rejection.status, rejection.body)
  }

  const armed = pool.setKeepWarmProfiles(ids)
  runtime.logger('recgov keep-warm set', `armed=${armed.keep_warm.length}`, `overflow=${armed.keep_warm_overflow}`)
  return jsonResponse(res, HTTP_OK, { ok: true, ...armed })
}
