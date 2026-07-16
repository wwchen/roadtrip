// HTTP executor for backend-owned one-shot companion work.
// The backend posts an ATC payload here; this process owns the browser profile
// and returns the same JSON result as the recgov:atc CLI.

import http from 'node:http'
import { runAtcOnce } from './runAtcOnce.js'

const DEFAULT_HOST = '0.0.0.0'
const DEFAULT_PORT = 8770
const MAX_BODY_BYTES = 64 * 1024
const HTTP_OK = 200
const HTTP_BAD_REQUEST = 400
const HTTP_CONFLICT = 409
const HTTP_PAYLOAD_TOO_LARGE = 413
const HTTP_UNPROCESSABLE_ENTITY = 422
const HTTP_INTERNAL_ERROR = 500
const EXIT_SUCCESS = 0
const EXIT_USAGE = 2

const HOST = process.env.COMPANION_HOST || DEFAULT_HOST
const PORT = Number.parseInt(process.env.COMPANION_PORT || String(DEFAULT_PORT), 10)
const COMPANION_ID = process.env.COMPANION_ID || 'recgov-companion'

let busy = false

function log (...items) {
  console.log(new Date().toISOString(), `[${COMPANION_ID}]`, ...items)
}

function jsonResponse (res, status, body) {
  const rendered = JSON.stringify(body)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(rendered),
  })
  res.end(rendered)
}

async function readBody (req) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > MAX_BODY_BYTES) {
      throw Object.assign(new Error('request body too large'), { status: HTTP_PAYLOAD_TOO_LARGE })
    }
    chunks.push(chunk)
  }
  return Buffer.concat(chunks).toString('utf8')
}

function payloadSummary (raw) {
  try {
    const payload = JSON.parse(raw)
    const openings = payload?.payload?.openings || payload?.openings || []
    const first = openings[0] || payload
    return [
      `watch=${payload?.payload?.watch_id ?? payload?.watch_id ?? '?'}`,
      `start=${payload?.payload?.start_date ?? payload?.start_date ?? first?.date ?? '?'}`,
      `end=${payload?.payload?.end_date ?? payload?.end_date ?? '?'}`,
      `site="${first?.label ?? first?.campsite_site ?? first?.vendor_id ?? '?'}"`,
    ].join(' ')
  } catch {
    return 'invalid-json'
  }
}

async function handleAtc (req, res) {
  if (busy) {
    jsonResponse(res, HTTP_CONFLICT, {
      ok: false,
      cart_added: false,
      error: 'companion_busy',
      detail: 'companion is already running an ATC request',
    })
    return
  }

  busy = true
  const stdout = captureStdout()
  const startedAt = Date.now()
  try {
    let raw
    try {
      raw = await readBody(req)
    } catch (error) {
      jsonResponse(res, error.status || HTTP_BAD_REQUEST, {
        ok: false,
        cart_added: false,
        error: 'invalid_request',
        detail: error.message,
      })
      return
    }

    log('recgov atc start', payloadSummary(raw))
    const code = await runAtcOnce({
      argv: ['--payload-json', raw],
      stdout,
      stderr: process.stderr,
    })
    const result = parseRunResult(stdout.value())
    const status =
      code === EXIT_SUCCESS && result.ok
        ? HTTP_OK
        : code === EXIT_USAGE
          ? HTTP_UNPROCESSABLE_ENTITY
          : HTTP_INTERNAL_ERROR
    log('recgov atc result', result.ok ? 'success' : 'fail', `code=${code}`, `duration_ms=${Date.now() - startedAt}`)
    jsonResponse(res, status, result)
  } catch (error) {
    log('recgov atc exception', error.message)
    jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      cart_added: false,
      error: 'add_to_cart_exception',
      detail: error.message,
    })
  } finally {
    busy = false
  }
}

function captureStdout () {
  let data = ''
  return {
    write (chunk) {
      data += chunk
    },
    value () {
      return data
    },
  }
}

function parseRunResult (raw) {
  try {
    return JSON.parse(raw.trim())
  } catch (error) {
    return {
      ok: false,
      cart_added: false,
      error: 'invalid_companion_result',
      detail: `companion returned non-json stdout: ${error.message}`,
    }
  }
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    jsonResponse(res, HTTP_OK, { ok: true, busy })
    return
  }
  if (req.method === 'POST' && req.url === '/recgov/atc') {
    await handleAtc(req, res)
    return
  }
  jsonResponse(res, HTTP_BAD_REQUEST, {
    ok: false,
    error: 'unsupported_route',
    detail: `${req.method} ${req.url}`,
  })
})

server.listen(PORT, HOST, () => {
  log('listening', `http://${HOST}:${PORT}`)
})

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    log('shutting down')
    server.close(() => process.exit(0))
    setTimeout(() => process.exit(0), 1000)
  })
}
