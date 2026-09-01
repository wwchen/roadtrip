// Playwright context management + injection helpers. Owns the persistent
// Chromium profile and rec.gov auth state.

import { chromium } from 'playwright'
import path from 'node:path'
import fs from 'node:fs'
import os from 'node:os'
import { getSetting, setSetting } from './store.js'
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
const RECGOV_ORIGIN = 'https://www.recreation.gov'
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
  // Register before any further await: from here on a real browser owns this
  // directory, and a concurrent cold launch must not sweep its singleton
  // locks and attach a second Chromium to it.
  liveProfileDirs.add(profileDir)
  context.once('close', () => liveProfileDirs.delete(profileDir))
  try {
    await installStealthInitScript(context)
  } catch (error) {
    liveProfileDirs.delete(profileDir)
    await context.close().catch(() => {})
    throw error
  }
  return context
}

// Test seam for the lock sweep, which is otherwise only reachable through a
// real launch.
export function clearStaleLocksForTest (profileDir) {
  clearStaleLocks(profileDir)
}

async function installStealthInitScript (context) {
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

// Stored cookies are per profile. The unkeyed setting is the legacy
// single-profile CLI/operator value; see `storedCookiesFor` for the one narrow
// way it still reaches a profile.
export function recgovCookieSettingKey (profileId = null) {
  return profileId ? `${RECGOV_COOKIES_SETTING}:${profileId}` : RECGOV_COOKIES_SETTING
}

/**
 * The stored jar to launch a profile with.
 *
 * A profile's own key wins. When it is empty the operator-set legacy global
 * `recgov_cookies` — the documented Akamai cookie-paste workaround — is used
 * instead, because keying injection per profile without this orphaned every
 * environment that had the paste set.
 *
 * The fallback reaches ONLY the operator's global value, never another
 * profile's saved jar: `saveProfileCookies` refuses a null profile id, so
 * nothing this code writes can ever land in the global key. And it stops the
 * first time this profile saves a jar of its own — the paste bootstraps a
 * profile, it does not keep feeding it.
 */
function storedCookiesFor (profileId) {
  const own = getSetting(recgovCookieSettingKey(profileId)) || ''
  if (own || !profileId) return own
  return getSetting(recgovCookieSettingKey(null)) || ''
}

export async function injectStoredCookies (context, rawInput = null, profileId = null) {
  const cookieStr = extractCookiesFromInput(rawInput || storedCookiesFor(profileId))
  if (!cookieStr) return 0
  const cookies = parseCookieString(cookieStr)
  if (!cookies.length) return 0
  await context.addCookies(cookies)
  return cookies.length
}

/**
 * Saves this context's recreation.gov cookies to the profile's store key.
 *
 * **This is what makes a session outlive the browser.** Rec.gov's session
 * cookies — the Akamai/fingerprint pair the JWT is pinned to among them — are
 * session-scoped in Chromium, so they die with the process and a container
 * restart loses the login. The legacy operator flow survived only because
 * somebody pasted a cookie header into the store by hand.
 *
 * Slice 2 keyed *injection* per profile and `launchProfileContext` re-injects on
 * every launch, but nothing ever wrote the key: the save half of the round trip
 * did not exist. Every successful auth-bearing operation calls this, so the
 * store always holds the freshest jar we have seen.
 *
 * Failure paths deliberately do NOT call it — overwriting a good jar with the
 * cookies of a failed attempt would destroy the very thing being preserved.
 *
 * Treat the stored value as credential material: it IS the session.
 */
export async function saveProfileCookies (context, profileId = null) {
  if (!context || !profileId) return 0
  let cookies
  try {
    cookies = await context.cookies(RECGOV_ORIGIN)
  } catch {
    // A context mid-teardown must not fail the operation that just succeeded.
    return 0
  }
  const usable = (cookies || []).filter((cookie) => cookie?.name && cookie?.value)
  if (!usable.length) return 0
  // The same `name=value; …` header shape `extractCookiesFromInput` already
  // parses, so injection needs no new format to understand.
  setSetting(
    recgovCookieSettingKey(profileId),
    usable.map((cookie) => `${cookie.name}=${cookie.value}`).join('; '),
  )
  return usable.length
}

/**
 * `saveProfileCookies` with the reporting every caller wants and the failure
 * handling every caller needs.
 *
 * Four sites persist a jar — login, MFA completion, refresh, verify — and all
 * four want the same thing: never throw. The operation already succeeded and
 * the caller has been told so; losing the durability of a session is worth a
 * log line, not a 500.
 */
export async function persistProfileCookies (context, profileId, { logger = console.log, operation } = {}) {
  try {
    const saved = await saveProfileCookies(context, profileId)
    if (saved > 0) logger('recgov cookies persisted', `profile=${profileId}`, `op=${operation}`, `count=${saved}`)
    return saved
  } catch (error) {
    logger('recgov cookie persist failed', `profile=${profileId}`, `op=${operation}`, error.message)
    return 0
  }
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
