import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  COMPANION_API_TOKEN_HEADER,
  ERROR_COMPANION_AUTH_UNCONFIGURED,
  ERROR_UNAUTHORIZED,
  authorizeCompanionRequest,
  companionApiToken,
  isLoopbackRequest,
} from '../src/server/apiToken.js'

const TOKEN = 'shared-secret-token'
const HEALTH_ROUTE = { operationId: 'getHealth', method: 'GET', path: '/health' }
const DOCS_ROUTE = { operationId: 'getSwaggerDocs', method: 'GET', path: '/docs' }
const ATC_ROUTE = { operationId: 'postAtc', method: 'POST', path: '/atc' }

test('companionApiToken reads the env var, then the mounted secret file', () => {
  assert.equal(companionApiToken({ COMPANION_API_TOKEN: ` ${TOKEN} ` }), TOKEN)
  assert.equal(companionApiToken({}), '')

  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'companion-token-')), 'companion_api_token')
  fs.writeFileSync(file, `${TOKEN}\n`)

  assert.equal(companionApiToken({ COMPANION_API_TOKEN_FILE: file }), TOKEN)
  assert.equal(companionApiToken({ COMPANION_API_TOKEN: 'env-wins', COMPANION_API_TOKEN_FILE: file }), 'env-wins')
})

test('isLoopbackRequest recognises the compose healthcheck and rejects the network', () => {
  assert.equal(isLoopbackRequest(fakeRequest({ remoteAddress: '127.0.0.1' })), true)
  assert.equal(isLoopbackRequest(fakeRequest({ remoteAddress: '::1' })), true)
  assert.equal(isLoopbackRequest(fakeRequest({ remoteAddress: '::ffff:127.0.0.1' })), true)
  assert.equal(isLoopbackRequest(fakeRequest({ remoteAddress: '172.18.0.4' })), false)
  assert.equal(isLoopbackRequest(fakeRequest({ remoteAddress: undefined })), false)
})

test('every route requires the shared-secret header', () => {
  for (const route of [DOCS_ROUTE, ATC_ROUTE, { operationId: 'getOperatorPage', path: '/' }, { operationId: 'getScreenshot', path: '/screenshot' }]) {
    const rejection = authorize({ route, headers: {} })
    assert.equal(rejection.status, 401, `${route.path} should require the token`)
    assert.equal(rejection.body.error, ERROR_UNAUTHORIZED)
  }
})

test('a matching header is accepted and a wrong one is not', () => {
  assert.equal(authorize({ route: ATC_ROUTE, headers: { [COMPANION_API_TOKEN_HEADER]: TOKEN } }), null)
  assert.equal(authorize({ route: ATC_ROUTE, headers: { [COMPANION_API_TOKEN_HEADER]: 'nope' } }).status, 401)
  assert.equal(authorize({ route: ATC_ROUTE, headers: { [COMPANION_API_TOKEN_HEADER]: `${TOKEN}-longer` } }).status, 401)
})

test('health is reachable without the token from localhost only', () => {
  assert.equal(authorize({ route: HEALTH_ROUTE, headers: {}, remoteAddress: '127.0.0.1' }), null)
  assert.equal(authorize({ route: HEALTH_ROUTE, headers: {}, remoteAddress: '172.18.0.4' }).status, 401)
  assert.equal(
    authorize({ route: HEALTH_ROUTE, headers: { [COMPANION_API_TOKEN_HEADER]: TOKEN }, remoteAddress: '172.18.0.4' }),
    null,
  )
})

test('an unconfigured token fails closed everywhere except the localhost healthcheck', () => {
  const rejection = authorize({ route: ATC_ROUTE, token: '', headers: { [COMPANION_API_TOKEN_HEADER]: TOKEN } })

  assert.equal(rejection.status, 503)
  assert.equal(rejection.body.error, ERROR_COMPANION_AUTH_UNCONFIGURED)
  assert.equal(authorize({ route: HEALTH_ROUTE, token: '', headers: {}, remoteAddress: '127.0.0.1' }), null)
})

function authorize ({ route, headers = {}, token = TOKEN, remoteAddress = '172.18.0.4' }) {
  return authorizeCompanionRequest({ req: fakeRequest({ headers, remoteAddress }), route, token })
}

function fakeRequest ({ headers = {}, remoteAddress = '172.18.0.4' } = {}) {
  return { headers, socket: { remoteAddress } }
}
