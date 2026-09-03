import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppProviders } from './AppProviders';
import { initSandboxChrome } from './sandbox/sandbox-chrome';
import { installIconSprite } from '@ui/icon-sprite';
import { useThemeStore } from '@/stores/themeStore';
import './shell.css';

/** The mount point every page shell provides. */
const ROOT_ELEMENT_ID = 'root';

/** One listener per document, however many times `mountPage` is called. */
let rejectionsLogged = false;

/**
 * Log promise rejections nothing awaited.
 *
 * Logged, never toasted: a rejection here has no user-facing story — the query
 * layer already surfaces the failures a user can act on — and a toast for every
 * aborted fetch would be noise over the page that is still working. The console
 * is where the shipped-bug trail lives (`useViewportPois` logs the same way).
 */
function logUnhandledRejections(): void {
  if (rejectionsLogged) return;
  rejectionsLogged = true;
  window.addEventListener('unhandledrejection', (event) => {
    console.error('unhandled promise rejection:', event.reason);
  });
}

/**
 * Mount a page into its shell.
 *
 * Each `main.tsx` was repeating the same getElementById / createRoot /
 * StrictMode block, which is also where the providers would get forgotten. One
 * helper means a page entry is a single call and every page is wrapped
 * identically.
 *
 * A missing mount point still does not throw — that would take out the sandbox
 * chrome and the theme with it — but it is logged rather than swallowed: the
 * shells always carry `#root`, so its absence is a broken shell and the blank
 * page is otherwise indistinguishable from a page that mounted nothing.
 *
 * The sandbox chrome starts here, before the root renders, for the same reason
 * the providers live here: it has to be on every page, and the failure mode of a
 * page forgetting it is invisible (see `sandbox/sandbox-chrome.ts`). It renders
 * outside `#root` and never blocks, so it does not wait on the mount point.
 *
 * The icon sprite installs here for that same reason, and before the mount point
 * is looked up: it belongs to the document rather than to any page, and a glyph
 * that renders before it lands keeps the href it was given.
 */
export function mountPage(node: ReactNode): void {
  logUnhandledRejections();
  installIconSprite();
  initSandboxChrome();
  useThemeStore.getState().initTheme();

  const el = document.getElementById(ROOT_ELEMENT_ID);
  if (!el) {
    console.error(`page shell has no #${ROOT_ELEMENT_ID} element; nothing was mounted`);
    return;
  }
  createRoot(el).render(
    <StrictMode>
      <AppProviders>{node}</AppProviders>
    </StrictMode>,
  );
}
