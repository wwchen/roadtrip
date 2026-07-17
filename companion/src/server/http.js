import {
  HTTP_PAYLOAD_TOO_LARGE,
  MAX_BODY_BYTES,
} from './constants.js'

export function jsonResponse (res, status, body) {
  const rendered = JSON.stringify(body)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(rendered),
  })
  res.end(rendered)
}

export function htmlResponse (res, status, body) {
  res.writeHead(status, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

export function imageResponse (res, status, body, contentType) {
  res.writeHead(status, {
    'content-type': contentType,
    'content-length': body.length,
  })
  res.end(body)
}

export async function readBody (req) {
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

export function wantsHtml (req) {
  const accept = String(req.headers.accept || '')
  return accept.includes('text/html') && !accept.includes('application/json')
}
