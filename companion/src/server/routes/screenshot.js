import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { RECGOV_DIAGNOSTIC_DIR } from '../../recgovSession.js'
import {
  SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX,
  captureRecgovScreenshot,
  recgovScreenshotTargetUrl,
} from '../../recgovScreenshot.js'
import {
  HTTP_BAD_REQUEST,
  HTTP_INTERNAL_ERROR,
  HTTP_NOT_FOUND,
  HTTP_OK,
  LOG_DETAIL_MAX_CHARS,
  PNG_CONTENT_TYPE,
} from '../constants.js'
import {
  imageResponse,
  jsonResponse,
} from '../http.js'
import { truncateLogField } from '../logging.js'

export async function handleDiagnosticImage (url, res) {
  const filename = diagnosticFilename(url)
  if (!filename) {
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_diagnostic_path',
    })
    return
  }

  const imagePath = diagnosticImagePath(filename)
  if (!imagePath) {
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_diagnostic_path',
    })
    return
  }

  await serveScreenshotImage(imagePath, res, 'diagnostic_not_found')
}

export async function handleLiveScreenshot (url, res, { runtime, recgovScreenshotDeps }) {
  const target = recgovScreenshotTargetUrl(url)
  if (!target) {
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'invalid_screenshot_target',
      detail: 'screenshot target must be a recreation.gov URL or path',
    })
    return
  }

  runtime.logger('recgov screenshot start', `target=${target.href}`)
  const startedAt = Date.now()
  try {
    const { image, recaccountPresent } = await captureRecgovScreenshot(target, recgovScreenshotDeps)
    runtime.logger('recgov screenshot result ok', `target=${target.href}`, `recaccount=${recaccountPresent}`, `duration_ms=${Date.now() - startedAt}`)
    imageResponse(res, HTTP_OK, image, PNG_CONTENT_TYPE)
  } catch (error) {
    runtime.logger('recgov screenshot result fail', `target=${target.href}`, `detail="${truncateLogField(error.message, LOG_DETAIL_MAX_CHARS)}"`, `duration_ms=${Date.now() - startedAt}`)
    jsonResponse(res, HTTP_INTERNAL_ERROR, {
      ok: false,
      error: 'screenshot_failed',
      detail: error.message,
      target_url: target.href,
    })
  }
}

async function serveScreenshotImage (imagePath, res, notFoundError) {
  try {
    const image = await readFile(imagePath)
    imageResponse(res, HTTP_OK, image, PNG_CONTENT_TYPE)
  } catch {
    jsonResponse(res, HTTP_NOT_FOUND, {
      ok: false,
      error: notFoundError,
    })
  }
}

function diagnosticFilename (url) {
  const filename = path.basename(url.pathname)
  const requested = decodeURIComponent(url.pathname.slice(`${SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX}/`.length))
  if (!filename || filename !== requested || !filename.endsWith('.png')) return null
  return filename
}

function diagnosticImagePath (filename) {
  return path.join(RECGOV_DIAGNOSTIC_DIR, filename)
}
