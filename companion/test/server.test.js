import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { Readable } from 'node:stream'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {
  HANDLED_OPERATION_IDS,
  createCompanionServer,
  getRecgovAuthStatus,
  getRecgovHealthStatus,
  runRecgovAuthCheck,
} from '../src/server.js'
import { COMPANION_API_ROUTES } from '../src/apiContract.js'
import { COMPANION_API_TOKEN_HEADER } from '../src/server/apiToken.js'
import { recgovCookieSettingKey } from '../src/browser.js'
import { getSetting, setSetting } from '../src/store.js'
import {
  DEFAULT_MFA_CHALLENGE_TTL_MS,
  createProfilePool,
} from '../src/profilePool.js'

// The cookie-jar assertions below write through store.js, which must never be
// the developer's real ~/.campsite-companion.
let storeDir
before(() => {
  storeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'companion-server-store-'))
  process.env.COMPANION_DIR = storeDir
})
after(() => {
  delete process.env.COMPANION_DIR
  fs.rmSync(storeDir, { recursive: true, force: true })
})

const TEST_API_TOKEN = 'test-companion-token'
const CONTAINER_ADDRESS = '172.18.0.4'
const LOOPBACK_ADDRESS = '127.0.0.1'
const PROFILE_ID = 'user-7'
const OTHER_PROFILE_ID = 'user-8'
const MFA_CHALLENGE_TTL_OVERSHOOT_MS = DEFAULT_MFA_CHALLENGE_TTL_MS + 1_000

test('an unscoped auth check records logged-in status on the companion-wide row', async () => {
  const log = logCapture()

  const status = await runRecgovAuthCheck({
    operation: 'check',
    testChromiumFn: async () => ({ ok: true, loggedIn: true }),
    logger: log.write,
  })

  assert.equal(status.state, 'ok')
  assert.equal(status.logged_in, true)
  assert.equal(getRecgovAuthStatus(), status)
  assert.match(log.text(), /recgov auth check start/)
  assert.match(log.text(), /recgov auth check ok/)
})

test('getRecgovHealthStatus exposes login status and refresh metadata', async () => {
  const diagnostic = {
    reason: 'login_success',
    screenshot_url: '/screenshot/diagnostics/recgov-login-success.png',
  }
  await runRecgovAuthCheck({
    operation: 'check',
    testChromiumFn: async () => ({ ok: true, loggedIn: true, diagnostic }),
    logger: () => {},
  })

  const health = getRecgovHealthStatus()

  assert.equal(health.login_status, 'ok')
  assert.equal(health.logged_in, true)
  assert.equal('last_refresh_at' in health, true)
  assert.equal('last_refresh_expires_at' in health, true)
  assert.equal('next_refresh_at' in health, true)
  assert.equal('diagnostic' in health, false)
  assert.equal('last_login_diagnostic' in health, false)
})

test('an unscoped auth check records actionable auth failure status', async () => {
  const log = logCapture()
  const diagnostic = {
    reason: 'captcha_required',
    screenshot_url: '/screenshot/diagnostics/recgov-login-captcha.png',
  }

  const status = await runRecgovAuthCheck({
    operation: 'check',
    testChromiumFn: async () => ({ ok: true, loggedIn: false, diagnostic }),
    authFailureFn: () => ({
      error: 'recgov_not_authenticated',
      detail: 'No Recreation.gov browser session is available.',
      corrective_action: 'Run make recgov-login.',
      auth: {
        headless: true,
      },
    }),
    logger: log.write,
  })

  assert.equal(status.state, 'failed')
  assert.equal(status.logged_in, false)
  assert.equal(status.error, 'recgov_not_authenticated')
  assert.equal(status.corrective_action, 'Run make recgov-login.')
  assert.deepEqual(status.auth, {
    headless: true,
  })
  assert.deepEqual(status.diagnostic, diagnostic)
  assert.match(log.text(), /recgov auth check fail/)
  assert.match(log.text(), /error=recgov_not_authenticated/)
  assert.match(log.text(), /diagnostic_reason=captcha_required/)
  assert.match(log.text(), /screenshot=\/screenshot\/diagnostics\/recgov-login-captcha\.png/)
})

test('an unscoped auth check records exceptions as auth failures', async () => {
  const log = logCapture()

  const status = await runRecgovAuthCheck({
    operation: 'check',
    testChromiumFn: async () => { throw new Error('browser launch failed') },
    logger: log.write,
  })

  assert.equal(status.state, 'failed')
  assert.equal(status.logged_in, false)
  assert.equal(status.error, 'recgov_auth_check_exception')
  assert.equal(status.detail, 'browser launch failed')
  assert.match(status.corrective_action, /recgov-login/)
  assert.match(log.text(), /recgov auth check exception/)
})

