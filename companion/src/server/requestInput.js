// Request parsing shared by every profile-scoped companion route.
//
// `profile_id` is required on every mutating route and on the screenshot
// route, and may arrive as a query parameter or in the body (JSON or form
// encoded), because the operator page posts forms while the backend posts
// JSON.

import {
  ERROR_BROWSER_CAP_REACHED,
  normalizeProfileId,
} from '../profilePool.js'
import {
  HTTP_BAD_REQUEST,
  HTTP_CONFLICT,
  HTTP_SERVICE_UNAVAILABLE,
} from './constants.js'
import { readBody } from './http.js'

export const ERROR_INVALID_REQUEST = 'invalid_request'
export const ERROR_PROFILE_BUSY = 'profile_busy'
export const PROFILE_ID_FIELD = 'profile_id'
// A parse error's message quotes the body, and a malformed /login body holds
// a password. Never echo it.
export const INVALID_JSON_DETAIL = 'request body is not valid JSON'

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

// Resolving the profile's browser is where the concurrency cap bites, so it
// happens in the route rather than deep inside an automation call — that is
// what turns `browser_cap_reached` into a structured answer instead of a 500.
export async function resolveProfileContext (pool, profileId) {
  try {
    return { ok: true, context: await pool.context(profileId) }
  } catch (error) {
    if (error.code !== ERROR_BROWSER_CAP_REACHED) throw error
    return {
      ok: false,
      rejection: {
        status: HTTP_SERVICE_UNAVAILABLE,
        body: {
          ok: false,
          error: ERROR_BROWSER_CAP_REACHED,
          detail: error.message,
        },
      },
    }
  }
}

export function invalidJsonRejection (error) {
  return {
    status: error?.status || HTTP_BAD_REQUEST,
    body: {
      ok: false,
      error: ERROR_INVALID_REQUEST,
      detail: error?.status ? error.message : INVALID_JSON_DETAIL,
    },
  }
}

function bodyFields (raw, contentType) {
  if (!raw.trim()) return {}
  if (contentType.includes('application/json')) {
    const parsed = parseJsonBody(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  }
  return Object.fromEntries(new URLSearchParams(raw).entries())
}

export function parseJsonBody (raw) {
  try {
    return JSON.parse(raw)
  } catch {
    throw new Error(INVALID_JSON_DETAIL)
  }
}
