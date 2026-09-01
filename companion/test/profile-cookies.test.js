// The stored recgov_cookies value is a per-session Akamai fingerprint
// workaround. It must never cross profiles: injecting one user's cookies into
// another user's Chromium hands over their rec.gov session.

import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

let tempDir
before(() => {
  tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'companion-cookies-'))
  process.env.COMPANION_DIR = tempDir
})
after(() => {
  fs.rmSync(tempDir, { recursive: true, force: true })
})

const LEGACY_COOKIES = 'legacy=operator'
const PROFILE_A = 'user-a'
const PROFILE_B = 'user-b'

test('the legacy global cookie value never reaches a per-profile context', async () => {
  const { setSetting } = await import('../src/store.js')
  const { injectStoredCookies } = await import('../src/browser.js')
  setSetting('recgov_cookies', LEGACY_COOKIES)
  const context = fakeContext()

  const injected = await injectStoredCookies(context, null, PROFILE_A)

  assert.equal(injected, 0)
  assert.deepEqual(context.cookies, [])
})

test('a profile only ever gets its own stored cookies', async () => {
  const { setSetting } = await import('../src/store.js')
  const { injectStoredCookies, recgovCookieSettingKey } = await import('../src/browser.js')
  setSetting(recgovCookieSettingKey(PROFILE_A), 'a=1')
  setSetting(recgovCookieSettingKey(PROFILE_B), 'b=2')

  const contextA = fakeContext()
  const contextB = fakeContext()
  await injectStoredCookies(contextA, null, PROFILE_A)
  await injectStoredCookies(contextB, null, PROFILE_B)

  assert.deepEqual(contextA.cookies.map((c) => c.name), ['a'])
  assert.deepEqual(contextB.cookies.map((c) => c.name), ['b'])
})

test('the legacy profile still reads the unkeyed setting', async () => {
  const { setSetting } = await import('../src/store.js')
  const { injectStoredCookies } = await import('../src/browser.js')
  setSetting('recgov_cookies', LEGACY_COOKIES)
  const context = fakeContext()

  const injected = await injectStoredCookies(context)

  assert.equal(injected, 1)
  assert.deepEqual(context.cookies.map((c) => c.name), ['legacy'])
})

test('an explicit raw cookie input still wins for the profile it is passed with', async () => {
  const { injectStoredCookies } = await import('../src/browser.js')
  const context = fakeContext()

  await injectStoredCookies(context, 'explicit=1', PROFILE_A)

  assert.deepEqual(context.cookies.map((c) => c.name), ['explicit'])
})

function fakeContext () {
  const context = {
    cookies: [],
    addCookies: async (cookies) => {
      context.cookies.push(...cookies)
    },
  }
  return context
}
