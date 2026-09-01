// The ATC path must never wait for a human to type credentials.
//
// `resolveRecaccount` falls through to the manual-login wait whenever the
// caller does not say otherwise and the browser is headed. /verify and
// /screenshot pass `allowManualLogin: false`; the ATC path passed nothing, so
// a headed companion turned a fired hold against a logged-out profile into a
// 120s silent wait inside the request, holding the profile lock — and, because
// /atc is traced, recorded whatever password got typed into that window.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { setupAuthPage } from '../src/cart.js'

test('the ATC path refuses the manual-login wait', async () => {
  const seen = []
  const { page, recaccount, authFailure } = await setupAuthPage({
    getContextFn: async () => fakeContext(),
    profileId: '7',
    resolveRecaccountFn: async (_page, options) => {
      seen.push(options)
      return null
    },
  })

  assert.deepEqual(seen, [{ allowManualLogin: false }])
  assert.equal(recaccount, null)
  assert.ok(page, 'the page is still returned so the caller can screenshot it')
  assert.equal(authFailure.error, 'recgov_not_authenticated')
})

function fakeContext () {
  return {
    addCookies: async () => {},
    newPage: async () => ({
      addInitScript: async () => {},
      route: async () => {},
      url: () => 'https://www.recreation.gov/',
    }),
  }
}
