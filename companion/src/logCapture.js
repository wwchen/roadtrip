// Per-request console capture for the companion, scoped by AsyncLocalStorage.
//
// The `/atc` route returns the browser-automation log lines in its response, so
// it needs the console output of one run. It used to get them by saving
// `console.log`, replacing it with a writer into that request's buffer, and
// restoring the saved value on the way out.
//
// That is a process global, and the `/atc` busy lock is per PROFILE, so two
// requests overlap by design. Both failures were real: profile B's login lines
// landed in profile A's response, and an interleaved restore (A saves, B saves
// A's writer, A restores, B restores) left `console.log` permanently pointing at
// A's finished buffer — every later line growing a dead string and bypassing the
// JSON envelope that gives Loki its `level` label.
//
// The tap here is installed once and never swapped. It appends to whichever sink
// the current async context carries, and always forwards to the real console, so
// captured lines stay in the log stream instead of being diverted out of it.

import { AsyncLocalStorage } from 'node:async_hooks'
import { format } from 'node:util'

const CAPTURED_METHODS = ['log', 'info', 'warn', 'error', 'debug']

const storage = new AsyncLocalStorage()

/**
 * Installs the capture tap. Idempotent.
 *
 * Call AFTER `installJsonConsole` so the forwarded line keeps its JSON
 * envelope: this wrapper binds whatever `console[method]` is at install time.
 */
export function installLogCapture () {
  if (installLogCapture.installed) return
  for (const method of CAPTURED_METHODS) {
    const emit = console[method].bind(console)
    console[method] = (...args) => {
      const sink = storage.getStore()
      if (sink) sink.write(`${format(...args)}\n`)
      emit(...args)
    }
  }
  installLogCapture.installed = true
}

/**
 * Runs `fn` with console output additionally written to `sink`.
 *
 * Nesting is intentionally last-one-wins: the inner scope's sink receives the
 * lines, matching what a caller that opened it would expect.
 */
export function withLogCapture (sink, fn) {
  return storage.run(sink, fn)
}

/** Exported for tests: whether a capture scope is currently active. */
export function capturing () {
  return storage.getStore() != null
}
