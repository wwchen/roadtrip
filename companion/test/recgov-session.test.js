import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import {
  getRecgovSessionStatus,
  logoutRecgovBrowserSession,
  recgovLoginCredentialsFromInput,
  resolveRecaccount,
  runRecgovProfileLogin,
} from '../src/recgovSession.js'

const JWT_HEADER = { alg: 'none' }
const JWT_SIGNATURE = 'sig'
const FRESH_OFFSET_SECONDS = 60 * 60
const NEAR_EXPIRY_OFFSET_SECONDS = 60
const REFRESH_RETRY_DELAY_MS = 1000

test('recgovLoginCredentialsFromInput requires username/email and password and accepts MFA code', () => {
  assert.deepEqual(
    recgovLoginCredentialsFromInput({}),
    {
      configured: false,
      reason: 'credentials_not_configured',
      emailConfigured: false,
      passwordConfigured: false,
      mfaConfigured: false,
    },
  )
  assert.deepEqual(
    recgovLoginCredentialsFromInput({ username: 'user@example.com' }),
    {
      configured: false,
      reason: 'credentials_incomplete',
      emailConfigured: true,
      passwordConfigured: false,
      mfaConfigured: false,
    },
  )

  const credentials = recgovLoginCredentialsFromInput({
    username: ' user@example.com ',
    password: 'secret',
    mfa_code: '123456',
  })

  assert.equal(credentials.configured, true)
  assert.equal(credentials.email, 'user@example.com')
  assert.equal(credentials.password, 'secret')
  assert.equal(credentials.mfaCode, '123456')
  assert.equal(credentials.mfaConfigured, true)
})

test('resolveRecaccount uses the existing companion browser recaccount', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-existing' }),
  })
  const page = fakePage({ rawRecaccount: JSON.stringify(recaccount) })

  const resolved = await resolveRecaccount(page)

  assert.equal(resolved.access_token, recaccount.access_token)
  assert.ok(Date.parse(getRecgovSessionStatus().next_refresh_at) > Date.now())
  assert.deepEqual(page.gotos, ['https://www.recreation.gov/'])
  assert.equal(page.refreshCalls.length, 0)
  assert.deepEqual(page.context().cookies, [{
    name: 'r1s-fingerprint',
    value: 'fp-existing',
    domain: '.recreation.gov',
    path: '/',
    secure: true,
    sameSite: 'Lax',
  }])
})

test('resolveRecaccount refreshes near-expiry recaccount in the companion browser', async () => {
  const stale = testRecaccount({
    token: fakeJwt({ offsetSeconds: NEAR_EXPIRY_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const refreshed = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-new' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(stale),
    refreshRecaccount: refreshed,
  })

  const resolved = await resolveRecaccount(page)

  assert.equal(resolved.access_token, refreshed.access_token)
  assert.equal(page.refreshCalls.length, 1)
  assert.equal(page.refreshCalls[0].url, 'https://www.recreation.gov/api/accounts/login/v2/refresh')
  assert.equal(page.refreshCalls[0].token, stale.access_token)
  assert.deepEqual(page.refreshCalls[0].credentials, {
    account_id: 'acct-1',
    refresh_id: 'refresh-1',
  })
  const sessionStatus = getRecgovSessionStatus()
  assert.ok(Date.parse(sessionStatus.last_refresh_at) > 0)
  assert.equal(sessionStatus.last_refresh_expires_at, refreshed.expiration)
  assert.ok(Date.parse(sessionStatus.next_refresh_at) < Date.parse(sessionStatus.last_refresh_expires_at))
})

test('resolveRecaccount force-refreshes a fresh browser recaccount', async () => {
  const fresh = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const refreshed = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-new' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(fresh),
    refreshRecaccount: refreshed,
  })

  const resolved = await resolveRecaccount(page, { forceRefresh: true })

  assert.equal(resolved.access_token, refreshed.access_token)
  assert.equal(page.refreshCalls.length, 1)
  assert.equal(page.refreshCalls[0].token, fresh.access_token)
  assert.equal(page.context().cookies.at(-1).value, 'fp-new')
})

test('resolveRecaccount retries transient forced browser refresh failures', async () => {
  const fresh = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const refreshed = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-new' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(fresh),
    refreshResponses: [
      { ok: false, error: 'Failed to fetch' },
      { ok: true, recaccount: refreshed },
    ],
  })

  const resolved = await resolveRecaccount(page, { forceRefresh: true })

  assert.equal(resolved.access_token, refreshed.access_token)
  assert.equal(page.refreshCalls.length, 2)
  assert.deepEqual(page.waits, [REFRESH_RETRY_DELAY_MS])
})

