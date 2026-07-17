import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  captureRecgovScreenshot,
  createRecgovScreenshotDeps,
  recgovScreenshotTargetUrl,
} from '../src/recgovScreenshot.js'

test('recgovScreenshotTargetUrl accepts Recreation.gov paths and preserves target query params', () => {
  const target = recgovScreenshotTargetUrl(new URL(
    '/screenshot?path=/camping/campgrounds/232447&startDate=2026-07-19',
    'http://companion.local',
  ))

  assert.equal(target.href, 'https://www.recreation.gov/camping/campgrounds/232447?startDate=2026-07-19')
})

test('recgovScreenshotTargetUrl accepts absolute Recreation.gov URLs', () => {
  const target = recgovScreenshotTargetUrl(new URL(
    '/screenshot?url=https%3A%2F%2Fwww.recreation.gov%2Fcamping%2Fcampgrounds%2F232447%23ignored',
    'http://companion.local',
  ))

  assert.equal(target.href, 'https://www.recreation.gov/camping/campgrounds/232447')
})

test('recgovScreenshotTargetUrl rejects external targets', () => {
  assert.equal(
    recgovScreenshotTargetUrl(new URL('/screenshot?url=https://example.com/', 'http://companion.local')),
    null,
  )
})

test('recgovScreenshotTargetUrl rejects undocumented screenshot path suffixes', () => {
  assert.equal(
    recgovScreenshotTargetUrl(new URL('/screenshot/camping/campgrounds/232447', 'http://companion.local')),
    null,
  )
})

test('captureRecgovScreenshot uses the companion browser session and top viewport', async () => {
  const image = Buffer.from([0x89, 0x50, 0x4e, 0x47])
  const page = fakeScreenshotPage(image)
  let storedCookieContext = null
  let resolveOptions = null
  let injectedRecaccount = null
  let injectedToken = null

  const result = await captureRecgovScreenshot(new URL('https://www.recreation.gov/'), createRecgovScreenshotDeps({
    getContextFn: async () => ({
      newPage: async () => page,
    }),
    injectStoredCookiesFn: async (context) => {
      storedCookieContext = context
      return 0
    },
    resolveRecaccountFn: async (resolvedPage, options) => {
      assert.equal(resolvedPage, page)
      resolveOptions = options
      return { access_token: 'recgov-token' }
    },
    injectRecaccountFn: async (resolvedPage, recaccount) => {
      assert.equal(resolvedPage, page)
      injectedRecaccount = recaccount
    },
    injectBearerRouteFn: async (resolvedPage, token) => {
      assert.equal(resolvedPage, page)
      injectedToken = token
      return true
    },
  }))

  assert.deepEqual(result.image, image)
  assert.equal(result.recaccountPresent, true)
  assert.ok(storedCookieContext)
  assert.equal(resolveOptions.allowManualLogin, false)
  assert.deepEqual(injectedRecaccount, { access_token: 'recgov-token' })
  assert.equal(injectedToken, 'recgov-token')
  assert.equal(page.gotos[0].url, 'https://www.recreation.gov/')
  assert.deepEqual(page.viewportSize, { width: 1280, height: 1000 })
  assert.equal(page.screenshotOptions.fullPage, false)
  assert.equal(page.closed, true)
})

function fakeScreenshotPage (image) {
  const page = {
    gotos: [],
    waits: [],
    screenshotOptions: null,
    closed: false,
    goto: async (url, options) => {
      page.gotos.push({ url, options })
    },
    waitForTimeout: async (ms) => {
      page.waits.push(ms)
    },
    screenshot: async (options) => {
      page.screenshotOptions = options
      return image
    },
    setViewportSize: async (size) => {
      page.viewportSize = size
    },
    close: async () => {
      page.closed = true
    },
  }
  return page
}
