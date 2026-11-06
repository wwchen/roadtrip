import { after } from 'next/server';
import { fetchEgress } from '@/server/client/availabilityEgress';
import {
  BAD_POI_ID,
  STATUS_BAD_REQUEST,
  STATUS_NOT_FOUND,
  STATUS_NOT_IMPLEMENTED,
  availabilityError,
  parseAvailabilityPoiId,
  parseAvailabilityQuery,
} from '@/server/availabilityRequest';
import { AVAILABILITY_EGRESS_TIMEOUT_MS, config } from '@/server/config';
import { serverDb, writerDb } from '@/server/db';
import { AvailabilityTargetError, currentWeekSettings } from '@/server/service/availability/availabilityMatrixService';
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
  const refresh = config.availabilityRefresh;
  try {
    const plan = await planRefresh(await serverDb(), poiId, query.startDate, query.endDate, currentWeekSettings(), refresh !== null);
    if (plan.kind === 'disabled') return availabilityError(STATUS_NOT_IMPLEMENTED, REFRESH_DISABLED);
    if (plan.kind === 'unsupported') return availabilityError(STATUS_NOT_IMPLEMENTED, UNKNOWN_CAMPGROUND);
    if (plan.kind === 'fetch' && refresh !== null) {
      const endpoint = { url: refresh.egressUrl, access: refresh.egressAccess };
      after(async () => {
        await runRefresh(plan, { db: await writerDb(), fetch: (body) => fetchEgress(endpoint, body, AVAILABILITY_EGRESS_TIMEOUT_MS) });
      });
    }
    return new Response(null, { status: STATUS_ACCEPTED });
  } catch (error) {
    if (error instanceof BadDateWindowError) return availabilityError(STATUS_BAD_REQUEST, error.code, error.extra);
    if (error instanceof AvailabilityTargetError) return availabilityError(STATUS_NOT_FOUND, error.code);
    throw error;
  }
}
