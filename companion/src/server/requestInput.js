// Request parsing shared by every profile-scoped companion route.
//
// `profile_id` is required on every mutating route and on the screenshot
// route, and may arrive as a query parameter or in the body (JSON or form
// encoded), because the operator page posts forms while the backend posts
// JSON.

import { normalizeProfileId } from '../profilePool.js'
import {
  HTTP_BAD_REQUEST,
  HTTP_CONFLICT,
} from './constants.js'
import { readBody } from './http.js'

export const ERROR_INVALID_REQUEST = 'invalid_request'
export const ERROR_PROFILE_BUSY = 'profile_busy'
export const PROFILE_ID_FIELD = 'profile_id'

export async function readRequestFields (req, url) {
  const raw = await readBody(req)
  const contentType = String(req.headers['content-type'] || '')
  return {
    raw,
    fields: {
      ...Object.fromEntries(url.searchParams.entries()),
      ...bodyFields(raw, contentType),
    },
  }
}

export function requireProfileId (fields) {
  return normalizeProfileId(fields?.[PROFILE_ID_FIELD])
}

export function badRequest (error, detail) {
  return {
    status: HTTP_BAD_REQUEST,
    body: { ok: false, error, detail },
  }
}

export function profileBusyResponse (profileId, operation) {
  return {
    status: HTTP_CONFLICT,
    body: {
      ok: false,
      error: ERROR_PROFILE_BUSY,
      detail: `profile ${profileId} is already running ${operation || 'work'}`,
    },
  }
}

function bodyFields (raw, contentType) {
  if (!raw.trim()) return {}
  if (contentType.includes('application/json')) {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  }
  return Object.fromEntries(new URLSearchParams(raw).entries())
}
