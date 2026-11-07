import { expect, test } from 'vitest';
import { parseStayCheckRequest } from './stayCheckRequest';

const VALID = { poi_ids: [7, 8], start_date: '2026-12-01', end_date: '2026-12-03' };

async function refusal(body: unknown): Promise<string> {
  const result = parseStayCheckRequest(body);
  if (!(result instanceof Response)) throw new Error('expected a refusal');
  expect(result.status).toBe(400);
  return (await result.json()).error;
}

test('a valid body parses with defaults', () => {
  expect(parseStayCheckRequest(VALID)).toEqual({ poiIds: [7n, 8n], startDate: '2026-12-01', endDate: '2026-12-03', siteTypes: [], groupSize: null, maxAgeSeconds: 7200 });
});

test('max_age_seconds is clamped into 60 s to 2 h, never refused', () => {
  expect(parseStayCheckRequest({ ...VALID, max_age_seconds: 5 })).toMatchObject({ maxAgeSeconds: 60 });
  expect(parseStayCheckRequest({ ...VALID, max_age_seconds: 999_999 })).toMatchObject({ maxAgeSeconds: 7200 });
  expect(parseStayCheckRequest({ ...VALID, max_age_seconds: 300 })).toMatchObject({ maxAgeSeconds: 300 });
});

test.each([
  [null, 'bad_request'],
  [{ ...VALID, poi_ids: [] }, 'bad_request'],
  [{ ...VALID, poi_ids: ['7'] }, 'bad_request'],
  [{ ...VALID, poi_ids: Array.from({ length: 51 }, (_, i) => i + 1) }, 'too_many_pois'],
  [{ ...VALID, group_size: 0 }, 'bad_group_size'],
  [{ ...VALID, start_date: 'soon' }, 'bad_date_window'],
  [{ ...VALID, end_date: undefined }, 'bad_date_window'],
  [{ ...VALID, end_date: '2026-12-01' }, 'end_before_start'],
  [{ ...VALID, site_type: ['yurt'] }, 'bad_request'],
])('%j is refused with %s', async (body, code) => {
  expect(await refusal(body)).toBe(code);
});
