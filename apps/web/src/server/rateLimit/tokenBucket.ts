const MS_PER_MINUTE = 60_000;
const IDLE_EVICT_MS = 5 * MS_PER_MINUTE;
const SWEEP_INTERVAL_MS = MS_PER_MINUTE;
const MAX_CALLERS = 10_000;
const SINGLE_CALL = 1;

export interface CallerLimit {
  allow(caller: string, cost?: number): boolean;
}

interface Bucket {
  tokens: number;
  refilledAt: number;
}

export function tokenBucket(perMinute: number, now: () => number = Date.now): CallerLimit {
  const buckets = new Map<string, Bucket>();
  const refillPerMs = perMinute / MS_PER_MINUTE;
  let sweptAt = now();

  function sweep(at: number): void {
    if (buckets.size > MAX_CALLERS) {
      buckets.clear();
      sweptAt = at;
      return;
    }
    if (at - sweptAt < SWEEP_INTERVAL_MS) return;
    sweptAt = at;
    for (const [caller, bucket] of buckets) {
      if (at - bucket.refilledAt >= IDLE_EVICT_MS) buckets.delete(caller);
    }
  }

  return {
    allow(caller, cost = SINGLE_CALL) {
      const at = now();
      sweep(at);
      const previous = buckets.get(caller) ?? { tokens: perMinute, refilledAt: at };
      const tokens = Math.min(perMinute, previous.tokens + (at - previous.refilledAt) * refillPerMs);
      const granted = cost >= SINGLE_CALL && tokens >= cost;
      buckets.set(caller, { tokens: granted ? tokens - cost : tokens, refilledAt: at });
      return granted;
    },
  };
}
