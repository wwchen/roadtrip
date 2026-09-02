import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_FAILED_LOGIN_BACKOFF_MS,
  DEFAULT_MAX_CONCURRENT_BROWSERS,
  DEFAULT_MFA_CHALLENGE_TTL_MS,
  ERROR_BROWSER_CAP_REACHED,
  ERROR_MFA_CHALLENGE_EXPIRED,
  ERROR_MFA_CHALLENGE_UNKNOWN,
  ERROR_PROFILE_ID_INVALID,
  ERROR_PROFILE_ID_REQUIRED,
  createProfilePool,
  normalizeProfileId,
  profileIdForSessionDir,
} from '../src/profilePool.js'

const PROFILE_A = 'user-a'
const PROFILE_B = 'user-b'
const PROFILE_C = 'user-c'
const TEST_ROOT_DIR = '/tmp/companion-test-profiles'
const MINUTE_MS = 60_000

test('the MFA challenge TTL is minutes-scale and the browser cap is a named default', () => {
  assert.equal(DEFAULT_MFA_CHALLENGE_TTL_MS, 5 * MINUTE_MS)
  assert.ok(DEFAULT_MAX_CONCURRENT_BROWSERS >= 1)
  assert.ok(DEFAULT_FAILED_LOGIN_BACKOFF_MS > 0)
})

test('normalizeProfileId rejects missing, malformed, and traversing ids', () => {
  assert.deepEqual(normalizeProfileId(undefined), { ok: false, error: ERROR_PROFILE_ID_REQUIRED })
  assert.deepEqual(normalizeProfileId('   '), { ok: false, error: ERROR_PROFILE_ID_REQUIRED })
  assert.deepEqual(normalizeProfileId('../escape'), { ok: false, error: ERROR_PROFILE_ID_INVALID })
  assert.deepEqual(normalizeProfileId('a/b'), { ok: false, error: ERROR_PROFILE_ID_INVALID })
  assert.deepEqual(normalizeProfileId('x'.repeat(200)), { ok: false, error: ERROR_PROFILE_ID_INVALID })
  assert.deepEqual(normalizeProfileId(' 42 '), { ok: true, profileId: '42' })
  assert.deepEqual(normalizeProfileId(7), { ok: true, profileId: '7' })
})

test('two profiles never share a browser context or a profile directory', async () => {
  const launcher = fakeLauncher()
  const pool = testPool({ launchContextFn: launcher.launch })

  const first = await pool.context(PROFILE_A)
  const second = await pool.context(PROFILE_B)

  assert.notEqual(first, second)
  assert.equal(launcher.launchedDirs.length, 2)
  assert.notEqual(launcher.launchedDirs[0], launcher.launchedDirs[1])
  assert.equal(pool.profileDir(PROFILE_A), launcher.launchedDirs[0])
  assert.match(pool.profileDir(PROFILE_A), new RegExp(`${TEST_ROOT_DIR}/profiles/${PROFILE_A}$`))
})

test('concurrent cold callers share one launch instead of starting two Chromiums', async () => {
  let launches = 0
  let release = null
  const pending = new Promise((resolve) => { release = resolve })
  const pool = testPool({
    launchContextFn: async () => {
      launches += 1
      await pending
      return { pages: async () => [], close: async () => {}, once: () => {} }
    },
  })

  const both = Promise.all([pool.context(PROFILE_A), pool.context(PROFILE_A)])
  release()
  const [first, second] = await both

  assert.equal(launches, 1)
  assert.equal(first, second)
})

test('a launch failure does not poison the next attempt', async () => {
  let launches = 0
  const pool = testPool({
    launchContextFn: async () => {
      launches += 1
      if (launches === 1) throw new Error('chromium exploded')
      return { pages: async () => [], close: async () => {}, once: () => {} }
    },
  })

  await assert.rejects(() => pool.context(PROFILE_A))
  const recovered = await pool.context(PROFILE_A)

  assert.ok(recovered)
  assert.equal(launches, 2)
})

