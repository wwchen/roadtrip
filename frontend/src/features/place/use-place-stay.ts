import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { streamStays, type StayAnswer } from '@/api/availability-api';
import type { CampsiteKind, StayCheckOutcome } from '@/api/generated/api-types';
import { queryKeys } from '@/queries/keys';

export interface PlaceStay {
  start: string;
  end: string;
  siteType: CampsiteKind | null;
  groupSize: number | null;
}

export type PlaceStayState =
  | { kind: 'checking' }
  | { kind: 'answered'; answer: StayAnswer }
  | { kind: 'unanswered'; outcome: StayCheckOutcome | null };

export type PlaceStayCheck = PlaceStayState & { retry: () => void };

const SERVER_DEFAULT_MAX_AGE_SECONDS: number | null = null;

const RETRY_AUTOMATICALLY = false;

interface StayRead {
  answer: StayAnswer | null;
  outcome: StayCheckOutcome | null;
}

const CHECKING: PlaceStayState = { kind: 'checking' };

function stateOf(read: StayRead | undefined, failed: boolean): PlaceStayState {
  if (read === undefined) return failed ? { kind: 'unanswered', outcome: null } : CHECKING;
  if (read.answer !== null) return { kind: 'answered', answer: read.answer };
  return { kind: 'unanswered', outcome: read.outcome };
}

export function usePlaceStay(poiId: number, stay: PlaceStay): PlaceStayCheck {
  const query = useQuery({
    queryKey: queryKeys.stays.forPoi(poiId, stay.start, stay.end, stay.siteType, stay.groupSize),
    retry: RETRY_AUTOMATICALLY,
    queryFn: async ({ signal }): Promise<StayRead> => {
      const request = { ...stay, poiIds: [poiId], maxAgeSeconds: SERVER_DEFAULT_MAX_AGE_SECONDS };
      const read: StayRead = { answer: null, outcome: null };
      for await (const event of streamStays(request, { signal })) {
        if (event.kind === 'done') read.outcome = event.done.outcome;
        else if (event.answer.poi_id === poiId) read.answer = event.answer;
      }
      return read;
    },
  });

  const { refetch } = query;
  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);

  return { ...stateOf(query.data, query.isError), retry };
}
