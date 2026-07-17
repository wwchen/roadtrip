import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Readable } from 'node:stream'
import {
  createCompanionServer,
  getRecgovAuthStatus,
  getRecgovHealthStatus,
  runStartupAuthCheck,
} from '../src/server.js'

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
  await runStartupAuthCheck({
    testChromiumFn: async () => ({ ok: true, loggedIn: true }),
    logger: () => {},
  })

  const health = getRecgovHealthStatus()

  assert.equal(health.login_status, 'ok')
  assert.equal(health.logged_in, true)
  assert.equal('last_refresh_at' in health, true)
  assert.equal('last_refresh_expires_at' in health, true)
})

test('runStartupAuthCheck records actionable auth failure status', async () => {
  const log = logCapture()
  const diagnostic = {
    reason: 'captcha_required',
    screenshot_url: '/diagnostics/recgov-login-captcha.png',
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
  assert.match(log.text(), /screenshot=\/diagnostics\/recgov-login-captcha\.png/)
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

test('GET /login returns a simple operator login form', async () => {
  const response = await request(createCompanionServer(), {
    path: '/login',
    headers: { accept: 'text/html' },
  })

  assert.equal(response.status, 200)
  assert.match(response.text, /<form id="login-form" method="post" action="\/login">/)
  assert.match(response.text, /name="username"/)
  assert.match(response.text, /name="password"/)
  assert.match(response.text, /name="mfa_code"/)
  assert.match(response.text, /id="loading"/)
  assert.match(response.text, /id="json-output"/)
  assert.match(response.text, /id="refresh-session"/)
  assert.match(response.text, /id="health-json"/)
  assert.match(response.text, /fetch\(url/)
  assert.doesNotMatch(response.text, /action="\/refresh"/)
  assert.doesNotMatch(response.text, /RECGOV_EMAIL|RECGOV_PASSWORD|RECGOV_MFA_CODE|RECGOV_OTP/)
})

test('POST /login passes request-scoped credentials to the auth check', async () => {
  let authOptions = null
  const response = await request(createCompanionServer({
    testChromiumFn: async (_rawCookieInput, options) => {
      authOptions = options
      return { ok: true, loggedIn: true }
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
})

test('POST /login HTML response renders a failed login diagnostic screenshot', async () => {
  const response = await request(createCompanionServer({
    testChromiumFn: async () => ({
      ok: true,
      loggedIn: false,
      diagnostic: {
        reason: 'login_error',
        screenshot_url: '/diagnostics/recgov-login-error.png',
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
  assert.match(response.text, /src="\/diagnostics\/recgov-login-error\.png"/)
})

test('POST /refresh force-refreshes the stored browser session without credentials', async () => {
  let authOptions = null
  const response = await request(createCompanionServer({
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
})

function logCapture () {
  const lines = []
  return {
    write: (...items) => {
      lines.push(items.join(' '))
    },
    text: () => lines.join('\n'),
  }
}

async function request (server, { method = 'GET', path = '/', headers = {}, body = null } = {}) {
  const handler = server.listeners('request')[0]
  const req = Readable.from(body ? [Buffer.from(body)] : [])
  req.method = method
  req.url = path
  req.headers = headers

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
        const text = Buffer.concat(chunks).toString('utf8')
        const contentType = res.headers['content-type'] || ''
        resolve({
          status: res.status,
          text,
          json: text && contentType.includes('application/json') ? JSON.parse(text) : null,
        })
      },
    }
    Promise.resolve(handler(req, res)).catch(reject)
  })
}
