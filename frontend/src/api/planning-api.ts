// Trip-planning templates and their date-anchored timelines.
//
// DTOs are pinned against the backend's planning routes. The server serializes
// with explicitNulls=false, so every nullable field may be ABSENT from the
// payload — they are optional (`?:`) here rather than `| null` alone.
import { jsonGetOk, type RequestOptions } from './http';

const BASE = '/api/planning';

/** Readiness grade shared by the EV and booking assessments. */
export type PlanningGrade = 'green' | 'yellow' | 'red';

export interface TripBudget {
  campFeesUsd: number;
  chargingUsd: number;
  entryFeesUsd: number;
  totalUsd: number;
  notes?: string | null;
}

export interface TripTemplate {
  id: string;
  name: string;
  tagline: string;
  origin: string;
  terminus: string;
  days: number;
  totalMiles: number;
  avgDriveMinutesPerDay: number;
  longestDriveMinutes: number;
  /** Calendar months (1–12) when the route is at its best. */
  seasonPrimeMonths: number[];
  seasonNotes?: string | null;
  evGrade: PlanningGrade;
  evNotes?: string | null;
  maxSuperchargerGapMi?: number | null;
  hookupCriticalDays: number[];
  bookingGrade: PlanningGrade;
  bookingLeadTimeDays?: number | null;
  bookingNotes?: string | null;
  budget: TripBudget;
}

/** The GET /api/planning/templates envelope. */
export interface TripTemplateListResponse {
  templates: TripTemplate[];
}

export interface TimelineDrive {
  from: string;
  to: string;
  miles: number;
  minutes: number;
  superchargers: string[];
}

export type StayBookingState = 'bookable' | 'call' | 'unlinked';

export interface TimelineStay {
  name: string;
  kind: 'catalog' | 'manual';
  bookingState: StayBookingState;
  campgroundId?: number | null;
  bookingProvider?: string | null;
  phone?: string | null;
  url?: string | null;
  resolved: boolean;
}

export interface TimelineDay {
  day: number;
  /** Calendar date as `YYYY-MM-DD`. */
  date: string;
  title: string;
  evStatus: PlanningGrade;
  /** Absent on layover days with no driving. */
  drive?: TimelineDrive | null;
  /** Absent when the day has no overnight stay resolved. */
  stay?: TimelineStay | null;
  highlights: string[];
  sidequests: string[];
}

/** The GET /api/planning/templates/{id}/timeline envelope. */
export interface TripTimelineResponse {
  templateId: string;
  name: string;
  startDate: string;
  endDate: string;
  warnings: string[];
  days: TimelineDay[];
}

export function listTripTemplates(
  { signal }: RequestOptions = {},
): Promise<TripTemplateListResponse> {
  return jsonGetOk<TripTemplateListResponse>(`${BASE}/templates`, { signal });
}

/** `start` is a `YYYY-MM-DD` local calendar date (see lib/local-date.ts). */
export function getTripTimeline(
  templateId: string,
  start: string,
  { signal }: RequestOptions = {},
): Promise<TripTimelineResponse> {
  const qs = new URLSearchParams({ start });
  return jsonGetOk<TripTimelineResponse>(
    `${BASE}/templates/${encodeURIComponent(templateId)}/timeline?${qs}`,
    { signal },
  );
}
