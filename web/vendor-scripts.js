// Deferred loader for the third-party <script>/<link> tags that used to sit
// unconditionally in index.html.
//
// They moved here because the landing page does not need any of them: a first
// screen that asks for a route has no map to draw and no session to resume, so
// parsing the map engine before the user has asked for a map is work done on
// spec. Callers pull a dependency in at the moment they actually need it.
//
// Every loader is idempotent and dedupes concurrent callers — two components
// asking for the same URL share one request and one promise.

const pending = new Map();

function trackedLoad(url, createElement) {
  const existing = pending.get(url);
  if (existing) return existing;

  const promise = new Promise((resolve, reject) => {
    const el = createElement();
    el.addEventListener('load', () => resolve(), { once: true });
    el.addEventListener('error', () => {
      // Drop the rejected promise so a later attempt can retry rather than
      // replaying the failure forever — these are network loads and a second
      // try on a flaky connection is often the whole fix.
      pending.delete(url);
      reject(new Error(`Failed to load ${url}`));
    }, { once: true });
    document.head.appendChild(el);
  });

  pending.set(url, promise);
  return promise;
}

export function loadScript(src) {
  return trackedLoad(src, () => {
    const el = document.createElement('script');
    el.src = src;
    el.async = false;
    return el;
  });
}

export function loadStylesheet(href) {
  return trackedLoad(href, () => {
    const el = document.createElement('link');
    el.rel = 'stylesheet';
    el.href = href;
    return el;
  });
}
