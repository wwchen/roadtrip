import { expect, test } from 'vitest';

const ROUTE_MODULES: Record<string, () => Promise<{ dynamic?: string }>> = {
  '/api/campgrounds/search': () => import('./api/campgrounds/search/route'),
  '/api/health': () => import('./api/health/route'),
  '/campgrounds/[poiId]': () => import('./campgrounds/[poiId]/page'),
  '/search': () => import('./search/page'),
};
const FORCE_DYNAMIC = 'force-dynamic';

test.each(Object.keys(ROUTE_MODULES))('%s is force-dynamic', async (route) => {
  const loaded = await (ROUTE_MODULES[route] as () => Promise<{ dynamic?: string }>)();
  expect(loaded.dynamic).toBe(FORCE_DYNAMIC);
});
