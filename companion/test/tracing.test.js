import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import {
  DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS,
  diagnosticArtifactName,
  listDiagnostics,
  maxDiagnosticArtifacts,
  pruneDiagnostics,
  sweepProfileDiagnostics,
  traceSessionOpsEnabled,
  withTrace,
} from '../src/tracing.js'

/** A Playwright context double that records what tracing was asked to do. */
function fakeContext ({ failStop = false } = {}) {
  const calls = { started: 0, stops: [] }
  return {
    calls,
    tracing: {
      start: async (options) => {
        calls.started += 1
        calls.startOptions = options
      },
      stop: async (options) => {
        calls.stops.push(options ?? null)
        if (failStop) throw new Error('tracing unavailable')
        // Playwright writes the file only when given a path.
        if (options?.path) await fs.writeFile(options.path, 'trace-bytes')
      },
    },
  }
}

test('a successful operation leaves no trace file behind', async () => {
  const context = fakeContext()

  const { result, trace } = await withTrace(context, { operation: 'login', failureReason: () => null }, async () => 'ok')

  assert.equal(result, 'ok')
  assert.equal(trace, null)
  assert.equal(context.calls.started, 1)
  // Stopped with NO path: Playwright discards the buffer, so nothing is ever
  // written — not written and then swept.
  assert.deepEqual(context.calls.stops, [null])
})

test('tracing captures screenshots, snapshots and sources', async () => {
  const context = fakeContext()

  await withTrace(context, { operation: 'verify', failureReason: () => null }, async () => 'ok')

  assert.deepEqual(context.calls.startOptions, { screenshots: true, snapshots: true, sources: true })
})

