# Light / dark theme toggle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give signed-in users a saved Light / Dark / System preference in Settings → Profile, and make anonymous visitors follow their OS, with no flash of the wrong theme on any page.

**Architecture:** One preference (`ThemeChoice`) resolves to one mode (`ThemeMode`), which is applied as a `mode-dark` class on `<html>`. A pure module owns the types and storage, a Zustand store owns the DOM side effects and the `matchMedia` subscription, and an inline script in each page shell applies the mirrored value before first paint. The map re-styles on mode change, which also swaps the basemap for users who never picked one.

**Tech Stack:** React 19 + TypeScript, Vite, Zustand, TanStack Query, Vitest + React Testing Library, LDS (`@lew-ds/lds-react`) via `@ui`, MapLibre GL, Kotlin/Ktor + jOOQ + Flyway backend.

**Spec:** `docs/superpowers/specs/2026-08-11-theme-toggle-design.md`
**Issue:** [#625](https://github.com/wwchen/roadtrip/issues/625)

## Global Constraints

- All frontend commands run from `frontend/`. Frontend tests: `npm test` (Vitest). Type check: `npm run typecheck`. Boundary lint: `npm run lint`.
- Backend tests run from the repo root: `./gradlew :backend:test`.
- **No inline magic constants.** Every literal — storage keys, the three theme values, the two `theme-color` hexes, basemap keys — is a named `const`.
- **Layering is `routes -> service -> repo`.** No SQL outside a repo; no business logic in a route.
- **Reuse `@ui` before writing markup or CSS.** `SegmentedControl` and `Select` already exist. This feature adds no new CSS file.
- The three legal choices are exactly `'light' | 'dark' | 'system'`, wire-identical on both sides.
- The storage key for the theme mirror is `'rt-theme'`. The existing basemap key is `'basemap'` (`BASEMAP_STORAGE_KEY`) and must not change.
- The dark `theme-color` is `#101215` (zion's `--surface-page` under `.mode-dark`); light is `#FFFFFF`.
- Every `localStorage` access is wrapped in `try/catch` — Safari private mode throws rather than returning null. Follow the shape already in `src/features/map/basemaps.ts:81-98`.
- Commit after every task. Conventional-commit prefixes, matching repo history (`feat(frontend):`, `feat(backend):`, `docs:`).

---

## File Structure

**New:**
- `frontend/src/lib/theme.ts` — pure types, resolution, and mirror storage
- `frontend/src/lib/theme.test.ts`
- `frontend/src/stores/themeStore.ts` — Zustand store; owns DOM application and `matchMedia`
- `frontend/src/stores/themeStore.test.ts`
- `frontend/src/features/account/AppearanceField.tsx` — the labelled `SegmentedControl`
- `frontend/src/features/account/AppearanceField.stories.tsx`
- `frontend/src/test/page-shells.test.ts` — asserts all three shells carry the boot script
- `backend/src/main/resources/db/migration/V51__user_theme.sql`

**Modified:**
- `frontend/index.html`, `frontend/watches.html`, `frontend/availability.html` — inline boot script
- `frontend/src/api/account-api.ts` — `theme` on `Profile` and the profile update payload
- `frontend/src/features/account/ProfilePanel.tsx` — renders `AppearanceField`, extends `ProfileValues`
- `frontend/src/features/account/SettingsModal.tsx` — revert-on-close
- `frontend/src/api/auth-api.ts` — clear the mirror on sign-out
- `frontend/src/features/account/useSettings.ts` — push the loaded choice into the store
- `frontend/src/features/map/basemaps.ts` — `DARK_BASEMAP`, `storedBasemapKey`, mode-aware `initialBasemapKey`
- `frontend/src/features/map/BasemapPicker.tsx` — the Auto option
- `frontend/src/features/map/MapProvider.tsx` — re-style on mode change
- `frontend/src/app/mount.tsx` — call `initTheme()`
- `backend/src/main/kotlin/ca/floo/roadtrip/model/api/SettingsResponseDto.kt` — `theme` on `ProfileDto`
- `backend/src/main/kotlin/ca/floo/roadtrip/model/api/UpdateProfileRequest.kt` — `theme`
- `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/auth/User.kt` — `theme`
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/UserRepo.kt` — persist and read `theme`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/settings/UserSettingsService.kt` — validate `theme`
- `docs/frontend-components.md` — a short section on the theme contract

---

## Task 1: The pure theme module

**Files:**
- Create: `frontend/src/lib/theme.ts`
- Test: `frontend/src/lib/theme.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `type ThemeChoice = 'light' | 'dark' | 'system'`; `type ThemeMode = 'light' | 'dark'`; `THEME_CHOICES: readonly ThemeChoice[]`; `THEME_STORAGE_KEY: 'rt-theme'`; `DARK_MODE_CLASS: 'mode-dark'`; `DEFAULT_THEME_CHOICE: 'system'`; `resolveMode(choice: ThemeChoice, prefersDark: boolean): ThemeMode`; `coerceChoice(value: unknown): ThemeChoice`; `readStoredMode(): ThemeMode | null`; `writeStoredMode(mode: ThemeMode): void`; `clearStoredMode(): void`; `THEME_COLORS: Readonly<Record<ThemeMode, string>>`.

**Note on what is mirrored:** the mirror stores the *resolved mode*, not the choice. The boot script must stay a single string comparison — re-deriving `system` there would mean duplicating `resolveMode` into HTML.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/theme.test.ts`:

```ts
import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  DEFAULT_THEME_CHOICE,
  THEME_COLORS,
  THEME_STORAGE_KEY,
  clearStoredMode,
  coerceChoice,
  readStoredMode,
  resolveMode,
  writeStoredMode,
} from './theme';

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe('resolveMode', () => {
  test.each([
    ['light', false, 'light'],
    ['light', true, 'light'],
    ['dark', false, 'dark'],
    ['dark', true, 'dark'],
    ['system', false, 'light'],
    ['system', true, 'dark'],
  ] as const)('%s with prefersDark=%s resolves to %s', (choice, prefersDark, expected) => {
    expect(resolveMode(choice, prefersDark)).toBe(expected);
  });
});

describe('coerceChoice', () => {
  test('passes the three legal values through', () => {
    expect(coerceChoice('light')).toBe('light');
    expect(coerceChoice('dark')).toBe('dark');
    expect(coerceChoice('system')).toBe('system');
  });

  // An older client or a hand-edited row must not throw.
  test.each([null, undefined, '', 'sepia', 42, {}])('coerces %s to the default', (value) => {
    expect(coerceChoice(value)).toBe(DEFAULT_THEME_CHOICE);
  });
});

describe('the mirror', () => {
  test('round-trips a mode', () => {
    writeStoredMode('dark');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(readStoredMode()).toBe('dark');
  });

  test('reads null when nothing is stored', () => {
    expect(readStoredMode()).toBeNull();
  });

  test('reads null for a value that is not a mode', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'system');
    expect(readStoredMode()).toBeNull();
  });

  test('clears', () => {
    writeStoredMode('dark');
    clearStoredMode();
    expect(readStoredMode()).toBeNull();
  });

  // Safari private mode throws on access rather than returning null.
  test('survives localStorage throwing', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(readStoredMode()).toBeNull();
    expect(() => writeStoredMode('dark')).not.toThrow();
  });
});

test('every mode has a theme-color', () => {
  expect(THEME_COLORS.light).toBe('#FFFFFF');
  expect(THEME_COLORS.dark).toBe('#101215');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/lib/theme.test.ts`
Expected: FAIL — `Failed to resolve import "./theme"`.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/lib/theme.ts`:

```ts
// The theme preference, as data.
//
// Pure: no DOM, no React, no side effects beyond the mirror below. The store
// (`@/stores/themeStore`) owns everything that touches the document; this module
// owns what the values *mean*, so both the store and the tests can reason about
// resolution without a document.
//
// The mirror holds the RESOLVED MODE, not the choice. The inline boot script in
// each page shell reads it before first paint and must stay a single string
// comparison — re-deriving `system` there would mean duplicating `resolveMode`
// into HTML.

/** What the user picked. */
export type ThemeChoice = 'light' | 'dark' | 'system';

/** What the document gets. `mode-dark` is applied for `dark`, nothing for `light`. */
export type ThemeMode = 'light' | 'dark';

/** The wire values, in the order the segmented control renders them. */
export const THEME_CHOICES: readonly ThemeChoice[] = ['light', 'dark', 'system'];

/** Anonymous visitors, and anyone who has never chosen. */
export const DEFAULT_THEME_CHOICE: ThemeChoice = 'system';

/** Where the resolved mode is mirrored for the pre-paint script. */
export const THEME_STORAGE_KEY = 'rt-theme';

/** The class roadtrip-zion.css keys its night block on. */
export const DARK_MODE_CLASS = 'mode-dark';

/**
 * `<meta name="theme-color">` per mode — browser chrome reads it before any
 * stylesheet loads, so it cannot be a token reference. Mirrors zion's
 * `--surface-page` in each mode.
 */
export const THEME_COLORS: Readonly<Record<ThemeMode, string>> = {
  light: '#FFFFFF',
  dark: '#101215',
};

/** The one place `system` becomes a concrete mode. */
export function resolveMode(choice: ThemeChoice, prefersDark: boolean): ThemeMode {
  if (choice === 'system') return prefersDark ? 'dark' : 'light';
  return choice;
}

/**
 * Narrow an untrusted value to a choice.
 *
 * A server running ahead of this client, or a hand-edited row, must degrade to
 * the default rather than throw — the theme is not worth a broken settings modal.
 */
export function coerceChoice(value: unknown): ThemeChoice {
  return THEME_CHOICES.includes(value as ThemeChoice)
    ? (value as ThemeChoice)
    : DEFAULT_THEME_CHOICE;
}

/** Reads the mirror. Null when absent, unreadable, or not a mode. */
export function readStoredMode(): ThemeMode | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(THEME_STORAGE_KEY);
  } catch {
    return null;
  }
  return raw === 'light' || raw === 'dark' ? raw : null;
}

