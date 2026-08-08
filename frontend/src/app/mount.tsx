import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppProviders } from './AppProviders';

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
 */
export function mountPage(node: ReactNode): void {
  const el = document.getElementById(ROOT_ELEMENT_ID);
  if (!el) return;
  createRoot(el).render(
    <StrictMode>
      <AppProviders>{node}</AppProviders>
    </StrictMode>,
  );
}