test('GET / returns a simple operator login form', async () => {
  const response = await request(testServer(), {
    path: '/',
    headers: { accept: 'text/html' },
  })

  assert.equal(response.status, 200)
  assert.match(response.text, /id="toggle-login"/)
  assert.match(response.text, /id="toggle-atc"/)
  assert.match(response.text, /<section id="login-panel" class="panel" hidden>/)
  assert.match(response.text, /<section id="atc-panel" class="panel" hidden>/)
  assert.match(response.text, /<form id="login-form" method="post" action="\/login">/)
  assert.match(response.text, /<form id="atc-form" method="post" action="\/atc">/)
  assert.match(response.text, /name="username"/)
  assert.match(response.text, /name="password"/)
  assert.match(response.text, /name="mfa_code"/)
  assert.match(response.text, /name="start_date"/)
  assert.match(response.text, /name="end_date"/)
  assert.match(response.text, /name="campsite_id"/)
  assert.doesNotMatch(response.text, /name="vendor"/)
  assert.doesNotMatch(response.text, /name="booking_url"/)
  assert.doesNotMatch(response.text, /name="campground_id"/)
  assert.match(response.text, /id="loading"/)
  assert.match(response.text, /id="json-output"/)
  assert.match(response.text, /id="refresh-session"/)
  assert.match(response.text, /id="health-json"/)
  assert.match(response.text, /id="logout-session"/)
  assert.match(response.text, /id="session-screenshot"/)
  assert.match(response.text, /name="profile_id"/)
  assert.match(response.text, /name="challenge_id"/)
  assert.match(response.text, /'x-companion-token': tokenInput\.value\.trim\(\)/)
  assert.match(response.text, /target\.searchParams\.set\('profile_id'/)
  assert.doesNotMatch(response.text, /src="\/screenshot\?path=\/"/)
  assert.match(response.text, /togglePanel\(loginPanel, loginToggle\)/)
  assert.match(response.text, /JSON\.stringify\(Object\.fromEntries\(new FormData\(atcForm\)\)\)/)
  assert.match(response.text, /fetch\(withProfile\(url\)/)
  assert.doesNotMatch(response.text, /action="\/refresh"/)
  assert.doesNotMatch(response.text, /RECGOV_EMAIL|RECGOV_PASSWORD|RECGOV_MFA_CODE|RECGOV_OTP/)
})

test('GET /openapi.json returns companion-owned OpenAPI docs', async () => {
  const response = await request(testServer(), {
    path: '/openapi.json',
  })

  assert.equal(response.status, 200)
  assert.equal(response.headers['content-type'], 'application/json; charset=utf-8')
  assert.equal(response.json.openapi, '3.0.3')
  assert.equal(response.json.info.title, 'Campsite Companion API')
  assert.deepEqual(openApiOperations(response.json), contractOperations(COMPANION_API_ROUTES))
  assert.deepEqual(
    HANDLED_OPERATION_IDS.toSorted(),
    COMPANION_API_ROUTES.map((route) => route.operationId).toSorted(),
  )
  assert.ok(response.json.paths['/'])
  assert.ok(response.json.paths['/health'])
  assert.ok(response.json.paths['/login'])
  assert.ok(response.json.paths['/logout'])
  assert.ok(response.json.paths['/refresh'])
  assert.ok(response.json.paths['/atc'])
  assert.ok(response.json.paths['/screenshot'])
  assert.ok(response.json.paths['/screenshot/diagnostics/{filename}'])
  assert.equal(response.json.paths['/refresh'].get, undefined)
  assert.ok(response.json.paths['/refresh'].post)

  assert.ok(response.json.paths['/verify'].post)
  for (const path of ['/login', '/logout', '/refresh', '/verify', '/atc', '/screenshot']) {
    const operation = response.json.paths[path].post || response.json.paths[path].get
    assert.ok(operation.responses[503], `${path} must document browser_cap_reached`)
  }
  assert.deepEqual(response.json.security, [{ companionToken: [] }])
  assert.equal(response.json.components.securitySchemes.companionToken.name, COMPANION_API_TOKEN_HEADER)

  const loginSchema = response.json.components.schemas.LoginRequest
  assert.deepEqual(
    Object.keys(loginSchema.properties),
    ['profile_id', 'username', 'password', 'mfa_code', 'challenge_id', 'unattended'],
  )
  assert.deepEqual(loginSchema.required, ['profile_id'])

  const authSchema = response.json.components.schemas.RecgovAuthStatus
  assert.equal(authSchema.properties.last_login_diagnostic, undefined)
  assert.equal(authSchema.properties.diagnostic, undefined)
  assert.ok(response.json.components.schemas.AuthResponse.properties.diagnostics)
  const diagnosticSchema = response.json.components.schemas.LoginDiagnostic
  assert.equal(diagnosticSchema.properties.screenshot_path, undefined)
  const atcSchema = response.json.components.schemas.AtcRequest
  assert.deepEqual(atcSchema.required, ['profile_id', 'start_date', 'end_date', 'campsite_id'])
  assert.deepEqual(Object.keys(atcSchema.properties), ['profile_id', 'start_date', 'end_date', 'campsite_id'])
  const atcResultSchema = response.json.components.schemas.AtcResult
  assert.equal(atcResultSchema.properties.campsite_site, undefined)
  assert.ok(atcResultSchema.properties.logs)
  assert.ok(atcResultSchema.properties.screenshots)
})

test('GET /docs returns Swagger UI for the companion OpenAPI spec', async () => {
  const response = await request(testServer(), {
    path: '/docs',
    headers: { accept: 'text/html' },
  })

  assert.equal(response.status, 200)
  assert.equal(response.headers['content-type'], 'text/html; charset=utf-8')
  assert.match(response.text, /SwaggerUIBundle/)
  assert.match(response.text, /url: '\/openapi\.json'/)
})

test('POST /login passes request-scoped credentials to the profile login', async () => {
  let loginCall = null
  const response = await request(testServer({
    credentialLoginFn: async (call) => {
      loginCall = call
      return {
        state: 'ok',
        logged_in: true,
        diagnostic: {
          reason: 'login_success',
          screenshot_url: '/screenshot/diagnostics/recgov-login-success.png',
        },
      }
    },
  }), {
    method: 'POST',
    path: '/login',
    headers: {
      accept: 'application/json',
      'content-type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({
      profile_id: PROFILE_ID,
      username: 'camper@example.test',
      password: 'secret',
      mfa_code: '123456',
    }).toString(),
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.deepEqual(loginCall.credentials, {
    email: 'camper@example.test',
    password: 'secret',
    mfaCode: '123456',
  })
  assert.equal(loginCall.profileId, PROFILE_ID)
  assert.match((await loginCall.getContextFn()).dir, new RegExp(`/profiles/${PROFILE_ID}$`))
  assert.equal(response.json.recgov_auth.diagnostic, undefined)
  assert.deepEqual(response.json.diagnostics, {
    reason: 'login_success',
    screenshot_url: '/screenshot/diagnostics/recgov-login-success.png',
  })
})

test('POST /login HTML response renders a failed login diagnostic screenshot', async () => {
  const response = await request(testServer({
    credentialLoginFn: async () => ({
      state: 'failed',
      logged_in: false,
      reason: 'login_error',
      diagnostic: {
        reason: 'login_error',
        screenshot_url: '/screenshot/diagnostics/recgov-login-error.png',
      },
    }),
  }), {
    method: 'POST',
    path: '/login',
    headers: {
      accept: 'text/html',
      'content-type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({
      profile_id: PROFILE_ID,
      username: 'camper@example.test',
      password: 'secret',
      mfa_code: '123456',
    }).toString(),
  })

  assert.equal(response.status, 401)
  assert.match(response.text, /Last login screenshot/)
  assert.match(response.text, /Reason: login_error/)
  assert.match(response.text, /src="\/screenshot\/diagnostics\/recgov-login-error\.png"/)
  assert.ok(response.text.indexOf('id="json-output"') < response.text.indexOf('Last login screenshot'))
})

test('POST /refresh force-refreshes the stored browser session without credentials', async () => {
  let authOptions = null
  const response = await request(testServer({
    testChromiumFn: async (_rawCookieInput, options) => {
      authOptions = options
      return { ok: true, loggedIn: true }
    },
  }), {
    method: 'POST',
    path: `/refresh?profile_id=${PROFILE_ID}`,
    headers: { accept: 'application/json' },
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.equal(authOptions.forceRefresh, true)
  assert.equal(authOptions.allowManualLogin, false)
  assert.equal(authOptions.credentials, undefined)
  assert.equal(response.json.diagnostics, null)
  assert.equal(response.json.recgov_auth.diagnostic, undefined)
})

test('POST /logout runs the Rec.gov browser logout flow', async () => {
  const response = await request(testServer({
    logoutRecgovSessionFn: async () => ({
      ok: true,
      logged_in: false,
      clicked: true,
      selector: 'button:has-text("Log Out")',
      page_url: 'https://www.recreation.gov/',
    }),
  }), {
    method: 'POST',
    path: `/logout?profile_id=${PROFILE_ID}`,
    headers: { accept: 'application/json' },
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.equal(response.json.recgov_auth.state, 'logged_out')
  assert.equal(response.json.recgov_auth.logged_in, false)
  assert.equal(response.json.recgov_auth.logout.clicked, true)
  assert.equal(response.json.recgov_auth.logout.selector, 'button:has-text("Log Out")')
  assert.equal(response.json.diagnostics, null)
  assert.equal(response.json.recgov_auth.diagnostic, undefined)
})

test('POST /atc passes the flat payload to the one-shot runner', async () => {
  const payload = {
    profile_id: PROFILE_ID,
    start_date: '2026-07-19',
    end_date: '2026-07-20',
    campsite_id: '102524',
  }
  let argv = null
  const response = await request(testServer({
    runAtcOnceFn: async ({ argv: receivedArgv, stdout, stderr }) => {
      argv = receivedArgv
      stderr.write('Cart: opening https://www.recreation.gov/camping/campsites/102524\n')
      stderr.write('Cart: confirmation buttons visible but none enabled\n')
      stdout.write(JSON.stringify({
        ok: true,
        cart_added: true,
        booking_url: 'https://www.recreation.gov/camping/campsites/102524?startDate=2026-07-19&endDate=2026-07-20',
        screenshots: [
          {
            label: 'opened-booking-url',
            screenshot_url: '/screenshot/diagnostics/recgov-atc-opened-booking-url.png',
          },
        ],
      }))
      return 0
    },
  }), {
    method: 'POST',
    path: '/atc',
    headers: {
      accept: 'application/json',
      'content-type': 'application/json',
    },
    body: JSON.stringify(payload),
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.deepEqual(argv, ['--payload-json', JSON.stringify(payload)])
  assert.match(
    response.json.logs[0],
    /^recgov atc start profile=user-7 start_date=2026-07-19 end_date=2026-07-20 campsite=102524 booking_url="https:\/\/www\.recreation\.gov\/camping\/campsites\/102524\?startDate=2026-07-19&endDate=2026-07-20"/,
  )
  assert.deepEqual(response.json.logs.slice(1, 3), [
    'Cart: opening https://www.recreation.gov/camping/campsites/102524',
    'Cart: confirmation buttons visible but none enabled',
  ])
  assert.match(response.json.logs[3], /^recgov atc result success code=0 duration_ms=/)
  assert.deepEqual(response.json.screenshots, [
    {
      label: 'opened-booking-url',
      screenshot_url: '/screenshot/diagnostics/recgov-atc-opened-booking-url.png',
    },
  ])
})

test('GET /screenshot captures a Recreation.gov path with the companion browser session', async () => {
  const image = Buffer.from([0x89, 0x50, 0x4e, 0x47])
  const page = fakeScreenshotPage(image)

  const response = await request(testServer({
    getContextFn: async () => ({
      newPage: async () => page,
    }),
    injectStoredCookiesFn: async () => 0,
    resolveRecaccountFn: async () => null,
    injectRecaccountFn: async () => {},
    injectBearerRouteFn: async () => true,
  }), {
    path: `/screenshot?profile_id=${PROFILE_ID}&path=/camping/campgrounds/232447&startDate=2026-07-19`,
  })

  assert.equal(response.status, 200)
  assert.equal(response.headers['content-type'], 'image/png')
  assert.deepEqual(response.body, image)
})

test('GET /screenshot rejects non-Recreation.gov targets', async () => {
  const response = await request(testServer(), {
    path: `/screenshot?profile_id=${PROFILE_ID}&url=https://example.com/`,
  })

  assert.equal(response.status, 400)
  assert.equal(response.json.error, 'invalid_screenshot_target')
})

test('GET /screenshot path suffix is not an undocumented API route', async () => {
  const response = await request(testServer(), {
    path: '/screenshot/camping/campgrounds/232447',
  })

  assert.equal(response.status, 400)
  assert.equal(response.json.error, 'unsupported_route')
})

test('GET /screenshot takes the profile lock like the other browser routes', async () => {
  const pool = testPool()
  const image = Buffer.from([0x89, 0x50, 0x4e, 0x47])
  const server = testServer({
    pool,
    getContextFn: async () => ({ newPage: async () => fakeScreenshotPage(image) }),
    injectStoredCookiesFn: async () => 0,
    resolveRecaccountFn: async () => null,
    injectRecaccountFn: async () => {},
    injectBearerRouteFn: async () => true,
  })
  pool.acquire(PROFILE_ID, 'login')

  const blocked = await request(server, { path: `/screenshot?profile_id=${PROFILE_ID}&path=/` })

  assert.equal(blocked.status, 409)
  assert.equal(blocked.json.error, 'profile_busy')

  const free = await request(server, { path: `/screenshot?profile_id=${OTHER_PROFILE_ID}&path=/` })

  assert.equal(free.status, 200)
  assert.equal(pool.isBusy(OTHER_PROFILE_ID), false, 'the screenshot must release the lock it took')
})

test('the concurrent-browser cap surfaces as a structured 503, not an opaque 500', async () => {
  const pool = testPool({ maxConcurrentBrowsers: 1 })
  const server = testServer({
    pool,
    testChromiumFn: async () => ({ ok: true, loggedIn: true }),
    verifyRecgovSessionFn: async ({ getContextFn }) => {
      await getContextFn()
      return { ok: true, logged_in: true, checked_at: new Date().toISOString() }
    },
  })
  // One resident profile, locked, so nothing is evictable.
  await pool.context(PROFILE_ID)
  pool.acquire(PROFILE_ID, 'login')

  for (const testCase of [
    { method: 'POST', path: `/verify?profile_id=${OTHER_PROFILE_ID}` },
    { method: 'POST', path: `/refresh?profile_id=${OTHER_PROFILE_ID}` },
    { method: 'GET', path: `/screenshot?profile_id=${OTHER_PROFILE_ID}&path=/` },
  ]) {
    const response = await request(server, { headers: { accept: 'application/json' }, ...testCase })

    assert.equal(response.status, 503, `${testCase.path} must map the cap to 503`)
    assert.equal(response.json.error, 'browser_cap_reached')
    assert.equal(pool.isBusy(OTHER_PROFILE_ID), false, 'a refused launch must not strand the lock')
  }
})

test('an empty MFA code does not consume the pending challenge', async () => {
  const pool = testPool()
  const server = testServer({
    pool,
    credentialLoginFn: mfaChallengeLogin(),
  })
  const challenged = await beginLogin(server)

  const empty = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, challenge_id: challenged.json.challenge_id }),
  })

  assert.equal(empty.status, 400)
  assert.equal(empty.json.error, 'mfa_required')
  assert.equal(pool.isBusy(PROFILE_ID), true, 'the challenge still holds its lock')

  const completed = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, challenge_id: challenged.json.challenge_id, mfa_code: '123456' }),
  })

  assert.equal(completed.status, 200, 'the challenge survived the empty-code attempt')
})

