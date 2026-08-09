import { useEffect, useState, type ReactNode } from 'react';
import { useDrawerDrag } from './useDrawerDrag';
import './drawer.css';

/** Matches the slide-out transition in drawer.css. */
const EXIT_MS = 220;

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
}

/**
 * The drawer shell: a right-hand panel on desktop, a bottom sheet on mobile.
 *
 * Port of the DOM lifecycle in web/drawer/chrome.js. Content is the caller's
 * problem — this owns the frame, the animation, and dismissal.
 *
 * **The backdrop deliberately does not take pointer events.** MapLibre has to keep
 * receiving pan and zoom while the drawer is open, so the usual "click the scrim to
 * dismiss" is impossible; the equivalent is a map click that hits no pin, which
 * `useMapOverlays` already turns into `clearSelectedPoi`. The backdrop is a visual
 * scrim only.
 *
 * **Not LDS's `Modal`**, which is the obvious-looking fit and the wrong one: it is a
 * blocking dialog with a focus trap and an interactive scrim, and blocking the map
 * is precisely what this must not do.
 */
export function Drawer({ open, onClose, children }: DrawerProps) {
  // State, not refs: this component returns null until `mounted` flips, so a ref
  // read inside the drag hook's effect would be null on the first commit and the
  // effect would never re-run to catch the element appearing. Holding the nodes in
  // state gives the effect a dependency that actually changes when they mount.
  const [root, setRoot] = useState<HTMLElement | null>(null);
  const [handle, setHandle] = useState<HTMLElement | null>(null);
  const { full } = useDrawerDrag(root, handle, onClose);

  // Stay mounted through the exit transition, then leave. The vanilla version kept
  // one drawer element alive for the life of the page and toggled classes; here the
  // content is a React subtree, so it unmounts — but not before it has slid out.
  const [mounted, setMounted] = useState(open);
  const [entered, setEntered] = useState(false);

  useEffect(() => {
    if (open) {
      setMounted(true);
      // A frame later, so the transition has a from-state to animate out of.
      const raf = requestAnimationFrame(() => setEntered(true));
      return () => cancelAnimationFrame(raf);
    }
    setEntered(false);
    const timer = setTimeout(() => setMounted(false), EXIT_MS);
    return () => clearTimeout(timer);
  }, [open]);

  if (!mounted) return null;

  const state = [entered && 'rt-drawer--open', full && 'rt-drawer--full'].filter(Boolean).join(' ');

  return (
    <>
      <div className={`rt-drawer-backdrop ${entered ? 'rt-drawer-backdrop--open' : ''}`} />
      <aside
        ref={setRoot}
        className={`rt-drawer ${state}`}
        role="dialog"
        aria-label="Pin details"
      >
        {/* Grab bar: always drag-eligible, and sized for a thumb in CSS. */}
        <div className="rt-drawer-handle" ref={setHandle} aria-hidden="true" />
        <button type="button" className="rt-drawer-close" aria-label="Close" onClick={onClose}>
          ×
        </button>
        <div className="rt-drawer-content">{children}</div>
      </aside>
    </>
  );
}
