import test from 'node:test'
import assert from 'node:assert/strict'
import { hasStoredSession } from '../src/browser.js'
import { setSetting } from '../src/store.js'

test('a profile with a persisted cookie jar reports a stored session', () => {
  setSetting('recgov_cookies:health-probe-1', 'sessionid=abc')

  assert.equal(hasStoredSession('health-probe-1'), true)
})

test('a profile that has never been signed in reports none', () => {
  assert.equal(hasStoredSession('health-probe-never'), false)
})

test('an empty stored jar is not a stored session', () => {
  setSetting('recgov_cookies:health-probe-empty', '')

  assert.equal(hasStoredSession('health-probe-empty'), false)
})