/** Refreshes the mirror. Silent on failure — a blocked write must not break the app. */
export function writeStoredMode(mode: ThemeMode): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    // Private mode / quota. The preference still round-trips through the server
    // for signed-in users; only the no-flash boot is lost.
  }
}

/** Drops the mirror, so the next load follows the OS. Used on sign-out. */
export function clearStoredMode(): void {
  try {
    window.localStorage.removeItem(THEME_STORAGE_KEY);
  } catch {
    // As above.
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/lib/theme.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/theme.ts frontend/src/lib/theme.test.ts
git commit -m "feat(frontend): add the pure theme module"
```

---

## Task 2: The theme store

**Files:**
- Create: `frontend/src/stores/themeStore.ts`
- Modify: `frontend/src/app/mount.tsx`
- Test: `frontend/src/stores/themeStore.test.ts`

**Interfaces:**
- Consumes: everything Task 1 produces, plus `resetTokenCache` from `@/tokens/tokens`.
- Produces: `useThemeStore` (Zustand hook) with state `{ choice: ThemeChoice; mode: ThemeMode }` and actions `setChoice(choice: ThemeChoice): void`, `initTheme(): () => void` (returns an unsubscribe), plus a non-hook helper `applyMode(mode: ThemeMode): void`.

**Why the store owns the DOM:** `mapStore` is the precedent for UI state in Zustand, and the class, the meta tag and the token cache must move together — three call sites doing two of the three is exactly how a stale map colour survives a theme switch.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/stores/themeStore.test.ts`:

```tsx
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { DARK_MODE_CLASS, THEME_COLORS, writeStoredMode } from '@/lib/theme';
import { useThemeStore } from './themeStore';

/** A controllable `matchMedia`, since jsdom does not implement it. */
function installMatchMedia(prefersDark: boolean) {
  const listeners = new Set<(e: MediaQueryListEvent) => void>();
  const mql = {
    matches: prefersDark,
    addEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.delete(fn),
  };
  vi.stubGlobal('matchMedia', () => mql);
  return {
    flip(nowPrefersDark: boolean) {
      mql.matches = nowPrefersDark;
      listeners.forEach((fn) => fn({ matches: nowPrefersDark } as MediaQueryListEvent));
    },
    get listenerCount() {
      return listeners.size;
    },
  };
}

function themeColor(): string | null {
  return document.querySelector('meta[name="theme-color"]')?.getAttribute('content') ?? null;
}

beforeEach(() => {
  document.documentElement.className = 'theme-roadtrip-zion';
  document.head.innerHTML = '<meta name="theme-color" content="#ffffff">';
});

afterEach(() => {
  window.localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('setChoice', () => {
  test('applies the dark class and the dark theme-color', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('dark');

    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    expect(themeColor()).toBe(THEME_COLORS.dark);
    expect(useThemeStore.getState().mode).toBe('dark');
  });

  test('removes the dark class going back to light', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('dark');
    useThemeStore.getState().setChoice('light');

    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(false);
    expect(themeColor()).toBe(THEME_COLORS.light);
  });

  test('never disturbs the theme class', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('dark');
    expect(document.documentElement.classList.contains('theme-roadtrip-zion')).toBe(true);
  });

  test('mirrors the resolved mode, not the choice', () => {
    installMatchMedia(true);
    useThemeStore.getState().setChoice('system');
    expect(window.localStorage.getItem('rt-theme')).toBe('dark');
  });
});

describe('initTheme', () => {
  test('seeds from the mirror', () => {
    installMatchMedia(false);
    writeStoredMode('dark');

    const stop = useThemeStore.getState().initTheme();
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    stop();
  });

  test('falls back to the OS when no mirror exists', () => {
    installMatchMedia(true);

    const stop = useThemeStore.getState().initTheme();
    expect(useThemeStore.getState().mode).toBe('dark');
    stop();
  });

  test('follows an OS flip while the choice is system', () => {
    const media = installMatchMedia(false);
    const stop = useThemeStore.getState().initTheme();

    media.flip(true);
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    stop();
  });

  test('ignores an OS flip once the choice is explicit', () => {
    const media = installMatchMedia(false);
    const stop = useThemeStore.getState().initTheme();
    useThemeStore.getState().setChoice('light');

    media.flip(true);
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(false);
    stop();
  });

  test('unsubscribes', () => {
    const media = installMatchMedia(false);
    const stop = useThemeStore.getState().initTheme();
    expect(media.listenerCount).toBe(1);
    stop();
    expect(media.listenerCount).toBe(0);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/stores/themeStore.test.ts`
Expected: FAIL — `Failed to resolve import "./themeStore"`.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/stores/themeStore.ts`:

```ts
// The live theme: the user's choice, the mode it resolves to, and the DOM that
// reflects it.
//
// Zustand, like `mapStore`, because the choice is UI state several unrelated
// surfaces read: the settings panel edits it, the map provider re-styles on it,
// and the settings query pushes the server's answer into it.
//
// This module owns every side effect of a mode change — the class, the
// `theme-color` meta, the memoized token cache. Doing two of the three is how a
// stale map colour survives a switch, so nothing else should add the class.
import { create } from 'zustand';
import {
  DARK_MODE_CLASS,
  DEFAULT_THEME_CHOICE,
  THEME_COLORS,
  readStoredMode,
  resolveMode,
  writeStoredMode,
  type ThemeChoice,
  type ThemeMode,
} from '@/lib/theme';
import { resetTokenCache } from '@/tokens/tokens';

const COLOR_SCHEME_QUERY = '(prefers-color-scheme: dark)';
const THEME_COLOR_SELECTOR = 'meta[name="theme-color"]';

/** True when the OS asks for dark. False anywhere `matchMedia` is unavailable. */
function osPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(COLOR_SCHEME_QUERY).matches;
}

/**
 * Put a mode on the document.
 *
 * `resetTokenCache()` is not optional: `tokens.ts` memoizes every value it reads
 * off the root, so without it the map keeps painting the previous mode's colours.
 */
export function applyMode(mode: ThemeMode): void {
  document.documentElement.classList.toggle(DARK_MODE_CLASS, mode === 'dark');
  document.querySelector(THEME_COLOR_SELECTOR)?.setAttribute('content', THEME_COLORS[mode]);
  resetTokenCache();
  writeStoredMode(mode);
}

interface ThemeState {
  choice: ThemeChoice;
  mode: ThemeMode;
  /** Set the preference and apply it immediately. */
  setChoice: (choice: ThemeChoice) => void;
  /**
   * Seed from the mirror (or the OS) and subscribe to OS changes.
   *
   * Returns an unsubscribe. Called once per page from `mountPage`; the listener
   * is live for the page's lifetime but only *acts* while the choice is
   * `system` — an explicit choice outranks the OS.
   */
  initTheme: () => () => void;
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  choice: DEFAULT_THEME_CHOICE,
  mode: 'light',

  setChoice: (choice) => {
    const mode = resolveMode(choice, osPrefersDark());
    applyMode(mode);
    set({ choice, mode });
  },

  initTheme: () => {
    // The mirror is the resolved mode the boot script already applied, so seeding
    // from it repaints nothing. Absent a mirror, the OS is the answer — which is
    // also what the boot script fell back to.
    const mode = readStoredMode() ?? resolveMode(DEFAULT_THEME_CHOICE, osPrefersDark());
    applyMode(mode);
    set({ mode });

    if (typeof window.matchMedia !== 'function') return () => {};

    const mql = window.matchMedia(COLOR_SCHEME_QUERY);
    const onChange = (event: MediaQueryListEvent) => {
      if (get().choice !== 'system') return;
      const next = resolveMode('system', event.matches);
      applyMode(next);
      set({ mode: next });
    };
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  },
}));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/stores/themeStore.test.ts`
Expected: PASS.

- [ ] **Step 5: Call `initTheme` at mount**

Read `frontend/src/app/mount.tsx` first. Inside `mountPage`, before the React render, add:

```tsx
import { useThemeStore } from '@/stores/themeStore';

  useThemeStore.getState().initTheme();
