import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Readable } from 'node:stream'
import {
  HANDLED_OPERATION_IDS,
  createCompanionServer,
  getRecgovAuthStatus,
  getRecgovHealthStatus,
  runStartupAuthCheck,
} from '../src/server.js'
import { COMPANION_API_ROUTES } from '../src/apiContract.js'
import { COMPANION_API_TOKEN_HEADER } from '../src/server/apiToken.js'

const TEST_API_TOKEN = 'test-companion-token'
const CONTAINER_ADDRESS = '172.18.0.4'
const LOOPBACK_ADDRESS = '127.0.0.1'

test('runStartupAuthCheck records logged-in status', async () => {
  const log = logCapture()

  const status = await runStartupAuthCheck({
    testChromiumFn: async () => ({ ok: true, loggedIn: true }),
    logger: log.write,
  })

  assert.equal(status.state, 'ok')
  assert.equal(status.logged_in, true)
  assert.equal(getRecgovAuthStatus(), status)
  assert.match(log.text(), /recgov auth startup check start/)
  assert.match(log.text(), /recgov auth startup check ok/)
})

test('getRecgovHealthStatus exposes login status and refresh metadata', async () => {
  const diagnostic = {
    reason: 'login_success',
    screenshot_url: '/screenshot/diagnostics/recgov-login-success.png',
  }
  await runStartupAuthCheck({
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

test('runStartupAuthCheck records actionable auth failure status', async () => {
  const log = logCapture()
  const diagnostic = {
    reason: 'captcha_required',
    screenshot_url: '/screenshot/diagnostics/recgov-login-captcha.png',
  }

  const status = await runStartupAuthCheck({
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
  assert.match(log.text(), /recgov auth startup check fail/)
  assert.match(log.text(), /error=recgov_not_authenticated/)
  assert.match(log.text(), /diagnostic_reason=captcha_required/)
  assert.match(log.text(), /screenshot=\/screenshot\/diagnostics\/recgov-login-captcha\.png/)
})

test('runStartupAuthCheck records exceptions as startup auth failures', async () => {
  const log = logCapture()

  const status = await runStartupAuthCheck({
    testChromiumFn: async () => { throw new Error('browser launch failed') },
    logger: log.write,
  })

  assert.equal(status.state, 'failed')
  assert.equal(status.logged_in, false)
  assert.equal(status.error, 'recgov_auth_check_exception')
  assert.equal(status.detail, 'browser launch failed')
  assert.match(status.corrective_action, /recgov-login/)
  assert.match(log.text(), /recgov auth startup check exception/)
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
  assert.match(response.text, /src="\/screenshot\?path=\/"/)
  assert.match(response.text, /togglePanel\(loginPanel, loginToggle\)/)
  assert.match(response.text, /JSON\.stringify\(Object\.fromEntries\(new FormData\(atcForm\)\)\)/)
  assert.match(response.text, /fetch\(url/)
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

  const loginSchema = response.json.components.schemas.LoginRequest
  assert.deepEqual(Object.keys(loginSchema.properties), ['username', 'password', 'mfa_code'])
  assert.deepEqual(loginSchema.required, ['username', 'password'])

  const authSchema = response.json.components.schemas.RecgovAuthStatus
  assert.equal(authSchema.properties.last_login_diagnostic, undefined)
  assert.equal(authSchema.properties.diagnostic, undefined)
  assert.ok(response.json.components.schemas.AuthResponse.properties.diagnostics)
  const diagnosticSchema = response.json.components.schemas.LoginDiagnostic
  assert.equal(diagnosticSchema.properties.screenshot_path, undefined)
  const atcSchema = response.json.components.schemas.AtcRequest
  assert.deepEqual(atcSchema.required, ['start_date', 'end_date', 'campsite_id'])
  assert.deepEqual(Object.keys(atcSchema.properties), ['start_date', 'end_date', 'campsite_id'])
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

test('POST /login passes request-scoped credentials to the auth check', async () => {
  let authOptions = null
  const response = await request(testServer({
    testChromiumFn: async (_rawCookieInput, options) => {
      authOptions = options
      return {
        ok: true,
        loggedIn: true,
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
      username: 'camper@example.test',
      password: 'secret',
      mfa_code: '123456',
    }).toString(),
  })

  assert.equal(response.status, 200)
  assert.equal(response.json.ok, true)
  assert.deepEqual(authOptions.credentials, {
    email: 'camper@example.test',
    password: 'secret',
    mfaCode: '123456',
  })
  assert.equal(authOptions.allowManualLogin, false)
  assert.equal(response.json.recgov_auth.diagnostic, undefined)
  assert.deepEqual(response.json.diagnostics, {
    reason: 'login_success',
    screenshot_url: '/screenshot/diagnostics/recgov-login-success.png',
  })
})

test('POST /login HTML response renders a failed login diagnostic screenshot', async () => {
  const response = await request(testServer({
    testChromiumFn: async () => ({
      ok: true,
      loggedIn: false,
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
    path: '/refresh',
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
    path: '/logout',
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
    /^recgov atc start start_date=2026-07-19 end_date=2026-07-20 campsite=102524 booking_url="https:\/\/www\.recreation\.gov\/camping\/campsites\/102524\?startDate=2026-07-19&endDate=2026-07-20"/,
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
    path: '/screenshot?path=/camping/campgrounds/232447&startDate=2026-07-19',
  })

  assert.equal(response.status, 200)
  assert.equal(response.headers['content-type'], 'image/png')
  assert.deepEqual(response.body, image)
})

test('GET /screenshot rejects non-Recreation.gov targets', async () => {
  const response = await request(testServer(), {
    path: '/screenshot?url=https://example.com/',
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

test('every route rejects a request without the shared-secret header', async () => {
  const server = testServer()

  for (const path of ['/', '/docs', '/openapi.json', '/health', '/screenshot', '/screenshot/diagnostics/x.png']) {
    const response = await request(server, { path, token: null })

    assert.equal(response.status, 401, `${path} must require the companion token`)
    assert.equal(response.json.error, 'unauthorized')
  }

  const posted = await request(server, { method: 'POST', path: '/atc', token: null })

  assert.equal(posted.status, 401)
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
  return createCompanionServer({ apiToken: TEST_API_TOKEN, ...overrides })
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
