import { isJsonConsoleInstalled } from '../jsonConsole.js'
import { LOG_DETAIL_MAX_CHARS } from './constants.js'

const COMPANION_ID = process.env.COMPANION_ID || 'recgov-companion'

export function log (...items) {
  // Under JSON output the envelope already carries `timestamp` and
  // `loggerName`; re-prefixing them would just duplicate both inside `message`.
  if (isJsonConsoleInstalled()) {
    console.log(...items)
    return
  }
  console.log(new Date().toISOString(), `[${COMPANION_ID}]`, ...items)
}

export function captureStdout () {
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

export function captureWritable (target) {
  let data = ''
  return {
    write (chunk) {
      const rendered = Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk)
      data += rendered
      target.write(chunk)
    },
    lines () {
      return data
        .split(/\r?\n/)
        .map(line => line.trimEnd())
        .filter(Boolean)
    },
  }
}

export function compactLogLines (lines) {
  return lines.filter(line => String(line || '').trim())
}

export function truncateLogField (value, maxLength = LOG_DETAIL_MAX_CHARS) {
  const rendered = String(value).replace(/\s+/g, ' ').trim()
  return rendered.length <= maxLength ? rendered : `${rendered.slice(0, maxLength)}...`
}
