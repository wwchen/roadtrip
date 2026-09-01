// Durability of store.json under the two writers that share it: the container
// serving requests and the macOS host running the headed operator runbook.
//
// Every profile's rec.gov cookie jar lives in this one file, so a torn write or
// a lost update signs real users out, and re-establishing a session is the
// expensive bot-wall operation. These tests drive the three hazards directly:
// a truncated file on disk, a reader racing a writer, and two writers
// interleaving read-modify-write cycles.

import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'

import {
  getAll,
  getSetting,
  removeSetting,
  setSetting,
  STORE_FILE,
  STORE_LOCK_FILE,
  StoreBusyError,
  StoreCorruptError,
} from '../src/store.js'

const STORE_MODULE_URL = new URL('../src/store.js', import.meta.url).href
const JAR_KEY = 'recgov_cookies:7'
const OTHER_JAR_KEY = 'recgov_cookies:9'

let dir
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'companion-store-durability-'))
  process.env.COMPANION_DIR = dir
})
afterEach(() => {
  delete process.env.COMPANION_STORE_LOCK_TIMEOUT_MS
  fs.rmSync(dir, { recursive: true, force: true })
})

function storeFile () {
  return path.join(dir, STORE_FILE)
}

/** What a crash or an interleaved writer leaves behind mid-`writeFileSync`. */
function writeTornStore () {
  fs.writeFileSync(storeFile(), '{\n  "recgov_cookies:7": "SESSION=abc; r1s-fing')
}

function writer (key, iterations, valueSize = 1) {
  const script = `
    import { setSetting } from ${JSON.stringify(STORE_MODULE_URL)}
    const value = 'v'.repeat(Number(process.env.VALUE_SIZE))
    for (let i = 0; i < Number(process.env.ITERATIONS); i++) {
      setSetting(process.env.KEY, value + i)
    }
  `
  const child = spawn(process.execPath, ['--input-type=module', '-e', script], {
    env: {
      ...process.env,
      COMPANION_DIR: dir,
      KEY: key,
      ITERATIONS: String(iterations),
      VALUE_SIZE: String(valueSize),
    },
    stdio: ['ignore', 'ignore', 'inherit'],
  })
  const done = new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('exit', (code) => {
      code === 0 ? resolve() : reject(new Error(`writer ${key} exited ${code}`))
    })
  })
  return { child, done }
}

test('a truncated store fails loudly instead of reading as "no settings"', () => {
  writeTornStore()

  assert.throws(() => getSetting(JAR_KEY), StoreCorruptError)
  assert.throws(() => getAll(), StoreCorruptError)
})

test('a writer refuses to overwrite an unparseable store', () => {
  writeTornStore()
  const before = fs.readFileSync(storeFile())

  assert.throws(() => setSetting(OTHER_JAR_KEY, 'SESSION=xyz'), StoreCorruptError)
  assert.throws(() => removeSetting(JAR_KEY), StoreCorruptError)
  assert.deepEqual(
    fs.readFileSync(storeFile()),
    before,
    'a store we cannot parse must survive untouched, not be replaced by one key',
  )
})

test('a zero-byte store is corruption, not an empty store', () => {
  fs.writeFileSync(storeFile(), '')

  assert.throws(() => getAll(), StoreCorruptError)
})

test('a write replaces the file whole rather than truncating it in place', () => {
  setSetting(JAR_KEY, 'SESSION=one')
  const first = fs.statSync(storeFile()).ino

  setSetting(JAR_KEY, 'SESSION=two')
  const second = fs.statSync(storeFile()).ino

  assert.notEqual(
    first,
    second,
    'the committed file must be a fresh inode renamed into place; ' +
      'a same-inode rewrite means a reader can see it half written',
  )
  assert.equal(getSetting(JAR_KEY), 'SESSION=two')
  assert.deepEqual(
    fs.readdirSync(dir).filter((name) => name !== STORE_FILE),
    [],
    'a completed write leaves no temp file behind',
  )
})

test('a temp file abandoned by a crashed write is not mistaken for the store', () => {
  setSetting(JAR_KEY, 'SESSION=committed')
  fs.writeFileSync(path.join(dir, `${STORE_FILE}.tmp-crashed`), '{"recgov_cookies:7": "SESS')

  assert.equal(getSetting(JAR_KEY), 'SESSION=committed')
  setSetting(OTHER_JAR_KEY, 'SESSION=other')
  assert.equal(getSetting(JAR_KEY), 'SESSION=committed')
})

test('a reader never sees the store empty while another process is writing it', async () => {
  setSetting(JAR_KEY, 'SESSION=initial')
  const { child, done } = writer(JAR_KEY, 400, 200_000)

  const observations = []
  while (child.exitCode === null) {
    for (let i = 0; i < 20; i++) {
      try {
        observations.push(getAll()[JAR_KEY] ? 'present' : 'MISSING')
      } catch (error) {
        observations.push(`THREW ${error.code || error.name}`)
      }
    }
    await new Promise((resolve) => setImmediate(resolve))
  }
  await done

  const bad = observations.filter((o) => o !== 'present')
  assert.deepEqual(
    bad,
    [],
    `reader saw the jar vanish mid-write ${bad.length}/${observations.length} times: ` +
      `${[...new Set(bad)].join(', ')}`,
  )
})

test('two processes writing different keys do not lose each other\'s updates', async () => {
  const first = writer(JAR_KEY, 300)
  const second = writer(OTHER_JAR_KEY, 300)
  await Promise.all([first.done, second.done])

  const all = getAll()
  assert.ok(all[JAR_KEY], `${JAR_KEY} was clobbered by the other writer`)
  assert.ok(all[OTHER_JAR_KEY], `${OTHER_JAR_KEY} was clobbered by the other writer`)
})

test('a live writer\'s lock makes a concurrent write fail loudly, not clobber', () => {
  setSetting(JAR_KEY, 'SESSION=held')
  fs.writeFileSync(
    path.join(dir, STORE_LOCK_FILE),
    JSON.stringify({ owner: 'other-host:1:abc', acquiredAt: Date.now() }),
  )
  process.env.COMPANION_STORE_LOCK_TIMEOUT_MS = '50'

  assert.throws(() => setSetting(OTHER_JAR_KEY, 'SESSION=racer'), StoreBusyError)
  assert.equal(getSetting(JAR_KEY), 'SESSION=held')
  assert.equal(getSetting(OTHER_JAR_KEY), null)
})

test('a lock left behind by a killed writer goes stale and is broken', () => {
  const ancient = Date.now() - 60 * 60 * 1000
  fs.writeFileSync(
    path.join(dir, STORE_LOCK_FILE),
    JSON.stringify({ owner: 'dead-host:1:abc', acquiredAt: ancient }),
  )

  setSetting(JAR_KEY, 'SESSION=after-crash')
  assert.equal(getSetting(JAR_KEY), 'SESSION=after-crash')
})
