import '@testing-library/jest-dom/vitest';
import { beforeAll } from 'vitest';

// jsdom 30 does not put localStorage on window under Node >=26, despite the
// jsdom environment. Not exercised on CI's pinned Node 22.
beforeAll(() => {
  if (!window.localStorage) {
    const store: Record<string, string> = {};
    Object.defineProperty(window, 'localStorage', {
      value: {
        getItem: (key: string) => store[key] || null,
        setItem: (key: string, value: string) => {
          store[key] = value;
        },
        removeItem: (key: string) => {
          delete store[key];
        },
        clear: () => {
          Object.keys(store).forEach((key) => {
            delete store[key];
          });
        },
        key: (index: number) => {
          const keys = Object.keys(store);
          return keys[index] || null;
        },
        get length() {
          return Object.keys(store).length;
        },
      } as Storage,
    });
  }
});

// jsdom ships no ResizeObserver, so a component that observes its own box cannot
// mount here at all. Nothing in this suite exercises layout, so a no-op is enough.
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  } as unknown as typeof ResizeObserver;
}
