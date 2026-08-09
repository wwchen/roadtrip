// A flag that only turns on if a condition is still true after a delay.
//
// The skeleton grid exists for slow fetches. Rendering it the instant a request
// starts makes a cache hit — which is most of them — flash a full skeleton table
// for one frame, and that reads as jank rather than as speed. The vanilla grid
// deferred the skeleton *render* with a `setTimeout` for exactly this reason
// (`SKELETON_RENDER_DELAY_MS`); this is that timer, as state.
//
// Lives here rather than in `lib/` because the availability grid is its only
// consumer. If a second one appears, move it — the same note the vanilla calendar
// popover carried.
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
