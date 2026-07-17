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

export const SCREENSHOT_ROUTE = '/screenshot'
export const SCREENSHOT_ROUTE_PREFIX = `${SCREENSHOT_ROUTE}/`

const RECGOV_ORIGIN = new URL(RECGOV_HOME_URL).origin
const SCREENSHOT_NAVIGATION_TIMEOUT_MS = 30_000
const SCREENSHOT_SETTLE_MS = 2_000
const SCREENSHOT_VIEWPORT_WIDTH = 1280
const SCREENSHOT_VIEWPORT_HEIGHT = 1000

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

export async function captureRecgovScreenshot (target, deps = createRecgovScreenshotDeps()) {
  let page = null
  const context = await deps.getContextFn()
  await deps.injectStoredCookiesFn(context)
  page = await context.newPage()
  try {
    const recaccount = await deps.resolveRecaccountFn(page, { allowManualLogin: false })
    if (recaccount?.access_token) {
      await deps.injectRecaccountFn(page, recaccount)
      await deps.injectBearerRouteFn(page, recaccount.access_token)
    }
    await setScreenshotViewport(page)
    await page.goto(target.href, {
      waitUntil: 'domcontentloaded',
      timeout: SCREENSHOT_NAVIGATION_TIMEOUT_MS,
    })
    await page.waitForTimeout(SCREENSHOT_SETTLE_MS)
    const image = await page.screenshot({ type: 'png', fullPage: false })
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
  if (!raw) return new URL(RECGOV_HOME_URL)
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

async function setScreenshotViewport (page) {
  if (typeof page.setViewportSize !== 'function') return
  await page.setViewportSize({
    width: SCREENSHOT_VIEWPORT_WIDTH,
    height: SCREENSHOT_VIEWPORT_HEIGHT,
  })
}

function screenshotTargetInput (url) {
  const urlParam = url.searchParams.get('url')
  if (urlParam) return urlParam
  const pathParam = url.searchParams.get('path')
  if (pathParam) return screenshotPathWithExtraParams(pathParam, url.searchParams)
  if (!url.pathname.startsWith(SCREENSHOT_ROUTE_PREFIX)) return null
  return `/${decodeURIComponent(url.pathname.slice(SCREENSHOT_ROUTE_PREFIX.length))}${url.search}`
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
