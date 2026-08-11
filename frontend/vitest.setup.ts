import '@testing-library/jest-dom/vitest';
import { beforeAll } from 'vitest';

// jsdom (v30.x) does not provide localStorage on window by default when running
// under Node >=26.x, despite the environment: 'jsdom' config in vitest.
// This polyfill ensures the Storage API is available for all tests. On Node 22
// (as pinned in CI), this branch may not be exercised, but maintaining this
// safety net ensures consistent behavior across environments.
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
