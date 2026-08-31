import { campsiteUrl } from '../../browser.js'
import { runAtcOnce } from '../../runAtcOnce.js'
import {
  EXIT_SUCCESS,
  EXIT_USAGE,
  HTTP_BAD_REQUEST,
  HTTP_INTERNAL_ERROR,
  HTTP_OK,
  HTTP_UNPROCESSABLE_ENTITY,
  LOG_DETAIL_MAX_CHARS,
} from '../constants.js'
import { jsonResponse, readBody } from '../http.js'
import {
  ERROR_INVALID_REQUEST,
  badRequest,
  profileBusyResponse,
  requireProfileId,
} from '../requestInput.js'
import {
  captureStdout,
  captureWritable,
  compactLogLines,
  truncateLogField,
} from '../logging.js'

const OPERATION_ATC = 'atc'

export async function handleAtc (req, res, {
  runtime,
  pool,
  runAtcOnceFn = runAtcOnce,
}) {
  let raw
  try {
    raw = await readBody(req)
  } catch (error) {
    jsonResponse(res, error.status || HTTP_BAD_REQUEST, {
      ok: false,
      cart_added: false,
      error: ERROR_INVALID_REQUEST,
      detail: error.message,
    })
    return
  }

  const profile = requireProfileId(atcRequestFields(req, raw))
  if (!profile.ok) {
    const rejection = badRequest(profile.error, 'profile_id identifies the browser profile that holds the cart')
    jsonResponse(res, rejection.status, { ...rejection.body, cart_added: false })
    return
  }

  const profileId = profile.profileId
  const lock = pool.acquire(profileId, OPERATION_ATC)
  if (!lock) {
    const rejection = profileBusyResponse(profileId, pool.busyOperation(profileId))
    jsonResponse(res, rejection.status, { ...rejection.body, cart_added: false })
    return
  }

  const stdout = captureStdout()
  const stderr = captureWritable(process.stderr)
  const startedAt = Date.now()
  let atcStartLine = null
  try {
    atcStartLine = `recgov atc start profile=${profileId} ${payloadSummary(raw)}`
    runtime.logger(atcStartLine)
    await runtime.waitForStartupAuthCheck()
    const code = await runAtcOnceFn({
      argv: ['--payload-json', raw],
      stdout,
      stderr,
      contextOptions: { getContextFn: () => pool.context(profileId) },
    })
    const baseResult = parseRunResult(stdout.value())
    const resultLine = `recgov atc result ${[
      ...resultLogFields(baseResult),
      `code=${code}`,
      `duration_ms=${Date.now() - startedAt}`,
    ].join(' ')}`
    const result = withCapturedLogs(baseResult, compactLogLines([
      atcStartLine,
      ...stderr.lines(),
      resultLine,
    ]))
    const status =
      code === EXIT_SUCCESS && result.ok
        ? HTTP_OK
        : code === EXIT_USAGE
          ? HTTP_UNPROCESSABLE_ENTITY
          : HTTP_INTERNAL_ERROR
    runtime.logger(resultLine)
    jsonResponse(res, status, result)
  } catch (error) {
    const exceptionLine = `recgov atc exception ${error.message}`
    runtime.logger(exceptionLine)
    jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      cart_added: false,
      error: 'add_to_cart_exception',
      detail: error.message,
      screenshots: [],
      logs: compactLogLines([
        atcStartLine,
        ...stderr.lines(),
        exceptionLine,
      ]),
    })
  } finally {
    lock.release()
  }
}

function atcRequestFields (req, raw) {
  const url = new URL(req.url || '/', 'http://companion.local')
  const fields = Object.fromEntries(url.searchParams.entries())
  try {
    const payload = JSON.parse(raw)
    const body = payload?.payload || payload
    return { ...fields, ...(body && typeof body === 'object' ? body : {}) }
  } catch {
    return fields
  }
}

function payloadSummary (raw) {
  try {
    const payload = JSON.parse(raw)
    const body = payload?.payload || payload
    const openings = Array.isArray(body?.openings) ? body.openings : []
    const first = openings[0] || body
    const startDate = body?.start_date ?? first?.date ?? first?.first_date
    const endDate = body?.end_date ?? first?.checkout_date
    const campsiteId = first?.vendor_id ?? first?.provider_campsite_id ?? first?.campsite_id ?? body?.campsite_id
    const bookingUrl =
      first?.booking_url ??
      body?.booking_url ??
      (campsiteId && startDate && endDate ? campsiteUrl(campsiteId, startDate, endDate) : '')
    return [
      `start_date=${startDate ?? '?'}`,
      `end_date=${endDate ?? '?'}`,
      `campsite=${campsiteId ?? '?'}`,
      `booking_url="${truncateLogField(bookingUrl, LOG_DETAIL_MAX_CHARS)}"`,
    ].join(' ')
  } catch {
    return 'invalid-json'
  }
}

function withCapturedLogs (result, logs) {
  return {
    ...result,
    screenshots: result.screenshots || [],
    logs,
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

function resultLogFields (result) {
  const fields = [result.ok ? 'success' : 'fail']
  if (!result.ok) {
    fields.push(`error=${result.error || '?'}`)
    fields.push(`detail="${truncateLogField(result.detail || '', LOG_DETAIL_MAX_CHARS)}"`)
  }
  if (result.cart_check?.reason) {
    fields.push(`cart_reason=${result.cart_check.reason}`)
    fields.push(`cart_status=${result.cart_check.status ?? '?'}`)
    fields.push(`cart_reservations=${result.cart_check.reservation_count ?? '?'}`)
    fields.push(`cart_response_signal=${result.cart_check.response_signal ?? '?'}`)
    fields.push(`cart_best_score=${result.cart_check.best_match?.score ?? '?'}`)
  }
  return fields
}
