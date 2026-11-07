import {
  STAY_CHECK_FAN_OUT,
  STAY_CHECK_HEARTBEAT_MS,
  STAY_CHECK_PER_POI_TIMEOUT_MS,
  STAY_CHECK_STREAM_TIMEOUT_MS,
} from '@/server/config';
import { serverDb } from '@/server/db';
import { jsonBody } from '@/server/requestBody';
import { configuredEgress } from '@/server/service/availability/availabilityMatrixService';
import { answerStay, runStayCheck, type StayEvent } from '@/server/service/availability/stayCheckService';
import { parseStayCheckRequest } from '@/server/stayCheckRequest';

export const dynamic = 'force-dynamic';
export const maxDuration = 150;

const KEEP_ALIVE = ': keep-alive\n\n';
const EVENT_STREAM_HEADERS = {
  'content-type': 'text/event-stream; charset=utf-8',
  'cache-control': 'no-cache, no-transform',
  'x-accel-buffering': 'no',
};

function frame(event: StayEvent): string {
  return `event: ${event.event}\ndata: ${JSON.stringify(event.data)}\n\n`;
}

export async function POST(request: Request): Promise<Response> {
  const parsed = parseStayCheckRequest(await jsonBody(request));
  if (parsed instanceof Response) return parsed;
  const db = await serverDb();
  const egress = configuredEgress(STAY_CHECK_PER_POI_TIMEOUT_MS);
  const encoder = new TextEncoder();
  let open = true;
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const write = (text: string) => {
        if (open) controller.enqueue(encoder.encode(text));
      };
      const heartbeat = setInterval(() => write(KEEP_ALIVE), STAY_CHECK_HEARTBEAT_MS);
      try {
        await runStayCheck(
          parsed.poiIds,
          (poiId) => answerStay(db, poiId, parsed, egress, new Date()),
          { fanOut: STAY_CHECK_FAN_OUT, perPoiTimeoutMs: STAY_CHECK_PER_POI_TIMEOUT_MS, streamTimeoutMs: STAY_CHECK_STREAM_TIMEOUT_MS },
          (event) => write(frame(event)),
        );
      } finally {
        clearInterval(heartbeat);
        if (open) {
          open = false;
          controller.close();
        }
      }
    },
    cancel() {
      open = false;
    },
  });
  return new Response(stream, { headers: EVENT_STREAM_HEADERS });
}
