import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  CHROMIUM_SINGLETON_LOCK_FILES,
  clearStaleLocksForTest,
  launchProfileContext,
} from '../src/browser.js'

test('a cold launch clears stale singleton locks left by a crashed Chromium', async () => {
  const dir = tempProfileDir()
  const locks = writeSingletonLocks(dir)
  const chromium = fakeChromium()

  await launchProfileContext(dir, { chromiumFn: chromium })

  for (const lock of locks) assert.equal(fs.existsSync(lock), false)
  assert.deepEqual(chromium.launchedDirs, [dir])
})

test('a second launch never unlinks the singleton locks of a live browser', async () => {
  const dir = tempProfileDir()
  const chromium = fakeChromium()

  await launchProfileContext(dir, { chromiumFn: chromium })
  // The live browser owns these now; clearing them would let a second
  // Chromium attach to the same user-data directory and corrupt the profile.
  const locks = writeSingletonLocks(dir)
  await launchProfileContext(dir, { chromiumFn: chromium })

  for (const lock of locks) assert.equal(fs.existsSync(lock), true)
})

test('a launch that fails after the browser starts closes it and frees the directory', async () => {
  const dir = tempProfileDir()
  const chromium = fakeChromium({ failInitScript: true })

  await assert.rejects(() => launchProfileContext(dir, { chromiumFn: chromium }))

  // The half-built browser must be closed, not left running unregistered —
  // an unregistered live browser is the double-attach race all over again.
  assert.equal(chromium.contexts[0].closed, true)
  const locks = writeSingletonLocks(dir)
  await launchProfileContext(dir, { chromiumFn: fakeChromium() })
  for (const lock of locks) assert.equal(fs.existsSync(lock), false)
})

test('a live browser is registered before its init scripts run', async () => {
  const dir = tempProfileDir()
  let locksDuringInit = null
  const chromium = fakeChromium({
    onInitScript: () => {
      // A concurrent cold launch at this moment must not sweep the locks of
      // the browser that is still setting itself up.
      const locks = writeSingletonLocks(dir)
      clearStaleLocksForTest(dir)
      locksDuringInit = locks.map((lock) => fs.existsSync(lock))
    },
  })

  await launchProfileContext(dir, { chromiumFn: chromium })

  assert.deepEqual(locksDuringInit, [true, true, true])
})

test('closing the only live context makes the directory cold again', async () => {
  const dir = tempProfileDir()
  const chromium = fakeChromium()

  const context = await launchProfileContext(dir, { chromiumFn: chromium })
  await context.close()
  const locks = writeSingletonLocks(dir)
  await launchProfileContext(dir, { chromiumFn: chromium })

  for (const lock of locks) assert.equal(fs.existsSync(lock), false)
})

function tempProfileDir () {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'companion-profile-'))
}

function writeSingletonLocks (dir) {
  return CHROMIUM_SINGLETON_LOCK_FILES.map((name) => {
    const file = path.join(dir, name)
    fs.writeFileSync(file, '')
    return file
  })
}

function fakeChromium ({ failInitScript = false, onInitScript = null } = {}) {
  const launchedDirs = []
  const contexts = []
  return {
    launchedDirs,
    contexts,
    launchPersistentContext: async (dir) => {
      launchedDirs.push(dir)
      const listeners = []
      const context = {
        closed: false,
        addInitScript: async () => {
          onInitScript?.()
          if (failInitScript) throw new Error('init script rejected')
        },
        once: (event, handler) => listeners.push({ event, handler }),
        close: async () => {
          context.closed = true
          for (const listener of listeners) {
            if (listener.event === 'close') listener.handler()
          }
        },
      }
      contexts.push(context)
      return context
    },
  }
}