```

If `mountPage` already returns a teardown, return the unsubscribe alongside it rather than dropping it. If it does not, dropping it is correct — the subscription lives as long as the page.

- [ ] **Step 6: Run the full suite and commit**

Run: `cd frontend && npm test && npm run typecheck`
Expected: PASS.

```bash
git add frontend/src/stores/themeStore.ts frontend/src/stores/themeStore.test.ts frontend/src/app/mount.tsx
git commit -m "feat(frontend): add the theme store and initialise it at mount"
```

---

## Task 3: The pre-paint boot script

**Files:**
- Modify: `frontend/index.html`, `frontend/watches.html`, `frontend/availability.html`
- Create: `frontend/src/test/page-shells.test.ts`

**Interfaces:**
- Consumes: the `'rt-theme'` key and the `'mode-dark'` class from Task 1 — restated as literals, because HTML cannot import.
- Produces: nothing importable. The guarantee is that `mode-dark` is on `<html>` before first paint.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/test/page-shells.test.ts`:

```ts
// The boot script is duplicated across the three page shells because it must run
// before first paint: a module script is deferred until after the browser has
// painted the body, and a <script src> costs a blocking request. Duplication is
// the honest cost — this test is what stops the copies drifting.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import { DARK_MODE_CLASS, THEME_STORAGE_KEY } from '@/lib/theme';

const SHELLS = ['index.html', 'watches.html', 'availability.html'];
const shell = (name: string) => readFileSync(join(process.cwd(), name), 'utf8');

describe.each(SHELLS)('%s', (name) => {
  const html = shell(name);

  test('carries the theme boot script', () => {
    expect(html).toContain(THEME_STORAGE_KEY);
    expect(html).toContain(DARK_MODE_CLASS);
  });

  test('falls back to the OS preference', () => {
    expect(html).toContain('prefers-color-scheme: dark');
  });

  test('runs before the module entry', () => {
    expect(html.indexOf(THEME_STORAGE_KEY)).toBeLessThan(html.indexOf('type="module"'));
  });

  test('still declares the theme class on <html>', () => {
    expect(html).toContain('class="theme-roadtrip-zion"');
  });

  test('has a theme-color meta for the script to update', () => {
    expect(html).toContain('name="theme-color"');
  });
});

test('all three shells carry byte-identical boot scripts', () => {
  const scripts = SHELLS.map((name) => {
    const match = shell(name).match(/<script>[\s\S]*?<\/script>/);
    expect(match, `${name} has no inline <script>`).not.toBeNull();
    return match![0];
  });
  expect(new Set(scripts).size).toBe(1);
});
```

