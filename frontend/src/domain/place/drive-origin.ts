import { placeCopy } from '@/lib/strings';

const MINUTES_PER_HOUR = 60;

export interface DriveOrigin {
  label: string;
  minutes: number | null;
}

function durationLabel(minutes: number): string {
  if (minutes < MINUTES_PER_HOUR) return `${minutes}m`;
  return `${Math.floor(minutes / MINUTES_PER_HOUR)}h ${minutes % MINUTES_PER_HOUR}m`;
}

export function driveTimeLabel(origin: DriveOrigin): string {
  if (origin.minutes === null) return origin.label;
  return placeCopy.driveFrom(durationLabel(origin.minutes), origin.label);
}
