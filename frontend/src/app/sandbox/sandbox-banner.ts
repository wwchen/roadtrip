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

/** Set on `<html>` while the bar is up; sandbox.css turns it into reserved room. */
const CHROME_CLASS = 'has-sandbox-chrome';
/**
 * Remembers the last answer, because the real one arrives over the network. The
 * bar is fixed, so the room for it appears the moment the fetch resolves — the
 * map shell shrinks, the body padding grows and an open drawer moves, all after
 * first paint and possibly mid-gesture. Mirroring lets every later load reserve
 * the room before painting anything; only the first visit can still shift.
 */
const CHROME_MIRROR_KEY = 'rt.sandbox-chrome';

function rememberChrome(isSandbox: boolean): void {
  try {
    if (isSandbox) localStorage.setItem(CHROME_MIRROR_KEY, '1');
    else localStorage.removeItem(CHROME_MIRROR_KEY);
  } catch {
    // Storage denied (private mode, blocked cookies) — the page still works,
    // it just reflows once per load like it did before the mirror existed.
  }
}

/**
 * Reserve the bar's room from the mirror, before the network answers.
 *
 * Synchronous and side-effect-only: a wrong guess is corrected by
 * `renderSandboxBanner` as soon as the real answer lands.
 */
export function reserveSandboxChrome(): void {
  try {
    if (localStorage.getItem(CHROME_MIRROR_KEY) === '1') {
      document.documentElement.classList.add(CHROME_CLASS);
    }
  } catch {
    // As above — no mirror, no pre-reservation.
  }
}

/**
 * Render the banner, or nothing when this build is not a sandbox.
 *
 * @returns the inserted bar, or null when there is nothing to show.
 */
export function renderSandboxBanner(buildInfo: BuildInfo | null): HTMLElement | null {
  const isSandbox = buildInfo?.env === SANDBOX_ENV;
  rememberChrome(isSandbox);
  document.documentElement.classList.toggle(CHROME_CLASS, isSandbox);
  if (!isSandbox || !buildInfo) return null;

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
 * surface anything to a user, and the banner is always optional. A failed fetch
 * leaves whatever the mirror reserved in place rather than tearing the layout
 * about on a flaky network.
 */
export async function initSandboxBanner(): Promise<void> {
  reserveSandboxChrome();
  try {
    renderSandboxBanner(await fetchBuildInfo());
  } catch {
    // build-info unavailable — no banner, never block the page.
  }
}
