import { test } from 'node:test'
import assert from 'node:assert/strict'
import { jsonLogLine } from '../src/jsonConsole.js'

// Field names here are a contract with two other files: Alloy's JSON parsing
// stage (grafana/alloy/config.alloy) reads `level` to build the Loki label, and
// the backend's logback.xml uses the same envelope so one LogQL `| json` shape
// serves both services. Renaming a field silently drops companion logs out of
// every level-filtered query.
test('emits one JSON object per line with the backend envelope fields', () => {
  const parsed = JSON.parse(jsonLogLine('INFO', 'Cart: clicked date 2026-08-01'))

  assert.deepEqual(Object.keys(parsed).sort(), ['level', 'loggerName', 'message', 'timestamp'])
  assert.equal(parsed.level, 'INFO')
  assert.equal(parsed.message, 'Cart: clicked date 2026-08-01')
  assert.ok(!Number.isNaN(Date.parse(parsed.timestamp)))
})

test('applies console format specifiers', () => {
  const parsed = JSON.parse(jsonLogLine('INFO', 'date %s of %d', '2026-08-01', 3))

  assert.equal(parsed.message, 'date 2026-08-01 of 3')
})

test('a multi-line stack stays on one line so Loki reads it as one entry', () => {
  const line = jsonLogLine('ERROR', new Error('boom'))

  assert.equal(line.split('\n').length, 1)
  assert.match(JSON.parse(line).message, /^Error: boom\n {4}at /)
})

test('objects are rendered rather than dropped', () => {
  assert.equal(JSON.parse(jsonLogLine('WARN', 'payload', { watchId: 12 })).message, "payload { watchId: 12 }")
})
