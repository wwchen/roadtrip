// Recreation.gov session lifecycle for the companion-owned browser profile.
// The backend never sees rec.gov credentials; this module reads and refreshes
// localStorage.recaccount inside the same Chromium context that clicks ATC.

import { Buffer } from 'node:buffer'
import {
  IS_HEADLESS,
  injectFingerprintCookie,
  readRecaccount,
} from './browser.js'

export const RECGOV_HOME_URL = 'https://www.recreation.gov/'
export const RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS = 20_000
export const RECGOV_LOGIN_STATE_SETTLE_MS = 2_000

const RECGOV_REFRESH_URL = 'https://www.recreation.gov/api/accounts/login/v2/refresh'
const RECGOV_REFRESH_CONTENT_TYPE = 'text/plain;charset=UTF-8'
const RECGOV_RECACCOUNT_STORAGE_KEY = 'recaccount'
const DEFAULT_RECGOV_LOGIN_TIMEOUT_MS = 120_000
const RECGOV_LOGIN_POLL_MS = 1_000
const RECGOV_LOGIN_BUTTON_TIMEOUT_MS = 5_000
const RECGOV_REFRESH_AHEAD_MS = 5 * 60 * 1_000
const RECGOV_REFRESH_MAX_ATTEMPTS = 3
const RECGOV_REFRESH_RETRY_DELAY_MS = 1_000
const RECGOV_JWT_PAYLOAD_INDEX = 1
const RECGOV_JWT_MIN_PARTS = 2
const RECGOV_REFRESH_LOG_BODY_LIMIT = 200
const MILLISECONDS_PER_SECOND = 1_000
const MIN_LOGIN_TIMEOUT_MS = 1

const LOGIN_LINK_SEL = 'button:has-text("Sign Up / Log In"), a:has-text("Sign Up / Log In")'

export async function resolveRecaccount (page, options = {}) {
  const browserSession = await recaccountFromBrowser(page, options)
  if (browserSession.recaccount) return browserSession.recaccount
  if (options.forceRefresh === true && browserSession.foundSession && !options.allowManualLoginAfterRefreshFailure) return null
  if (options.forceRefresh === true && browserSession.foundSession && options.allowManualLoginAfterRefreshFailure && !IS_HEADLESS) {
    console.log('Cart: Recreation.gov refresh failed; log in again to replace the stale browser session')
  }

  if (!IS_HEADLESS) {
    const loginRecaccount = await recaccountFromManualLogin(page, options)
    if (loginRecaccount) return loginRecaccount
  }

  if (IS_HEADLESS) {
    console.log('Cart: no Recreation.gov browser session and companion is headless — run the companion headed and log in once')
  }
  return null
}

export function parseRecaccount (raw) {
  if (!raw || typeof raw !== 'string') return null
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

export function recaccountNeedsRefresh (recaccount, nowMs = Date.now()) {
  const expiresAt = tokenExpiresAtMs(recaccount)
  return expiresAt !== null && nowMs >= expiresAt - RECGOV_REFRESH_AHEAD_MS
}

async function recaccountFromBrowser (page, options) {
  await page.goto(RECGOV_HOME_URL, {
    waitUntil: 'domcontentloaded',
    timeout: RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS,
  }).catch((err) => {
    console.log(`Cart: could not open Recreation.gov login page — ${err.message}`)
  })

  const browserSession = await readRecaccountFromOpenPages(page)
  if (!browserSession) return { foundSession: false, recaccount: null }
  console.log('Cart: found Recreation.gov session in companion browser')
  return {
    foundSession: true,
    recaccount: await activateBrowserRecaccount(browserSession.page, browserSession.raw, options),
  }
}

async function activateBrowserRecaccount (page, raw, options) {
  const recaccount = parseRecaccount(raw)
  if (!recaccount?.access_token) {
    console.log('Cart: companion browser recaccount is invalid')
    return null
  }

  await injectFingerprintCookie(page.context(), recaccount.access_token)
  return refreshBrowserRecaccountIfNeeded(page, recaccount, options)
}

async function recaccountFromManualLogin (page, options) {
  const timeoutMs = recgovLoginTimeoutMs()
  console.log(`Cart: log in to Recreation.gov in the companion browser (waiting up to ${secondsLabel(timeoutMs)}s)`)
  await page.goto(RECGOV_HOME_URL, {
    waitUntil: 'domcontentloaded',
    timeout: RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS,
  }).catch((err) => {
    console.log(`Cart: could not reopen Recreation.gov login page — ${err.message}`)
  })
  await openLoginIfPossible(page)
  const browserSession = await waitForBrowserRecaccount(page, timeoutMs)
  if (!browserSession) {
    console.log(`Cart: Recreation.gov login wait timed out after ${secondsLabel(timeoutMs)}s`)
    return null
  }
  return activateBrowserRecaccount(browserSession.page, browserSession.raw, options)
}

function recgovLoginTimeoutMs () {
  const configured = Number.parseInt(process.env.RECGOV_LOGIN_TIMEOUT_MS || '', 10)
  return Number.isFinite(configured) && configured >= MIN_LOGIN_TIMEOUT_MS
    ? configured
    : DEFAULT_RECGOV_LOGIN_TIMEOUT_MS
}

function secondsLabel (millis) {
  return Math.ceil(millis / MILLISECONDS_PER_SECOND)
}

async function openLoginIfPossible (page) {
  await page.locator(LOGIN_LINK_SEL).first()
    .click({ timeout: RECGOV_LOGIN_BUTTON_TIMEOUT_MS })
    .catch(() => {})
}

async function waitForBrowserRecaccount (page, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const browserSession = await readRecaccountFromOpenPages(page)
    if (browserSession) return browserSession

    const remaining = deadline - Date.now()
    if (remaining <= 0) return null
    await page.waitForTimeout(Math.min(RECGOV_LOGIN_POLL_MS, remaining))
  }
  return null
}

