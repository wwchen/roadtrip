// The deployment chrome every page carries: the sandbox build banner.
//
// One entry point rather than two imports per page, because the failure mode of
// forgetting one is invisible. `mountPage` calls this, so the guarantee is
// structural: a page that mounts at all has the chrome, with no build-time
// indirection to keep in step.
import './sandbox.css';
import { initSandboxBanner } from './sandbox-banner';

/**
 * Render the sandbox chrome if this deployment is a sandbox.
 *
 * Fire-and-forget on purpose: both halves resolve to "render nothing" outside a
 * sandbox, and neither may delay the page. Nothing awaits this, and neither
 * `init*` can reject.
 */
export function initSandboxChrome(): void {
  void initSandboxBanner();
}
