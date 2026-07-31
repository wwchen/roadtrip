// Sandbox build-info banner.
// Renders a fixed top bar showing env/sha/branch ONLY when the running build
// is a sandbox (env === "sandbox").  All other envs and all fetch failures
// produce no visible output — the page is never blocked.

const SANDBOX_ENV = 'sandbox';
const GITHUB_REPO_URL = 'https://github.com/wwchen/roadtrip';
const BUILD_INFO_URL = '/api/build-info';

/**
 * Render the sandbox banner into `doc.body`.
 * @param {{ env: string, sha: string, branch: string } | null} buildInfo
 * @param {Document} doc  - real document or test stub
 * @returns {Element|null}  the inserted bar element, or null when not sandbox
 */
export function renderSandboxBanner(buildInfo, doc = document) {
  if (!buildInfo || buildInfo.env !== SANDBOX_ENV) return null;

  const bar = doc.createElement('div');
  bar.setAttribute('class', 'sandbox-banner');
  bar.setAttribute('role', 'status');

  const envLabel = doc.createElement('span');
  envLabel.setAttribute('class', 'sandbox-banner__env');
  envLabel.textContent = 'SANDBOX';

  const sha = doc.createElement('a');
  sha.setAttribute('href', `${GITHUB_REPO_URL}/commit/${buildInfo.sha}`);
  sha.setAttribute('class', 'sandbox-banner__sha');
  sha.textContent = buildInfo.sha;

  const branch = doc.createElement('span');
  branch.setAttribute('class', 'sandbox-banner__branch');
  branch.textContent = buildInfo.branch;

  bar.append(envLabel);
  bar.append(sha);
  bar.append(branch);

  doc.body.append(bar);
  return bar;
}

/**
 * Fetch /api/build-info and render the banner if this is a sandbox build.
 * Errors are swallowed — banner is always optional.
 * @param {Document} doc
 * @param {typeof fetch} fetchFn
 */
export async function initSandboxBanner(doc = document, fetchFn = fetch) {
  try {
    const res = await fetchFn(BUILD_INFO_URL);
    if (!res.ok) return;
    renderSandboxBanner(await res.json(), doc);
  } catch {
    // build-info unavailable — no banner, never block the page
  }
}

// Auto-init when running in a real browser context.
if (typeof document !== 'undefined') {
  initSandboxBanner();
}
