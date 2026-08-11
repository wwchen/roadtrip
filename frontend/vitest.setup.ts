import '@testing-library/jest-dom/vitest';
import { beforeAll } from 'vitest';

// jsdom doesn't provide localStorage by default in all versions.
// Ensure it's available before tests run.
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