test('resolveRecaccount fails closed when forced browser refresh is rejected', async () => {
  const fresh = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(fresh),
    refreshResponse: { ok: false, status: 401, body: 'unauthorized' },
  })

  const resolved = await resolveRecaccount(page, { forceRefresh: true })

  assert.equal(resolved, null)
  assert.equal(page.refreshCalls.length, 1)
  assert.equal(page.clearCalls, 1)
  assert.equal(page.loginClicks, 0)
})

test('resolveRecaccount prompts manual login after allowed forced refresh failure', async () => {
  const stale = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const relogged = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-new' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(stale),
    rawRecaccountAfterClear: JSON.stringify(relogged),
    refreshResponses: [
      { ok: false, status: 404, body: '{"error":"Item not found"}' },
      { ok: true, recaccount: relogged },
    ],
  })

  const resolved = await resolveRecaccount(page, {
    forceRefresh: true,
    allowManualLoginAfterRefreshFailure: true,
  })

  assert.equal(resolved.access_token, relogged.access_token)
  assert.equal(page.refreshCalls.length, 2)
  assert.equal(page.clearCalls, 1)
  assert.equal(page.loginClicks, 1)
})

test('resolveRecaccount can log in with request-scoped Recreation.gov credentials', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-credential' }),
  })
  const page = fakePage({
    credentialRawRecaccount: JSON.stringify(recaccount),
  })

  const resolved = await resolveRecaccount(page, {
    credentials: { username: 'user@example.com', password: 'secret' },
  })

  assert.equal(resolved.access_token, recaccount.access_token)
  assert.equal(page.loginClicks, 1)
  assert.equal(page.credentialSubmitClicks, 1)
  assert.equal(page.screenshots.length, 1)
  assert.equal(getRecgovSessionStatus().last_login_diagnostic.reason, 'login_success')
  assert.equal(getRecgovSessionStatus().last_login_diagnostic.screenshot_path, undefined)
  assert.match(getRecgovSessionStatus().last_login_diagnostic.screenshot_url, /^\/screenshot\/diagnostics\//)
  assert.deepEqual(page.viewportSize, { width: 1280, height: 1000 })
  assert.equal(page.screenshots[0].fullPage, false)
  assert.deepEqual(page.fills.map(({ value }) => value), ['user@example.com', 'secret'])
})

test('resolveRecaccount can submit credentials with Enter when the login button is not found', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-enter-submit' }),
  })
  const page = fakePage({
    credentialRawRecaccount: JSON.stringify(recaccount),
    submitSelectorVisible: false,
  })

  const resolved = await resolveRecaccount(page, {
    credentials: { username: 'user@example.com', password: 'secret' },
  })

  assert.equal(resolved.access_token, recaccount.access_token)
  assert.equal(page.credentialSubmitClicks, 0)
  assert.equal(page.enterPresses, 1)
  assert.deepEqual(page.fills.map(({ value }) => value), ['user@example.com', 'secret'])
})

test('resolveRecaccount can submit a provided Recreation.gov 2FA code', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-mfa' }),
  })
  const page = fakePage({
    credentialRawRecaccount: JSON.stringify(recaccount),
    mfaRequired: true,
    expectedMfaCode: '123456',
  })

  const resolved = await resolveRecaccount(page, {
    credentials: { username: 'user@example.com', password: 'secret', mfa_code: '123456' },
  })

  assert.equal(resolved.access_token, recaccount.access_token)
  assert.equal(page.credentialSubmitClicks, 1)
  assert.equal(page.mfaSubmitClicks, 1)
  assert.deepEqual(page.fills.map(({ value }) => value), ['user@example.com', 'secret', '123456'])
})

test('resolveRecaccount fails closed when Recreation.gov 2FA has no supplied code', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-mfa' }),
  })
  const page = fakePage({
    credentialRawRecaccount: JSON.stringify(recaccount),
    mfaRequired: true,
  })

  const resolved = await resolveRecaccount(page, {
    credentials: { username: 'user@example.com', password: 'secret' },
    loginTimeoutMs: '1',
    allowManualLogin: false,
  })

  assert.equal(resolved, null)
  assert.equal(page.credentialSubmitClicks, 1)
  assert.equal(page.mfaSubmitClicks, 0)
  assert.equal(page.screenshots.length, 1)
  assert.equal(getRecgovSessionStatus().last_login_diagnostic.reason, 'mfa_required')
  assert.equal(getRecgovSessionStatus().last_login_diagnostic.screenshot_path, undefined)
  assert.match(getRecgovSessionStatus().last_login_diagnostic.screenshot_url, /^\/screenshot\/diagnostics\//)
  assert.deepEqual(page.viewportSize, { width: 1280, height: 1000 })
  assert.equal(page.screenshots[0].fullPage, false)
})