Confirm `process.cwd()` is `frontend/` under this project's Vitest config; if the config sets a different root, use a path relative to `import.meta.url` instead.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/test/page-shells.test.ts`
Expected: FAIL — the shells contain neither `rt-theme` nor `mode-dark`.

- [ ] **Step 3: Add the identical script to all three shells**

`index.html` is the only shell with a `theme-color` meta today. First add `<meta name="theme-color" content="#ffffff">` to `watches.html` and `availability.html`.

Then insert this **immediately before `</head>`** in all three, byte-for-byte identical:

```html
<script>
/* Applied before first paint, so a dark user never sees a white flash. Reads the
   mirror src/lib/theme.ts writes; falls back to the OS. Duplicated in all three
   shells and pinned by src/test/page-shells.test.ts — edit all three together. */
(function(){try{var m=localStorage.getItem('rt-theme');if(m!=='light'&&m!=='dark'){m=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';}if(m==='dark'){document.documentElement.classList.add('mode-dark');var t=document.querySelector('meta[name="theme-color"]');if(t){t.setAttribute('content','#101215');}}}catch(e){}})();
</script>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/test/page-shells.test.ts`
Expected: PASS.

- [ ] **Step 5: Verify by eye**

Run `cd frontend && npm run dev`, open the map page, and in DevTools run `localStorage.setItem('rt-theme','dark')` then hard-reload. Expected: dark from the first frame, no white flash. Then `localStorage.removeItem('rt-theme')` and reload — it follows your OS setting.

- [ ] **Step 6: Commit**

```bash
git add frontend/index.html frontend/watches.html frontend/availability.html frontend/src/test/page-shells.test.ts
git commit -m "feat(frontend): apply the theme before first paint in all three shells"
```

---

## Task 4: Backend — persist the preference

**Files:**
- Create: `backend/src/main/resources/db/migration/V51__user_theme.sql`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/auth/User.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/SettingsResponseDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/UpdateProfileRequest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/UserRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/settings/UserSettingsService.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/settings/UserSettingsServiceTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ProfileDto.theme: String` on the wire as `"theme"`; `UpdateProfileRequest.theme: String?` where absent means unchanged; `UserRepo.updateProfile(id, displayName, theme)`; `THEME_VALUES: Set<String>`.

- [ ] **Step 1: Write the migration**

Confirm the table name against `V47__auth.sql` first — the jOOQ constant is `APP_USER`, so `app_user` is expected, but check rather than assume. Create `backend/src/main/resources/db/migration/V51__user_theme.sql`:

```sql
-- The UI theme preference, per user. 'system' means follow the browser's
-- prefers-color-scheme; it is the default so every existing row keeps today's
-- behaviour on a light-mode device.
ALTER TABLE app_user
  ADD COLUMN theme TEXT NOT NULL DEFAULT 'system';

ALTER TABLE app_user
  ADD CONSTRAINT app_user_theme_check CHECK (theme IN ('light', 'dark', 'system'));
```

- [ ] **Step 2: Write the failing service tests**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/service/settings/UserSettingsServiceTest.kt`, following the fakes and helpers already in that file:

```kotlin
@Test
fun `updateProfile persists a valid theme`() {
    val result = service.updateProfile(userId, UpdateProfileRequest(displayName = "Wm", theme = "dark"))
    assertEquals("dark", result.profile.theme)
}

@Test
fun `updateProfile leaves the theme unchanged when absent`() {
    service.updateProfile(userId, UpdateProfileRequest(displayName = "Wm", theme = "dark"))
    val result = service.updateProfile(userId, UpdateProfileRequest(displayName = "Wm2", theme = null))
    assertEquals("dark", result.profile.theme)
}

@Test
fun `updateProfile rejects an unknown theme`() {
    val error = assertThrows<SettingsError.InvalidField> {
        service.updateProfile(userId, UpdateProfileRequest(displayName = "Wm", theme = "sepia"))
    }
    assertTrue(error.message!!.contains("theme"))
}

@Test
fun `read surfaces the stored theme`() {
    service.updateProfile(userId, UpdateProfileRequest(displayName = "Wm", theme = "light"))
    assertEquals("light", service.read(principal).profile.theme)
}
```

The existing fake `UserRepo` in that file will need its `updateProfile` widened and its stored `User` given a `theme` — do that as part of this step.

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests '*UserSettingsServiceTest*'`
Expected: FAIL — compilation error, `UpdateProfileRequest` has no `theme`.

- [ ] **Step 4: Implement**

`UpdateProfileRequest.kt`:

```kotlin
@Serializable
data class UpdateProfileRequest(
    @SerialName("display_name") val displayName: String?,
    /** One of [ca.floo.roadtrip.service.settings.THEME_VALUES]. Null means unchanged. */
    val theme: String? = null,
)
```

`SettingsResponseDto.kt` — add to `ProfileDto`:

```kotlin
    val theme: String,
```

`User.kt` — add to the `User` data class, after `displayName`:

```kotlin
    val theme: String,
```

`UserSettingsService.kt` — beside `MAX_SLACK_CHANNEL_CHARS`:

```kotlin
/** The UI theme preference values. Mirrors the CHECK constraint in V51 and the
 *  `ThemeChoice` union in frontend/src/lib/theme.ts. */
val THEME_VALUES = setOf("light", "dark", "system")
```

In `updateProfile`, before the repo call:

```kotlin
        if (req.theme != null && req.theme !in THEME_VALUES) {
            throw SettingsError.InvalidField("theme must be one of ${THEME_VALUES.joinToString(", ")}")
        }
        val user =
            requireNotNull(userRepo.updateProfile(userId, req.displayName, req.theme)) {
                "user not found: $userId"
            }
