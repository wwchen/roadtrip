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

const SESSION_DIR = process.env.SESSION_DIR
  || path.join(os.homedir(), '.campsite-companion', 'browser-session')

let sharedContext = null

function clearStaleLocks () {
  for (const name of ['SingletonLock', 'SingletonSocket', 'SingletonCookie']) {
    const f = path.join(SESSION_DIR, name)
    try { if (fs.existsSync(f)) fs.unlinkSync(f) } catch {}
  }
}

export async function getContext () {
  if (sharedContext) {
    try { await sharedContext.pages(); return sharedContext } catch { sharedContext = null }
  }
  if (!fs.existsSync(SESSION_DIR)) fs.mkdirSync(SESSION_DIR, { recursive: true })
  clearStaleLocks()
  sharedContext = await chromium.launchPersistentContext(SESSION_DIR, {
    headless: IS_HEADLESS,
    slowMo: IS_HEADLESS ? 0 : 200,
    viewport: { width: 1280, height: 900 },
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    args: ['--disable-blink-features=AutomationControlled'],
    ignoreDefaultArgs: ['--enable-automation'],
  })
  await sharedContext.addInitScript(() => {
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
  sharedContext.once('close', () => { sharedContext = null })
  return sharedContext
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

export async function injectStoredCookies (context, rawInput = null) {
  const cookieStr = extractCookiesFromInput(rawInput || getSetting('recgov_cookies') || '')
  if (!cookieStr) return 0
  const cookies = parseCookieString(cookieStr)
  if (!cookies.length) return 0
  await context.addCookies(cookies)
  return cookies.length
}

export async function injectBearerRoute (page, token = null) {
  const t = token || getSetting('recgov_token') || ''
  if (!t) return false
  await page.route('https://www.recreation.gov/api/**', async route => {
    await route.continue({ headers: { ...route.request().headers(), authorization: `Bearer ${t}` } })
  })
  return true
}

export async function injectRecaccount (page, recaccount) {
  const v = JSON.stringify(recaccount)
  await page.addInitScript(({ v }) => {
    try { localStorage.setItem('recaccount', v) } catch {}
  }, { v })
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
  return `https://www.recreation.gov/camping/campsites/${campsiteId}?startDate=${startDate}&endDate=${checkoutDate}`
}

export function toCheckoutDate (lastNight) {
  const d = new Date(lastNight + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10)
}