test('a mistyped MFA code reports mfa_invalid and never arms the credential backoff', async () => {
  const pool = testPool()
  const server = testServer({
    pool,
    credentialLoginFn: mfaChallengeLogin({ acceptCode: '123456' }),
  })
  const challenged = await beginLogin(server)

  const wrong = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, challenge_id: challenged.json.challenge_id, mfa_code: '000000' }),
  })

  assert.equal(wrong.status, 401)
  assert.equal(wrong.json.error, 'mfa_invalid')
  assert.equal(pool.loginBackoff(PROFILE_ID).blocked, false, 'a wrong code is not a failed credential login')
  assert.equal(pool.isBusy(PROFILE_ID), false)

  const retried = await beginLogin(server)

  assert.equal(retried.status, 401)
  assert.equal(retried.json.error, 'mfa_required')
})

test('a malformed JSON body never echoes its content back', async () => {
  const server = testServer()

  for (const path of ['/login', '/verify', '/atc']) {
    const response = await request(server, {
      method: 'POST',
      path,
      headers: { accept: 'application/json', 'content-type': 'application/json' },
      body: '{"profile_id":"user-7","password":"hunter2-and-a-half',
    })

    assert.equal(response.status, 400, path)
    assert.equal(response.json.error, 'invalid_request')
    assert.equal(response.json.detail, 'request body is not valid JSON')
    assert.doesNotMatch(JSON.stringify(response.json), /hunter2/, `${path} must not echo body content`)
  }
})

