// Verifies store.js read/write behavior against a temp dir so we don't
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

test('getAll returns the full map', async () => {
  const { setSetting, getAll } = await import('../src/store.js')
  setSetting('a', '1')
  setSetting('b', '2')
  const all = getAll()
  assert.equal(all.a, '1')
  assert.equal(all.b, '2')
})

test('recgov_cookies survives roundtrip (still a local-only setting after PR 6)', async () => {
  const { setSetting, getSetting } = await import('../src/store.js')
  setSetting('recgov_cookies', 'foo=1; bar=2')
  assert.equal(getSetting('recgov_cookies'), 'foo=1; bar=2')
})
