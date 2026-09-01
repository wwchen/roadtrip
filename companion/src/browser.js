// Playwright context management + injection helpers. Owns the persistent
// Chromium profile and rec.gov auth state.

import { chromium } from 'playwright'
import path from 'node:path'
import fs from 'node:fs'
import os from 'node:os'
import { getSetting } from './store.js'
import { extractCookiesFromInput } from './auth.js'

export const IS_HEADLESS = process.env.HEADLESS !== undefined
  ? process.env.HEADLESS !== 'false'
  : fs.existsSync('/.dockerenv')

export function resolveSessionDir (env = process.env, homeDir = os.homedir()) {
  return env.COMPANION_BROWSER_PROFILE ||
    env.SESSION_DIR ||
    path.join(homeDir, '.campsite-companion', 'browser-session')
}

const SESSION_DIR = resolveSessionDir()
const RECGOV_RECACCOUNT_STORAGE_KEY = 'recaccount'
const RECGOV_COOKIES_SETTING = 'recgov_cookies'
export const RECGOV_CAMPSITE_BOOKING_URL_PATTERN =
  'https://www.recreation.gov/camping/campsites/{campsite_id}?startDate={start_date}&endDate={end_date}'
export const COMPANION_USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'

export const CHROMIUM_SINGLETON_LOCK_FILES = Object.freeze(['SingletonLock', 'SingletonSocket', 'SingletonCookie'])

let sharedContext = null

// Directories with a live browser on them. A singleton lock there belongs to
// that browser, not to a crash, and deleting it would let a second Chromium
// attach to the same user-data directory and corrupt the profile.
const liveProfileDirs = new Set()

function clearStaleLocks (profileDir) {
  if (liveProfileDirs.has(profileDir)) return
  for (const name of CHROMIUM_SINGLETON_LOCK_FILES) {
    const f = path.join(profileDir, name)
    try { if (fs.existsSync(f)) fs.unlinkSync(f) } catch {}
  }
}

export async function getContext () {
  if (sharedContext) {
    try { await sharedContext.pages(); return sharedContext } catch { sharedContext = null }
  }
  sharedContext = await launchProfileContext(SESSION_DIR)
  sharedContext.once('close', () => { sharedContext = null })
  return sharedContext
}

// One persistent Chromium profile directory in, one browser process out. The
// profile pool calls this per profile id; getContext keeps the single legacy
// profile the CLI entrypoints use.
export async function launchProfileContext (profileDir, { chromiumFn = chromium } = {}) {
  if (!fs.existsSync(profileDir)) fs.mkdirSync(profileDir, { recursive: true })
  clearStaleLocks(profileDir)
  const context = await chromiumFn.launchPersistentContext(profileDir, {
    headless: IS_HEADLESS,
    slowMo: IS_HEADLESS ? 0 : 200,
    viewport: { width: 1280, height: 900 },
    userAgent: COMPANION_USER_AGENT,
    args: ['--disable-blink-features=AutomationControlled'],
    ignoreDefaultArgs: ['--enable-automation'],
  })
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined })
    if (!window.chrome) window.chrome = { runtime: {}, loadTimes: () => {}, csi: () => {}, app: {} }
    if (navigator.plugins.length === 0) {
      Object.defineProperty(navigator, 'plugins', {
        get: () => Object.assign([
          { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
          { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
          { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' },
        ], { item: function (i) { return this[i] }, namedItem: function (n) { return this.find(p => p.name === n) }, refresh: () => {} }),
      })
    }
    if (!navigator.languages || navigator.languages.length === 0) {
      Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] })
    }
    if (navigator.platform === 'Linux x86_64' || navigator.platform === '') {
      Object.defineProperty(navigator, 'platform', { get: () => 'MacIntel' })
    }
    const origQuery = window.Permissions?.prototype?.query
    if (origQuery) {
      window.Permissions.prototype.query = function (params) {
        if (params?.name === 'notifications') return Promise.resolve({ state: 'default', onchange: null })
        return origQuery.call(this, params)
      }
    }
    const _origFetch = window.fetch
    window.fetch = function (...args) {
      try {
        const [url, init] = args
        if (typeof url === 'string' && url.includes('/camps/reservations/campgrounds') &&
            (init?.method || '').toUpperCase() === 'POST' && init?.body) {
          const body = JSON.parse(init.body)
          if (body?.gate_a?.value) {
            localStorage.setItem('__gate_a', JSON.stringify({ ...body.gate_a, ts: Date.now() }))
          }
        }
      } catch {}
      return _origFetch.apply(this, args)
    }
  })
  liveProfileDirs.add(profileDir)
  context.once('close', () => liveProfileDirs.delete(profileDir))
  return context
}

