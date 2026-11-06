import { expect, test } from 'vitest';
import { createMapboxClient, GeocodeUnavailableError, RoutingUnavailableError } from './mapbox';

const TOKEN = 'pk.secret-token';
const ROUTE = {
  code: 'Ok',
  routes: [{ geometry: { type: 'LineString', coordinates: [[-120, 39], [-119, 39.5]] }, distance: 1000, duration: 60, legs: [{ distance: 1000, duration: 60 }] }],
};

function stubFetch(status: number, body: unknown) {
  const urls: string[] = [];
  const fetch = async (url: string | URL | Request) => {
    urls.push(String(url));
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
  };
  return { fetch: fetch as typeof globalThis.fetch, urls };
}

test('directions sends the token and coords and returns the geometry, legs, distance and duration', async () => {
  const { fetch, urls } = stubFetch(200, ROUTE);
  const directions = await createMapboxClient({ token: TOKEN, fetch }).directions([[-120, 39], [-119, 39.5]]);
  expect(directions).toEqual({ coordinates: [[-120, 39], [-119, 39.5]], distanceMeters: 1000, durationSeconds: 60, legs: [{ distanceMeters: 1000, durationSeconds: 60 }] });
  const url = new URL(urls[0] ?? '');
  expect(url.pathname).toBe('/directions/v5/mapbox/driving/-120,39;-119,39.5');
  expect(url.searchParams.get('access_token')).toBe(TOKEN);
  expect(url.searchParams.get('geometries')).toBe('geojson');
  expect(url.searchParams.get('overview')).toBe('full');
});

test.each([
  ['a non-2xx', 500, { message: 'boom' }],
  ['a code other than Ok', 200, { code: 'NoRoute', message: 'no route' }],
  ['no routes', 200, { code: 'Ok', routes: [] }],
  ['a body that is not JSON', 200, 'nope'],
])('%s is RoutingUnavailableError, never naming the token', async (_name, status, body) => {
  const { fetch } = stubFetch(status, body);
  await expect(createMapboxClient({ token: TOKEN, fetch }).directions([[0, 0], [1, 1]])).rejects.toSatisfy(
    (error: Error) => error instanceof RoutingUnavailableError && !error.message.includes(TOKEN),
  );
});

test('geocode maps features, keeps a bbox only when it is four finite numbers, and defaults place_type', async () => {
  const { fetch, urls } = stubFetch(200, {
    features: [
      { id: 'place.1', place_name: 'Dallas, Texas', center: [-96.8, 32.8], place_type: ['place'], bbox: [-97, 32, -96, 33] },
      { id: 'addr.2', place_name: '1 Main St', center: [-96.7, 32.7], bbox: [1, 2, 3] },
      { id: 'bad', place_name: 'No centre' },
    ],
  });
  const results = await createMapboxClient({ token: TOKEN, fetch }).geocode('dallas', { autocomplete: false, proximity: '-97,32', limit: 3 });
  expect(results).toEqual([
    { id: 'place.1', place_name: 'Dallas, Texas', place_type: 'place', lng: -96.8, lat: 32.8, bbox: [-97, 32, -96, 33] },
    { id: 'addr.2', place_name: '1 Main St', place_type: 'place', lng: -96.7, lat: 32.7 },
  ]);
  const url = new URL(urls[0] ?? '');
  expect(url.pathname).toBe('/geocoding/v5/mapbox.places/dallas.json');
  expect(Object.fromEntries(url.searchParams)).toEqual({ access_token: TOKEN, autocomplete: 'false', limit: '3', proximity: '-97,32' });
});

test('a geocode upstream failure is GeocodeUnavailableError', async () => {
  const { fetch } = stubFetch(401, 'no');
  await expect(createMapboxClient({ token: TOKEN, fetch }).geocode('x', { autocomplete: true, limit: 5 })).rejects.toBeInstanceOf(GeocodeUnavailableError);
});

test('a Mapbox call that stalls is abandoned at the timeout as unavailable', { timeout: 2000 }, async () => {
  const stalled = ((_url: unknown, init?: RequestInit) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(init.signal?.reason));
    })) as typeof globalThis.fetch;
  const client = createMapboxClient({ token: TOKEN, fetch: stalled, timeoutMs: 20 });
  await expect(client.directions([[0, 0], [1, 1]])).rejects.toBeInstanceOf(RoutingUnavailableError);
  await expect(client.geocode('x', { autocomplete: true, limit: 5 })).rejects.toBeInstanceOf(GeocodeUnavailableError);
});
