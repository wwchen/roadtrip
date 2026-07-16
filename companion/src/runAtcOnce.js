// One-shot ATC executor for local integration tests and backend process calls.
// Browser automation logs are redirected to stderr; stdout is one JSON result.

import { readFile } from 'node:fs/promises'
import { format } from 'node:util'
import { pathToFileURL } from 'node:url'
import { addToCart } from './cart.js'
import {
  cartMatchFromArgs,
  cartMatchFromAtcInput,
  validateCartMatch,
} from './atcPayload.js'
import { bookingUrlForMatch } from './cart.js'

const EXIT_SUCCESS = 0
const EXIT_RUNTIME_FAILURE = 1
const EXIT_USAGE = 2
const ERROR_INVALID_PAYLOAD = 'invalid_payload'
const ERROR_CART_NOT_ADDED = 'cart_not_added'
const ERROR_ADD_TO_CART_EXCEPTION = 'add_to_cart_exception'
const DEFAULT_CART_NOT_ADDED_DETAIL = 'cart automation did not confirm a cart hold'

export async function runAtcOnce ({
  argv = process.argv.slice(2),
  stdout = process.stdout,
  stderr = process.stderr,
  addToCartFn = addToCart,
} = {}) {
  const args = parseArgs(argv)
  if (args.help) {
    stderr.write(usage())
    return EXIT_USAGE
  }

  let match
  try {
    match = await matchFromArgs(args)
  } catch (err) {
    writeResult(stdout, failureResult(ERROR_INVALID_PAYLOAD, err.message))
    return EXIT_USAGE
  }

  const validationError = validateCartMatch(match)
  if (validationError) {
    writeResult(stdout, failureResult(ERROR_INVALID_PAYLOAD, validationError, { match }))
    return EXIT_USAGE
  }

  let result
  const restoreConsole = redirectConsoleLog(stderr)
  try {
    result = await addToCartFn(match)
  } catch (err) {
    writeResult(stdout, failureResult(ERROR_ADD_TO_CART_EXCEPTION, err.message, { match }))
    return EXIT_RUNTIME_FAILURE
  } finally {
    restoreConsole()
  }

  await result?.page?.close?.().catch(() => {})

  const base = {
    booking_url: bookingUrlForMatch(match),
    campsite_site: match.campsite_site,
    first_date: match.first_date,
    checkout_date: match.checkout_date,
  }

  if (result?.ok) {
    writeResult(stdout, {
      ok: true,
      cart_added: true,
      ...base,
    })
    return EXIT_SUCCESS
  }

  writeResult(stdout, failureResult(
    ERROR_CART_NOT_ADDED,
    cartNotAddedDetail(result?.cart_check),
    { ...base, cart_check: result?.cart_check },
  ))
  return EXIT_RUNTIME_FAILURE
}

function parseArgs (argv) {
  const args = {}
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index]
    if (arg === '--help' || arg === '-h') {
      args.help = true
      continue
    }
    const match = arg.match(/^--([^=]+)(?:=(.*))?$/)
    if (!match) continue
    const [, name, inlineValue] = match
    if (inlineValue !== undefined) {
      args[name] = inlineValue
    } else {
      const next = argv[index + 1]
      if (next && !next.startsWith('--')) {
        args[name] = next
        index++
      } else {
        args[name] = true
      }
    }
  }
  return args
}

async function matchFromArgs (args) {
  if (args['payload-file']) {
    const raw = await readFile(String(args['payload-file']), 'utf8')
    return cartMatchFromAtcInput(parseJson(raw, 'payload file'))
  }
  if (args['payload-json']) {
    return cartMatchFromAtcInput(parseJson(String(args['payload-json']), 'payload JSON'))
  }
  return cartMatchFromArgs(args)
}

function parseJson (raw, label) {
  try {
    return JSON.parse(raw)
  } catch (err) {
    throw new Error(`invalid ${label}: ${err.message}`)
  }
}

function redirectConsoleLog (stderr) {
  const originalLog = console.log
  console.log = (...items) => {
    stderr.write(`${format(...items)}\n`)
  }
  return () => {
    console.log = originalLog
  }
}

function failureResult (error, detail, extra = {}) {
  return {
    ok: false,
    cart_added: false,
    error,
    detail,
    ...extra,
  }
}

function cartNotAddedDetail (cartCheck) {
  if (!cartCheck?.reason) return DEFAULT_CART_NOT_ADDED_DETAIL
  const status = cartCheck.status === null || cartCheck.status === undefined ? '?' : cartCheck.status
  const reservations = cartCheck.reservation_count === undefined ? '?' : cartCheck.reservation_count
  return `cart verification failed: reason=${cartCheck.reason} status=${status} reservations=${reservations}`
}

function writeResult (stdout, result) {
  stdout.write(`${JSON.stringify(result)}\n`)
}

function usage () {
  return [
    'Usage:',
    '  npm run recgov:atc -- --payload-file /path/to/atc.json',
    '  npm run recgov:atc -- --payload-json \'{"start_date":"2026-07-15",...}\'',
    '  npm run recgov:atc -- --booking-url URL --start-date YYYY-MM-DD --end-date YYYY-MM-DD',
    '',
    'Payload may be either a { "payload": ... } envelope or the raw ATC payload.',
    '',
  ].join('\n')
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const code = await runAtcOnce()
  process.exit(code)
}
