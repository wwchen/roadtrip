import '@testing-library/jest-dom/vitest';

// jsdom ships no ResizeObserver, so a component that observes its own box cannot
// mount here at all. Nothing in this suite exercises layout, so a no-op is enough.
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  } as unknown as typeof ResizeObserver;
}
