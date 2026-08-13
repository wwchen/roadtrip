// Display formatting shared by the shelf and timeline views.
import type { PlanningGrade, StayBookingState } from '@/api/planning-api';
import type { Hue } from '@ui';

const MONTH_SHORT_NAMES = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
] as const;

/** The separator used everywhere a list is flattened into one line. */
export const LIST_SEPARATOR = ' · ';

/** `[5, 6, 9]` → `"May, Jun, Sep"`. Out-of-range months are dropped. */
export function formatPrimeMonths(months: readonly number[]): string {
  return months
    .map((m) => MONTH_SHORT_NAMES[m - 1])
    .filter(Boolean)
    .join(', ');
}

/**
 * A grade is already an LDS hue name — the identity mapping is stated here so
 * the coincidence is a checked contract rather than an accident at call sites.
 */
export function gradeHue(grade: PlanningGrade): Hue {
  return grade;
}

const BOOKING_STATE_HUE: Record<StayBookingState, Hue> = {
  bookable: 'green',
  call: 'yellow',
  unlinked: 'gray',
};

export function bookingStateHue(state: StayBookingState): Hue {
  return BOOKING_STATE_HUE[state];
}

/** `405.2` → `"~$405 est"`. */
export function formatBudgetTotal(totalUsd: number): string {
  return `~$${Math.round(totalUsd)} est`;
}