test('a profile reuses its launched context', async () => {
  const launcher = fakeLauncher()
  const pool = testPool({ launchContextFn: launcher.launch })

  const first = await pool.context(PROFILE_A)
  const again = await pool.context(PROFILE_A)

  assert.equal(first, again)
  assert.equal(launcher.launchedDirs.length, 1)
})

test('the concurrent-browser cap evicts an idle profile before launching another', async () => {
  const launcher = fakeLauncher()
  const pool = testPool({ launchContextFn: launcher.launch, maxConcurrentBrowsers: 1 })

  const first = await pool.context(PROFILE_A)
  await pool.context(PROFILE_B)

  assert.equal(first.closed, true)
  assert.equal(pool.snapshot().resident, 1)
})

test('the cap refuses a launch when every resident profile is locked', async () => {
  const launcher = fakeLauncher()
  const pool = testPool({ launchContextFn: launcher.launch, maxConcurrentBrowsers: 1 })

  await pool.context(PROFILE_A)
  pool.acquire(PROFILE_A, 'login')

  await assert.rejects(
    () => pool.context(PROFILE_B),
    (error) => error.code === ERROR_BROWSER_CAP_REACHED,
  )
})

test('an in-flight launch holds its slot, so concurrent cold profiles cannot exceed the cap', async () => {
  // state.context is assigned only once Chromium is up. Counting residency
  // alone, two cold callers for two different profiles both saw a free slot
  // and both launched — the cap held only while launches happened to be serial.
  let launches = 0
  let release = null
  const pending = new Promise((resolve) => { release = resolve })
  const pool = testPool({
    launchContextFn: async () => {
      launches += 1
      await pending
      return fakeContext()
    },
    maxConcurrentBrowsers: 1,
  })

  const first = pool.context(PROFILE_A)
  // Nothing is evictable — the only slot is held by a launch, not a browser.
  // Caught inline rather than with assert.rejects: B must be settled before
  // the gate opens, and an unreverted regression would otherwise deadlock the
  // assertion instead of failing it.
  const second = pool.context(PROFILE_B).catch((error) => error)

  release()
  await first

  assert.equal((await second)?.code, ERROR_BROWSER_CAP_REACHED)
  assert.equal(launches, 1)
})

test('keep-warm profiles are exempt from the cap and the overflow is reported', async () => {
  const launcher = fakeLauncher()
  const pool = testPool({ launchContextFn: launcher.launch, maxConcurrentBrowsers: 1 })

  pool.setKeepWarmProfiles([PROFILE_A, PROFILE_B])
  await pool.context(PROFILE_A)
  await pool.context(PROFILE_B)

  const snapshot = pool.snapshot()
  assert.equal(snapshot.resident, 2)
  assert.equal(snapshot.max_concurrent_browsers, 1)
  assert.deepEqual(snapshot.keep_warm.toSorted(), [PROFILE_A, PROFILE_B])
  assert.equal(snapshot.keep_warm_overflow, 1)
})

test('keep-warm defaults to no profiles until the backend pushes a set', () => {
  const pool = testPool()

  assert.deepEqual(pool.snapshot().keep_warm, [])
  assert.equal(pool.isKeepWarm(PROFILE_A), false)

  pool.setKeepWarmProfiles([PROFILE_C])

  assert.equal(pool.isKeepWarm(PROFILE_C), true)
  assert.equal(pool.isKeepWarm(PROFILE_A), false)
})

test('the per-profile busy lock serializes mutating work but leaves reads open', () => {
  const pool = testPool()

  const held = pool.acquire(PROFILE_A, 'login')

  assert.ok(held)
  assert.equal(pool.acquire(PROFILE_A, 'atc'), null)
  assert.equal(pool.isBusy(PROFILE_A), true)
  assert.equal(pool.isBusy(PROFILE_B), false)
  assert.ok(pool.acquire(PROFILE_B, 'atc'))

  // Reads never wait behind the lock.
  assert.equal(pool.getAuthStatus(PROFILE_A).state, 'unchecked')
  assert.equal(pool.snapshot().profiles.find((p) => p.profile_id === PROFILE_A).busy, true)

  held.release()

  assert.equal(pool.isBusy(PROFILE_A), false)
  assert.ok(pool.acquire(PROFILE_A, 'login'))
})

