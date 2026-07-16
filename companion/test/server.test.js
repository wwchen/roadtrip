import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
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

  const status = await runStartupAuthCheck({
    testChromiumFn: async () => ({ ok: true, loggedIn: false }),
    authFailureFn: () => ({
      error: 'recgov_not_authenticated',
      detail: 'No Recreation.gov browser session is available.',
      corrective_action: 'Run make recgov-login.',
      auth: {
        headless: true,
        credentials_reason: 'credentials_not_configured',
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
    credentials_reason: 'credentials_not_configured',
  })
  assert.match(log.text(), /recgov auth startup check fail/)
  assert.match(log.text(), /error=recgov_not_authenticated/)
  assert.match(log.text(), /credentials=credentials_not_configured/)
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

function logCapture () {
  const lines = []
  return {
    write: (...items) => {
      lines.push(items.join(' '))
    },
    text: () => lines.join('\n'),
  }
}