test('every profile-scoped route rejects a request without profile_id', async () => {
  const server = testServer()
  const cases = [
    { method: 'POST', path: '/login', body: new URLSearchParams({ username: 'a@b.test', password: 'p' }).toString(), headers: { 'content-type': 'application/x-www-form-urlencoded' } },
    { method: 'POST', path: '/logout' },
    { method: 'POST', path: '/refresh' },
    { method: 'POST', path: '/verify' },
    { method: 'POST', path: '/atc', body: JSON.stringify({ start_date: '2026-07-19', end_date: '2026-07-20', campsite_id: '1' }), headers: { 'content-type': 'application/json' } },
    { method: 'GET', path: '/screenshot?path=/' },
  ]

  for (const testCase of cases) {
    const response = await request(server, { headers: { accept: 'application/json' }, ...testCase })

    assert.equal(response.status, 400, `${testCase.path} must require profile_id`)
    assert.equal(response.json.error, 'profile_id_required')
  }
})

test('a malformed profile_id is rejected before any browser work', async () => {
  const response = await request(testServer(), {
    method: 'POST',
    path: '/verify?profile_id=../escape',
    headers: { accept: 'application/json' },
  })

  assert.equal(response.status, 400)
  assert.equal(response.json.error, 'invalid_profile_id')
})

test('each profile runs in its own browser context', async () => {
  const pool = testPool()
  const contexts = []
  const server = testServer({
    pool,
    testChromiumFn: async (_raw, options) => {
      contexts.push(await options.getContextFn())
      return { ok: true, loggedIn: true }
    },
  })

  await request(server, { method: 'POST', path: `/refresh?profile_id=${PROFILE_ID}` })
  await request(server, { method: 'POST', path: `/refresh?profile_id=${OTHER_PROFILE_ID}` })

  assert.equal(contexts.length, 2)
  assert.notEqual(contexts[0], contexts[1])
  assert.match(contexts[0].dir, new RegExp(`/profiles/${PROFILE_ID}$`))
  assert.match(contexts[1].dir, new RegExp(`/profiles/${OTHER_PROFILE_ID}$`))
})

test('a mutating route is refused while the same profile is busy, and never for another', async () => {
  const pool = testPool()
  const server = testServer({ pool, testChromiumFn: async () => ({ ok: true, loggedIn: true }) })
  pool.acquire(PROFILE_ID, 'login')

  const blocked = await request(server, { method: 'POST', path: `/refresh?profile_id=${PROFILE_ID}` })
  const allowed = await request(server, { method: 'POST', path: `/refresh?profile_id=${OTHER_PROFILE_ID}` })

  assert.equal(blocked.status, 409)
  assert.equal(blocked.json.error, 'profile_busy')
  assert.equal(allowed.status, 200)
})

test('POST /login opens an MFA challenge and a second call completes it', async () => {
  const pool = testPool()
  const resumedCodes = []
  const server = testServer({
    pool,
    credentialLoginFn: mfaChallengeLogin({ resumedCodes }),
  })

  const challenged = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, username: 'camper@example.test', password: 'secret' }),
  })

  assert.equal(challenged.status, 401)
  assert.equal(challenged.json.error, 'mfa_required')
  assert.match(challenged.json.challenge_id, /^[0-9a-f]+$/)
  assert.ok(Date.parse(challenged.json.expires_at) > Date.now())
  assert.equal(pool.isBusy(PROFILE_ID), true, 'the pending challenge holds the profile lock')

  const completed = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({
      profile_id: PROFILE_ID,
      challenge_id: challenged.json.challenge_id,
      mfa_code: '123456',
    }),
  })

  assert.equal(completed.status, 200)
  assert.equal(completed.json.ok, true)
  assert.deepEqual(resumedCodes, ['123456'])
  assert.equal(pool.isBusy(PROFILE_ID), false)
})

test('POST /logout runs in the requesting profile scope', async () => {
  let received = null
  const response = await request(testServer({
    logoutRecgovSessionFn: async (options) => {
      received = options
      return { ok: true, logged_in: false, clicked: true }
    },
  }), {
    method: 'POST',
    path: `/logout?profile_id=${PROFILE_ID}`,
    headers: { accept: 'application/json' },
  })

  assert.equal(response.status, 200)
  // Without this the logout writes the companion-wide session row instead of
  // the user's, and their own row keeps a stale refresh window.
  assert.equal(received.profileId, PROFILE_ID)
})

