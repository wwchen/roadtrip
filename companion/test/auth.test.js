import { test } from 'node:test'
import assert from 'node:assert/strict'
import { jwtExpiry, jwtFingerprint, extractCookiesFromInput, extractBearerFromInput, extractRefreshCredsFromInput, buildRecaccountFromToken } from '../src/auth.js'

// Helper: synth a JWT with a payload (signature ignored — we never verify).
function jwt (payload) {
  const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url')
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url')
  return `${header}.${body}.sig`
}

test('jwtExpiry decodes exp claim', () => {
  const t = jwt({ exp: 1900000000 })
  const got = jwtExpiry(t)
  assert.equal(got.toISOString(), new Date(1900000000 * 1000).toISOString())
})

test('jwtExpiry returns null for malformed token', () => {
  assert.equal(jwtExpiry('not-a-jwt'), null)
})

test('jwtFingerprint pulls fingerprint claim', () => {
  assert.equal(jwtFingerprint(jwt({ fingerprint: 'abc123' })), 'abc123')
  assert.equal(jwtFingerprint(jwt({})), '')
})

test('extractCookiesFromInput passes raw cookie strings through', () => {
  assert.equal(extractCookiesFromInput('foo=1; bar=2'), 'foo=1; bar=2')
})

test('extractCookiesFromInput parses curl -b form', () => {
  const cmd = `curl 'https://example.com' -b 'foo=1; bar=2'`
  assert.equal(extractCookiesFromInput(cmd), 'foo=1; bar=2')
})

test('extractCookiesFromInput parses curl -H Cookie form', () => {
  const cmd = `curl 'https://example.com' -H 'Cookie: foo=1; bar=2'`
  assert.equal(extractCookiesFromInput(cmd), 'foo=1; bar=2')
})

test('extractBearerFromInput parses Authorization header', () => {
  const cmd = `curl -H 'Authorization: Bearer eyJabc.def.ghi'`
  assert.equal(extractBearerFromInput(cmd), 'eyJabc.def.ghi')
})

test('extractRefreshCredsFromInput parses bare JSON localStorage paste', () => {
  const ls = JSON.stringify({ account: { account_id: 'A1' }, refresh_id: 'R1', access_token: 'x' })
  assert.deepEqual(extractRefreshCredsFromInput(ls), { account_id: 'A1', refresh_id: 'R1' })
})

test('buildRecaccountFromToken synthesizes a valid recaccount', () => {
  const t = jwt({ exp: 1900000000, acct: { account_id: 'A1', email: 'me@x.com', first_name: 'Me', last_name: 'X' } })
  const ra = buildRecaccountFromToken(t)
  assert.equal(ra.access_token, t)
  assert.equal(ra.account.account_id, 'A1')
  assert.equal(ra.account.email, 'me@x.com')
  assert.equal(ra.is_guest, false)
  assert.ok(ra.expiration.startsWith('20'))
})
