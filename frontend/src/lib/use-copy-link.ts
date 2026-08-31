// The "Copied" flash every share control shows, in one place.
//
// Two surfaces copy a share link — the topbar's trip button and the POI page's
// action row — and neither may import the other (one is a feature, one is a
// domain component). The transient state is identical in both, so it lives here
// rather than being written twice.
import { useCallback, useEffect, useRef, useState } from 'react';
import { COPIED_STATE_MS, copyShareUrl } from './share-links';

export interface CopyLink {
  /** True for `COPIED_STATE_MS` after a copy that actually reached the clipboard. */
  copied: boolean;
  copy: (url: string) => void;
}

/**
 * Copy a URL and flash the confirmation.
 *
 * The flash follows the *result*, not the click: `copyShareUrl` falls back to a
 * textarea and still reports failure, and a button that says "Copied" over an
 * empty clipboard is worse than one that does nothing visible.
 */
export function useCopyLink(): CopyLink {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  // A copy that resolves after the control unmounts must not set state.
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      clearTimeout(timer.current);
    };
  }, []);

  const copy = useCallback((url: string) => {
    void copyShareUrl(url).then((ok) => {
      if (!ok || !alive.current) return;
      setCopied(true);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => setCopied(false), COPIED_STATE_MS);
    });
  }, []);

  return { copied, copy };
}
