import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  clearStaleLocks,
  OWNER_FILE,
  claimProfileDir,
  releaseProfileDir,
  ProfileDirBusyError,
} from '../src/browser.js'

const LOCK = 'SingletonLock'

function profileDir () {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'profile-owner-'))
  fs.writeFileSync(path.join(dir, LOCK), 'x')
  return dir
}

// Simulates the OTHER process (host CLI or container) holding the dir: an owner
// file whose heartbeat is current, written by a pid this process cannot judge.
function foreignOwner (dir, ageMs = 0) {
  fs.writeFileSync(
    path.join(dir, OWNER_FILE),
    JSON.stringify({ owner: 'someone-else', heartbeatAt: Date.now() - ageMs }),
  )
}

test('a lock held by a live process on the other side of the volume is not swept', () => {
  const dir = profileDir()
  foreignOwner(dir)

  assert.throws(() => clearStaleLocks(dir), ProfileDirBusyError)
  assert.ok(fs.existsSync(path.join(dir, LOCK)), 'the live owner keeps its lock')
})

test('a lock whose owner stopped heartbeating is swept', () => {
  const dir = profileDir()
  foreignOwner(dir, 10 * 60 * 1000)

  clearStaleLocks(dir)
  assert.ok(!fs.existsSync(path.join(dir, LOCK)), 'a dead owner must not block forever')
})

test('a lock with no owner file at all is swept, as before', () => {
  const dir = profileDir()

  clearStaleLocks(dir)
  assert.ok(!fs.existsSync(path.join(dir, LOCK)))
})

test('claiming publishes a heartbeat and releasing removes it', () => {
  const dir = profileDir()

  claimProfileDir(dir)
  const claimed = JSON.parse(fs.readFileSync(path.join(dir, OWNER_FILE), 'utf8'))
  assert.equal(typeof claimed.owner, 'string')
  assert.ok(claimed.heartbeatAt > 0)

  releaseProfileDir(dir)
  assert.ok(!fs.existsSync(path.join(dir, OWNER_FILE)), 'release must not leave a lease behind')
})

test('this process sweeping its own claimed dir is a no-op, not a refusal', () => {
  const dir = profileDir()
  claimProfileDir(dir)

  clearStaleLocks(dir)
  assert.ok(fs.existsSync(path.join(dir, LOCK)), 'our own live browser keeps its lock')
  releaseProfileDir(dir)
})
