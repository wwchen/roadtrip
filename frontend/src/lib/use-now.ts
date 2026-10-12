// The wall clock as React state, for text that reads it ("checked 4m ago", a
// cooldown): the component re-renders on each tick instead of freezing the
// value it read on its last render.
import { useEffect, useState } from 'react';

/** `Date.now()`, re-read every [intervalMs] while [active]. */
export function useNow(intervalMs: number, active = true): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return undefined;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs, active]);
  return now;
}
