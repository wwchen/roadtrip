import { useEffect, useState, type ReactNode } from 'react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { ToastProvider } from '@ui';
import { createTestQueryClient } from './query-client';

export type StoryRoute = (url: URL, init: RequestInit | undefined) => Response | Promise<Response>;

export type StoryRoutes = Readonly<Record<string, StoryRoute>>;

const HTTP_OK = 200;
const HTTP_NOT_FOUND = 404;
const JSON_HEADERS = { 'Content-Type': 'application/json' };
export const STORY_UNROUTED_CODE = 'story_unrouted';

export function storyJson(body: unknown, status = HTTP_OK): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

export function storyPending(_url: URL, init: RequestInit | undefined): Promise<Response> {
  return new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
  });
}

export const answer =
  (body: unknown, status = HTTP_OK): StoryRoute =>
  () =>
    storyJson(body, status);

function requestUrl(input: RequestInfo | URL): URL {
  const raw = input instanceof Request ? input.url : String(input);
  return new URL(raw, window.location.origin);
}

function storyFetch(routes: StoryRoutes): typeof window.fetch {
  return async (input, init) => {
    const url = requestUrl(input);
    const route = routes[url.pathname];
    if (route) return route(url, init);
    const method = init?.method ?? 'GET';
    console.warn(`story fetch has no route for ${method} ${url.pathname}`);
    return storyJson({ error: STORY_UNROUTED_CODE, detail: `${method} ${url.pathname}` }, HTTP_NOT_FOUND);
  };
}

export interface StoryApiProps {
  routes: StoryRoutes;
  seed?: (client: QueryClient) => void;
  children: ReactNode;
}

export function StoryApi({ routes, seed, children }: StoryApiProps) {
  const [client] = useState(() => {
    const created = createTestQueryClient();
    seed?.(created);
    return created;
  });
  const [installed, setInstalled] = useState(false);

  useEffect(() => {
    const original = window.fetch;
    window.fetch = storyFetch(routes);
    setInstalled(true);
    return () => {
      window.fetch = original;
    };
  }, [routes]);

  if (!installed) return null;
  return (
    <QueryClientProvider client={client}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}

export function isolatedDocs(heightPx: number) {
  return { story: { inline: false, height: `${heightPx}px` } };
}
