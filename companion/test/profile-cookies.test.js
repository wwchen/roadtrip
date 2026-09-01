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

test('a profile with no jar of its own bootstraps from the operator paste', async () => {
  // Keying injection per profile with no fallback orphaned every environment
  // that had the documented Akamai cookie-paste set: the value was still in
  // the store and silently stopped being injected.
  const { setSetting } = await import('../src/store.js')
  const { injectStoredCookies, recgovCookieSettingKey } = await import('../src/browser.js')
  setSetting('recgov_cookies', LEGACY_COOKIES)
  setSetting(recgovCookieSettingKey(PROFILE_A), '')
  const context = fakeContext()

  const injected = await injectStoredCookies(context, null, PROFILE_A)

  assert.equal(injected, 1)
  assert.deepEqual(context.cookies.map((c) => c.name), ['legacy'])
})

test('a profile that has its own jar stops consulting the operator paste', async () => {
  // The paste bootstraps a profile once; it must not keep overriding, or
  // reviving, a session the profile has since minted for itself.
  const { setSetting } = await import('../src/store.js')
  const { injectStoredCookies, recgovCookieSettingKey } = await import('../src/browser.js')
  setSetting('recgov_cookies', LEGACY_COOKIES)
  setSetting(recgovCookieSettingKey(PROFILE_A), 'own=1')
  const context = fakeContext()

  await injectStoredCookies(context, null, PROFILE_A)

  assert.deepEqual(context.cookies.map((c) => c.name), ['own'])
})

test('one profile never gets another profile saved jar, fallback or not', async () => {
  // The security property the per-profile keying exists for: a rec.gov cookie
  // jar IS a session, so B must not be reachable from A by any path.
  const { setSetting } = await import('../src/store.js')
  const { injectStoredCookies, recgovCookieSettingKey } = await import('../src/browser.js')
  setSetting('recgov_cookies', '')
  setSetting(recgovCookieSettingKey(PROFILE_A), '')
  setSetting(recgovCookieSettingKey(PROFILE_B), 'b=2')
  const context = fakeContext()

  const injected = await injectStoredCookies(context, null, PROFILE_A)

  assert.equal(injected, 0)
  assert.deepEqual(context.cookies, [])
})

test('a saved per-profile jar can never land in the shared legacy key', async () => {
  // The fallback only stays safe while nothing writes the global key.
  const { setSetting, getSetting } = await import('../src/store.js')
  const { saveProfileCookies } = await import('../src/browser.js')
  setSetting('recgov_cookies', '')

  const saved = await saveProfileCookies({ cookies: async () => [{ name: 'x', value: '1' }] }, null)

  assert.equal(saved, 0)
  assert.equal(getSetting('recgov_cookies') || '', '')
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
  // No profile id at all: the CLI's own single profile.
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