async function readRecaccountFromOpenPages (page) {
  for (const candidate of page.context().pages()) {
    const raw = await readRecaccount(candidate)
    if (raw) return { page: candidate, raw }
  }
  return null
}

async function refreshBrowserRecaccountIfNeeded (page, recaccount, options = {}) {
  const forceRefresh = options.forceRefresh === true
  if (!forceRefresh && !recaccountNeedsRefresh(recaccount)) return recaccount

  const credentials = refreshCredentials(recaccount)
  if (!credentials) {
    if (forceRefresh) {
      console.log('Cart: Recreation.gov browser recaccount has no refresh credentials')
      return null
    }
    if (recaccountIsExpired(recaccount)) {
      console.log('Cart: Recreation.gov browser recaccount is expired and has no refresh credentials')
      return null
    }
    return recaccount
  }

  const refreshed = await refreshRecaccountInBrowserWithRetry(page, recaccount.access_token, credentials)
  if (refreshed?.access_token) {
    await injectFingerprintCookie(page.context(), refreshed.access_token)
    console.log(`Cart: refreshed Recreation.gov browser session (expires ${refreshed.expiration})`)
    return refreshed
  }

  if (forceRefresh) {
    console.log('Cart: forced Recreation.gov browser refresh failed')
    await clearBrowserRecaccount(page)
    return null
  }

  if (recaccountIsExpired(recaccount)) {
    console.log('Cart: Recreation.gov browser recaccount is expired and refresh failed — log in again')
    await clearBrowserRecaccount(page)
    return null
  }

  return recaccount
}

async function refreshRecaccountInBrowserWithRetry (page, token, credentials) {
  for (let attempt = 1; attempt <= RECGOV_REFRESH_MAX_ATTEMPTS; attempt++) {
    const result = await refreshRecaccountInBrowser(page, token, credentials)
    if (result.recaccount?.access_token) return result.recaccount
    if (!result.retryable || attempt === RECGOV_REFRESH_MAX_ATTEMPTS) return null

    console.log(`Cart: retrying Recreation.gov browser refresh (${attempt + 1}/${RECGOV_REFRESH_MAX_ATTEMPTS})`)
    await page.waitForTimeout(RECGOV_REFRESH_RETRY_DELAY_MS)
  }
  return null
}

function refreshCredentials (recaccount) {
  const accountId = recaccount?.account?.account_id
  const refreshId = recaccount?.refresh_id
  if (!accountId || !refreshId) return null
  return { account_id: accountId, refresh_id: refreshId }
}

function recaccountIsExpired (recaccount, nowMs = Date.now()) {
  const expiresAt = tokenExpiresAtMs(recaccount)
  return expiresAt !== null && nowMs >= expiresAt
}

function tokenExpiresAtMs (recaccount) {
  const jwtExp = decodeJwtPayload(recaccount?.access_token)?.exp
  if (Number.isFinite(jwtExp)) return jwtExp * MILLISECONDS_PER_SECOND

  const parsedExpiration = Date.parse(recaccount?.expiration || '')
  return Number.isFinite(parsedExpiration) ? parsedExpiration : null
}

function decodeJwtPayload (token) {
  if (!token || typeof token !== 'string') return null
  const parts = token.split('.')
  if (parts.length < RECGOV_JWT_MIN_PARTS) return null

  try {
    return JSON.parse(Buffer.from(parts[RECGOV_JWT_PAYLOAD_INDEX], 'base64url').toString('utf8'))
  } catch {
    return null
  }
}

async function clearBrowserRecaccount (page) {
  await page.evaluate(({ key }) => {
    localStorage.removeItem(key)
  }, { key: RECGOV_RECACCOUNT_STORAGE_KEY, clearRecaccount: true }).catch(() => {})
}

async function refreshRecaccountInBrowser (page, token, credentials) {
  const result = await page.evaluate(async ({ url, token, credentials, contentType, bodyLimit }) => {
    try {
      const response = await fetch(url, {
        method: 'POST',
        credentials: 'include',
        headers: {
          authorization: `Bearer ${token}`,
          'content-type': contentType,
        },
        body: JSON.stringify(credentials),
      })
      const text = await response.text()
      if (!response.ok) {
        return { ok: false, status: response.status, body: text.slice(0, bodyLimit) }
      }

      const recaccount = JSON.parse(text)
      localStorage.setItem('recaccount', JSON.stringify(recaccount))
      return { ok: true, recaccount }
    } catch (err) {
      return { ok: false, error: err.message }
    }
  }, {
    url: RECGOV_REFRESH_URL,
    token,
    credentials,
    contentType: RECGOV_REFRESH_CONTENT_TYPE,
    bodyLimit: RECGOV_REFRESH_LOG_BODY_LIMIT,
  }).catch((err) => ({ ok: false, error: err.message }))

  if (result.ok) return { recaccount: result.recaccount, retryable: false }
  const detail = result.status ? `HTTP ${result.status} ${result.body || ''}` : result.error
  console.log(`Cart: Recreation.gov browser refresh failed — ${detail}`)
  return { recaccount: null, retryable: !result.status }
}
