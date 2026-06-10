// Verifies the HTTP client posts the exact payload shapes the backend expects.

import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'

let server, baseUrl, log

before(async () => {
  log = []
  server = createServer((req, res) => {
    let body = ''
    req.on('data', (c) => { body += c })
    req.on('end', () => {
      log.push({ method: req.method, url: req.url, body })
      if (req.url.endsWith('/claim')) {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ ok: true, leaseExpires: '2026-06-04T13:00:00Z' }))
      } else if (req.url.endsWith('/result')) {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end('{"ok":true}')
      } else if (req.url === '/api/campsite/companion/heartbeat') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end('{"ok":true}')
      } else if (req.url.match(/^\/api\/campsite\/matches\/\d+$/)) {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ id: 42, campgroundId: '232447', campsiteId: '12345', firstDate: '2026-07-01', availableDates: ['2026-07-01'], site: 'A12' }))
      } else if (req.url === '/api/campsite/companion/work/next') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({
          match: { id: 7, alertId: 1, campgroundId: '232447', campsiteId: '100', firstDate: '2026-07-01', availableDates: ['2026-07-01'], site: 'A12' },
        }))
      } else if (req.url === '/api/campsite/booking/session/fresh-token') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({
          access_token: 'fake-jwt',
          expiration: '2026-06-04T13:00:00Z',
          account: { account_id: 'A-1', email: 'a@b.c' },
          is_guest: false,
          refresh_id: '',
        }))
      } else {
        res.writeHead(404); res.end()
      }
    })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  baseUrl = `http://127.0.0.1:${port}`
  process.env.BACKEND_URL = baseUrl
})

after(async () => {
  await new Promise((resolve) => server.close(resolve))
})

test('claimMatch posts companion_id', async () => {
  const { claimMatch } = await import('../src/backend.js')
  const r = await claimMatch(42, 'companion-A')
  assert.equal(r.status, 200)
  assert.equal(r.json.leaseExpires, '2026-06-04T13:00:00Z')
  const last = log.pop()
  assert.equal(last.method, 'POST')
  assert.equal(last.url, '/api/campsite/companion/matches/42/claim')
  assert.deepEqual(JSON.parse(last.body), { companion_id: 'companion-A' })
})

test('reportResult posts cart_added boolean', async () => {
  const { reportResult } = await import('../src/backend.js')
  const r = await reportResult(42, true)
  assert.equal(r.status, 200)
  const last = log.pop()
  assert.equal(last.url, '/api/campsite/companion/matches/42/result')
  assert.deepEqual(JSON.parse(last.body), { cart_added: true })
})

test('heartbeat posts companion_id', async () => {
  const { heartbeat } = await import('../src/backend.js')
  const r = await heartbeat('companion-A')
  assert.equal(r.status, 200)
  const last = log.pop()
  assert.equal(last.url, '/api/campsite/companion/heartbeat')
  assert.deepEqual(JSON.parse(last.body), { companion_id: 'companion-A' })
})

test('getMatch returns parsed envelope', async () => {
  const { getMatch } = await import('../src/backend.js')
  const r = await getMatch(42)
  assert.equal(r.status, 200)
  assert.equal(r.json.id, 42)
  assert.equal(r.json.campgroundId, '232447')
})

test('fetchFreshRecaccount returns the recaccount-shaped JSON from backend', async () => {
  const { fetchFreshRecaccount } = await import('../src/backend.js')
  const ra = await fetchFreshRecaccount()
  assert.equal(ra.access_token, 'fake-jwt')
  assert.equal(ra.account.account_id, 'A-1')
  assert.equal(ra.is_guest, false)
})

test('getNextWork returns the planner-picked match', async () => {
  const { getNextWork } = await import('../src/backend.js')
  const r = await getNextWork()
  assert.equal(r.status, 200)
  assert.equal(r.json.match.id, 7)
  assert.equal(r.json.match.alertId, 1)
  assert.equal(r.json.match.campsiteId, '100')
})
