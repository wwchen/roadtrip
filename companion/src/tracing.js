// Playwright traces for the browser operations, kept only when they fail.
//
// A failed rec.gov automation is close to undebuggable from a log line and a
// single screenshot: the interesting part is what the page was doing in the
// seconds before, and whether the click landed. A trace has all of it. But a
// trace per attempt would fill the volume in a week, and the successful ones
// are the ones nobody ever opens.
//
// So: start tracing around every browser operation, and let the OUTCOME decide.
// A success stops tracing with no path, which makes Playwright discard the
// buffer — nothing is ever written, rather than written and swept. A failure
// stops with a path beside the failure screenshot, under the same naming
// convention, and prunes the directory to a bounded size.

import fs from 'node:fs/promises'
import path from 'node:path'
import { SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX } from './recgovScreenshotRoutes.js'
import { TRACE_SUFFIX } from './server/constants.js'

/**
 * How many diagnostic artifacts to keep. Screenshots and traces share the
 * budget, because they share the directory and a trace is the expensive one.
 */
export const DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS = 40

const DEFAULT_DIAGNOSTIC_DIR = '/tmp/campsite-companion/recgov-diagnostics'

/**
 * Where artifacts go, re-read per call.
 *
 * This module owns the directory, the naming convention and the sweep, so
 * every writer agrees on where an artifact lands and every reader can tell
 * whose it is. `recgovSession.js` re-exports the load-time value it has always
 * exported; reading the env here keeps a test's override live.
 */
export function diagnosticDir (env = process.env) {
  return env.RECGOV_DIAGNOSTIC_DIR || DEFAULT_DIAGNOSTIC_DIR
}

/**
 * Whether to trace the login flows. **Off by default, deliberately.**
 *
 * A trace records fill parameters and DOM snapshots, so a login trace contains
 * the user's plaintext rec.gov password — the one thing the whole design goes
 * out of its way never to persist (the backend seals it, the companion holds
 * it in memory for one attempt, and V54 dropped even its last four characters).
 * Writing it to a file on a failure would undo all of that for the sake of
 * debuggability.
 *
 * `/verify` and `/atc` hold no raw password, but their traces are not clean
 * either: see [traceSessionOpsEnabled].
 */
export function traceLoginEnabled (env = process.env) {
  return String(env.COMPANION_TRACE_LOGIN || '').trim().toLowerCase() === 'true'
}

/** Values that turn an on-by-default switch off. */
const FALSEY_FLAG_VALUES = new Set(['false', '0', 'off', 'no'])

/**
 * Whether to trace the session operations — `/verify` and `/atc`. **On by
 * default.**
 *
 * A trace records the network log with full request headers, and the whole
 * point of these contexts is that they hold a signed-in rec.gov session: a
 * kept trace therefore contains the profile's live session cookie jar and the
 * `authorization: Bearer …` header `injectBearerRoute` adds. Playwright offers
 * no redaction, so the archive is credential material — which is why it is
 * named with its profile and swept by `POST /destroy`.
 *
 * Default on because the fire path is where failure visibility matters most
 * and nobody is watching it. An operator who wants a cookie-clean diagnostics
 * directory sets `COMPANION_TRACE_SESSION_OPS=false` and keeps the screenshots.
 */
export function traceSessionOpsEnabled (env = process.env) {
  const raw = String(env.COMPANION_TRACE_SESSION_OPS ?? '').trim().toLowerCase()
  return !FALSEY_FLAG_VALUES.has(raw)
}

export function maxDiagnosticArtifacts (env = process.env) {
  const raw = Number.parseInt(env.COMPANION_MAX_DIAGNOSTIC_ARTIFACTS || '', 10)
  return Number.isInteger(raw) && raw > 0 ? raw : DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS
}

const EXCEPTION_REASON = 'exception'

const SEGMENT_MAX_CHARS = 64

