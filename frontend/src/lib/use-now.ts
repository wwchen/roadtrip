// The wall clock as React state, for what reads it: text that ages ("checked
// 4m ago") re-renders on a tick instead of freezing the value it read on its
// last render, and a deadline (a cooldown) re-renders once, when it passes.
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

/**
 * True until [deadline] (epoch ms) passes, then false; null is no deadline.
 *
 * One timer, set for the deadline itself, rather than a tick: it re-renders the
 * caller exactly once, when the answer changes, and holds nothing once it has.
 */
export function useBefore(deadline: number | null): boolean {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (deadline === null) return undefined;
    const wait = deadline - Date.now();
    if (wait <= 0) {
      setNow(Date.now());
      return undefined;
    }
    const timer = setTimeout(() => setNow(Date.now()), wait);
    return () => clearTimeout(timer);
  }, [deadline]);
  return deadline !== null && now < deadline;
}
