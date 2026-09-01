// Local JSON-file store for companion-side state.
//
// The companion no longer stores recgov tokens or refresh creds in this JSON
// file. Its persistent Chromium profile is the login source of truth, including
// localStorage.recaccount and refresh lifecycle.
//
// What still lives here: `recgov_cookies` (paste-derived cookie string used
// for the Akamai TLS-fingerprint workaround in the Playwright browser
// context). The cookies must stay local because they're tied to the same
// browser session that runs ATC.
//
// Two processes write this file. The container serves requests and persists a
// jar on every successful login/MFA/refresh/verify plus the keepalive sweep,
// while the macOS host runs the headed mint runbook against the same mounted
// volume. Every profile's session lives in this one file, so a whole-file
// rewrite is a data-loss primitive: a reader that catches a half-written file
// and calls it "no settings" writes an empty store back over every user's
// session, and a read-modify-write cycle that loses the race drops whichever
// key the other side just saved. Hence the three rules below — commit by
// rename, mutate under a lock, and refuse to write over a file we cannot
// parse.

import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { randomUUID } from 'node:crypto'

export const STORE_FILE = 'store.json'
export const STORE_LOCK_FILE = 'store.json.lock'
const STORE_TMP_PREFIX = `${STORE_FILE}.tmp-`

/**
 * How long a mutator waits for the other writer's lock, how often it retries,
 * and how long a lock survives its owner.
 *
 * A store write is a few milliseconds of file I/O, so contention resolves by
 * waiting rather than by refusing — unlike the profile-directory lease in
 * browser.js, where the contended resource is a browser held for minutes and
 * the only safe answer is to stop and tell the operator. The stale window is
 * wide enough that a descheduled writer is never mistaken for a dead one, and
 * short enough that a hard-killed host CLI does not block the container's
 * keepalive sweep for long.
 */
const DEFAULT_LOCK_TIMEOUT_MS = 5_000
const DEFAULT_LOCK_RETRY_MS = 20
const DEFAULT_LOCK_STALE_AFTER_MS = 60_000

// Resolved per call, not at import: the location is environment, and binding
// it at module load meant any test that imported this transitively — directly
// or through server.js — wrote to the developer's real store.
function storeDir () {
  return process.env.COMPANION_DIR || path.join(os.homedir(), '.campsite-companion')
}

function storePath () {
  return path.join(storeDir(), STORE_FILE)
}

function lockPath () {
  return path.join(storeDir(), STORE_LOCK_FILE)
}

function envMs (name, fallback) {
  const parsed = Number(process.env[name])
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback
}

/**
 * The store exists but cannot be parsed, so we do not know what it holds.
 *
 * Swallowing this into `{}` is what turns one torn write into permanent loss:
 * the caller reads "nobody is logged in" and the next mutator commits that
 * emptiness over the top of every profile's jar. Nothing here can recover the
 * bytes, so the only correct move is to stop and let an operator look.
 */
export class StoreCorruptError extends Error {
  constructor (file, cause) {
    super(
      `companion store ${file} is not parseable JSON and will not be treated as empty: ` +
        'it holds every profile\'s rec.gov session, and writing an empty store over it ' +
        `would make the loss permanent. Inspect or move the file, then retry. (${cause})`,
    )
    this.name = 'StoreCorruptError'
    this.code = 'store_corrupt'
  }
}

/** The other writer is holding the store lock and did not let go in time. */
export class StoreBusyError extends Error {
  constructor (file, waitedMs) {
    super(
      `companion store ${file} is locked by another writer (waited ${waitedMs}ms). ` +
        'Host and container share this file; retry once the other side finishes.',
    )
    this.name = 'StoreBusyError'
    this.code = 'store_busy'
  }
}

function read () {
  const file = storePath()
  let raw
  try {
    raw = fs.readFileSync(file, 'utf8')
  } catch (error) {
    if (error.code === 'ENOENT') return {}
    throw error
  }
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    throw new StoreCorruptError(file, error.message)
  }
  // A truncated write can still parse (`null`, a bare number), so shape is part
  // of the check rather than a formality.
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new StoreCorruptError(file, `top level is ${Array.isArray(parsed) ? 'an array' : typeof parsed}`)
  }
  return parsed
}

