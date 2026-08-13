// Server state for the plan page: the template shelf and one selected
// template's date-anchored timeline. Read-only — there are no mutations, so no
// invalidation wiring is needed yet.
import { useQuery } from '@tanstack/react-query';
import {
  getTripTimeline,
  listTripTemplates,
  type TripTemplate,
  type TripTimelineResponse,
} from '@/api/planning-api';
import { queryKeys } from '@/queries/keys';

export interface TripTemplatesResult {
  templates: TripTemplate[];
  isPending: boolean;
  error: unknown;
  refetch: () => void;
}

export function useTripTemplates(): TripTemplatesResult {
  const { data, isPending, error, refetch } = useQuery({
    queryKey: queryKeys.planning.templates(),
    queryFn: ({ signal }) => listTripTemplates({ signal }),
  });
  return { templates: data?.templates ?? [], isPending, error, refetch };
}

export interface TripTimelineResult {
  timeline: TripTimelineResponse | undefined;
  isPending: boolean;
  error: unknown;
  refetch: () => void;
}

/**
 * The timeline for `templateId` starting on `start` (`YYYY-MM-DD`).
 *
 * `enabled: false` until the caller has a valid start date — the backend
 * answers 400 for a bad/missing start, and an in-progress date input should not
 * generate doomed requests.
 */
export function useTripTimeline(
  templateId: string,
  start: string,
  enabled: boolean,
): TripTimelineResult {
  const { data, isPending, error, refetch } = useQuery({
    queryKey: queryKeys.planning.timeline(templateId, start),
    queryFn: ({ signal }) => getTripTimeline(templateId, start, { signal }),
    enabled,
  });
  return { timeline: data, isPending, error, refetch };
}
