import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppProviders } from './AppProviders';
import { initSandboxChrome } from './sandbox/sandbox-chrome';
import './shell.css';

/** The mount point every page shell provides. */
const ROOT_ELEMENT_ID = 'root';

/**
 * Mount a page into its shell.
 *
 * Each `main.tsx` was repeating the same getElementById / createRoot /
 * StrictMode block, which is also where the providers would get forgotten. One
 * helper means a page entry is a single call and every page is wrapped
 * identically.
 *
 * Silently does nothing when the mount point is absent, matching the previous
 * behaviour: the shells always carry it, and throwing during module evaluation
 * would take out the page with no useful signal.
 *
 * The sandbox chrome starts here, before the root renders, for the same reason
 * the providers live here: it has to be on every page, and the failure mode of a
 * page forgetting it is invisible (see `sandbox/sandbox-chrome.ts`). It renders
 * outside `#root` and never blocks, so it does not wait on the mount point.
 */
export function mountPage(node: ReactNode): void {
  initSandboxChrome();

  const el = document.getElementById(ROOT_ELEMENT_ID);
  if (!el) return;
  createRoot(el).render(
    <StrictMode>
      <AppProviders>{node}</AppProviders>
    </StrictMode>,
  );
}
