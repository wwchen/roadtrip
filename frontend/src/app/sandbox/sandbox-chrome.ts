// The deployment chrome every page carries: the sandbox build banner and the
// assume-user switcher.
//
// One entry point rather than two imports per page, because the failure mode of
// forgetting one is invisible. `mountPage` calls this, so the guarantee is
// structural: a page that mounts at all has the chrome. (Until Phase 5 this was a
// pair of `<script type="module">` tags injected into every HTML entry by a Vite
// plugin and served from the legacy `web/` tree; the tag list was pinned by a test
// for exactly the same reason. Calling one function from the shared mount is the
// same guarantee without a build-time indirection.)
import './sandbox.css';
import { initSandboxBanner } from './sandbox-banner';
import { initUserSwitcher } from './sandbox-user-switcher';

/**
 * Render the sandbox chrome if this deployment is a sandbox.
 *
 * Fire-and-forget on purpose: both halves resolve to "render nothing" outside a
 * sandbox, and neither may delay the page. Nothing awaits this, and neither
 * `init*` can reject.
 */
export function initSandboxChrome(): void {
  void initSandboxBanner();
  void initUserSwitcher();
}