test('a pending MFA challenge holds the lock until it is completed', async () => {
  const clock = fakeClock()
  const pool = testPool({ now: clock.now })
  const held = pool.acquire(PROFILE_A, 'login')

  const challenge = pool.openMfaChallenge(PROFILE_A, { lock: held, complete: async () => 'completed' })

  assert.match(challenge.challenge_id, /^[0-9a-f]{8,}$/)
  assert.equal(Date.parse(challenge.expires_at) - clock.value(), DEFAULT_MFA_CHALLENGE_TTL_MS)
  assert.equal(pool.isBusy(PROFILE_A), true)

  const taken = pool.takeMfaChallenge(PROFILE_A, challenge.challenge_id)

  assert.equal(taken.ok, true)
  assert.equal(await taken.challenge.complete(), 'completed')
  taken.challenge.release()
  assert.equal(pool.isBusy(PROFILE_A), false)
})

test('an expired MFA challenge closes the page it was holding open', () => {
  const clock = fakeClock()
  const pool = testPool({ now: clock.now })
  const held = pool.acquire(PROFILE_A, 'login')
  let abandoned = 0
  const challenge = pool.openMfaChallenge(PROFILE_A, {
    lock: held,
    complete: async () => null,
    abandon: () => { abandoned += 1 },
  })

  clock.advance(DEFAULT_MFA_CHALLENGE_TTL_MS + 1)

  assert.equal(
    pool.takeMfaChallenge(PROFILE_A, challenge.challenge_id).error,
    ERROR_MFA_CHALLENGE_EXPIRED,
  )
  assert.equal(abandoned, 1, 'the held Playwright page must be closed, not leaked')
  assert.equal(pool.isBusy(PROFILE_A), false)
})

test('the background sweep also closes an abandoned challenge page', () => {
  const clock = fakeClock()
  const pool = testPool({ now: clock.now })
  const held = pool.acquire(PROFILE_A, 'login')
  let abandoned = 0
  pool.openMfaChallenge(PROFILE_A, {
    lock: held,
    complete: async () => null,
    abandon: () => { abandoned += 1 },
  })

  clock.advance(DEFAULT_MFA_CHALLENGE_TTL_MS + 1)
  // Any lock-aware read sweeps; the user simply never comes back.
  pool.isBusy(PROFILE_A)
  pool.snapshot()

  assert.equal(abandoned, 1, 'abandoning must happen once, not once per sweep')
})

test('completing a challenge does not abandon the page the resume owns', () => {
  const pool = testPool()
  const held = pool.acquire(PROFILE_A, 'login')
  let abandoned = 0
  const challenge = pool.openMfaChallenge(PROFILE_A, {
    lock: held,
    complete: async () => 'completed',
    abandon: () => { abandoned += 1 },
  })

  const taken = pool.takeMfaChallenge(PROFILE_A, challenge.challenge_id)
  taken.challenge.release()

  assert.equal(taken.ok, true)
  assert.equal(abandoned, 0)
})

test('an unknown MFA challenge id is rejected without dropping the pending one', () => {
  const pool = testPool()
  const held = pool.acquire(PROFILE_A, 'login')
  const challenge = pool.openMfaChallenge(PROFILE_A, { lock: held, complete: async () => null })

  assert.deepEqual(
    pool.takeMfaChallenge(PROFILE_A, 'not-the-id'),
    { ok: false, error: ERROR_MFA_CHALLENGE_UNKNOWN },
  )
  assert.equal(pool.takeMfaChallenge(PROFILE_A, challenge.challenge_id).ok, true)
})

test('an expired MFA challenge releases the lock and reports expiry', () => {
  const clock = fakeClock()
  const pool = testPool({ now: clock.now })
  const held = pool.acquire(PROFILE_A, 'login')
  const challenge = pool.openMfaChallenge(PROFILE_A, { lock: held, complete: async () => null })

  clock.advance(DEFAULT_MFA_CHALLENGE_TTL_MS + 1)

  assert.deepEqual(
    pool.takeMfaChallenge(PROFILE_A, challenge.challenge_id),
    { ok: false, error: ERROR_MFA_CHALLENGE_EXPIRED },
  )
  assert.equal(pool.isBusy(PROFILE_A), false)
  assert.equal(pool.snapshot().profiles.find((p) => p.profile_id === PROFILE_A).mfa_pending, false)
})

