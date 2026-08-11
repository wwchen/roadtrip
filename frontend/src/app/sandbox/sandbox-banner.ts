// Sandbox build-info banner.
//
// A fixed top bar naming the env/sha/branch, rendered ONLY when the running
// build is a sandbox. Every other env — and every fetch failure — produces no
// visible output; the page is never blocked and never waits on this.
//
// Rendered imperatively into `document.body` rather than as a React component
// because it is chrome OUTSIDE `#root`: it belongs to the deployment, not to the
// page, and it must be identical on every page whatever they render.
import { fetchBuildInfo, type BuildInfo } from '@/api/sandbox-api';

/** The one `build-info.env` value that gets a banner. */
const SANDBOX_ENV = 'sandbox';
const GITHUB_REPO_URL = 'https://github.com/wwchen/roadtrip';

/**
 * Render the banner, or nothing when this build is not a sandbox.
 *
 * @returns the inserted bar, or null when there is nothing to show.
 */
export function renderSandboxBanner(buildInfo: BuildInfo | null): HTMLElement | null {
  if (!buildInfo || buildInfo.env !== SANDBOX_ENV) return null;

  const bar = document.createElement('div');
  bar.className = 'sandbox-banner';
  bar.setAttribute('role', 'status');

  const envLabel = document.createElement('span');
  envLabel.className = 'sandbox-banner__env';
  envLabel.textContent = 'SANDBOX';

  const sha = document.createElement('a');
  sha.href = `${GITHUB_REPO_URL}/commit/${buildInfo.sha}`;
  sha.className = 'sandbox-banner__sha';
  sha.textContent = buildInfo.sha;

  const branch = document.createElement('span');
  branch.className = 'sandbox-banner__branch';
  branch.textContent = buildInfo.branch;

  bar.append(envLabel, sha, branch);
  document.body.append(bar);
  return bar;
}

/**
 * Ask which build this is and render the banner if it is a sandbox.
 *
 * Errors are swallowed: `/api/build-info` being unreachable is not a reason to
 * surface anything to a user, and the banner is always optional.
 */
export async function initSandboxBanner(): Promise<void> {
  try {
    renderSandboxBanner(await fetchBuildInfo());
  } catch {
    // build-info unavailable — no banner, never block the page.
  }
}
