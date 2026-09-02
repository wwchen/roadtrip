import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  injectStoredCookies,
  recgovCookieSettingKey,
  saveProfileCookies,
} from '../src/browser.js'
import { getSetting, setSetting } from '../src/store.js'

// Never the developer's real ~/.campsite-companion: an earlier run of this
// file left a `recgov_cookies:user-7` key in it.
let storeDir
before(() => {
  storeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'companion-cookie-save-'))
  process.env.COMPANION_DIR = storeDir
})
after(() => {
  delete process.env.COMPANION_DIR
  fs.rmSync(storeDir, { recursive: true, force: true })
})

// The shape every real caller uses: the backend sends `userId.value.toString()`
// and the host runbook derives the id from the pool directory name.
const PROFILE = '7'

/** A context double that answers the cookies a real logged-in one would. */
function fakeContext (cookies = []) {
  const added = []
  return {
    added,
    cookies: async () => cookies,
    addCookies: async (list) => added.push(...list),
  }
}

const LIVE_JAR = [
  { name: 'r1s-fingerprint', value: 'fp-abc', domain: '.recreation.gov', path: '/' },
  { name: 'ak_bmsc', value: 'akamai-xyz', domain: '.recreation.gov', path: '/' },
]

test('a saved jar survives into a fresh context for the same profile', async () => {
  // The whole point: rec.gov's session cookies are session-scoped in Chromium,
  // so without this round trip a container restart loses the login.
  setSetting(recgovCookieSettingKey(PROFILE), '')

  const saved = await saveProfileCookies(fakeContext(LIVE_JAR), PROFILE)
  assert.equal(saved, 2)

  const fresh = fakeContext()
  const injected = await injectStoredCookies(fresh, null, PROFILE)

  assert.equal(injected, 2)
  assert.deepEqual(fresh.added.map((c) => c.name).toSorted(), ['ak_bmsc', 'r1s-fingerprint'])
  assert.equal(fresh.added.find((c) => c.name === 'r1s-fingerprint').value, 'fp-abc')
})

test('the jar is stored under the profile key, never the legacy unkeyed one', async () => {
  setSetting(recgovCookieSettingKey(PROFILE), '')
  setSetting(recgovCookieSettingKey(null), '')

  await saveProfileCookies(fakeContext(LIVE_JAR), PROFILE)

  assert.match(getSetting(recgovCookieSettingKey(PROFILE)), /r1s-fingerprint=fp-abc/)
  // A cookie jar IS a session; leaking one into the shared key shares an account.
  assert.equal(getSetting(recgovCookieSettingKey(null)) || '', '')
})

test('an empty jar never overwrites a good one', async () => {
  // A failed attempt's context has nothing worth keeping, and clobbering the
  // stored jar with it destroys the session we are trying to preserve.
  setSetting(recgovCookieSettingKey(PROFILE), 'r1s-fingerprint=keep-me')

  const saved = await saveProfileCookies(fakeContext([]), PROFILE)

  assert.equal(saved, 0)
  assert.equal(getSetting(recgovCookieSettingKey(PROFILE)), 'r1s-fingerprint=keep-me')
})

test('a context that throws does not fail the operation that just succeeded', async () => {
  setSetting(recgovCookieSettingKey(PROFILE), 'r1s-fingerprint=keep-me')
  const throwing = { cookies: async () => { throw new Error('context closed') } }

  assert.equal(await saveProfileCookies(throwing, PROFILE), 0)
  assert.equal(getSetting(recgovCookieSettingKey(PROFILE)), 'r1s-fingerprint=keep-me')
})

test('no profile id means no save — the legacy CLI jar is not ours to write', async () => {
  setSetting(recgovCookieSettingKey(null), '')

  assert.equal(await saveProfileCookies(fakeContext(LIVE_JAR), null), 0)
  assert.equal(getSetting(recgovCookieSettingKey(null)) || '', '')
})
