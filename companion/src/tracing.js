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
import { RECGOV_DIAGNOSTIC_DIR } from './recgovSession.js'
import { SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX } from './recgovScreenshotRoutes.js'

/**
 * How many diagnostic artifacts to keep. Screenshots and traces share the
 * budget, because they share the directory and a trace is the expensive one.
 */
export const DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS = 40

/**
 * Where artifacts go, re-read per call.
 *
 * `RECGOV_DIAGNOSTIC_DIR` is captured at module load in `recgovSession.js`,
 * which is fine for a long-lived process and impossible to point anywhere in a
 * test. Reading the env here keeps that override live without changing the
 * shared constant every other consumer imports.
 */
export function diagnosticDir (env = process.env) {
  return env.RECGOV_DIAGNOSTIC_DIR || RECGOV_DIAGNOSTIC_DIR
}

export function maxDiagnosticArtifacts (env = process.env) {
  const raw = Number.parseInt(env.COMPANION_MAX_DIAGNOSTIC_ARTIFACTS || '', 10)
  return Number.isInteger(raw) && raw > 0 ? raw : DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS
}

const TRACE_SUFFIX = '.trace.zip'
const EXCEPTION_REASON = 'exception'

/** Where the reason starts once the ISO timestamp's own hyphens are counted. */
const REASON_INDEX = 8

/** Same shape the screenshot names use, so the two sort together. */
export function diagnosticArtifactName (operation, reason, capturedAt = new Date().toISOString()) {
  return `recgov-${sanitize(operation)}-${capturedAt.replace(/[:.]/g, '-')}-${sanitize(reason)}`
}

function sanitize (value) {
  return String(value || 'unknown').replace(/[^a-zA-Z0-9_-]+/g, '_').slice(0, 64)
}

/**
 * Runs [work] with tracing on, keeping the trace only if it failed.
 *
 * [failureReason] inspects the result and returns a reason string for a
 * failure, or null for a success. A thrown error is always a failure.
 *
 * Returns `{ result, trace }` — `trace` is `{ file, url }` when one was kept,
 * else null. Tracing never changes the outcome: if the tracing API itself
 * throws (an old browser, a context already closed), the work's result stands
 * and the trace is simply absent. Adds no waits of its own, which matters on
 * the ATC fire path.
 */
export async function withTrace (context, { operation, failureReason, dir = diagnosticDir() }, work) {
  const started = await startTracing(context)
  let result
  let reason = null
  try {
    result = await work()
    reason = failureReason ? failureReason(result) : null
  } catch (error) {
    await stopTracing(context, started, { operation, reason: EXCEPTION_REASON, dir })
    throw error
  }
  const trace = await stopTracing(context, started, { operation, reason, dir })
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
async function stopTracing (context, started, { operation, reason, dir }) {
  if (!started) return null
  if (!reason) {
    await context?.tracing?.stop().catch(() => {})
    return null
  }
  const file = `${diagnosticArtifactName(operation, reason)}${TRACE_SUFFIX}`
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
  // recgov-<op>-<YYYY>-<MM>-<DD>T<HH>-<mm>-<ss>-<ms>Z-<reason>
  //    0      1     2      3       4      5     6      7        8
  const parts = stem.split('-')
  const operation = parts[1] || null
  const reason = parts.length > REASON_INDEX ? parts.slice(REASON_INDEX).join('-') : null
  return {
    file: name,
    kind,
    operation,
    reason: reason || null,
    url: `${SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX}/${name}`,
  }
}
