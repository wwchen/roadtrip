// Per-user browser-profile pool.
//
// One roadtrip user id ("profile id", an opaque string to the companion) maps
// to one persistent Chromium user-data directory under the browser-session
// volume, so no two users ever share a rec.gov session. Playwright persistent
// contexts are one browser process per directory, so residency is a real
// memory cost: a global cap governs on-demand launches, while keep-warm
// (armed) profiles — the ones backing an active `atc` watch — are exempt and
// merely report their overflow.
//
// The pool also owns the per-profile state the HTTP layer needs: the busy
// lock for mutating operations (health reads never take it), the pending MFA
// challenge, the failed-login backoff marker, and the last auth status.

import path from 'node:path'
import { randomBytes } from 'node:crypto'
import {
  launchProfileContext,
  resolveSessionDir,
} from './browser.js'
import { log } from './server/logging.js'

export const PROFILE_DIR_SEGMENT = 'profiles'
export const PROFILE_ID_MAX_CHARS = 64
export const PROFILE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]*$/

export const DEFAULT_MAX_CONCURRENT_BROWSERS = 3
export const DEFAULT_MFA_CHALLENGE_TTL_MS = 5 * 60 * 1_000
export const DEFAULT_FAILED_LOGIN_BACKOFF_MS = 60 * 1_000
const MFA_CHALLENGE_ID_BYTES = 16
const MILLISECONDS_PER_SECOND = 1_000

export const ERROR_PROFILE_ID_REQUIRED = 'profile_id_required'
export const ERROR_PROFILE_ID_INVALID = 'invalid_profile_id'
export const ERROR_PROFILE_BUSY = 'profile_busy'
export const ERROR_BROWSER_CAP_REACHED = 'browser_cap_reached'
export const ERROR_MFA_CHALLENGE_UNKNOWN = 'mfa_challenge_unknown'
export const ERROR_MFA_CHALLENGE_EXPIRED = 'mfa_challenge_expired'
export const ERROR_LOGIN_BACKOFF = 'login_backoff'

const UNCHECKED_AUTH_STATUS = Object.freeze({ state: 'unchecked', logged_in: false })

export function normalizeProfileId (raw) {
  if (raw === null || raw === undefined) return { ok: false, error: ERROR_PROFILE_ID_REQUIRED }
  const profileId = String(raw).trim()
  if (!profileId) return { ok: false, error: ERROR_PROFILE_ID_REQUIRED }
  if (profileId.length > PROFILE_ID_MAX_CHARS) return { ok: false, error: ERROR_PROFILE_ID_INVALID }
  if (!PROFILE_ID_PATTERN.test(profileId)) return { ok: false, error: ERROR_PROFILE_ID_INVALID }
  return { ok: true, profileId }
}

export function profilesRootDir (env = process.env) {
  return path.join(resolveSessionDir(env), PROFILE_DIR_SEGMENT)
}