```

In `assembleDto`, add `theme = user.theme,` to the `ProfileDto(...)` construction.

`UserRepo.kt` — widen `updateProfile`, keeping null-means-unchanged in the DSL rather than in a branch:

```kotlin
    open fun updateProfile(
        id: UserId,
        displayName: String?,
        theme: String? = null,
    ): User? {
        ctx
            .update(APP_USER)
            .set(APP_USER.DISPLAY_NAME, displayName)
            // Null means "unchanged", so coalesce to the stored value rather than
            // writing a null the NOT NULL column would reject.
            .set(APP_USER.THEME, DSL.coalesce(DSL.value(theme), APP_USER.THEME))
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(id.value))
            .execute()
        return findById(id)
    }
```

Add `theme = record.get(APP_USER.THEME),` to every record-to-`User` mapper in that file — search for `displayName = ` to find them all. Import `org.jooq.impl.DSL` if it is not already imported.

- [ ] **Step 5: Regenerate jOOQ and run the tests**

Run `./gradlew :backend:build -x test` so `APP_USER.THEME` is generated, then `./gradlew :backend:test --tests '*UserSettingsServiceTest*'`.
Expected: PASS. If jOOQ generation is a separate Gradle task here, check `backend/build.gradle.kts` for the generator configuration and run that task instead.

- [ ] **Step 6: Run the whole backend suite and commit**

Run: `./gradlew :backend:test`
Expected: PASS.

```bash
git add backend/
git commit -m "feat(backend): store a per-user theme preference"
```

---

## Task 5: The Appearance control

**Files:**
- Create: `frontend/src/features/account/AppearanceField.tsx`
- Modify: `frontend/src/api/account-api.ts`
- Modify: `frontend/src/features/account/ProfilePanel.tsx`
- Test: `frontend/src/features/account/ProfilePanel.test.tsx` (create if absent)

**Interfaces:**
- Consumes: `ThemeChoice`, `THEME_CHOICES`, `coerceChoice` (Task 1); `useThemeStore` (Task 2); `theme` on `ProfileDto` (Task 4).
- Produces: `<AppearanceField value={choice} onChange={(next: ThemeChoice) => void} />`; `ProfileValues = { display_name: string; theme: ThemeChoice }`; `buildProfilePayload` returning `{ display_name: string; theme: ThemeChoice }`.

- [ ] **Step 1: Extend the API client**

In `frontend/src/api/account-api.ts`, add `theme` to `Profile`:

```ts
export interface Profile {
  display_name: string | null;
  login_email: string;
  is_email_verified: boolean;
  roles: string[];
  provider_label: string | null;
  /** One of ThemeChoice. Narrow with `coerceChoice` before use — an older
   *  server may omit it. */
  theme: string;
}
```

and widen `updateProfile`:

```ts
export function updateProfile(
  { display_name, theme }: { display_name: string; theme: string },
  options: RequestOptions = {},
): Promise<SettingsResponse> {
  return jsonPutOk<SettingsResponse>(PROFILE_URL, { display_name, theme }, options);
}
```

- [ ] **Step 2: Write the failing test**

Create or extend `frontend/src/features/account/ProfilePanel.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import type { Profile } from '@/api/account-api';
import { useThemeStore } from '@/stores/themeStore';
import {
  ProfilePanel,
  buildProfilePayload,
  isProfileDirty,
  profileValuesOf,
  type ProfileValues,
} from './ProfilePanel';

const profile: Profile = {
  display_name: 'William Chen',
  login_email: 'wm@example.com',
  is_email_verified: true,
  roles: [],
  provider_label: null,
  theme: 'system',
};

const settings = { profile, notifications: {} } as never;

beforeEach(() => {
  document.documentElement.className = 'theme-roadtrip-zion';
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
});

describe('profile values', () => {
  test('seeds the theme from the profile', () => {
    expect(profileValuesOf(settings).theme).toBe('system');
  });

  test('coerces an unknown theme to system', () => {
    const odd = { profile: { ...profile, theme: 'sepia' }, notifications: {} } as never;
    expect(profileValuesOf(odd).theme).toBe('system');
  });

  test('a changed theme is dirty', () => {
    const values: ProfileValues = { display_name: 'William Chen', theme: 'dark' };
    expect(isProfileDirty(settings, values)).toBe(true);
  });

  test('an unchanged theme is not dirty', () => {
    const values: ProfileValues = { display_name: 'William Chen', theme: 'system' };
    expect(isProfileDirty(settings, values)).toBe(false);
  });

  test('the payload carries the theme', () => {
    expect(buildProfilePayload({ display_name: 'Wm', theme: 'dark' })).toEqual({
      display_name: 'Wm',
      theme: 'dark',
    });
  });
});

describe('the Appearance control', () => {
  test('renders the three options', () => {
    render(<ProfilePanel profile={profile} values={profileValuesOf(settings)} onChange={() => {}} />);
    expect(screen.getByRole('radio', { name: 'Light' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'System' })).toBeInTheDocument();
  });

  test('picking Dark previews immediately and reports the change', async () => {
    const onChange = vi.fn();
    render(<ProfilePanel profile={profile} values={profileValuesOf(settings)} onChange={onChange} />);

    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));

    expect(onChange).toHaveBeenCalledWith({ display_name: 'William Chen', theme: 'dark' });
    expect(useThemeStore.getState().choice).toBe('dark');
    expect(document.documentElement.classList.contains('mode-dark')).toBe(true);
  });
});
```

**If `SegmentedControl` does not render `radio` roles**, read `frontend/node_modules/@lew-ds/lds-react` for what it actually emits and adjust these queries to match. Do not add ARIA to the LDS component to satisfy the test.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/account/ProfilePanel.test.tsx`
Expected: FAIL — `profileValuesOf(...).theme` is undefined.

- [ ] **Step 4: Implement**

Create `frontend/src/features/account/AppearanceField.tsx`:

