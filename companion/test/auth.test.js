import { test } from 'node:test'
import assert from 'node:assert/strict'
import { extractCookiesFromInput } from '../src/auth.js'

// As of RFC 0001 / PR 6, companion no longer holds JWT/refresh logic — that
// lives in the backend (`RecgovAuth.kt` + `TokenManager.kt`). What remains
// here is cookie parsing for the local Akamai-fingerprint workaround.

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

test('extractCookiesFromInput returns empty string for empty input', () => {
  assert.equal(extractCookiesFromInput(''), '')
  assert.equal(extractCookiesFromInput(null), '')
  assert.equal(extractCookiesFromInput(undefined), '')
})