/**
 * Marks the profile segment of an artifact name.
 *
 * The name is the only record of whose artifact this is, and `POST /destroy`
 * reads it back to erase that profile's diagnostics — so the segment has to be
 * parseable out of a name whose reason may itself contain hyphens. The profile
 * id is sanitized hyphen-free and the marker plus the fixed timestamp shape
 * pin its position, which also leaves names written before this convention
 * (no marker) parsing exactly as they did.
 */
const PROFILE_NAME_MARKER = 'profile_'

const TIMESTAMP_SHAPE = '\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-\\d{3}Z'

const ARTIFACT_NAME_PATTERN = new RegExp(
  '^recgov-(?<operation>[^-]+)' +
  `-(?<capturedAt>${TIMESTAMP_SHAPE})` +
  `(?:-${PROFILE_NAME_MARKER}(?<profileId>[A-Za-z0-9_]+))?` +
  '-(?<reason>.+)$',
)

/**
 * Same shape the screenshot names use, so the two sort together.
 *
 * `recgov-<operation>-<timestamp>-profile_<id>-<reason>`.
 */
export function diagnosticArtifactName (operation, reason, { profileId = null, capturedAt = new Date().toISOString() } = {}) {
  const segment = profileNameSegment(profileId)
  return [
    `recgov-${sanitize(operation)}`,
    capturedAt.replace(/[:.]/g, '-'),
    ...(segment ? [segment] : []),
    sanitize(reason),
  ].join('-')
}

/**
 * The name segment for a profile, or null when the writer has no profile.
 *
 * Sanitizing is many-to-one (`a-b` and `a.b` both land on `a_b`), so a sweep
 * can over-match in principle. Profile ids are decimal user ids, and
 * over-deleting a diagnostic is the safe direction for a credential wipe.
 */
function profileNameSegment (profileId) {
  const raw = profileId === null || profileId === undefined ? '' : String(profileId).trim()
  if (!raw) return null
  return `${PROFILE_NAME_MARKER}${sanitizeProfileId(raw)}`
}

function sanitizeProfileId (value) {
  return String(value).replace(/[^A-Za-z0-9_]+/g, '_').slice(0, SEGMENT_MAX_CHARS)
}

function sanitize (value) {
  return String(value || 'unknown').replace(/[^a-zA-Z0-9_-]+/g, '_').slice(0, SEGMENT_MAX_CHARS)
}

/**
 * Deletes every diagnostic artifact belonging to one profile.
 *
 * This is the trace half of the true wipe: a kept `/verify` or `/atc` trace
 * holds the same live session the destroyed cookie jar held, so removing the
 * jar and leaving the archive would erase the copy and keep the original.
 *
 * Throws rather than swallowing: a caller that reports "wiped" over material
 * still on disk is the exact failure this exists to prevent. A missing
 * directory is not a failure — nothing was ever written.
 */
export async function sweepProfileDiagnostics (profileId, dir = diagnosticDir()) {
  const segment = profileNameSegment(profileId)
  if (!segment) throw new Error('sweepProfileDiagnostics requires a profile id')
  let entries
  try {
    entries = await fs.readdir(dir)
  } catch (error) {
    if (error.code === 'ENOENT') return []
    throw error
  }
  const owned = entries.filter((name) => describeArtifact(name).profile_id === sanitizeProfileId(profileId))
  for (const name of owned) {
    await fs.rm(path.join(dir, name), { force: true })
  }
  return owned
}

/**
 * Runs [work] with tracing on, keeping the trace only if it failed.
 *
 * [failureReason] inspects the result and returns a reason string for a
 * failure, or null for a success. A thrown error is always a failure.
 *
 * [enabled] false runs the work untraced — the login paths use it, see
 * [traceLoginEnabled].
 *
 * Returns `{ result, trace }` — `trace` is `{ file, url }` when one was kept,
 * else null. Tracing never changes the outcome: if the tracing API itself
 * throws (an old browser, a context already closed), the work's result stands
 * and the trace is simply absent. Adds no waits of its own, which matters on
 * the ATC fire path.
 */