test('an abandoned MFA challenge closes its held page when it expires', async () => {
  let clock = Date.now()
  const pool = testPool({ now: () => clock })
  let abandoned = 0
  const server = testServer({
    pool,
    credentialLoginFn: async () => ({
      state: 'mfa_required',
      logged_in: false,
      diagnostic: { reason: 'mfa_required' },
      resume: async () => ({ state: 'ok', logged_in: true }),
      abandon: async () => { abandoned += 1 },
    }),
  })

  await beginLogin(server)
  clock += MFA_CHALLENGE_TTL_OVERSHOOT_MS
  await request(server, { path: `/health?profile_id=${PROFILE_ID}` })

  assert.equal(abandoned, 1)
  assert.equal(pool.isBusy(PROFILE_ID), false)
})

test('a failed login keeps the documented error code and carries the internal reason', async () => {
  const response = await request(testServer({
    credentialLoginFn: async () => ({
      state: 'failed',
      logged_in: false,
      reason: 'login_link_not_found',
      detail: 'the login control never appeared',
    }),
  }), {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, username: 'camper@example.test', password: 'secret' }),
  })

  assert.equal(response.status, 401)
  // The stable code recgovAuthenticationFailure has always produced for an
  // attempted login — never an internal blocker name.
  assert.equal(response.json.recgov_auth.error, 'recgov_login_failed')
  assert.equal(response.json.recgov_auth.reason, 'login_link_not_found')
  assert.equal(response.json.recgov_auth.detail, 'the login control never appeared')
  assert.ok(response.json.recgov_auth.corrective_action)
})

test('an expired MFA challenge is refused and the lock is released', async () => {
  let clock = Date.now()
  const pool = testPool({ now: () => clock })
  const server = testServer({
    pool,
    credentialLoginFn: mfaChallengeLogin(),
  })

  const challenged = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, username: 'camper@example.test', password: 'secret' }),
  })
  clock += MFA_CHALLENGE_TTL_OVERSHOOT_MS

  const expired = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({
      profile_id: PROFILE_ID,
      challenge_id: challenged.json.challenge_id,
      mfa_code: '123456',
    }),
  })

  assert.equal(expired.status, 400)
  assert.equal(expired.json.error, 'mfa_challenge_expired')
  assert.equal(pool.isBusy(PROFILE_ID), false)
})

test('a failed credential login backs the profile off before the next attempt', async () => {
  const pool = testPool()
  const server = testServer({
    pool,
    credentialLoginFn: async () => ({ state: 'failed', logged_in: false, reason: 'login_error' }),
  })
  const loginRequest = {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, username: 'camper@example.test', password: 'wrong' }),
  }

  const failed = await request(server, loginRequest)
  const backedOff = await request(server, loginRequest)

  assert.equal(failed.status, 401)
  assert.equal(backedOff.status, 429)
  assert.equal(backedOff.json.error, 'login_backoff')
  assert.ok(backedOff.json.retry_after_ms > 0)
})

test('POST /verify dry-runs the profile session and reports the cart read', async () => {
  const pool = testPool()
  let verifiedContext = null
  const response = await request(testServer({
    pool,
    verifyRecgovSessionFn: async ({ getContextFn }) => {
      verifiedContext = await getContextFn()
      return { ok: true, logged_in: true, cart_status: 200, cart_reservation_count: 0, checked_at: new Date().toISOString() }
    },
  }), {
    method: 'POST',
    path: '/verify',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID }),
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.equal(response.json.profile_id, PROFILE_ID)
  assert.equal(response.json.verify.cart_status, 200)
  assert.equal(response.json.recgov_auth.state, 'ok')
  assert.match(verifiedContext.dir, new RegExp(`/profiles/${PROFILE_ID}$`))
  assert.equal(pool.isBusy(PROFILE_ID), false)
})

test('POST /verify reports an unusable session as 401 without failing the request', async () => {
  const response = await request(testServer({
    verifyRecgovSessionFn: async () => ({
      ok: false,
      logged_in: false,
      error: 'recgov_not_authenticated',
      detail: 'no session',
      checked_at: new Date().toISOString(),
    }),
  }), {
    method: 'POST',
    path: `/verify?profile_id=${PROFILE_ID}`,
    headers: { accept: 'application/json' },
  })

  assert.equal(response.status, 401)
  assert.equal(response.json.ok, false)
  assert.equal(response.json.verify.error, 'recgov_not_authenticated')
})

test('GET /health reports per-profile auth without taking the profile lock', async () => {
  const pool = testPool()
  pool.setAuthStatus(PROFILE_ID, { state: 'ok', logged_in: true, operation: 'login' })
  pool.acquire(PROFILE_ID, 'login')
  const server = testServer({ pool })

  const scoped = await request(server, { path: `/health?profile_id=${PROFILE_ID}` })
  const other = await request(server, { path: `/health?profile_id=${OTHER_PROFILE_ID}` })

  assert.equal(scoped.status, 200)
  assert.equal(scoped.json.profile_id, PROFILE_ID)
  assert.equal(scoped.json.busy, true)
  assert.equal(scoped.json.recgov_auth.login_status, 'ok')
  assert.equal(scoped.json.recgov_auth.logged_in, true)
  assert.equal(scoped.json.pool.max_concurrent_browsers > 0, true)

  assert.equal(other.json.busy, false)
  assert.equal(other.json.recgov_auth.login_status, 'unchecked')
})

test('a profile operation never overwrites another profile or the companion-wide status', async () => {
  await runRecgovAuthCheck({
    operation: 'check',
    testChromiumFn: async () => ({ ok: true, loggedIn: true }),
    logger: () => {},
  })
  const pool = testPool()
  const server = testServer({ pool, testChromiumFn: async () => ({ ok: true, loggedIn: false }) })

  const refreshed = await request(server, { method: 'POST', path: `/refresh?profile_id=${PROFILE_ID}` })
  const scoped = await request(server, { path: `/health?profile_id=${PROFILE_ID}` })
  const other = await request(server, { path: `/health?profile_id=${OTHER_PROFILE_ID}` })
  const companionWide = await request(server, { path: '/health' })

  assert.equal(refreshed.status, 401)
  assert.equal(scoped.json.recgov_auth.login_status, 'failed')
  assert.equal(other.json.recgov_auth.login_status, 'unchecked')
  assert.equal(companionWide.json.recgov_auth.login_status, 'ok', 'an unscoped check still owns the global row')
})

test('GET /health without profile_id keeps answering the companion-wide check', async () => {
  const response = await request(testServer(), { path: '/health' })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.equal(response.json.profile_id, undefined)
  assert.ok(response.json.recgov_auth)
  assert.deepEqual(response.json.pool.keep_warm, [])
})

