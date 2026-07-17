import { log } from './logging.js'

export function createServerRuntime ({
  logger = log,
} = {}) {
  let busy = false
  let startupAuthCheck = null

  return {
    logger,
    isBusy: () => busy,
    setBusy: (value) => {
      busy = value
    },
    setStartupAuthCheck: (promise) => {
      startupAuthCheck = promise
    },
    waitForStartupAuthCheck: async () => {
      if (!startupAuthCheck) return
      await startupAuthCheck.catch(() => {})
    },
  }
}