test('a failed operation keeps a trace named like the failure screenshot', async () => {
  const dir = await tempDiagnosticsDir()
  const context = fakeContext()

  const { trace } = await withTrace(
    context,
    { operation: 'login', failureReason: (r) => (r.ok ? null : r.reason), dir },
    async () => ({ ok: false, reason: 'captcha_required' }),
  )

  assert.ok(trace, 'a failure must keep its trace')
  assert.match(trace.file, /^recgov-login-.*-captcha_required\.trace\.zip$/)
  assert.match(trace.url, /^\/screenshot\/diagnostics\//)
  assert.deepEqual(await fs.readdir(dir), [trace.file])
})

test('a thrown operation keeps a trace and still rethrows', async () => {
  const dir = await tempDiagnosticsDir()
  const context = fakeContext()

  await assert.rejects(
    () => withTrace(context, { operation: 'atc', failureReason: () => null, dir }, async () => {
      throw new Error('browser closed')
    }),
    /browser closed/,
  )

  assert.match(context.calls.stops[0].path, /recgov-atc-.*-exception\.trace\.zip$/)
})

test('tracing failing never changes the operation outcome', async () => {
  // An old browser or a context mid-teardown must not turn a successful ATC
  // into a failed one.
  const context = fakeContext({ failStop: true })

  const { result, trace } = await withTrace(
    context,
    { operation: 'atc', failureReason: (r) => (r.ok ? null : 'nope') },
    async () => ({ ok: false }),
  )

  assert.deepEqual(result, { ok: false })
  assert.equal(trace, null)
})

test('pruning keeps the newest artifacts and drops the oldest', async () => {
  const dir = await tempDiagnosticsDir()
  for (let i = 0; i < 5; i += 1) {
    const file = path.join(dir, `recgov-login-2026-09-0${i + 1}T00-00-00-000Z-failed.png`)
    await fs.writeFile(file, 'x')
    await fs.utimes(file, new Date(1000 + i * 1000), new Date(1000 + i * 1000))
  }

  const removed = await pruneDiagnostics(dir, 2)

  assert.equal(removed.length, 3)
  const left = (await fs.readdir(dir)).sort()
  assert.deepEqual(left, [
    'recgov-login-2026-09-04T00-00-00-000Z-failed.png',
    'recgov-login-2026-09-05T00-00-00-000Z-failed.png',
  ])
})

test('the artifact cap is env-tunable with a named default', () => {
  assert.equal(maxDiagnosticArtifacts({}), DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS)
  assert.equal(maxDiagnosticArtifacts({ COMPANION_MAX_DIAGNOSTIC_ARTIFACTS: '7' }), 7)
  // Nonsense falls back rather than disabling the bound.
  assert.equal(maxDiagnosticArtifacts({ COMPANION_MAX_DIAGNOSTIC_ARTIFACTS: 'lots' }), DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS)
  assert.equal(maxDiagnosticArtifacts({ COMPANION_MAX_DIAGNOSTIC_ARTIFACTS: '0' }), DEFAULT_MAX_DIAGNOSTIC_ARTIFACTS)
})

test('the listing reads operation and reason back out of the filename', async () => {
  const dir = await tempDiagnosticsDir()
  await fs.writeFile(path.join(dir, 'recgov-login-2026-09-01T00-00-00-000Z-captcha_required.trace.zip'), 'x')
  await fs.writeFile(path.join(dir, 'recgov-verify-2026-09-01T00-00-01-000Z-not_authenticated.png'), 'x')

  const listed = await listDiagnostics(dir)

  assert.equal(listed.length, 2)
  const trace = listed.find((a) => a.kind === 'trace')
  assert.equal(trace.operation, 'login')
  assert.equal(trace.reason, 'captcha_required')
  const shot = listed.find((a) => a.kind === 'screenshot')
  assert.equal(shot.operation, 'verify')
  assert.equal(shot.reason, 'not_authenticated')
})

test('listing a directory that does not exist is empty, not an error', async () => {
  assert.deepEqual(await listDiagnostics(path.join(os.tmpdir(), 'companion-no-such-dir-xyz')), [])
})

// --- Profile attribution -----------------------------------------------
//
// A kept trace records the network log, so a /verify or /atc trace holds the
// profile's live rec.gov session cookies and its bearer token. That makes the
// archive credential material, and credential material has to be erasable by
// the operation that claims to erase the profile — which is only possible if
// the filename says whose it is.

test('a kept trace names the profile it belongs to', async () => {
  const dir = await tempDiagnosticsDir()
  const context = fakeContext()

  const { trace } = await withTrace(
    context,
    { operation: 'atc', profileId: '7', failureReason: () => 'exit_1', dir },
    async () => 1,
  )

  assert.match(trace.file, /^recgov-atc-.*Z-profile_7-exit_1\.trace\.zip$/)
})

test('artifact names round-trip the profile id, and legacy names have none', () => {
  const named = diagnosticArtifactName('verify', 'recgov_cart_unreachable', {
    profileId: '7',
    capturedAt: '2026-09-01T00:00:00.000Z',
  })

  assert.equal(named, 'recgov-verify-2026-09-01T00-00-00-000Z-profile_7-recgov_cart_unreachable')
  assert.equal(
    diagnosticArtifactName('verify', 'boom', { capturedAt: '2026-09-01T00:00:00.000Z' }),
    'recgov-verify-2026-09-01T00-00-00-000Z-boom',
  )
})

test('the listing reports which profile an artifact belongs to', async () => {
  const dir = await tempDiagnosticsDir()
  await fs.writeFile(path.join(dir, 'recgov-atc-2026-09-01T00-00-00-000Z-profile_7-exit_1.trace.zip'), 'x')
  await fs.writeFile(path.join(dir, 'recgov-login-2026-09-01T00-00-01-000Z-captcha_required.png'), 'x')

  const listed = await listDiagnostics(dir)

  const mine = listed.find((a) => a.kind === 'trace')
  assert.equal(mine.profile_id, '7')
  assert.equal(mine.operation, 'atc')
  assert.equal(mine.reason, 'exit_1')
  // Written before artifacts carried a profile: unattributable, so no wipe can
  // honestly claim it.
  assert.equal(listed.find((a) => a.kind === 'screenshot').profile_id, null)
})

test("sweeping a profile deletes that profile's artifacts and nothing else", async () => {
  const dir = await tempDiagnosticsDir()
  const mine = [
    'recgov-atc-2026-09-01T00-00-00-000Z-profile_7-exit_1.trace.zip',
    'recgov-login-2026-09-01T00-00-01-000Z-profile_7-captcha_required.png',
  ]
  const theirs = [
    'recgov-verify-2026-09-01T00-00-02-000Z-profile_8-recgov_not_authenticated.trace.zip',
    'recgov-login-2026-09-01T00-00-03-000Z-captcha_required.png',
  ]
  for (const name of [...mine, ...theirs]) await fs.writeFile(path.join(dir, name), 'x')

  const removed = await sweepProfileDiagnostics('7', dir)

  assert.deepEqual(removed.sort(), [...mine].sort())
  assert.deepEqual((await fs.readdir(dir)).sort(), [...theirs].sort())
})

test('a profile with no artifacts sweeps to nothing', async () => {
  assert.deepEqual(await sweepProfileDiagnostics('7', await tempDiagnosticsDir()), [])
  assert.deepEqual(
    await sweepProfileDiagnostics('7', path.join(os.tmpdir(), 'companion-no-such-dir-xyz')),
    [],
  )
})

test('a sweep that cannot delete an artifact fails loudly', async () => {
  // "Wiped" must never be reported over material still on disk, so an
  // undeletable artifact is an error the caller has to see.
  const dir = await tempDiagnosticsDir()
  await fs.mkdir(path.join(dir, 'recgov-atc-2026-09-01T00-00-00-000Z-profile_7-exit_1.trace.zip'))

  await assert.rejects(() => sweepProfileDiagnostics('7', dir))
})

test('session-op tracing is on by default and can be turned off', () => {
  // /verify and /atc traces hold the live session cookie. Default on, because
  // the fire path is where failure visibility matters most; an operator who
  // wants a cookie-clean diagnostics directory can say so.
  assert.equal(traceSessionOpsEnabled({}), true)
  assert.equal(traceSessionOpsEnabled({ COMPANION_TRACE_SESSION_OPS: 'true' }), true)
  assert.equal(traceSessionOpsEnabled({ COMPANION_TRACE_SESSION_OPS: 'false' }), false)
  assert.equal(traceSessionOpsEnabled({ COMPANION_TRACE_SESSION_OPS: 'FALSE' }), false)
})

/** A fresh diagnostics dir per test, passed in explicitly — the module's
 *  default is read at import time and cannot be redirected afterwards. */
async function tempDiagnosticsDir () {
  return fs.mkdtemp(path.join(os.tmpdir(), 'companion-diag-'))
}