test('an unattended login that hits MFA opens no challenge and frees the profile', async () => {
  // The fire path has nobody to read a code. Opening a challenge there would
  // pin the profile's busy lock for the whole 5-minute TTL, wedging the owner's
  // own Test login, the keepalive refresh and any second ATC.
  const abandoned = []
  const pool = testPool()
  const server = testServer({
    pool,
    credentialLoginFn: async () => ({
      state: 'mfa_required',
      logged_in: false,
      diagnostic: { reason: 'mfa_required' },
      resume: async () => ({ state: 'ok', logged_in: true }),
      abandon: () => abandoned.push(true),
    }),
  })

  const response = await request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({
      profile_id: PROFILE_ID,
      username: 'camper@example.test',
      password: 'secret',
      unattended: true,
    }),
  })

  assert.equal(response.status, 401)
  assert.equal(response.json.error, 'mfa_required')
  assert.equal(response.json.challenge_id, undefined, 'an unattended caller can never complete a challenge')
  assert.equal(pool.isBusy(PROFILE_ID), false, 'the lock must be released immediately')
  assert.equal(pool.snapshot().profiles.find((p) => p.profile_id === PROFILE_ID)?.mfa_pending, false)
  assert.deepEqual(abandoned, [true], 'the held login page must be closed')
})

test('an interactive login still opens a challenge and holds the lock', async () => {
  const pool = testPool()
  const server = testServer({ pool, credentialLoginFn: mfaChallengeLogin() })

  const response = await beginLogin(server)

  assert.equal(response.status, 401)
  assert.equal(response.json.error, 'mfa_required')
  assert.ok(response.json.challenge_id, 'the interactive flow is unchanged')
  assert.equal(pool.isBusy(PROFILE_ID), true)
})

test('POST /keep-warm replaces the armed set wholesale', async () => {
  const pool = testPool()
  const server = testServer({ pool })

  const armed = await request(server, {
    method: 'POST',
    path: '/keep-warm',
    body: JSON.stringify({ profile_ids: [PROFILE_ID, OTHER_PROFILE_ID] }),
    headers: { 'content-type': 'application/json' },
  })
  const disarmed = await request(server, {
    method: 'POST',
    path: '/keep-warm',
    body: JSON.stringify({ profile_ids: [OTHER_PROFILE_ID] }),
    headers: { 'content-type': 'application/json' },
  })

  assert.equal(armed.status, 200)
  assert.deepEqual(armed.json.keep_warm.toSorted(), [PROFILE_ID, OTHER_PROFILE_ID].toSorted())
  // Replaced, not merged: a paused watch has to be able to disarm its profile.
  assert.deepEqual(disarmed.json.keep_warm, [OTHER_PROFILE_ID])
  assert.deepEqual(pool.snapshot().keep_warm, [OTHER_PROFILE_ID])
})

test('POST /keep-warm answers while a profile is mid-operation', async () => {
  const pool = testPool()
  const server = testServer({ pool })
  pool.acquire(PROFILE_ID, 'login')

  const response = await request(server, {
    method: 'POST',
    path: '/keep-warm',
    body: JSON.stringify({ profile_ids: [PROFILE_ID] }),
    headers: { 'content-type': 'application/json' },
  })

  assert.equal(response.status, 200, 'marking profiles must never queue behind browser work')
  assert.deepEqual(response.json.keep_warm, [PROFILE_ID])
})

test('POST /keep-warm refuses a body that is not an array of profile ids', async () => {
  const server = testServer()

  const response = await request(server, {
    method: 'POST',
    path: '/keep-warm',
    body: JSON.stringify({ profile_ids: PROFILE_ID }),
    headers: { 'content-type': 'application/json' },
  })

  assert.equal(response.status, 400)
  assert.equal(response.json.error, 'invalid_request')
})

