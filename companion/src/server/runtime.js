import { log } from './logging.js'

export function createServerRuntime ({
  logger = log,
} = {}) {
  let startupAuthCheck = null

  return {
    logger,
    setStartupAuthCheck: (promise) => {
      startupAuthCheck = promise
    },
    waitForStartupAuthCheck: async () => {
      if (!startupAuthCheck) return
      await startupAuthCheck.catch(() => {})
    },
  }
}
