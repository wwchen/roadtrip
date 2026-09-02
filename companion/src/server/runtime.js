import { log } from './logging.js'

/**
 * Per-server state the route handlers share. Today that is only the logger:
 * the boot-time auth-check gate is gone with the startup check itself.
 */
export function createServerRuntime ({
  logger = log,
} = {}) {
  return { logger }
}
