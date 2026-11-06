import { expect, test } from 'vitest';
import { RoutingUnavailableError } from '../client/mapbox';
import { createRoutePlanner } from './routePlanService';

const DIRECTIONS = { coordinates: [[-120, 39], [-119, 39.5]], distanceMeters: 1000, durationSeconds: 60, legs: [{ distanceMeters: 1000, durationSeconds: 60 }] };
const CORRIDOR = '{"type":"Polygon","coordinates":[[[-120,38.9],[-119,38.9],[-119,39.6],[-120,38.9]]]}';

function planner() {
  const calls = { directions: 0, corridor: [] as number[] };
  const plan = createRoutePlanner({
    directions: async () => {
      calls.directions += 1;
      return DIRECTIONS;
    },
    corridor: async (_line, meters) => {
      calls.corridor.push(meters);
      return CORRIDOR;
    },
  });
  return { plan, calls };
}

test('a planned route is the line feature with distance, duration, legs and waypoints, then the corridor', async () => {
  const { plan, calls } = planner();
  const result = await plan.plan([[-120, 39], [-119, 39.5]], 2);
  expect(result).toEqual({
    status: 'planned',
    body: {
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          geometry: { type: 'LineString', coordinates: DIRECTIONS.coordinates },
          properties: { distance_m: 1000, duration_s: 60, legs: [{ distance_m: 1000, duration_s: 60 }], waypoints: [[-120, 39], [-119, 39.5]] },
        },
        { type: 'Feature', geometry: JSON.parse(CORRIDOR), properties: { role: 'corridor', radius_miles: 2 } },
      ],
    },
  });
  expect(calls.corridor).toEqual([2 * 1609.34]);
});

test('a repeated plan is served from the in-process cache without a second upstream call', async () => {
  const { plan, calls } = planner();
  await plan.plan([[-120, 39], [-119, 39.5]], null);
  await plan.plan([[-120.0000001, 39], [-119, 39.5]], null);
  expect(calls.directions).toBe(1);
});

test('identical adjacent waypoints are duplicate_adjacent without an upstream call', async () => {
  const { plan, calls } = planner();
  expect(await plan.plan([[-120, 39], [-120, 39], [-119, 39.5]], null)).toEqual({ status: 'duplicate_adjacent', index: 1 });
  expect(calls.directions).toBe(0);
});

test('an upstream failure is routing_unavailable with its message', async () => {
  const plan = createRoutePlanner({
    directions: async () => {
      throw new RoutingUnavailableError('Mapbox HTTP 500');
    },
    corridor: async () => CORRIDOR,
  });
  expect(await plan.plan([[0, 0], [1, 1]], null)).toEqual({ status: 'routing_unavailable', detail: 'Mapbox HTTP 500' });
});