test('logoutRecgovBrowserSession clicks the Rec.gov logout control and verifies logged-out state', async () => {
  const page = fakePage({
    loggedIn: true,
    logoutSelectorVisible: true,
  })

  const result = await logoutRecgovBrowserSession({
    getContextFn: async () => ({
      newPage: async () => page,
    }),
    isSpaLoggedInFn: async () => page.loggedIn,
  })

  assert.equal(result.ok, true)
  assert.equal(result.clicked, true)
  assert.equal(result.selector, 'button:has-text("Log Out")')
  assert.equal(page.logoutClicks, 1)
  assert.equal(page.closed, true)
  assert.equal(getRecgovSessionStatus().next_refresh_at, null)
})

test('runRecgovProfileLogin holds the MFA page open and resumes on it', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-held' }),
  })
  const page = fakePage({
    credentialRawRecaccount: JSON.stringify(recaccount),
    mfaRequired: true,
    expectedMfaCode: '123456',
    loggedIn: true,
  })

  const pending = await runRecgovProfileLogin({
    getContextFn: async () => page.context(),
    credentials: { username: 'user@example.com', password: 'secret' },
    options: { loginTimeoutMs: '1', allowManualLogin: false },
  })

  assert.equal(pending.state, 'mfa_required')
  assert.equal(pending.logged_in, false)
  assert.equal(typeof pending.resume, 'function')
  assert.equal(page.closed, false, 'the pending login page stays open')
  assert.equal(page.credentialSubmitClicks, 1)

  const gotosBeforeResume = page.gotos.length
  const completed = await pending.resume('123456')

  assert.equal(completed.state, 'ok')
  assert.equal(completed.logged_in, true)
  // The whole point: phase two types the code into the page that is already
  // sitting on the prompt. It must not navigate again or re-POST credentials,
  // which would make Rec.gov issue a new code and invalidate the user's.
  assert.equal(page.credentialSubmitClicks, 1)
  assert.equal(page.mfaSubmitClicks, 1)
  assert.equal(page.gotos.length, gotosBeforeResume)
  assert.deepEqual(page.fills.map(({ value }) => value), ['user@example.com', 'secret', '123456'])
  assert.equal(page.closed, true, 'the held page closes once the challenge resolves')
})

test('runRecgovProfileLogin reports a rejected MFA code without re-submitting credentials', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-held' }),
  })
  const page = fakePage({
    credentialRawRecaccount: JSON.stringify(recaccount),
    mfaRequired: true,
    expectedMfaCode: '123456',
  })

  const pending = await runRecgovProfileLogin({
    getContextFn: async () => page.context(),
    credentials: { username: 'user@example.com', password: 'secret' },
    options: { loginTimeoutMs: '1', allowManualLogin: false, credentialSessionTimeoutMs: '1' },
  })
  const rejected = await pending.resume('000000')

  assert.equal(rejected.state, 'failed')
  assert.equal(rejected.logged_in, false)
  assert.equal(page.credentialSubmitClicks, 1)
  assert.equal(page.mfaSubmitClicks, 1)
})

test('runRecgovProfileLogin returns the existing session without touching the login form', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-existing' }),
  })
  const page = fakePage({ rawRecaccount: JSON.stringify(recaccount), loggedIn: true })

  const outcome = await runRecgovProfileLogin({
    getContextFn: async () => page.context(),
    credentials: { username: 'user@example.com', password: 'secret' },
  })

  assert.equal(outcome.state, 'ok')
  assert.equal(outcome.logged_in, true)
  assert.equal(page.credentialSubmitClicks, 0)
  assert.equal(page.closed, true)
})

