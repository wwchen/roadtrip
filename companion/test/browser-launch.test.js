import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  CHROMIUM_SINGLETON_LOCK_FILES,
  clearStaleLocks,
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
      clearStaleLocks(dir)
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

test('a cold launch clears a DANGLING singleton lock symlink', async () => {
  // Chromium writes SingletonLock as a symlink whose target is the literal
  // string `<hostname>-<pid>` — a name that never exists as a file. So the
  // link always dangles, `fs.existsSync` follows it and answers false, and a
  // guard written with existsSync never unlinks it. The lock then survives
  // forever and Chromium refuses to start:
  //   "The profile appears to be in use by another Chromium process (35740)
  //    on another computer (firebolt.local)"
  const dir = tempProfileDir()
  const locks = writeDanglingSingletonLocks(dir)
  for (const lock of locks) assert.equal(linkExists(lock), true, 'fixture must start with the links present')

  await launchProfileContext(dir, { chromiumFn: fakeChromium() })

  for (const lock of locks) assert.equal(linkExists(lock), false, `${path.basename(lock)} must be removed`)
})

test('a dangling lock left by another machine is swept', async () => {
  // The browser-session volume is shared between the macOS host (the headed
  // operator runbook) and the Linux container, so each sees locks naming a
  // host and pid that mean nothing to it. Once `liveProfileDirs` says this
  // process holds nothing for the directory, such a lock is by definition
  // stale — there is no live browser of ours behind it.
  const dir = tempProfileDir()
  fs.symlinkSync('firebolt.local-35740', path.join(dir, 'SingletonLock'))
  fs.symlinkSync('/var/folders/xy/T/.org.chromium.Chromium.AbCdEf/SingletonSocket', path.join(dir, 'SingletonSocket'))

  clearStaleLocks(dir)

  assert.equal(linkExists(path.join(dir, 'SingletonLock')), false)
  assert.equal(linkExists(path.join(dir, 'SingletonSocket')), false)
})

test('a dangling lock of a LIVE browser is still never swept', async () => {
  const dir = tempProfileDir()
  const chromium = fakeChromium()

  await launchProfileContext(dir, { chromiumFn: chromium })
  const locks = writeDanglingSingletonLocks(dir)
  await launchProfileContext(dir, { chromiumFn: chromium })

  for (const lock of locks) assert.equal(linkExists(lock), true, 'a live browser owns its locks, dangling or not')
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

/** The locks Chromium really writes: symlinks to targets that never exist. */
function writeDanglingSingletonLocks (dir) {
  return CHROMIUM_SINGLETON_LOCK_FILES.map((name, i) => {
    const file = path.join(dir, name)
    fs.symlinkSync(`somehost-1234${i}`, file)
    return file
  })
}

/** Presence of the link itself. `existsSync` answers false when it dangles. */
function linkExists (file) {
  return fs.lstatSync(file, { throwIfNoEntry: false }) !== undefined
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