export async function clearSession () {
  if (sharedContext) {
    await sharedContext.close().catch(() => {})
    sharedContext = null
  }
  fs.rmSync(SESSION_DIR, { recursive: true, force: true })
  console.log('Browser session cleared')
}

function parseCookieString (str) {
  return str.split(';').map(part => {
    const eq = part.indexOf('=')
    if (eq < 0) return null
    const name = part.slice(0, eq).trim()
    const value = part.slice(eq + 1).trim()
    if (!name) return null
    return { name, value, domain: '.recreation.gov', path: '/', secure: true, sameSite: 'Lax' }
  }).filter(Boolean)
}

// Stored cookies are per profile. The unkeyed setting belongs to the legacy
// single-profile CLI session and must never be handed to a user's profile:
// a rec.gov cookie jar is a session, and sharing one is sharing an account.
export function recgovCookieSettingKey (profileId = null) {
  return profileId ? `${RECGOV_COOKIES_SETTING}:${profileId}` : RECGOV_COOKIES_SETTING
}

export async function injectStoredCookies (context, rawInput = null, profileId = null) {
  const stored = getSetting(recgovCookieSettingKey(profileId)) || ''
  const cookieStr = extractCookiesFromInput(rawInput || stored)
  if (!cookieStr) return 0
  const cookies = parseCookieString(cookieStr)
  if (!cookies.length) return 0
  await context.addCookies(cookies)
  return cookies.length
}

// rec.gov pins the JWT's `fingerprint` claim against the `r1s-fingerprint`
// cookie — sending Bearer with no cookie returns 401 {"error":"bad fingerprint"}
// from /api/cart/* and /api/camps/reservations/*. Inject it whenever we have
// a fingerprint claim in the active token.
export async function injectFingerprintCookie (context, token) {
  if (!token) return false
  let fingerprint
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
    fingerprint = payload.fingerprint
  } catch { return false }
  if (!fingerprint) return false
  await context.addCookies([{
    name: 'r1s-fingerprint',
    value: fingerprint,
    domain: '.recreation.gov',
    path: '/',
    secure: true,
    sameSite: 'Lax',
  }])
  return true
}

export async function injectBearerRoute (page, token = null) {
  // Backend owns the token (RFC 0001 / PR 6). Caller must pass it explicitly;
  // there's no longer a local fallback. Returning false when no token lets
  // the caller continue without auth (handy for diagnostic flows like
  // testChromium when the SPA can do a silent Auth0 round-trip).
  if (!token) return false
  await page.route('https://www.recreation.gov/api/**', async route => {
    await route.continue({ headers: { ...route.request().headers(), authorization: `Bearer ${token}` } })
  })
  return true
}

export async function injectRecaccount (page, recaccount) {
  const v = JSON.stringify(recaccount)
  await page.addInitScript(({ v }) => {
    try { localStorage.setItem('recaccount', v) } catch {}
  }, { v })
}

export async function readRecaccount (page) {
  return page.evaluate((key) => {
    try {
      const value = localStorage.getItem(key)
      return typeof value === 'string' && value.trim() ? value : null
    } catch {
      return null
    }
  }, RECGOV_RECACCOUNT_STORAGE_KEY).catch(() => null)
}

export async function isSpaLoggedIn (page) {
  const loggedOut = await page
    .locator('button:has-text("Sign Up / Log In"), a:has-text("Sign Up / Log In")')
    .first().isVisible().catch(() => null)
  if (loggedOut === null) return null
  return !loggedOut
}

export function reservationUrl (campgroundId, startDate, checkoutDate) {
  return `https://www.recreation.gov/camping/campgrounds/${campgroundId}?startDate=${startDate}&endDate=${checkoutDate}`
}

export function campsiteUrl (campsiteId, startDate, checkoutDate) {
  return RECGOV_CAMPSITE_BOOKING_URL_PATTERN
    .replace('{campsite_id}', campsiteId)
    .replace('{start_date}', startDate)
    .replace('{end_date}', checkoutDate)
}

export function toCheckoutDate (lastNight) {
  const d = new Date(lastNight + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10)
}
