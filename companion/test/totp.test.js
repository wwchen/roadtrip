import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateTotp, parseTotpConfig } from '../src/totp.js'

const RFC_TOTP_SECRET = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'

test('generateTotp returns the current RFC 6238 code', () => {
  assert.equal(generateTotp(RFC_TOTP_SECRET, { nowMs: 59_000 }), '287082')
})

test('generateTotp supports otpauth URL metadata', () => {
  const uri = `otpauth://totp/Recreation.gov:test@example.com?secret=${RFC_TOTP_SECRET}&digits=8&period=30`

  assert.equal(generateTotp(uri, { nowMs: 59_000 }), '94287082')
})

test('parseTotpConfig rejects invalid secrets', () => {
  assert.throws(() => parseTotpConfig('not base32!'), /Base32/)
})
