// Structured stdout for the companion, so its logs are queryable in Loki the
// same way the backend's are.
//
// Alloy tails every `roadtrip-*` container's stdout, but its JSON parsing stage
// only matched `roadtrip-backend`, because the companion wrote plain sentences.
// The practical cost: companion lines had no `level` label, so "show me errors
// across the stack" silently skipped the process that drives the browser — the
// component most likely to be the thing that broke.
//
// Rather than rewrite ~80 call sites, this wraps the console methods once. Field
// names deliberately mirror backend/src/main/resources/logback.xml
// (`timestamp`, `level`, `loggerName`, `message`) so one LogQL `| json` shape
// works for both services.
//
// JSON is emitted only when stdout is not a TTY — i.e. under Docker, where Alloy
// is reading. Interactive runs (`npm run recgov:login`) keep human-readable
// output, which is the tradeoff logback.xml notes it could not make.

import { format } from 'node:util'

const COMPANION_ID = process.env.COMPANION_ID || 'recgov-companion'

// console method -> logback level name, so `level` means the same thing in both
// services' log lines.
const CONSOLE_LEVELS = {
  log: 'INFO',
  info: 'INFO',
  warn: 'WARN',
  error: 'ERROR',
  debug: 'DEBUG',
}

function renderLine (level, args) {
  return JSON.stringify({
    timestamp: new Date().toISOString(),
    level,
    loggerName: COMPANION_ID,
    // format() applies console's own %s/%d/%o semantics and stringifies Errors
    // with their stack, which JSON.stringify then escapes onto one line.
    message: format(...args),
  })
}

/**
 * Replaces console.log/info/warn/error/debug with JSON-emitting equivalents.
 * Idempotent, and a no-op on a TTY. Call once at process start.
 *
 * @param {{force?: boolean}} [options] force JSON even on a TTY (tests).
 * @returns {boolean} whether JSON output was installed.
 */
export function installJsonConsole ({ force = false } = {}) {
  if (installJsonConsole.installed) return true
  if (!force && process.stdout.isTTY) return false

  for (const [method, level] of Object.entries(CONSOLE_LEVELS)) {
    // Bind the original so the replacement cannot recurse into itself.
    const emit = console[method].bind(console)
    console[method] = (...args) => emit(renderLine(level, args))
  }
  installJsonConsole.installed = true
  return true
}

/** Whether JSON output is active, so callers do not hand-prefix a timestamp or
 *  service name that the JSON envelope already carries as its own fields. */
export function isJsonConsoleInstalled () {
  return installJsonConsole.installed === true
}

/** Exported for tests: the line this module would write for one call. */
export function jsonLogLine (level, ...args) {
  return renderLine(level, args)
}