function fakePage ({
  rawRecaccount,
  rawRecaccountAfterClear = null,
  refreshRecaccount = null,
  refreshResponse = null,
  refreshResponses = null,
  credentialRawRecaccount = null,
  mfaRequired = false,
  expectedMfaCode = null,
  submitSelectorVisible = true,
  loggedIn = false,
  logoutSelectorVisible = false,
}) {
  let refreshResponseIndex = 0
  let currentRawRecaccount = rawRecaccount
  let loginOpened = false
  let mfaPromptVisible = false
  let submittedMfaCode = null
  const context = {
    cookies: [],
    pages: () => [page],
    newPage: async () => page,
    addCookies: async (cookies) => {
      context.cookies.push(...cookies)
    },
  }
  const page = {
    gotos: [],
    refreshCalls: [],
    clearCalls: 0,
    loginClicks: 0,
    credentialSubmitClicks: 0,
    mfaSubmitClicks: 0,
    logoutClicks: 0,
    loggedIn,
    fills: [],
    screenshots: [],
    waits: [],
    enterPresses: 0,
    closed: false,
    context: () => context,
    url: () => 'https://www.recreation.gov/',
    goto: async (url) => {
      page.gotos.push(url)
    },
    screenshot: async (options) => {
      page.screenshots.push(options)
    },
    setViewportSize: async (size) => {
      page.viewportSize = size
    },
    locator: (selector) => ({
      first: () => ({
        waitFor: async () => {
          if (!selectorVisible(selector)) throw new Error(`selector not visible: ${selector}`)
        },
        fill: async (value) => {
          if (!selectorVisible(selector)) throw new Error(`selector not visible: ${selector}`)
          page.fills.push({ selector, value })
          if (isMfaInputSelector(selector)) submittedMfaCode = value
        },
        click: async () => {
          if (selector.includes('Sign Up / Log In')) {
            page.loginClicks += 1
            loginOpened = true
            return
          }
          if (isLogoutSelector(selector)) {
            page.logoutClicks += 1
            page.loggedIn = false
            return
          }
          if (isSubmitSelector(selector)) {
            if (mfaPromptVisible) {
              page.mfaSubmitClicks += 1
              if (!expectedMfaCode || submittedMfaCode === expectedMfaCode) currentRawRecaccount = credentialRawRecaccount
              return
            }

            page.credentialSubmitClicks += 1
            if (mfaRequired) mfaPromptVisible = true
            else currentRawRecaccount = credentialRawRecaccount
          }
        },
        isVisible: async () => selectorVisible(selector),
        textContent: async () => '',
      }),
    }),
    waitForTimeout: async (ms) => {
      page.waits.push(ms)
    },
    close: async () => {
      page.closed = true
    },
    keyboard: {
      press: async (key) => {
        if (key !== 'Enter') throw new Error(`unexpected key: ${key}`)
        page.enterPresses += 1
        if (mfaPromptVisible) {
          if (!expectedMfaCode || submittedMfaCode === expectedMfaCode) currentRawRecaccount = credentialRawRecaccount
          return
        }
        if (mfaRequired) mfaPromptVisible = true
        else currentRawRecaccount = credentialRawRecaccount
      },
    },
    evaluate: async (_fn, arg) => {
      if (arg === 'recaccount') return currentRawRecaccount
      if (arg?.clearRecaccount) {
        page.clearCalls += 1
        currentRawRecaccount = rawRecaccountAfterClear
        return undefined
      }
      if (arg?.url) {
        page.refreshCalls.push(arg)
        if (refreshResponses) return refreshResponses[refreshResponseIndex++]
        return refreshResponse || { ok: true, recaccount: refreshRecaccount }
      }
      throw new Error('unexpected evaluate call')
    },
  }
  function selectorVisible (selector) {
    if (selector.includes('Sign Up / Log In')) return true
    if (isLogoutSelector(selector)) return logoutSelectorVisible
    if (isMfaInputSelector(selector)) return mfaPromptVisible
    if (isEmailSelector(selector) || isPasswordSelector(selector)) return loginOpened
    if (isSubmitSelector(selector)) return loginOpened && submitSelectorVisible
    return false
  }
  return page
}

function isEmailSelector (selector) {
  return /email|username/.test(selector)
}

function isPasswordSelector (selector) {
  return /password/.test(selector)
}

function isMfaInputSelector (selector) {
  return /one-time-code|code/.test(selector) && /input/.test(selector)
}

function isSubmitSelector (selector) {
  return /button/.test(selector)
}

function isLogoutSelector (selector) {
  return /Log Out|Logout|Sign Out|Sign out/.test(selector)
}

function testRecaccount ({ token }) {
  return {
    access_token: token,
    expiration: new Date(Date.now() + FRESH_OFFSET_SECONDS * 1000).toISOString(),
    account: { account_id: 'acct-1', email: 'test@example.com' },
    is_guest: false,
    refresh_id: 'refresh-1',
  }
}

function fakeJwt ({ offsetSeconds, fingerprint }) {
  const exp = Math.floor(Date.now() / 1000) + offsetSeconds
  return `${base64Url(JWT_HEADER)}.${base64Url({ exp, fingerprint })}.${JWT_SIGNATURE}`
}

function base64Url (value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url')
}
