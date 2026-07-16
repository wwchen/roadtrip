import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { resolveRecaccount } from '../src/recgovSession.js'

const JWT_HEADER = { alg: 'none' }
const JWT_SIGNATURE = 'sig'
const FRESH_OFFSET_SECONDS = 60 * 60
const NEAR_EXPIRY_OFFSET_SECONDS = 60
const REFRESH_RETRY_DELAY_MS = 1000

test('resolveRecaccount uses the existing companion browser recaccount', async () => {
  const recaccount = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-existing' }),
  })
  const page = fakePage({ rawRecaccount: JSON.stringify(recaccount) })

  const resolved = await resolveRecaccount(page)

  assert.equal(resolved.access_token, recaccount.access_token)
  assert.deepEqual(page.gotos, ['https://www.recreation.gov/'])
  assert.equal(page.refreshCalls.length, 0)
  assert.deepEqual(page.context().cookies, [{
    name: 'r1s-fingerprint',
    value: 'fp-existing',
    domain: '.recreation.gov',
    path: '/',
    secure: true,
    sameSite: 'Lax',
  }])
})

test('resolveRecaccount refreshes near-expiry recaccount in the companion browser', async () => {
  const stale = testRecaccount({
    token: fakeJwt({ offsetSeconds: NEAR_EXPIRY_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const refreshed = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-new' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(stale),
    refreshRecaccount: refreshed,
  })

  const resolved = await resolveRecaccount(page)

  assert.equal(resolved.access_token, refreshed.access_token)
  assert.equal(page.refreshCalls.length, 1)
  assert.equal(page.refreshCalls[0].url, 'https://www.recreation.gov/api/accounts/login/v2/refresh')
  assert.equal(page.refreshCalls[0].token, stale.access_token)
  assert.deepEqual(page.refreshCalls[0].credentials, {
    account_id: 'acct-1',
    refresh_id: 'refresh-1',
  })
})

test('resolveRecaccount force-refreshes a fresh browser recaccount', async () => {
  const fresh = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const refreshed = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-new' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(fresh),
    refreshRecaccount: refreshed,
  })

  const resolved = await resolveRecaccount(page, { forceRefresh: true })

  assert.equal(resolved.access_token, refreshed.access_token)
  assert.equal(page.refreshCalls.length, 1)
  assert.equal(page.refreshCalls[0].token, fresh.access_token)
  assert.equal(page.context().cookies.at(-1).value, 'fp-new')
})

test('resolveRecaccount retries transient forced browser refresh failures', async () => {
  const fresh = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const refreshed = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-new' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(fresh),
    refreshResponses: [
      { ok: false, error: 'Failed to fetch' },
      { ok: true, recaccount: refreshed },
    ],
  })

  const resolved = await resolveRecaccount(page, { forceRefresh: true })

  assert.equal(resolved.access_token, refreshed.access_token)
  assert.equal(page.refreshCalls.length, 2)
  assert.deepEqual(page.waits, [REFRESH_RETRY_DELAY_MS])
})

test('resolveRecaccount fails closed when forced browser refresh is rejected', async () => {
  const fresh = testRecaccount({
    token: fakeJwt({ offsetSeconds: FRESH_OFFSET_SECONDS, fingerprint: 'fp-old' }),
  })
  const page = fakePage({
    rawRecaccount: JSON.stringify(fresh),
    refreshResponse: { ok: false, status: 401, body: 'unauthorized' },
  })

  const resolved = await resolveRecaccount(page, { forceRefresh: true })

  assert.equal(resolved, null)
  assert.equal(page.refreshCalls.length, 1)
})

function fakePage ({ rawRecaccount, refreshRecaccount = null, refreshResponse = null, refreshResponses = null }) {
  let refreshResponseIndex = 0
  const context = {
    cookies: [],
    pages: () => [page],
    addCookies: async (cookies) => {
      context.cookies.push(...cookies)
    },
  }
  const page = {
    gotos: [],
    refreshCalls: [],
    waits: [],
    context: () => context,
    goto: async (url) => {
      page.gotos.push(url)
    },
    waitForTimeout: async (ms) => {
      page.waits.push(ms)
    },
    evaluate: async (_fn, arg) => {
      if (arg === 'recaccount') return rawRecaccount
      if (arg?.url) {
        page.refreshCalls.push(arg)
        if (refreshResponses) return refreshResponses[refreshResponseIndex++]
        return refreshResponse || { ok: true, recaccount: refreshRecaccount }
      }
      throw new Error('unexpected evaluate call')
    },
  }
  return page
}

function testRecaccount ({ token }) {
  return {
    access_token: token,
    expiration: new Date(Date.now() + FRESH_OFFSET_SECONDS * 1000).toISOString(),
    account: { account_id: 'acct-1', email: 'test@example.com' },
    is_guest: false,
    refresh_id: 'refresh-1',
  }
}

function fakeJwt ({ offsetSeconds, fingerprint }) {
  const exp = Math.floor(Date.now() / 1000) + offsetSeconds
  return `${base64Url(JWT_HEADER)}.${base64Url({ exp, fingerprint })}.${JWT_SIGNATURE}`
}

function base64Url (value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url')
}