test('POST /destroy erases the profile and answers ok', async () => {
  // "Remove my credentials" must be a true wipe: logout leaves the Chromium
  // directory and the saved cookie jar on disk, this is what deletes them.
  setSetting(recgovCookieSettingKey(PROFILE_ID), 'r1s-fingerprint=live-session')
  const server = testServer()

  const response = await request(server, {
    method: 'POST',
    path: '/destroy',
    body: JSON.stringify({ profile_id: PROFILE_ID }),
    headers: { 'content-type': 'application/json' },
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.equal(response.json.profile_id, PROFILE_ID)
  assert.equal(getSetting(recgovCookieSettingKey(PROFILE_ID)), null)
})

test('POST /destroy erases the traces a failed verify kept for that profile', async () => {
  // The trigger the audit named: a failed /verify keeps a trace, the trace's
  // network log holds the profile's live session cookies and bearer token, and
  // the user then removes their credentials. Deleting the jar while that
  // archive stays on disk erases the copy and keeps the original.
  const dir = await freshDiagnosticsDir()
  const pool = tracingPool(dir)
  const server = testServer({
    pool,
    verifyRecgovSessionFn: async () => ({ ok: false, logged_in: false, error: 'recgov_cart_unreachable' }),
  })
  const someoneElse = 'recgov-verify-2026-09-01T00-00-00-000Z-profile_user_8-recgov_cart_unreachable.trace.zip'
  await fsp.writeFile(path.join(dir, someoneElse), 'trace-bytes')

  const verified = await request(server, {
    method: 'POST',
    path: `/verify?profile_id=${PROFILE_ID}`,
    headers: { accept: 'application/json' },
  })
  assert.equal(verified.status, 401)
  const kept = (await fsp.readdir(dir)).filter((name) => name !== someoneElse)
  assert.equal(kept.length, 1, 'a failed verify keeps its trace')
  assert.match(kept[0], /-profile_user_7-recgov_cart_unreachable\.trace\.zip$/)

  const destroyed = await request(server, {
    method: 'POST',
    path: '/destroy',
    body: JSON.stringify({ profile_id: PROFILE_ID }),
    headers: { 'content-type': 'application/json' },
  })

  assert.equal(destroyed.status, 200)
  assert.equal(destroyed.json.diagnostics_removed, 1)
  assert.deepEqual(await fsp.readdir(dir), [someoneElse], "another profile's diagnostics stay")
})

test('POST /destroy queues behind live work on the same profile', async () => {
  // It deletes the user-data directory out from under a browser, so it must
  // never race a login, verify or ATC mid-flight.
  const pool = testPool()
  const server = testServer({ pool })
  pool.acquire(PROFILE_ID, 'login')

  const response = await request(server, {
    method: 'POST',
    path: '/destroy',
    body: JSON.stringify({ profile_id: PROFILE_ID }),
    headers: { 'content-type': 'application/json' },
  })

  assert.equal(response.status, 409)
  assert.equal(response.json.error, 'profile_busy')
})

test('POST /destroy refuses a profile id that could escape the profiles root', async () => {
  const server = testServer()

  for (const bad of ['../../etc', '/etc/passwd', '.']) {
    const response = await request(server, {
      method: 'POST',
      path: '/destroy',
      body: JSON.stringify({ profile_id: bad }),
      headers: { 'content-type': 'application/json' },
    })
    assert.equal(response.status, 400, `${bad} must be refused`)
    assert.equal(response.json.error, 'invalid_profile_id')
  }
})

test('POST /destroy requires a profile id', async () => {
  const server = testServer()

  const response = await request(server, {
    method: 'POST',
    path: '/destroy',
    body: JSON.stringify({}),
    headers: { 'content-type': 'application/json' },
  })

  assert.equal(response.status, 400)
  assert.equal(response.json.error, 'profile_id_required')
})

test('every data route rejects a request without the shared-secret header', async () => {
  const server = testServer()

  for (const path of ['/docs', '/openapi.json', '/health', '/screenshot', '/screenshot/diagnostics/x.png']) {
    const response = await request(server, { path, token: null })

    assert.equal(response.status, 401, `${path} must require the companion token`)
    assert.equal(response.json.error, 'unauthorized')
  }

  for (const path of ['/atc', '/destroy']) {
    const posted = await request(server, { method: 'POST', path, token: null })
    assert.equal(posted.status, 401, `${path} must require the companion token`)
  }
})

test('a failed login writes no trace by default, but keeps its screenshot', async () => {
  // A login trace records fill params and DOM snapshots, so it contains the
  // typed password. Off unless the operator asks for it; the screenshot
  // diagnostic, which holds no password, is unaffected.
  delete process.env.COMPANION_TRACE_LOGIN
  const dir = await freshDiagnosticsDir()
  const server = testServer({
    pool: tracingPool(dir),
    credentialLoginFn: async () => ({
      state: 'failed',
      logged_in: false,
      reason: 'captcha_required',
      diagnostic: { reason: 'captcha_required', screenshot_url: '/screenshot/diagnostics/shot.png' },
    }),
  })

  const response = await beginLogin(server)

  assert.equal(response.status, 401)
  assert.equal(response.json.diagnostics.trace, undefined, 'no trace without the opt-in')
  assert.equal(response.json.diagnostics.screenshot_url, '/screenshot/diagnostics/shot.png')
  assert.deepEqual(await fsp.readdir(dir), [], 'nothing containing a password may be written')
})

test('a failed login keeps a trace when the operator opts in', async () => {
  process.env.COMPANION_TRACE_LOGIN = 'true'
  try {
    const dir = await freshDiagnosticsDir()
    const server = testServer({
      pool: tracingPool(dir),
      credentialLoginFn: async () => ({
        state: 'failed',
        logged_in: false,
        reason: 'captcha_required',
        diagnostic: { reason: 'captcha_required', screenshot_url: '/screenshot/diagnostics/shot.png' },
      }),
    })

    const response = await beginLogin(server)

    assert.match(response.json.diagnostics.trace, /recgov-login-.*-captcha_required\.trace\.zip$/)
    assert.equal(response.json.diagnostics.screenshot_url, '/screenshot/diagnostics/shot.png')
    const written = await fsp.readdir(dir)
    assert.equal(written.length, 1)
    assert.match(written[0], /\.trace\.zip$/)
  } finally {
    delete process.env.COMPANION_TRACE_LOGIN
  }
})

test('a successful opted-in login leaves zero trace files behind', async () => {
  process.env.COMPANION_TRACE_LOGIN = 'true'
  const dir = await freshDiagnosticsDir()
  const pool = tracingPool(dir)
  const server = testServer({
    pool,
    credentialLoginFn: async () => ({ state: 'ok', logged_in: true }),
  })

  const response = await beginLogin(server)

  assert.equal(response.status, 200)
  assert.equal(response.json.diagnostics, null, 'nothing was kept, so nothing is named')
  assert.deepEqual(await fsp.readdir(dir), [], 'a success must write no trace at all')
  delete process.env.COMPANION_TRACE_LOGIN
})

test('a successful login stores the profile cookie jar so it outlives the browser', async () => {
  // Rec.gov's session cookies are session-scoped in Chromium: without this
  // write, a container restart loses the login the user just performed. The
  // save is best-effort by design, so only asserting on the store catches a
  // persist path that silently stopped running.
  setSetting(recgovCookieSettingKey(PROFILE_ID), '')
  const server = testServer({
    pool: cookieJarPool([{ name: 'r1s-fingerprint', value: 'fp-abc' }]),
    credentialLoginFn: async () => ({ state: 'ok', logged_in: true }),
  })

  const response = await beginLogin(server)

  assert.equal(response.status, 200)
  assert.equal(getSetting(recgovCookieSettingKey(PROFILE_ID)), 'r1s-fingerprint=fp-abc')
})

test('a failed login leaves the stored jar alone', async () => {
  // The failed attempt's context holds nothing worth keeping, and overwriting
  // a good jar with it destroys the session we are trying to preserve.
  setSetting(recgovCookieSettingKey(PROFILE_ID), 'r1s-fingerprint=keep-me')
  const server = testServer({
    pool: cookieJarPool([{ name: 'r1s-fingerprint', value: 'fp-new' }]),
    credentialLoginFn: async () => ({ state: 'failed', logged_in: false, reason: 'captcha_required' }),
  })

  const response = await beginLogin(server)

  assert.equal(response.status, 401)
  assert.equal(getSetting(recgovCookieSettingKey(PROFILE_ID)), 'r1s-fingerprint=keep-me')
})

test('a listed trace can actually be downloaded', async () => {
  // The listing handed out .trace.zip urls that the download route rejected as
  // invalid_diagnostic_path, so every one of them was a dead link.
  const dir = await freshDiagnosticsDir()
  const file = 'recgov-login-2026-09-01T00-00-00-000Z-captcha_required.trace.zip'
  await fsp.writeFile(path.join(dir, file), 'trace-bytes')
  const server = testServer()

  const listed = await request(server, { path: '/screenshot/diagnostics' })
  const artifact = listed.json.artifacts.find((a) => a.file === file)
  assert.ok(artifact, 'the trace must be listed')

  const downloaded = await request(server, { path: artifact.url })

  assert.equal(downloaded.status, 200)
  assert.equal(downloaded.headers['content-type'], 'application/zip')
})

test('a listed screenshot still downloads as a png', async () => {
  const dir = await freshDiagnosticsDir()
  const file = 'recgov-verify-2026-09-01T00-00-00-000Z-not_authenticated.png'
  await fsp.writeFile(path.join(dir, file), 'png-bytes')

  const downloaded = await request(testServer(), { path: `/screenshot/diagnostics/${file}` })

  assert.equal(downloaded.status, 200)
  assert.equal(downloaded.headers['content-type'], 'image/png')
})

test('the download route still refuses traversal and anything that is not an artifact', async () => {
  const server = testServer()

  for (const target of [
    '/screenshot/diagnostics/..%2F..%2Fetc%2Fpasswd',
    '/screenshot/diagnostics/nested%2Fpath.png',
    '/screenshot/diagnostics/companion.env',
    '/screenshot/diagnostics/notes.txt',
  ]) {
    const response = await request(server, { path: target })
    assert.equal(response.status, 400, `${target} must be refused`)
    assert.equal(response.json.error, 'invalid_diagnostic_path')
  }
})

test('the diagnostics listing is token-gated and reports the prune bound', async () => {
  const server = testServer()

  const refused = await request(server, { path: '/screenshot/diagnostics', token: null })
  assert.equal(refused.status, 401, 'diagnostics name real failures on real profiles')

  const listed = await request(server, { path: '/screenshot/diagnostics' })
  assert.equal(listed.status, 200)
  assert.equal(listed.json.ok, true)
  assert.equal(Array.isArray(listed.json.artifacts), true)
  assert.equal(listed.json.max_artifacts > 0, true)
})

test('the operator shell loads without a token, because it carries nothing', async () => {
  // A browser cannot set a header on a navigation, and the compose port mapping
  // means a request from the host is not in-container loopback — so the page was
  // unreachable in the one situation it exists for. It is static markup; the
  // controls on it send the token from their own field.
  const response = await request(testServer(), { path: '/', token: null })

  assert.equal(response.status, 200)
  assert.match(response.headers['content-type'], /text\/html/)
})

test('the un-gated shell does not un-gate the data behind it', async () => {
  const server = testServer()

  for (const path of ['/screenshot?profile_id=user-7', '/health?profile_id=user-7']) {
    const response = await request(server, { path, token: null })
    assert.equal(response.status, 401, `${path} must still require the companion token`)
  }
})

test('an unmatched route is refused before it reports whether it exists', async () => {
  const response = await request(testServer(), { path: '/not-a-route', token: null })

  assert.equal(response.status, 401)
  assert.equal(response.json.error, 'unauthorized')
})

test('GET /health answers the compose healthcheck from localhost without a token', async () => {
  const response = await request(testServer(), {
    path: '/health',
    token: null,
    remoteAddress: LOOPBACK_ADDRESS,
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
})

test('an unconfigured companion token fails closed off localhost', async () => {
  const response = await request(testServer({ apiToken: '' }), { path: '/health', token: null })

  assert.equal(response.status, 503)
  assert.equal(response.json.error, 'companion_auth_unconfigured')
})

function contractOperations (routes) {
  return routes
    .map(({ method, path }) => `${method} ${path}`)
    .sort()
}

function openApiOperations (spec) {
  return Object
    .entries(spec.paths)
    .flatMap(([path, operations]) =>
      Object
        .keys(operations)
        .map((method) => `${method.toUpperCase()} ${path}`),
    )
    .sort()
}

function logCapture () {
  const lines = []
  return {
    write: (...items) => {
      lines.push(items.join(' '))
    },
    text: () => lines.join('\n'),
  }
}

function testServer (overrides = {}) {
  return createCompanionServer({
    apiToken: TEST_API_TOKEN,
    pool: overrides.pool || testPool(),
    ...overrides,
  })
}

// A credential login that stops at the 2FA prompt and hands back a resume
// closure, the way runRecgovProfileLogin does with the page held open.
function mfaChallengeLogin ({ acceptCode = '123456', resumedCodes = [] } = {}) {
  return async () => ({
    state: 'mfa_required',
    logged_in: false,
    diagnostic: { reason: 'mfa_required' },
    resume: async (code) => {
      resumedCodes.push(code)
      if (code === acceptCode) return { state: 'ok', logged_in: true }
      return { state: 'failed', logged_in: false, reason: 'mfa_invalid', detail: 'code rejected' }
    },
  })
}

async function beginLogin (server) {
  return request(server, {
    method: 'POST',
    path: '/login',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ profile_id: PROFILE_ID, username: 'camper@example.test', password: 'secret' }),
  })
}

/** A pool whose contexts record tracing into `dir`, like a real browser would. */
function tracingPool (dir) {
  return testPool({
    launchContextFn: async () => ({
      pages: async () => [],
      close: async () => {},
      once: () => {},
      tracing: {
        start: async () => {},
        stop: async (options) => {
          if (options?.path) await fsp.writeFile(options.path, 'trace-bytes')
        },
      },
    }),
  })
}

/** A pool whose contexts answer a real cookie jar, the way Playwright's do. */
function cookieJarPool (cookies) {
  return testPool({
    launchContextFn: async (dir) => ({
      dir,
      pages: async () => [],
      close: async () => {},
      once: () => {},
      cookies: async () => cookies,
    }),
  })
}

async function freshDiagnosticsDir () {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'companion-server-diag-'))
  process.env.RECGOV_DIAGNOSTIC_DIR = dir
  return dir
}