```tsx
import { SegmentedControl } from '@ui';
import { THEME_CHOICES, type ThemeChoice } from '@/lib/theme';

const LABELS: Readonly<Record<ThemeChoice, string>> = {
  light: 'Light',
  dark: 'Dark',
  system: 'System',
};

const FIELD_LABEL = 'Appearance';
const HELP_TEXT = 'System follows your device setting.';

const OPTIONS = THEME_CHOICES.map((value) => ({ value, label: LABELS[value] }));

export interface AppearanceFieldProps {
  value: ThemeChoice;
  onChange: (choice: ThemeChoice) => void;
}

/**
 * The theme picker, as a form field.
 *
 * Three states in one control rather than a switch: a switch carries two, and
 * keeping `system` would cost a second switch that disables the first. The
 * selected segment renders basalt rather than persimmon — the theme's rule is
 * that selection is structural colour and the action colour appears once per
 * screen, which here is Save.
 */
export function AppearanceField({ value, onChange }: AppearanceFieldProps) {
  return (
    <SegmentedControl
      label={FIELD_LABEL}
      helpText={HELP_TEXT}
      options={OPTIONS}
      value={value}
      onChange={(next: string) => onChange(next as ThemeChoice)}
    />
  );
}
```

Check `SegmentedControl`'s real prop names in `frontend/node_modules/@lew-ds/lds-react` before running — `label`, `helpText`, `options`, `value`, `onChange` are the expected shape, but `onChange` may hand you an event rather than a value. Adapt this component, not its call site.

In `ProfilePanel.tsx`:

```tsx
import { SeededTextField } from '@ui';
import { coerceChoice, type ThemeChoice } from '@/lib/theme';
import { useThemeStore } from '@/stores/themeStore';
import { AppearanceField } from './AppearanceField';
import type { Profile, SettingsResponse } from '@/api/account-api';
import './account.css';

export interface ProfileValues {
  display_name: string;
  theme: ThemeChoice;
}

export function profileValuesOf(settings: SettingsResponse): ProfileValues {
  return {
    display_name: settings.profile.display_name || '',
    theme: coerceChoice(settings.profile.theme),
  };
}

export function isProfileDirty(settings: SettingsResponse, values: ProfileValues): boolean {
  return (
    values.display_name !== (settings.profile.display_name || '') ||
    values.theme !== coerceChoice(settings.profile.theme)
  );
}

export function buildProfilePayload(
  values: ProfileValues,
): { display_name: string; theme: ThemeChoice } {
  return { display_name: values.display_name, theme: values.theme };
}
```

and inside the component, after the `SeededTextField`:

```tsx
      <AppearanceField
        value={values.theme}
        onChange={(theme) => {
          // Preview immediately: a whole-page visual choice made blind is not a
          // choice. Save commits it; SettingsModal reverts an unsaved preview
          // when it closes.
          useThemeStore.getState().setChoice(theme);
          onChange({ ...values, theme });
        }}
      />
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/account && npm run typecheck`
Expected: PASS. `SettingsModal.test.tsx`'s fixtures will need `theme: 'system'` added — do that; a fixture missing a required field is the test's problem, not the code's.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/account frontend/src/api/account-api.ts
git commit -m "feat(frontend): add the Appearance control to the profile panel"
```

---

## Task 6: Commit, revert, and sign-out

**Files:**
- Modify: `frontend/src/features/account/SettingsModal.tsx`
- Modify: `frontend/src/features/account/useSettings.ts`
- Modify: `frontend/src/api/auth-api.ts`
- Test: `frontend/src/features/account/SettingsModal.test.tsx`

**Interfaces:**
- Consumes: `useThemeStore` (Task 2), `coerceChoice` / `clearStoredMode` (Task 1).
- Produces: no new exports. The guarantee: the applied theme equals the saved theme whenever the modal is closed.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/features/account/SettingsModal.test.tsx`, matching the render helper already in that file:

```tsx
test('reverts an unsaved theme preview when the modal closes', async () => {
  const { unmount } = renderSettingsModal({ profile: { ...profile, theme: 'light' } });

  await userEvent.click(await screen.findByRole('radio', { name: 'Dark' }));
  expect(document.documentElement.classList.contains('mode-dark')).toBe(true);

  unmount();

  expect(useThemeStore.getState().choice).toBe('light');
  expect(document.documentElement.classList.contains('mode-dark')).toBe(false);
});

test('keeps a saved theme when the modal closes', async () => {
  const { unmount } = renderSettingsModal({ profile: { ...profile, theme: 'light' } });

  await userEvent.click(await screen.findByRole('radio', { name: 'Dark' }));
  await userEvent.click(screen.getByRole('button', { name: 'Save' }));
  await screen.findByText('Settings saved.');

  unmount();

  expect(useThemeStore.getState().choice).toBe('dark');
  expect(document.documentElement.classList.contains('mode-dark')).toBe(true);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/account/SettingsModal.test.tsx`
Expected: FAIL — the preview survives unmount.

- [ ] **Step 3: Implement the revert**

In `SettingsModal.tsx`, add an effect whose cleanup restores the saved choice. It reads through a ref so the cleanup is armed once rather than re-armed on every keystroke:

```tsx
import { useEffect, useRef, useState } from 'react';
import { coerceChoice } from '@/lib/theme';
import { useThemeStore } from '@/stores/themeStore';

  // …after `settings` is read:

  // The saved choice, tracked in a ref so the revert-on-close effect below runs
  // its cleanup exactly once, on unmount, rather than on every edit.
  const savedChoice = settings ? coerceChoice(settings.profile.theme) : null;
  const savedChoiceRef = useRef(savedChoice);
  savedChoiceRef.current = savedChoice;

  useEffect(
    () => () => {
      const saved = savedChoiceRef.current;
      // Unconditional: setChoice is idempotent, so a saved preview costs one
      // no-op rather than an equality check that could go stale.
      if (saved) useThemeStore.getState().setChoice(saved);
    },
    [],
  );
```

- [ ] **Step 4: Make the server the authority on load**

In `useSettings.ts`, extend the query hook so a loaded document governs the theme. In the hook rather than a component, so every consumer agrees on the applied theme:

```ts
import { useEffect } from 'react';
import { coerceChoice } from '@/lib/theme';
import { useThemeStore } from '@/stores/themeStore';

export function useSettings(): UseQueryResult<SettingsResponse> {
  const query = useQuery({
    queryKey: queryKeys.settings(),
    queryFn: ({ signal }) => fetchSettings({ signal }),
  });

  // Runs on each successful load and after each save, which is also what
  // refreshes the localStorage mirror the boot script reads.
  const serverTheme = query.data?.profile.theme;
  useEffect(() => {
    if (serverTheme === undefined) return;
    useThemeStore.getState().setChoice(coerceChoice(serverTheme));
  }, [serverTheme]);

  return query;
}
```

- [ ] **Step 5: Clear the mirror on sign-out**

In `frontend/src/api/auth-api.ts`, in `signOut()`, before the redirect:

```ts
import { clearStoredMode } from '@/lib/theme';

  // The next visitor at this browser is anonymous until proven otherwise, and an
  // anonymous visitor follows their OS. Leaving the mirror would hand them the
  // previous user's preference.
  clearStoredMode();
```

