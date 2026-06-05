// Verifies store.js read/write/seed behavior against a temp dir so we don't
// touch the user's real ~/.campsite-companion.

import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

let tempDir
before(() => {
  tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'companion-store-'))
  process.env.COMPANION_DIR = tempDir
})
after(() => {
  fs.rmSync(tempDir, { recursive: true, force: true })
})

test('setSetting / getSetting roundtrip', async () => {
  const { setSetting, getSetting } = await import('../src/store.js')
  setSetting('foo', 'bar')
  assert.equal(getSetting('foo'), 'bar')
  setSetting('foo', null)
  assert.equal(getSetting('foo'), null)
})

test('seedFromEnv extracts refresh_id + account_id from RECGOV_RECACCOUNT', async () => {
  const { seedFromEnv, getSetting } = await import('../src/store.js')
  process.env.RECGOV_RECACCOUNT = JSON.stringify({
    access_token: 'fresh-token',
    refresh_id: 'R-123',
    account: { account_id: 'A-456' },
  })
  // Ensure starting blank
  const filePath = path.join(tempDir, 'store.json')
  if (fs.existsSync(filePath)) fs.unlinkSync(filePath)
  seedFromEnv()
  const creds = JSON.parse(getSetting('recgov_refresh_creds'))
  assert.equal(creds.account_id, 'A-456')
  assert.equal(creds.refresh_id, 'R-123')
  // token only seeded when missing — verify it's there now
  assert.equal(getSetting('recgov_token'), 'fresh-token')
})

test('seedFromEnv does NOT overwrite existing recgov_token', async () => {
  const { setSetting, seedFromEnv, getSetting } = await import('../src/store.js')
  setSetting('recgov_token', 'existing')
  process.env.RECGOV_RECACCOUNT = JSON.stringify({
    access_token: 'NEW',
    refresh_id: 'R-X',
    account: { account_id: 'A-X' },
  })
  seedFromEnv()
  assert.equal(getSetting('recgov_token'), 'existing')
})