export function createProfilePool ({
  rootDir = null,
  env = process.env,
  launchContextFn = launchProfileContext,
  now = Date.now,
  logger = log,
  maxConcurrentBrowsers = null,
  mfaChallengeTtlMs = null,
  failedLoginBackoffMs = null,
} = {}) {
  const profilesDir = rootDir ? path.join(rootDir, PROFILE_DIR_SEGMENT) : profilesRootDir(env)
  const browserCap = positiveNumber(maxConcurrentBrowsers, env.COMPANION_MAX_CONCURRENT_BROWSERS, DEFAULT_MAX_CONCURRENT_BROWSERS)
  const challengeTtlMs = positiveNumber(mfaChallengeTtlMs, env.COMPANION_MFA_CHALLENGE_TTL_MS, DEFAULT_MFA_CHALLENGE_TTL_MS)
  const backoffMs = positiveNumber(failedLoginBackoffMs, env.COMPANION_FAILED_LOGIN_BACKOFF_MS, DEFAULT_FAILED_LOGIN_BACKOFF_MS)

  const profiles = new Map()
  let keepWarmIds = new Set()

  function entry (profileId) {
    let state = profiles.get(profileId)
    if (!state) {
      state = {
        profileId,
        context: null,
        lock: null,
        challenge: null,
        backoffUntil: 0,
        authStatus: { ...UNCHECKED_AUTH_STATUS },
        lastUsedAt: 0,
      }
      profiles.set(profileId, state)
    }
    return state
  }

  function sweepExpiredChallenges () {
    const nowMs = now()
    for (const state of profiles.values()) {
      if (state.challenge && nowMs >= state.challenge.expiresAt) {
        logger('recgov profile mfa challenge expired', `profile=${state.profileId}`)
        dropChallenge(state)
      }
    }
  }

  function dropChallenge (state) {
    const challenge = state.challenge
    state.challenge = null
    if (challenge?.lock) releaseLock(state, challenge.lock)
  }

  function releaseLock (state, token) {
    if (state.lock && state.lock.token === token) state.lock = null
  }

  function profileDir (profileId) {
    return path.join(profilesDir, profileId)
  }

  function isKeepWarm (profileId) {
    return keepWarmIds.has(profileId)
  }

  function residentStates () {
    return [...profiles.values()].filter((state) => state.context)
  }

  function onDemandResidents () {
    return residentStates().filter((state) => !isKeepWarm(state.profileId))
  }

  async function evictForLaunch (profileId) {
    if (isKeepWarm(profileId)) return
    while (onDemandResidents().length >= browserCap) {
      const candidate = onDemandResidents()
        .filter((state) => !state.lock && state.profileId !== profileId)
        .toSorted((a, b) => a.lastUsedAt - b.lastUsedAt)[0]
      if (!candidate) {
        throw Object.assign(
          new Error(`concurrent browser cap of ${browserCap} reached and every resident profile is busy`),
          { code: ERROR_BROWSER_CAP_REACHED },
        )
      }
      logger('recgov profile evicted', `profile=${candidate.profileId}`, `cap=${browserCap}`)
      await closeState(candidate)
    }
  }

  async function closeState (state) {
    const context = state.context
    state.context = null
    if (context) await context.close().catch(() => {})
  }

  return {
    profileDir,
    profilesDir: () => profilesDir,
    isKeepWarm,

    async context (profileId) {
      const state = entry(profileId)
      state.lastUsedAt = now()
      if (state.context) {
        try {
          await state.context.pages()
          return state.context
        } catch {
          state.context = null
        }
      }
      await evictForLaunch(profileId)
      state.context = await launchContextFn(profileDir(profileId))
      state.context.once?.('close', () => {
        if (profiles.get(profileId)?.context === state.context) profiles.get(profileId).context = null
      })
      logger('recgov profile launched', `profile=${profileId}`, `keep_warm=${isKeepWarm(profileId)}`)
      return state.context
    },

    async closeProfile (profileId) {
      const state = profiles.get(profileId)
      if (!state) return
      await closeState(state)
    },

    setKeepWarmProfiles (ids = []) {
      const normalized = []
      for (const raw of ids) {
        const parsed = normalizeProfileId(raw)
        if (parsed.ok) normalized.push(parsed.profileId)
      }
      keepWarmIds = new Set(normalized)
      const overflow = Math.max(0, keepWarmIds.size - browserCap)
      if (overflow > 0) {
        logger('recgov profile keep-warm overflow', `armed=${keepWarmIds.size}`, `cap=${browserCap}`)
      }
      return { keep_warm: [...keepWarmIds], keep_warm_overflow: overflow }
    },

    acquire (profileId, operation) {
      sweepExpiredChallenges()
      const state = entry(profileId)
      if (state.lock) return null
      const token = Symbol(operation)
      state.lock = { token, operation, since: now() }
      state.lastUsedAt = now()
      return {
        operation,
        release: () => releaseLock(state, token),
        token,
      }
    },

    isBusy (profileId) {
      sweepExpiredChallenges()
      return Boolean(profiles.get(profileId)?.lock)
    },

    busyOperation (profileId) {
      sweepExpiredChallenges()
      return profiles.get(profileId)?.lock?.operation ?? null
    },

    openMfaChallenge (profileId, { lock, complete }) {
      const state = entry(profileId)
      const challengeId = randomBytes(MFA_CHALLENGE_ID_BYTES).toString('hex')
      const expiresAt = now() + challengeTtlMs
      state.challenge = {
        id: challengeId,
        expiresAt,
        complete,
        lock: lock?.token ?? null,
      }
      return {
        challenge_id: challengeId,
        expires_at: new Date(expiresAt).toISOString(),
        expires_in_seconds: Math.round(challengeTtlMs / MILLISECONDS_PER_SECOND),
      }
    },

    takeMfaChallenge (profileId, challengeId) {
      const state = entry(profileId)
      const challenge = state.challenge
      if (!challenge || !challengeId || challenge.id !== challengeId) {
        sweepExpiredChallenges()
        return { ok: false, error: ERROR_MFA_CHALLENGE_UNKNOWN }
      }
      if (now() >= challenge.expiresAt) {
        dropChallenge(state)
        return { ok: false, error: ERROR_MFA_CHALLENGE_EXPIRED }
      }
      state.challenge = null
      return {
        ok: true,
        challenge: {
          complete: challenge.complete,
          release: () => releaseLock(state, challenge.lock),
        },
      }
    },

    recordLoginFailure (profileId) {
      const state = entry(profileId)
      state.backoffUntil = now() + backoffMs
      logger('recgov profile login backoff', `profile=${profileId}`, `backoff_ms=${backoffMs}`)
      return state.backoffUntil
    },

    clearLoginFailure (profileId) {
      entry(profileId).backoffUntil = 0
    },

    loginBackoff (profileId) {
      const state = profiles.get(profileId)
      const remaining = state ? state.backoffUntil - now() : 0
      if (!state || remaining <= 0) return { blocked: false, retry_after_ms: 0 }
      return { blocked: true, retry_after_ms: remaining }
    },

    setAuthStatus (profileId, status) {
      entry(profileId).authStatus = status
      return status
    },

    getAuthStatus (profileId) {
      return profiles.get(profileId)?.authStatus ?? { ...UNCHECKED_AUTH_STATUS }
    },

    snapshot () {
      sweepExpiredChallenges()
      const resident = residentStates()
      return {
        resident: resident.length,
        max_concurrent_browsers: browserCap,
        keep_warm: [...keepWarmIds],
        keep_warm_overflow: Math.max(0, keepWarmIds.size - browserCap),
        mfa_challenge_ttl_ms: challengeTtlMs,
        profiles: [...profiles.values()].map((state) => ({
          profile_id: state.profileId,
          resident: Boolean(state.context),
          busy: Boolean(state.lock),
          operation: state.lock?.operation ?? null,
          keep_warm: isKeepWarm(state.profileId),
          mfa_pending: Boolean(state.challenge),
          login_backoff_ms: Math.max(0, state.backoffUntil - now()),
        })),
      }
    },
  }
}

function positiveNumber (override, envValue, fallback) {
  for (const candidate of [override, envValue]) {
    const parsed = Number.parseInt(candidate ?? '', 10)
    if (Number.isFinite(parsed) && parsed > 0) return parsed
  }
  return fallback
}

export const profilePool = createProfilePool()