- [ ] **Step 6: Run the suite and commit**

Run: `cd frontend && npm test && npm run typecheck && npm run lint`
Expected: PASS.

```bash
git add frontend/src/features/account frontend/src/api/auth-api.ts
git commit -m "feat(frontend): commit, revert and clear the theme preference"
```

---

## Task 7: The map follows the theme

**Files:**
- Modify: `frontend/src/features/map/basemaps.ts`
- Modify: `frontend/src/features/map/BasemapPicker.tsx`
- Modify: `frontend/src/features/map/MapProvider.tsx`
- Test: `frontend/src/features/map/basemaps.test.ts`

**Interfaces:**
- Consumes: `ThemeMode` (Task 1), `useThemeStore` (Task 2).
- Produces: `DARK_BASEMAP = 'carto-dark'`; `AUTO_BASEMAP_VALUE = ''`; `storedBasemapKey(): string | null`; `initialBasemapKey(mode: ThemeMode): string`; `forgetBasemapKey(): void`; context additions `isAutoBasemap: boolean` and `resetBasemap: () => void`.

**Why a full `setStyle` rather than per-layer repaint:** `MapProvider` already tears down and reinstalls every overlay when the style reloads (`setStyleReady(false)` then `setStyle(..., { diff: false })`). Reusing that path means one code path re-colours the map, instead of every layer module growing a repaint entry point that could be forgotten.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/features/map/basemaps.test.ts`, extending its import list with `DARK_BASEMAP`, `storedBasemapKey` and `forgetBasemapKey`:

```ts
describe('theme-aware defaults', () => {
  test('light mode with nothing stored uses the light default', () => {
    expect(initialBasemapKey('light')).toBe(DEFAULT_BASEMAP);
  });

  test('dark mode with nothing stored uses the dark default', () => {
    expect(initialBasemapKey('dark')).toBe(DARK_BASEMAP);
  });

  test('an explicit pick outranks the mode', () => {
    rememberBasemapKey('osm');
    expect(initialBasemapKey('dark')).toBe('osm');
  });

  test('a stored key that no longer exists falls back to the mode default', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'a-basemap-we-dropped');
    expect(initialBasemapKey('dark')).toBe(DARK_BASEMAP);
  });

  test('storedBasemapKey reports whether the user has pinned one', () => {
    expect(storedBasemapKey()).toBeNull();
    rememberBasemapKey('osm');
    expect(storedBasemapKey()).toBe('osm');
  });

  test('forgetBasemapKey returns to auto', () => {
    rememberBasemapKey('osm');
    forgetBasemapKey();
    expect(storedBasemapKey()).toBeNull();
    expect(initialBasemapKey('dark')).toBe(DARK_BASEMAP);
  });

  test('the dark default is a real registry entry', () => {
    expect(BASEMAPS[DARK_BASEMAP]).toBeDefined();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/map/basemaps.test.ts`
Expected: FAIL — `DARK_BASEMAP` is not exported.

- [ ] **Step 3: Implement in `basemaps.ts`**

Replace the existing `initialBasemapKey` with the following, keeping `rememberBasemapKey` and `basemapStyle` as they are:

```ts
import type { ThemeMode } from '@/lib/theme';

/** The basemap dark mode reaches for when the user has never picked one. */
export const DARK_BASEMAP = 'carto-dark';

/** The picker's "follow the theme" option. An empty string, because selecting it
 *  REMOVES the stored key — absence already means auto, and a stored sentinel
 *  would be a second encoding of the same state. */
export const AUTO_BASEMAP_VALUE = '';

/** The mode's default when the user has expressed no preference. */
function defaultBasemapFor(mode: ThemeMode): string {
  return mode === 'dark' ? DARK_BASEMAP : DEFAULT_BASEMAP;
}

/**
 * The user's explicit pick, or null when they have never made one.
 *
 * Reads defensively — Safari's private mode throws rather than returning null —
 * and drops a key the registry no longer has: a stored key outlives the
 * registry, and one that was renamed would otherwise reach `setStyle` as an
 * undefined style and leave a blank map.
 */
export function storedBasemapKey(): string | null {
  let saved: string | null = null;
  try {
    saved = window.localStorage.getItem(BASEMAP_STORAGE_KEY);
  } catch {
    return null;
  }
  return saved != null && saved in BASEMAPS ? saved : null;
}

/** Drop the explicit pick, returning to "follow the theme". */
export function forgetBasemapKey(): void {
  try {
    window.localStorage.removeItem(BASEMAP_STORAGE_KEY);
  } catch {
    // Private mode / quota. The map still works.
  }
}

/** The basemap to open with: the remembered one if it still exists, else the one
 *  this mode calls for. */
export function initialBasemapKey(mode: ThemeMode): string {
  return storedBasemapKey() ?? defaultBasemapFor(mode);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/map/basemaps.test.ts`
Expected: PASS.

- [ ] **Step 5: Wire the provider**

Read `MapProvider.tsx`'s existing `changeBasemap` first. Extract its body into a local `applyBasemap(key: string)` — it already does exactly the four steps below — and call it from both `changeBasemap` and the new effect rather than duplicating them.

```tsx
import { useThemeStore } from '@/stores/themeStore';
import { forgetBasemapKey, initialBasemapKey, storedBasemapKey } from './basemaps';

  const mode = useThemeStore((s) => s.mode);
  const [basemapKey, setBasemapKey] = useState(() => initialBasemapKey(mode));

  // Re-style on every mode change, even when the key is unchanged: the overlays
  // read their colours through `tokens.ts`, whose cache the theme store has just
  // reset, and a full setStyle is what reinstalls them. Skipped on the first run,
  // when the map was created with the right style already.
  const appliedMode = useRef(mode);
  useEffect(() => {
    if (appliedMode.current === mode) return;
    appliedMode.current = mode;
    if (!map) return;
    applyBasemap(initialBasemapKey(mode));
  }, [mode, map]);
```

Add to the context value: `isAutoBasemap: storedBasemapKey() === null`, and

```tsx
  const resetBasemap = useCallback(() => {
    forgetBasemapKey();
    applyBasemap(initialBasemapKey(mode));
  }, [mode]);
```

Note that `applyBasemap` must call `rememberBasemapKey` only on an explicit pick — `resetBasemap` and the mode effect must not re-store the key they just resolved, or "auto" would pin itself on first use. Split `rememberBasemapKey` out of `applyBasemap` and call it from `changeBasemap` only.

- [ ] **Step 6: Wire the picker**

In `BasemapPicker.tsx`:

```tsx
import { AUTO_BASEMAP_VALUE, BASEMAPS } from './basemaps';

const AUTO_OPTION = { value: AUTO_BASEMAP_VALUE, label: 'Auto (match theme)' };

const BASEMAP_OPTIONS = [
  AUTO_OPTION,
  ...Object.entries(BASEMAPS).map(([value, basemap]) => ({ value, label: basemap.name })),
];

// …in the component:
  const { basemapKey, setBasemap, isAutoBasemap, resetBasemap, satellite, setSatellite } =
    useMapContext();

        <Select
          id="rt-basemap"
          aria-label="Basemap"
          options={BASEMAP_OPTIONS}
          value={isAutoBasemap ? AUTO_BASEMAP_VALUE : basemapKey}
          onChange={(e) => {
            const next = (e.target as HTMLSelectElement).value;
            if (next === AUTO_BASEMAP_VALUE) resetBasemap();
            else setBasemap(next);
          }}
        />
```

- [ ] **Step 7: Verify by hand**

Run `cd frontend && npm run dev`. With the basemap select on **Auto**, switch Settings → Appearance to Dark. Expected: chrome, basemap and route line go dark together, and the POI pins survive the style reload. Then pick **OpenStreetMap** explicitly and toggle the theme again — expected: the basemap stays OSM, and the pins still reappear.

**Record whether the campground green (`#2e7d32`) and supercharger red (`#e82127`) read acceptably on the dark basemap.** That is the spec's one open risk, and this step is what closes it.

- [ ] **Step 8: Run the suite and commit**

Run: `cd frontend && npm test && npm run typecheck && npm run lint`
Expected: PASS.

```bash
git add frontend/src/features/map
git commit -m "feat(frontend): follow the theme on the map and its basemap"
```

---

## Task 8: Catalog, documentation, and verification

**Files:**
- Create: `frontend/src/features/account/AppearanceField.stories.tsx`
- Modify: `docs/frontend-components.md`

**Interfaces:**
- Consumes: `AppearanceField` (Task 5).
- Produces: nothing importable.

- [ ] **Step 1: Write the story**

Read one existing story first to match the local pattern, then create `frontend/src/features/account/AppearanceField.stories.tsx`:

```tsx
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react';
import { type ThemeChoice } from '@/lib/theme';
import { AppearanceField } from './AppearanceField';

const meta: Meta<typeof AppearanceField> = {
  title: 'Account/AppearanceField',
  component: AppearanceField,
};
export default meta;

/** Local state rather than the real store, so opening the story does not
 *  repaint Storybook itself. */
function Demo({ initial }: { initial: ThemeChoice }) {
  const [value, setValue] = useState<ThemeChoice>(initial);
  return <AppearanceField value={value} onChange={setValue} />;
}

export const System: StoryObj = { render: () => <Demo initial="system" /> };
export const Light: StoryObj = { render: () => <Demo initial="light" /> };
export const Dark: StoryObj = { render: () => <Demo initial="dark" /> };

/** The same control under the night palette, which is where the basalt selection
 *  and the muted segments have to be checked against each other. */
export const InDarkMode: StoryObj = {
  render: () => (
    <div className="mode-dark" style={{ background: 'var(--surface-page)', padding: 24 }}>
      <Demo initial="dark" />
    </div>
  ),
};
```

- [ ] **Step 2: Verify the catalog builds**

Run: `cd frontend && npm run build-storybook`
Expected: exits 0.

- [ ] **Step 3: Document the contract**

In `docs/frontend-components.md`, after the "Two layers" section:

```markdown
## Theme

`<html>` carries `theme-roadtrip-zion` always, and `mode-dark` when the resolved
mode is dark. Three things own this and nothing else should touch it:

- `src/lib/theme.ts` — the types, `resolveMode`, and the `localStorage` mirror.
- `src/stores/themeStore.ts` — the only writer of the class, the `theme-color`
  meta and `resetTokenCache()`. Adding the class anywhere else leaves the map
  painting the previous mode's colours.
- The inline script in each of the three page shells, which applies the mirrored
  mode before first paint. It is duplicated on purpose and pinned by
  `src/test/page-shells.test.ts` — edit all three together.

A signed-in user's choice lives on `profile.theme`; anonymous visitors follow
`prefers-color-scheme`. New colours must come from mode-aware `--rt-*` roles, not
from a literal `--gray-*` step: zion's grey ramp does not invert, so a literal is
correct-looking in light and unreadable in dark.
```

- [ ] **Step 4: Full verification**

From the repo root:

```bash
cd frontend && npm test && npm run typecheck && npm run lint && npm run build && cd ..
./gradlew :backend:test
```

Expected: all green. Report any failure rather than working around it.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/account/AppearanceField.stories.tsx docs/frontend-components.md
git commit -m "docs: catalog the Appearance control and the theme contract"
```

- [ ] **Step 6: Update the issue**

Post a comment on #625: branch name, one line per task, and the answer to the open risk recorded in Task 7 Step 7.

---

## Self-Review

**Spec coverage.** Every section of the spec maps to a task: the model and mirror → Task 1; the store, class, meta and token cache → Task 2; the pre-paint script and its drift test → Task 3; the migration, DTOs, service validation and repo → Task 4; the segmented control, dirty tracking and payload → Task 5; live preview, revert-on-close, the server as authority, and sign-out → Task 6; the basemap defaults, the Auto option and the map re-style → Task 7; Storybook and the docs → Task 8. The spec's error-handling list is covered by Task 1's `localStorage`-throws and `coerceChoice` tests, Task 6's revert, and Task 7's dropped-registry-key test.

**Placeholders.** None. Every code step carries its code. Three steps say "read the existing file first" — LDS's `SegmentedControl` props, `MapProvider`'s `changeBasemap`, and Vitest's `cwd` — and each names exactly what to look for and what to do with either answer.

**Type consistency.** `ThemeChoice` and `ThemeMode` are used with one meaning throughout: `resolveMode` takes a choice and returns a mode, the mirror stores a mode, `profile.theme` and `ProfileValues.theme` hold a choice. `initialBasemapKey` takes a `ThemeMode` in its implementation, its tests and `MapProvider` alike. `coerceChoice` is the single narrowing point on both the API and the store side. `applyMode` (store) and `applyBasemap` (provider) are distinct names for distinct things.

**One deviation from the spec's wording,** made deliberately: the spec says the mirror holds "the resolved mode" — this plan makes that explicit and adds `storedBasemapKey` / `forgetBasemapKey`, which the spec implied ("selecting Auto removes the stored key") but did not name.