test('a failed credential login backs the profile off before the next attempt', () => {
  const clock = fakeClock()
  const pool = testPool({ now: clock.now, failedLoginBackoffMs: 30_000 })

  assert.deepEqual(pool.loginBackoff(PROFILE_A), { blocked: false, retry_after_ms: 0 })

  pool.recordLoginFailure(PROFILE_A)

  assert.deepEqual(pool.loginBackoff(PROFILE_A), { blocked: true, retry_after_ms: 30_000 })
  assert.equal(pool.loginBackoff(PROFILE_B).blocked, false)

  clock.advance(30_000)

  assert.equal(pool.loginBackoff(PROFILE_A).blocked, false)
})

test('a successful login clears the backoff marker', () => {
  const pool = testPool()

  pool.recordLoginFailure(PROFILE_A)
  pool.clearLoginFailure(PROFILE_A)

  assert.equal(pool.loginBackoff(PROFILE_A).blocked, false)
})

test('per-profile auth status is isolated and defaults to unchecked', () => {
  const pool = testPool()

  pool.setAuthStatus(PROFILE_A, { state: 'ok', logged_in: true })

  assert.equal(pool.getAuthStatus(PROFILE_A).state, 'ok')
  assert.equal(pool.getAuthStatus(PROFILE_B).state, 'unchecked')
  assert.equal(pool.getAuthStatus(PROFILE_B).logged_in, false)
})

test('closing a profile drops its context but keeps its directory name stable', async () => {
  const launcher = fakeLauncher()
  const pool = testPool({ launchContextFn: launcher.launch })
  const context = await pool.context(PROFILE_A)

  await pool.closeProfile(PROFILE_A)

  assert.equal(context.closed, true)
  assert.equal(pool.snapshot().resident, 0)
  assert.equal(pool.profileDir(PROFILE_A), `${TEST_ROOT_DIR}/profiles/${PROFILE_A}`)
})

function testPool (overrides = {}) {
  return createProfilePool({
    rootDir: TEST_ROOT_DIR,
    launchContextFn: fakeLauncher().launch,
    logger: () => {},
    ...overrides,
  })
}

function fakeLauncher () {
  const launchedDirs = []
  return {
    launchedDirs,
    launch: async (dir) => {
      launchedDirs.push(dir)
      return fakeContext()
    },
  }
}

function fakeContext () {
  const context = {
    closed: false,
    pages: async () => [],
    close: async () => {
      context.closed = true
    },
    once: () => {},
  }
  return context
}

function fakeClock (start = 1_700_000_000_000) {
  let value = start
  return {
    now: () => value,
    value: () => value,
    advance: (ms) => {
      value += ms
    },
  }
}

test('the host runbook takes its profile id from the pool directory name', () => {
  // The container and the backend both key a profile by the bare user id, so a
  // headed mint on the host must land under that same key or the launch path
  // never reads it.
  assert.equal(
    profileIdForSessionDir({ COMPANION_BROWSER_PROFILE: '/var/lib/campsite-companion/browser-session/profiles/7' }),
    '7',
  )
})

test('an explicit profile id beats the directory name', () => {
  assert.equal(
    profileIdForSessionDir({
      COMPANION_BROWSER_PROFILE: '/var/lib/campsite-companion/browser-session/profiles/7',
      COMPANION_PROFILE_ID: '9',
    }),
    '9',
  )
})

test('a directory that is not a pool profile mints no profile id', () => {
  // The legacy single-profile directory has no owner. Guessing one would write
  // a jar under a key that belongs to somebody else.
  assert.equal(profileIdForSessionDir({ COMPANION_BROWSER_PROFILE: '/var/lib/campsite-companion/browser-session' }), null)
  assert.equal(profileIdForSessionDir({ COMPANION_BROWSER_PROFILE: '/tmp/scratch' }), null)
})
