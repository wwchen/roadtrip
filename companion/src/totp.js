import crypto from 'node:crypto'

const DEFAULT_TOTP_DIGITS = 6
const DEFAULT_TOTP_PERIOD_SECONDS = 30
const DEFAULT_TOTP_ALGORITHM = 'sha1'
const MIN_TOTP_DIGITS = 6
const MAX_TOTP_DIGITS = 8
const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
const TOTP_ALGORITHMS = new Set(['sha1', 'sha256', 'sha512'])
const MILLISECONDS_PER_SECOND = 1_000

export function generateTotp (secretInput, options = {}) {
  const config = parseTotpConfig(secretInput)
  const nowMs = options.nowMs ?? Date.now()
  const counter = Math.floor(Math.floor(nowMs / MILLISECONDS_PER_SECOND) / config.periodSeconds)
  const counterBuffer = Buffer.alloc(8)
  counterBuffer.writeBigUInt64BE(BigInt(counter))

  const digest = crypto
    .createHmac(config.algorithm, config.secret)
    .update(counterBuffer)
    .digest()
  const offset = digest[digest.length - 1] & 0x0f
  const binary =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff)
  const modulus = 10 ** config.digits

  return String(binary % modulus).padStart(config.digits, '0')
}

export function parseTotpConfig (secretInput) {
  const raw = String(secretInput || '').trim()
  if (!raw) throw new Error('empty TOTP secret')

  const parsed = raw.startsWith('otpauth://')
    ? parseOtpAuthUrl(raw)
    : { secret: raw }
  const algorithm = normalizeAlgorithm(parsed.algorithm || DEFAULT_TOTP_ALGORITHM)
  const digits = normalizeDigits(parsed.digits || DEFAULT_TOTP_DIGITS)
  const periodSeconds = normalizePeriod(parsed.periodSeconds || DEFAULT_TOTP_PERIOD_SECONDS)

  return {
    secret: decodeBase32Secret(parsed.secret),
    algorithm,
    digits,
    periodSeconds,
  }
}

function parseOtpAuthUrl (raw) {
  let url
  try {
    url = new URL(raw)
  } catch {
    throw new Error('invalid otpauth URL')
  }
  if (url.protocol !== 'otpauth:' || url.hostname !== 'totp') {
    throw new Error('otpauth URL must use otpauth://totp')
  }

  return {
    secret: url.searchParams.get('secret') || '',
    algorithm: url.searchParams.get('algorithm') || undefined,
    digits: url.searchParams.get('digits') || undefined,
    periodSeconds: url.searchParams.get('period') || undefined,
  }
}

function normalizeAlgorithm (algorithm) {
  const normalized = String(algorithm).trim().toLowerCase()
  if (!TOTP_ALGORITHMS.has(normalized)) throw new Error(`unsupported TOTP algorithm: ${algorithm}`)
  return normalized
}

function normalizeDigits (digits) {
  const parsed = Number.parseInt(String(digits), 10)
  if (!Number.isInteger(parsed) || parsed < MIN_TOTP_DIGITS || parsed > MAX_TOTP_DIGITS) {
    throw new Error(`unsupported TOTP digits: ${digits}`)
  }
  return parsed
}

function normalizePeriod (periodSeconds) {
  const parsed = Number.parseInt(String(periodSeconds), 10)
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`unsupported TOTP period: ${periodSeconds}`)
  }
  return parsed
}

function decodeBase32Secret (secret) {
  const normalized = String(secret || '').toUpperCase().replace(/[\s=-]/g, '')
  if (!normalized) throw new Error('empty TOTP secret')

  let bits = 0
  let value = 0
  const bytes = []
  for (const char of normalized) {
    const index = BASE32_ALPHABET.indexOf(char)
    if (index < 0) throw new Error('TOTP secret must be Base32')
    value = (value << 5) | index
    bits += 5
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(bytes)
}
