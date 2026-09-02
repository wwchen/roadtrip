// Shared-secret middleware for the backend -> companion channel.
//
// The channel carries rec.gov passwords and drives a logged-in browser, so
// every route requires the header — the operator page, the docs, the OpenAPI
// document and the screenshots included. The single exemption is `GET /health`
// from loopback, which is the compose healthcheck: it has no way to hold a
// secret and cannot reach the companion from off-box (the service publishes no
// ports and has no Caddy route).

import fs from 'node:fs'
import { timingSafeEqual } from 'node:crypto'
import {
  HTTP_SERVICE_UNAVAILABLE,
  HTTP_UNAUTHORIZED,
} from './constants.js'

export const COMPANION_API_TOKEN_HEADER = 'x-companion-token'
export const COMPANION_API_TOKEN_FILE_DEFAULT = '/run/secrets/companion_api_token'
export const HEALTH_OPERATION_ID = 'getHealth'

export const ERROR_UNAUTHORIZED = 'unauthorized'
export const ERROR_COMPANION_AUTH_UNCONFIGURED = 'companion_auth_unconfigured'

const LOOPBACK_ADDRESSES = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1', 'localhost'])

export function companionApiToken (env = process.env) {
  const fromEnv = String(env.COMPANION_API_TOKEN || '').trim()
  if (fromEnv) return fromEnv
  const file = env.COMPANION_API_TOKEN_FILE || COMPANION_API_TOKEN_FILE_DEFAULT
  try {
    return fs.readFileSync(file, 'utf8').trim()
  } catch {
    return ''
  }
}

export function isLoopbackRequest (req) {
  const address = req?.socket?.remoteAddress ?? req?.connection?.remoteAddress ?? null
  return address !== null && LOOPBACK_ADDRESSES.has(address)
}

export function authorizeCompanionRequest ({ req, route, token }) {
  const healthFromLoopback = route?.operationId === HEALTH_OPERATION_ID && isLoopbackRequest(req)
  if (healthFromLoopback) return null
  if (!token) {
    return {
      status: HTTP_SERVICE_UNAVAILABLE,
      body: {
        ok: false,
        error: ERROR_COMPANION_AUTH_UNCONFIGURED,
        detail: 'COMPANION_API_TOKEN is not set; the companion refuses every non-localhost-health request',
      },
    }
  }
  const presented = String(req?.headers?.[COMPANION_API_TOKEN_HEADER] || '')
  if (secretsMatch(presented, token)) return null
  return {
    status: HTTP_UNAUTHORIZED,
    body: {
      ok: false,
      error: ERROR_UNAUTHORIZED,
      detail: `missing or invalid ${COMPANION_API_TOKEN_HEADER} header`,
    },
  }
}

function secretsMatch (presented, expected) {
  const a = Buffer.from(presented)
  const b = Buffer.from(expected)
  if (a.length !== b.length) return false
  return timingSafeEqual(a, b)
}
