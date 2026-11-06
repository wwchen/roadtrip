import { after } from 'next/server';
import {
  BAD_POI_ID,
  STATUS_BAD_REQUEST,
  STATUS_NOT_FOUND,
  STATUS_NOT_IMPLEMENTED,
  availabilityError,
  parseAvailabilityPoiId,
  parseAvailabilityQuery,
} from '@/server/availabilityRequest';
import { AVAILABILITY_EGRESS_TIMEOUT_MS } from '@/server/config';
import { serverDb } from '@/server/db';
import { AvailabilityTargetError, configuredEgress, currentWeekSettings } from '@/server/service/availability/availabilityMatrixService';
import { planRefresh, runRefresh } from '@/server/service/availability/availabilityRefreshService';
import { BadDateWindowError } from '@/server/service/availability/availabilityWindow';

export const dynamic = 'force-dynamic';
export const maxDuration = 60;

const STATUS_ACCEPTED = 202;
const REFRESH_DISABLED = 'refresh_disabled';
const UNKNOWN_CAMPGROUND = 'unknown_campground';

export async function POST(request: Request, context: { params: Promise<{ id: string }> }): Promise<Response> {
  const poiId = parseAvailabilityPoiId((await context.params).id);
  if (poiId === null) return availabilityError(STATUS_BAD_REQUEST, BAD_POI_ID);
  const query = parseAvailabilityQuery(new URL(request.url));
  if (query instanceof Response) return query;
  const egress = configuredEgress(AVAILABILITY_EGRESS_TIMEOUT_MS);
  try {
    const plan = await planRefresh(await serverDb(), poiId, query.startDate, query.endDate, currentWeekSettings(), egress !== null);
    if (plan.kind === 'disabled') return availabilityError(STATUS_NOT_IMPLEMENTED, REFRESH_DISABLED);
    if (plan.kind === 'unsupported') return availabilityError(STATUS_NOT_IMPLEMENTED, UNKNOWN_CAMPGROUND);
    if (plan.kind === 'fetch' && egress !== null) {
      after(async () => {
        await runRefresh(plan, egress);
      });
    }
    return new Response(null, { status: STATUS_ACCEPTED });
  } catch (error) {
    if (error instanceof BadDateWindowError) return availabilityError(STATUS_BAD_REQUEST, error.code, error.extra);
    if (error instanceof AvailabilityTargetError) return availabilityError(STATUS_NOT_FOUND, error.code);
    throw error;
  }
}