export async function withTrace (context, { operation, profileId = null, failureReason, dir = diagnosticDir(), enabled = true }, work) {
  const started = enabled ? await startTracing(context) : false
  let result
  let reason = null
  try {
    result = await work()
    reason = failureReason ? failureReason(result) : null
  } catch (error) {
    await stopTracing(context, started, { operation, profileId, reason: EXCEPTION_REASON, dir })
    throw error
  }
  const trace = await stopTracing(context, started, { operation, profileId, reason, dir })
  return { result, trace }
}

async function startTracing (context) {
  try {
    await context?.tracing?.start({ screenshots: true, snapshots: true, sources: true })
    return true
  } catch {
    // No tracing available (a test double, a context mid-teardown). The
    // operation is what matters; run it untraced.
    return false
  }
}

/** Discards on success (no path = nothing written); writes and prunes on failure. */
async function stopTracing (context, started, { operation, profileId, reason, dir }) {
  if (!started) return null
  if (!reason) {
    await context?.tracing?.stop().catch(() => {})
    return null
  }
  const file = `${diagnosticArtifactName(operation, reason, { profileId })}${TRACE_SUFFIX}`
  try {
    await fs.mkdir(dir, { recursive: true })
    await context.tracing.stop({ path: path.join(dir, file) })
  } catch {
    await context?.tracing?.stop().catch(() => {})
    return null
  }
  await pruneDiagnostics(dir)
  return { file, url: `${SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX}/${file}` }
}

/**
 * Keeps the newest [maxDiagnosticArtifacts] files and deletes the rest.
 *
 * Runs on every artifact write rather than on a timer: the directory only
 * grows when something is written to it, so that is exactly when it can need
 * trimming, and a companion with no traffic sweeps nothing.
 */
export async function pruneDiagnostics (dir = diagnosticDir(), keep = maxDiagnosticArtifacts()) {
  let entries
  try {
    entries = await fs.readdir(dir)
  } catch {
    return []
  }
  const stated = await Promise.all(entries.map(async (name) => {
    try {
      return { name, mtime: (await fs.stat(path.join(dir, name))).mtimeMs }
    } catch {
      return null
    }
  }))
  const files = stated.filter(Boolean).sort((a, b) => b.mtime - a.mtime)
  const doomed = files.slice(keep)
  await Promise.all(doomed.map((f) => fs.rm(path.join(dir, f.name), { force: true }).catch(() => {})))
  return doomed.map((f) => f.name)
}

/**
 * The diagnostics directory as the operator page lists it, newest first.
 *
 * Names carry their own metadata (`recgov-<op>-<timestamp>-<reason>`), so the
 * listing parses rather than tracking state — a file copied in by hand still
 * reads correctly, and there is no index to fall out of sync.
 */
export async function listDiagnostics (dir = diagnosticDir()) {
  let entries
  try {
    entries = await fs.readdir(dir)
  } catch {
    return []
  }
  const stated = await Promise.all(entries.map(async (name) => {
    try {
      const stat = await fs.stat(path.join(dir, name))
      return { ...describeArtifact(name), size_bytes: stat.size, modified_at: new Date(stat.mtimeMs).toISOString() }
    } catch {
      return null
    }
  }))
  return stated.filter(Boolean).sort((a, b) => (a.modified_at < b.modified_at ? 1 : -1))
}

function describeArtifact (name) {
  const kind = name.endsWith(TRACE_SUFFIX) ? 'trace' : 'screenshot'
  const stem = name.replace(TRACE_SUFFIX, '').replace(/\.png$/, '')
  const parsed = ARTIFACT_NAME_PATTERN.exec(stem)?.groups
  return {
    file: name,
    kind,
    operation: parsed?.operation || stem.split('-')[1] || null,
    // Null for anything written before names carried a profile: unattributable,
    // so no per-profile sweep can honestly claim it.
    profile_id: parsed?.profileId || null,
    reason: parsed?.reason || null,
    url: `${SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX}/${name}`,
  }
}
