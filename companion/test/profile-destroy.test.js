// Removing rec.gov credentials must be a TRUE wipe. `logout` clicks through
// rec.gov's sign-out flow and leaves the Chromium user-data directory and the
// saved cookie jar on disk, so a removal built on logout alone left the user's
// session material behind. `destroy` is the operation that actually deletes it.

import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createProfilePool, ERROR_PROFILE_ID_INVALID } from '../src/profilePool.js'
import { recgovCookieSettingKey } from '../src/browser.js'
import { getSetting, setSetting } from '../src/store.js'

const PROFILE = '7'
const OTHER_PROFILE = '8'

let storeDir
let rootDir
before(() => {
  storeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'companion-destroy-store-'))
  process.env.COMPANION_DIR = storeDir
})
after(() => {
  delete process.env.COMPANION_DIR
  fs.rmSync(storeDir, { recursive: true, force: true })
})

test('destroy removes the profile directory and the stored cookie jar', async () => {
  const pool = testPool()
  setSetting(recgovCookieSettingKey(PROFILE), 'r1s-fingerprint=live-session')
  const dir = seedProfileDir(pool, PROFILE)

  const result = await pool.destroyProfile(PROFILE)

  assert.equal(result.ok, true)
  assert.equal(result.directory_removed, true)
  assert.equal(result.cookie_jar_removed, true)
  assert.equal(fs.existsSync(dir), false, 'the Chromium profile directory must be gone')
  assert.equal(getSetting(recgovCookieSettingKey(PROFILE)), null, 'the saved session must be gone')
})

test('destroy closes a live browser and drops it from the keep-warm set', async () => {
  const pool = testPool()
  pool.setKeepWarmProfiles([PROFILE])
  const context = await pool.context(PROFILE)

  await pool.destroyProfile(PROFILE)

  assert.equal(context.closed, true, 'a destroyed profile must not leave a browser running')
  assert.equal(pool.isKeepWarm(PROFILE), false, 'the keepalive sweep must stop asking for it')
  assert.equal(pool.liveContext(PROFILE), null)
})

test('destroying a profile that never existed is a success, not an error', async () => {
  const pool = testPool()

  const first = await pool.destroyProfile('never-launched')

  assert.equal(first.ok, true)
  assert.equal(first.directory_removed, false)
  // Idempotent: the caller asked for it to be gone, and it is gone.
  assert.deepEqual(await pool.destroyProfile('never-launched'), first)
})

test('a second destroy of a real profile is still a success', async () => {
  const pool = testPool()
  seedProfileDir(pool, PROFILE)

  assert.equal((await pool.destroyProfile(PROFILE)).directory_removed, true)
  assert.equal((await pool.destroyProfile(PROFILE)).directory_removed, false)
  assert.equal((await pool.destroyProfile(PROFILE)).ok, true)
})

test('destroy touches only the profile it was given', async () => {
  const pool = testPool()
  setSetting(recgovCookieSettingKey(OTHER_PROFILE), 'someone-else=session')
  const mine = seedProfileDir(pool, PROFILE)
  const theirs = seedProfileDir(pool, OTHER_PROFILE)

  await pool.destroyProfile(PROFILE)

  assert.equal(fs.existsSync(mine), false)
  assert.equal(fs.existsSync(theirs), true, 'no prefix or wildcard match — one id, one profile')
  assert.equal(getSetting(recgovCookieSettingKey(OTHER_PROFILE)), 'someone-else=session')
})

test('destroy refuses a profile id that would escape the profiles root', async () => {
  // The whole function is a recursive delete, so the containment check is
  // asserted against the resolved path rather than trusted to the id pattern.
  const pool = testPool()
  const outside = path.join(pool.profilesDir(), '..', 'browser-session-sibling')
  fs.mkdirSync(outside, { recursive: true })

  for (const bad of ['../browser-session-sibling', '..', '/etc', 'a/../../b', './x']) {
    const result = await pool.destroyProfile(bad)
    assert.equal(result.ok, false, `${bad} must be refused`)
    assert.equal(result.error, ERROR_PROFILE_ID_INVALID)
  }

  assert.equal(fs.existsSync(outside), true, 'nothing outside the profiles root may be deleted')
  fs.rmSync(outside, { recursive: true, force: true })
})

test('the legacy unkeyed cookie jar is never deleted by destroying a profile', async () => {
  // It belongs to the operator's CLI profile, not to any user.
  const pool = testPool()
  setSetting(recgovCookieSettingKey(null), 'legacy=operator')
  seedProfileDir(pool, PROFILE)

  await pool.destroyProfile(PROFILE)

  assert.equal(getSetting(recgovCookieSettingKey(null)), 'legacy=operator')
})

function testPool () {
  rootDir = fs.mkdtempSync(path.join(os.tmpdir(), 'companion-destroy-'))
  return createProfilePool({
    rootDir,
    launchContextFn: async (dir) => {
      fs.mkdirSync(dir, { recursive: true })
      const context = { dir, closed: false, pages: async () => [], once: () => {} }
      context.close = async () => { context.closed = true }
      return context
    },
    logger: () => {},
  })
}

function seedProfileDir (pool, profileId) {
  const dir = pool.profileDir(profileId)
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(dir, 'Cookies'), 'sqlite-bytes')
  return dir
}
