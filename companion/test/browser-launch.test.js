import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  CHROMIUM_SINGLETON_LOCK_FILES,
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

function fakeChromium () {
  const launchedDirs = []
  return {
    launchedDirs,
    launchPersistentContext: async (dir) => {
      launchedDirs.push(dir)
      const listeners = []
      return {
        addInitScript: async () => {},
        once: (event, handler) => listeners.push({ event, handler }),
        close: async () => {
          for (const listener of listeners) {
            if (listener.event === 'close') listener.handler()
          }
        },
      }
    },
  }
}