/**
 * Commits the whole store by rename, so no reader ever sees it half written.
 *
 * `writeFileSync` on the live path truncates first: any reader landing in that
 * window gets an empty or partial file, and a crash there leaves one on disk.
 * The temp file shares the directory so the rename is same-filesystem and
 * therefore atomic, and both the file and the directory entry are fsynced
 * because a session that survives only until the next power loss is not
 * durable in the sense this store exists to provide.
 */
function write (data) {
  const dir = storeDir()
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  const tmp = path.join(dir, `${STORE_TMP_PREFIX}${process.pid}-${randomUUID()}`)
  const fd = fs.openSync(tmp, 'wx')
  try {
    fs.writeFileSync(fd, JSON.stringify(data, null, 2))
    fs.fsyncSync(fd)
  } finally {
    fs.closeSync(fd)
  }
  try {
    fs.renameSync(tmp, path.join(dir, STORE_FILE))
  } catch (error) {
    fs.rmSync(tmp, { force: true })
    throw error
  }
  // Best effort: directory fsync is unsupported on some platforms, and the
  // rename itself has already made the new contents visible.
  let dirFd
  try {
    dirFd = fs.openSync(dir, 'r')
    fs.fsyncSync(dirFd)
  } catch {} finally {
    if (dirFd !== undefined) try { fs.closeSync(dirFd) } catch {}
  }
}

function sleepSync (ms) {
  // Synchronous by necessity: the store API is synchronous, and a busy spin
  // would starve the very writer we are waiting on.
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)
}

function lockIsStale (file, staleAfterMs) {
  let held
  try {
    held = JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    // Unreadable or half-written: no interpretable claim, so not a live one.
    return true
  }
  if (!Number.isFinite(held?.acquiredAt)) return true
  return Date.now() - held.acquiredAt >= staleAfterMs
}

/**
 * Serializes read-modify-write cycles across both writers.
 *
 * Atomic commits stop a reader seeing a torn file but not two writers reading
 * the same store and each committing its own key over the other's. The claim is
 * a file because the claimants are in different pid namespaces — the container
 * and the macOS host — so, as with the profile-directory lease, neither can
 * evaluate the other's pid and staleness has to be wall-clock.
 */
function withStoreLock (mutate) {
  const dir = storeDir()
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  const file = lockPath()
  const timeoutMs = envMs('COMPANION_STORE_LOCK_TIMEOUT_MS', DEFAULT_LOCK_TIMEOUT_MS)
  const retryMs = envMs('COMPANION_STORE_LOCK_RETRY_MS', DEFAULT_LOCK_RETRY_MS)
  const staleAfterMs = envMs('COMPANION_STORE_LOCK_STALE_AFTER_MS', DEFAULT_LOCK_STALE_AFTER_MS)
  const startedAt = Date.now()

  let fd
  for (;;) {
    try {
      fd = fs.openSync(file, 'wx')
      break
    } catch (error) {
      if (error.code !== 'EEXIST') throw error
      if (lockIsStale(file, staleAfterMs)) {
        // The owner died holding it; leaving it would block every writer forever.
        fs.rmSync(file, { force: true })
        continue
      }
      if (Date.now() - startedAt >= timeoutMs) throw new StoreBusyError(storePath(), Date.now() - startedAt)
      sleepSync(retryMs)
    }
  }
  try {
    fs.writeFileSync(fd, JSON.stringify({
      owner: `${os.hostname()}:${process.pid}:${randomUUID()}`,
      acquiredAt: Date.now(),
    }))
  } catch {} finally {
    fs.closeSync(fd)
  }

  try {
    const data = read()
    const changed = mutate(data)
    if (changed === false) return false
    write(data)
    return true
  } finally {
    fs.rmSync(file, { force: true })
  }
}

export function getSetting (key) {
  return read()[key] ?? null
}

export function setSetting (key, value) {
  withStoreLock((data) => {
    data[key] = value == null ? null : String(value)
  })
}

/**
 * Deletes a key outright rather than nulling it.
 *
 * `setSetting(key, null)` leaves the key present with a null value, which for
 * a rec.gov cookie jar is the difference between "this session is gone" and
 * "this session is gone, and the file no longer records that it ever existed".
 * Removing credentials should leave nothing behind.
 */
export function removeSetting (key) {
  return withStoreLock((data) => {
    if (!(key in data)) return false
    delete data[key]
  })
}

export function getAll () {
  return read()
}
