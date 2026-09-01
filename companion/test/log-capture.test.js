import test from 'node:test'
import assert from 'node:assert/strict'
import { installLogCapture, withLogCapture } from '../src/logCapture.js'

function sink () {
  let data = ''
  return {
    write (chunk) { data += chunk },
    lines () { return data.split('\n').filter(Boolean) },
  }
}

test('concurrent captures do not bleed into each other', async () => {
  installLogCapture()
  const a = sink()
  const b = sink()

  // Interleave deliberately: A starts, B starts, A finishes, B finishes — the
  // ordering that made the old save/restore of a global leave console.log
  // pointing at a dead buffer.
  await Promise.all([
    withLogCapture(a, async () => {
      console.log('a-first')
      await new Promise(resolve => setTimeout(resolve, 20))
      console.log('a-second')
    }),
    withLogCapture(b, async () => {
      await new Promise(resolve => setTimeout(resolve, 10))
      console.log('b-only')
    }),
  ])

  assert.deepEqual(a.lines(), ['a-first', 'a-second'])
  assert.deepEqual(b.lines(), ['b-only'])
})

test('logging outside any capture scope still reaches the real console', async () => {
  installLogCapture()
  const captured = sink()
  await withLogCapture(captured, async () => { console.log('inside') })

  // After the scope ends the tap must be inert, not pointing at a dead buffer.
  console.log('outside')
  assert.deepEqual(captured.lines(), ['inside'])
})
