import {
  getContext,
  injectBearerRoute,
  injectRecaccount,
  injectStoredCookies,
} from './browser.js'
import {
  RECGOV_HOME_URL,
  resolveRecaccount,
} from './recgovSession.js'
import { captureRecgovPageImage } from './recgovScreenshotCapture.js'
import {
  SCREENSHOT_ROUTE,
} from './recgovScreenshotRoutes.js'

export {
  SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX,
  SCREENSHOT_ROUTE,
  SCREENSHOT_ROUTE_PREFIX,
} from './recgovScreenshotRoutes.js'

const RECGOV_ORIGIN = new URL(RECGOV_HOME_URL).origin
const SCREENSHOT_NAVIGATION_TIMEOUT_MS = 30_000
const SCREENSHOT_SETTLE_MS = 2_000

export function createRecgovScreenshotDeps ({
  getContextFn = getContext,
  injectStoredCookiesFn = injectStoredCookies,
  resolveRecaccountFn = resolveRecaccount,
  injectRecaccountFn = injectRecaccount,
  injectBearerRouteFn = injectBearerRoute,
} = {}) {
  return {
    getContextFn,
    injectStoredCookiesFn,
    resolveRecaccountFn,
    injectRecaccountFn,
    injectBearerRouteFn,
  }
}

export async function captureRecgovScreenshot (target, deps = createRecgovScreenshotDeps(), { profileId = null } = {}) {
  let page = null
  const context = await deps.getContextFn(profileId)
  await deps.injectStoredCookiesFn(context)
  page = await context.newPage()
  try {
    const recaccount = await deps.resolveRecaccountFn(page, { allowManualLogin: false })
    if (recaccount?.access_token) {
      await deps.injectRecaccountFn(page, recaccount)
      await deps.injectBearerRouteFn(page, recaccount.access_token)
    }
    await page.goto(target.href, {
      waitUntil: 'domcontentloaded',
      timeout: SCREENSHOT_NAVIGATION_TIMEOUT_MS,
    })
    await page.waitForTimeout(SCREENSHOT_SETTLE_MS)
    const image = await captureRecgovPageImage(page)
    return {
      image,
      recaccountPresent: Boolean(recaccount?.access_token),
    }
  } finally {
    if (page) await page.close().catch(() => {})
  }
}

export function recgovScreenshotTargetUrl (url) {
  const raw = screenshotTargetInput(url)
  if (raw === null) return new URL(RECGOV_HOME_URL)
  if (raw === false) return null
  try {
    const target = /^https?:\/\//i.test(raw)
      ? new URL(raw)
      : new URL(raw.startsWith('/') ? raw : `/${raw}`, RECGOV_HOME_URL)
    if (target.origin !== RECGOV_ORIGIN) return null
    target.hash = ''
    return target
  } catch {
    return null
  }
}

function screenshotTargetInput (url) {
  const urlParam = url.searchParams.get('url')
  if (urlParam) return urlParam
  const pathParam = url.searchParams.get('path')
  if (pathParam) return screenshotPathWithExtraParams(pathParam, url.searchParams)
  return url.pathname === SCREENSHOT_ROUTE ? null : false
}

function screenshotPathWithExtraParams (pathParam, searchParams) {
  const extra = new URLSearchParams(searchParams)
  extra.delete('path')
  extra.delete('url')
  const renderedExtra = extra.toString()
  if (!renderedExtra) return pathParam
  const separator = pathParam.includes('?') ? '&' : '?'
  return `${pathParam}${separator}${renderedExtra}`
}