function testPool (overrides = {}) {
  return createProfilePool({
    rootDir: '/tmp/companion-server-test-profiles',
    launchContextFn: async (dir) => ({ dir, pages: async () => [], close: async () => {}, once: () => {} }),
    logger: () => {},
    ...overrides,
  })
}

async function request (server, {
  method = 'GET',
  path = '/',
  headers = {},
  body = null,
  token = TEST_API_TOKEN,
  remoteAddress = CONTAINER_ADDRESS,
} = {}) {
  const handler = server.listeners('request')[0]
  const req = Readable.from(body ? [Buffer.from(body)] : [])
  req.method = method
  req.url = path
  req.headers = token === null ? { ...headers } : { [COMPANION_API_TOKEN_HEADER]: token, ...headers }
  req.socket = { remoteAddress }

  return new Promise((resolve, reject) => {
    const chunks = []
    const res = {
      status: null,
      headers: {},
      writeHead (status, responseHeaders) {
        res.status = status
        res.headers = responseHeaders || {}
      },
      end (chunk = '') {
        if (chunk) chunks.push(Buffer.from(chunk))
        const body = Buffer.concat(chunks)
        const text = body.toString('utf8')
        const contentType = res.headers['content-type'] || ''
        resolve({
          status: res.status,
          text,
          body,
          headers: res.headers,
          json: text && contentType.includes('application/json') ? JSON.parse(text) : null,
        })
      },
    }
    Promise.resolve(handler(req, res)).catch(reject)
  })
}

function fakeScreenshotPage (image) {
  const page = {
    gotos: [],
    waits: [],
    screenshotOptions: null,
    closed: false,
    goto: async (url, options) => {
      page.gotos.push({ url, options })
    },
    waitForTimeout: async (ms) => {
      page.waits.push(ms)
    },
    screenshot: async (options) => {
      page.screenshotOptions = options
      return image
    },
    setViewportSize: async (size) => {
      page.viewportSize = size
    },
    close: async () => {
      page.closed = true
    },
  }
  return page
}
