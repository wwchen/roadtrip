import { test } from 'node:test'
import assert from 'node:assert/strict'

import { dispatchCompanionToken, parseDotenv } from '../src/env.js'

test('parseDotenv ignores comments and strips simple quotes', () => {
  assert.deepEqual(parseDotenv(`
    # local secrets
    DISPATCH_COMPANION_TOKEN="file-token"
    COMPANION_DISPATCH_TOKEN='legacy-token'
    EMPTY=
  `), {
    DISPATCH_COMPANION_TOKEN: 'file-token',
    COMPANION_DISPATCH_TOKEN: 'legacy-token',
    EMPTY: '',
  })
})

test('dispatchCompanionToken prefers explicit environment over repo dotenv', () => {
  assert.equal(
    dispatchCompanionToken(
      { DISPATCH_COMPANION_TOKEN: 'env-token' },
      { DISPATCH_COMPANION_TOKEN: 'file-token' },
    ),
    'env-token',
  )
})

test('dispatchCompanionToken falls back to repo dotenv values', () => {
  assert.equal(
    dispatchCompanionToken({}, { DISPATCH_COMPANION_TOKEN: 'file-token' }),
    'file-token',
  )
})
