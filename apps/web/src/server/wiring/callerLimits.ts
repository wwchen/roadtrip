import { config } from '../config';
import { tokenBucket, type CallerLimit } from '../rateLimit/tokenBucket';

export interface CallerLimits {
  refresh: CallerLimit;
  stayCheckPois: CallerLimit;
  mapbox: CallerLimit;
}

let limits: CallerLimits | null = null;

export function callerLimits(): CallerLimits {
  if (limits === null) {
    const perMinute = config.callerLimits;
    limits = {
      refresh: tokenBucket(perMinute.refreshPerMinute),
      stayCheckPois: tokenBucket(perMinute.stayPoisPerMinute),
      mapbox: tokenBucket(perMinute.mapboxPerMinute),
    };
  }
  return limits;
}
