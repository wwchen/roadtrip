import { expect, test, vi } from 'vitest';

const runStayCheck = vi.fn();
vi.mock('@/server/service/availability/stayCheckService', () => ({ runStayCheck, answerStay: vi.fn() }));
vi.mock('@/server/db', () => ({ serverDb: vi.fn().mockResolvedValue({}) }));
const { POST, maxDuration } = await import('./route');

const post = (body: unknown) =>
  POST(new Request('http://localhost/api/pois/availability/stays', { method: 'POST', body: JSON.stringify(body) }));

test('the route allows the stream deadline to finish', () => {
  expect(maxDuration).toBe(150);
});

test('a refused body is JSON 400 before any event', async () => {
  const response = await post({ poi_ids: [] });
  expect(response.status).toBe(400);
  expect((await response.json()).error).toBe('bad_request');
  expect(runStayCheck).not.toHaveBeenCalled();
});

test('answers stream as named SSE events, then done, and the stream closes', async () => {
  runStayCheck.mockImplementation(async (_ids, _answer, _limits, send) => {
    send({ event: 'answer', data: { poi_id: 7, outcome: 'answered' } });
    send({ event: 'done', data: { outcome: 'complete' } });
  });
  const response = await post({ poi_ids: [7], start_date: '2026-12-01', end_date: '2026-12-03' });
  expect(response.status).toBe(200);
  expect(response.headers.get('content-type')).toContain('text/event-stream');
  expect(await response.text()).toBe(
    'event: answer\ndata: {"poi_id":7,"outcome":"answered"}\n\nevent: done\ndata: {"outcome":"complete"}\n\n',
  );
  expect(runStayCheck.mock.calls[0]![0]).toEqual([7n]);
});
