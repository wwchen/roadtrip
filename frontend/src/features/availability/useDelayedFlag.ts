// A flag that only turns on if a condition is still true after a delay.
//
// The skeleton grid exists for slow fetches. Rendering it the instant a request
// starts makes a cache hit — which is most of them — flash a full skeleton table
// for one frame, which reads as jank rather than speed.
import { useEffect, useState } from 'react';

/**
 * True once `active` has been continuously true for `delayMs`.
 *
 * Turns off immediately when `active` does, with no trailing timer: a fetch that
 * finishes at 149ms must not flash a skeleton at 150.
 */
export function useDelayedFlag(active: boolean, delayMs: number): boolean {
  const [raised, setRaised] = useState(false);

  useEffect(() => {
    if (!active) {
      setRaised(false);
      return;
    }
    const timer = setTimeout(() => setRaised(true), delayMs);
    return () => clearTimeout(timer);
  }, [active, delayMs]);

  return raised;
}
