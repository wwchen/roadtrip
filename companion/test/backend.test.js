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
      if (req.url === '/api/dispatches/claim') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({
          dispatch: {
            id: 9,
            kind: 'test',
            vendor: 'recgov',
            payload_version: 'atc.recgov.v1',
            payload: { simulate_result: 'success' },
            lease_token: 'lease-1',
            lease_expires_at: '2026-06-04T13:00:00Z',
            expires_at: '2026-06-04T13:00:30Z',
          },
        }))
      } else if (req.url === '/api/dispatches/9/complete') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end('{"id":9,"status":"completed"}')
      } else if (req.url === '/api/dispatches/9/fail') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end('{"id":9,"status":"failed"}')
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

test('fetchFreshRecaccount returns the recaccount-shaped JSON from backend', async () => {
  const { fetchFreshRecaccount } = await import('../src/backend.js')
  const ra = await fetchFreshRecaccount()
  assert.equal(ra.access_token, 'fake-jwt')
  assert.equal(ra.account.account_id, 'A-1')
  assert.equal(ra.is_guest, false)
})

test('claimDispatch posts dispatch selector', async () => {
  const { claimDispatch } = await import('../src/backend.js')
  const r = await claimDispatch({
    kind: 'test',
    vendors: ['recgov'],
    payloadVersions: ['atc.recgov.v1'],
    waitSec: 30,
    leaseSec: 30,
  })
  assert.equal(r.status, 200)
  assert.equal(r.json.dispatch.id, 9)
  const last = log.pop()
  assert.equal(last.url, '/api/dispatches/claim')
  assert.deepEqual(JSON.parse(last.body), {
    kind: 'test',
    vendors: ['recgov'],
    payload_versions: ['atc.recgov.v1'],
    wait_sec: 30,
    lease_sec: 30,
  })
})

test('completeDispatch posts lease token and result', async () => {
  const { completeDispatch } = await import('../src/backend.js')
  const r = await completeDispatch(9, 'lease-1', { simulated: true })
  assert.equal(r.status, 200)
  const last = log.pop()
  assert.equal(last.url, '/api/dispatches/9/complete')
  assert.deepEqual(JSON.parse(last.body), {
    lease_token: 'lease-1',
    result: { simulated: true },
  })
})

test('failDispatch posts lease token and failure detail', async () => {
  const { failDispatch } = await import('../src/backend.js')
  const r = await failDispatch(9, 'lease-1', 'simulated_failure', 'requested by test', { simulated: true })
  assert.equal(r.status, 200)
  const last = log.pop()
  assert.equal(last.url, '/api/dispatches/9/fail')
  assert.deepEqual(JSON.parse(last.body), {
    lease_token: 'lease-1',
    error: 'simulated_failure',
    detail: 'requested by test',
    result: { simulated: true },
  })
})
