export const RECGOV_SCREENSHOT_VIEWPORT = {
  width: 1280,
  height: 1000,
}

export const RECGOV_SCREENSHOT_OPTIONS = {
  type: 'png',
  fullPage: false,
}

export async function captureRecgovPageImage (page, options = {}) {
  await setScreenshotViewport(page)
  return page.screenshot({
    ...RECGOV_SCREENSHOT_OPTIONS,
    ...options,
  })
}

async function setScreenshotViewport (page) {
  if (typeof page.setViewportSize !== 'function') return
  await page.setViewportSize(RECGOV_SCREENSHOT_VIEWPORT)
}
