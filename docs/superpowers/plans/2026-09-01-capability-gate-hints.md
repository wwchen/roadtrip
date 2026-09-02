# Capability Gate Hints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the availability grid's silently-hidden watch and add-to-cart controls with visible hints that name the one step which unlocks them — sign in, or add rec.gov credentials in Settings.

**Architecture:** Show the control, gate the action. Every gated surface keeps its shape and swaps the action for the next step. Three pieces: a pure `cartGate` predicate derived from the capabilities the week response already carries (no new request), a `settingsStore` plus an app-level `SettingsHost` so any surface can open Settings on the Booking tab, and gate-aware rendering in `SiteMatrix`, `CellBookPopover`, `WatchPopover`, `DayDetail` and `WatchEditor`.

**Tech Stack:** React 19 + TypeScript, Vite, Vitest + React Testing Library, zustand v5, LDS design system via `@ui`, `--rt-*` theme tokens.

**Spec:** Design canvas at https://claude.ai/code/artifact/582693ad-0841-4f36-9ecb-6a1e6653d642 (four artboards, screenshot committed to `docs/design-references/capability-gate-hints.png` in Task 9).

## Global Constraints

- Colours are `--rt-*` semantic roles only. No raw hex in CSS or TSX. `scripts/check-color-tokens.mjs` enforces it.
- Layering: `pages/` compose `features/`; `features/` may import `@ui`, `@/api`, `@/stores`, `@/lib`, `@/domain`; features never import another feature. `domain/` never imports `features/`. `npm run lint` enforces it.
- Reuse before adding: compose existing `@ui` primitives and existing CSS classes. `LinkButton` is the primitive for an action word inside a sentence.
- Comments stay short and rare (repo-wide convention from #626).
- Copy is exactly as specified per task. It is the deliverable, not a placeholder.
- Every task ends green: `npm test`, `npm run typecheck`, `npm run lint` from `frontend/`.
- Never edit an applied migration. This plan touches no migrations.

---

### Task 1: `cartGate` predicate

**Files:**
- Modify: `frontend/src/lib/watch-windows.ts`
- Test: `frontend/src/lib/watch-windows.test.ts`

**Interfaces:**
- Consumes: existing `WatchCapabilities`, `scopeSupportsAddToCart`, `supportsAddToCart`.
- Produces: `export type CartGate = 'ready' | 'signed-out' | 'no-credentials' | 'unsupported'` and `export function cartGate(capabilities: WatchCapabilities, signedIn: boolean): CartGate`.

Background: the backend adds the `atc` trigger kind only when the requesting user has rec.gov credentials configured, while `booking_actions` containing `add_to_cart` is a property of the scope alone. So scope-yes plus trigger-no means "this campground has a cart and you cannot drive it", and `signedIn` says which of the two hints applies.

- [ ] **Step 1: Write the failing tests**

Append to `frontend/src/lib/watch-windows.test.ts`:

```ts
describe('cartGate', () => {
  const caps = (bookingActions: string[], triggerKinds: string[]): WatchCapabilities => ({
    bookingActions: new Set(bookingActions),
    triggerKinds: new Set(triggerKinds),
  });

  it('is unsupported when the scope has no cart', () => {
    expect(cartGate(caps([], ['slack_notify']), true)).toBe('unsupported');
  });

  it('is ready when the scope has a cart and the caller may drive it', () => {
    expect(cartGate(caps(['add_to_cart'], ['atc']), true)).toBe('ready');
  });

  it('is signed-out when the scope has a cart and nobody is signed in', () => {
    expect(cartGate(caps(['add_to_cart'], []), false)).toBe('signed-out');
  });

  it('is no-credentials when signed in without the atc trigger', () => {
    expect(cartGate(caps(['add_to_cart'], ['slack_notify']), true)).toBe('no-credentials');
  });
});
```

Add `cartGate` to the existing import from `./watch-windows` at the top of the file.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/watch-windows.test.ts`
Expected: FAIL — `cartGate is not a function` / TS error that it is not exported.

- [ ] **Step 3: Implement**

Append to `frontend/src/lib/watch-windows.ts`:

```ts
/**
 * Why add-to-cart is unavailable, or `ready` when it is not.
 *
 * `no-credentials` and `signed-out` are the two actionable causes, and they need
 * different sentences: one is two minutes in Settings, the other is a sign-in.
 */
export type CartGate = 'ready' | 'signed-out' | 'no-credentials' | 'unsupported';

export function cartGate(capabilities: WatchCapabilities, signedIn: boolean): CartGate {
  if (!scopeSupportsAddToCart(capabilities)) return 'unsupported';
  if (supportsAddToCart(capabilities)) return 'ready';
  return signedIn ? 'no-credentials' : 'signed-out';
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/watch-windows.test.ts`
Expected: PASS, all cases.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/watch-windows.ts frontend/src/lib/watch-windows.test.ts
git commit -m "feat(availability): derive an add-to-cart gate from week capabilities"
```

---

### Task 2: Settings tab vocabulary in `@/lib`

**Files:**
- Create: `frontend/src/lib/settings-tabs.ts`
- Modify: `frontend/src/features/account/SettingsModal.tsx:45-62,126`
- Test: `frontend/src/features/account/SettingsModal.test.tsx`

**Interfaces:**
- Produces: `export type SettingsTab = 'profile' | 'appearance' | 'notifications' | 'booking' | 'account'`, `export const SETTINGS_TABS: ReadonlyArray<{ id: SettingsTab; label: string }>`, `export const DEFAULT_SETTINGS_TAB: SettingsTab`.
- Consumed by: Task 3's store and Task 4's host.

The tab ids currently live as module-private consts inside `SettingsModal.tsx`. A store cannot import a feature, so the vocabulary moves down to `@/lib` and both sides import it.

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/features/account/SettingsModal.test.tsx`:

```tsx
it('opens on the tab it was given', async () => {
  renderModal({ initialTab: 'booking' });
  expect(await screen.findByRole('button', { name: 'Booking' })).toHaveAttribute('aria-current', 'true');
});
```

Use the file's existing render helper and mock setup. If it has no helper, render `<SettingsModal initialTab="booking" onClose={() => {}} />` inside the same providers the other tests in the file use.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/account/SettingsModal.test.tsx`
Expected: FAIL — TS error that `initialTab` is not a prop, or the Profile tab is current instead of Booking.

- [ ] **Step 3: Implement**

Create `frontend/src/lib/settings-tabs.ts`:

```ts
// The settings sections, shared by the modal that renders them and the store that
// opens it. It lives here rather than in the feature because a store may not
// import a feature.

export type SettingsTab = 'profile' | 'appearance' | 'notifications' | 'booking' | 'account';

export const SETTINGS_TABS: ReadonlyArray<{ id: SettingsTab; label: string }> = [
  { id: 'profile', label: 'Profile' },
  { id: 'appearance', label: 'Appearance' },
  { id: 'notifications', label: 'Notifications' },
  { id: 'booking', label: 'Booking' },
  { id: 'account', label: 'Account' },
];

export const DEFAULT_SETTINGS_TAB: SettingsTab = 'profile';
```

In `SettingsModal.tsx`: delete the local `TAB_*` consts, `TABS` and `type TabId`; import `SETTINGS_TABS`, `DEFAULT_SETTINGS_TAB` and `type SettingsTab` from `@/lib/settings-tabs`; replace every `TabId` with `SettingsTab` and every `TAB_PROFILE`-style reference with its string literal; add the prop and seed the state:

```tsx
export interface SettingsModalProps {
  onClose: () => void;
  /** The section to open on. Defaults to Profile. */
  initialTab?: SettingsTab;
}
```

```tsx
const [activeTab, setActiveTab] = useState<SettingsTab>(initialTab ?? DEFAULT_SETTINGS_TAB);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/account/SettingsModal.test.tsx`
Expected: PASS, including the pre-existing tests in the file.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/settings-tabs.ts frontend/src/features/account/SettingsModal.tsx frontend/src/features/account/SettingsModal.test.tsx
git commit -m "refactor(settings): move tab vocabulary to lib and accept an initial tab"
```

---

### Task 3: `settingsStore`

**Files:**
- Create: `frontend/src/stores/settingsStore.ts`
- Test: `frontend/src/stores/settingsStore.test.ts`

**Interfaces:**
- Produces: `useSettingsStore` with `{ open: boolean; tab: SettingsTab | null; openSettings(tab?: SettingsTab): void; closeSettings(): void }`.

Follow the shape of `frontend/src/stores/mapStore.ts`: a state interface with the actions on it, an `INITIAL_*` const, `create<State>()((set) => ({...}))`, no middleware.

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/stores/settingsStore.test.ts`:

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import { useSettingsStore } from './settingsStore';

describe('settingsStore', () => {
  beforeEach(() => useSettingsStore.getState().closeSettings());

  it('starts closed', () => {
    expect(useSettingsStore.getState().open).toBe(false);
    expect(useSettingsStore.getState().tab).toBeNull();
  });

  it('opens on a named tab', () => {
    useSettingsStore.getState().openSettings('booking');
    expect(useSettingsStore.getState().open).toBe(true);
    expect(useSettingsStore.getState().tab).toBe('booking');
  });

  it('opens with no tab when none is named', () => {
    useSettingsStore.getState().openSettings();
    expect(useSettingsStore.getState().open).toBe(true);
    expect(useSettingsStore.getState().tab).toBeNull();
  });

  it('forgets the tab on close', () => {
    useSettingsStore.getState().openSettings('booking');
    useSettingsStore.getState().closeSettings();
    expect(useSettingsStore.getState().open).toBe(false);
    expect(useSettingsStore.getState().tab).toBeNull();
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/stores/settingsStore.test.ts`
Expected: FAIL — cannot resolve `./settingsStore`.

- [ ] **Step 3: Implement**

Create `frontend/src/stores/settingsStore.ts`:

```ts
// Whether the settings modal is up, and which section it opened on.
//
// It is a store rather than `AuthRow` state because the account pill is no longer
// the only thing that opens it: an availability cell can send a user straight to
// the Booking tab, and that cell has no path to the pill's local state.
import { create } from 'zustand';
import type { SettingsTab } from '@/lib/settings-tabs';

interface SettingsState {
  open: boolean;
  /** The section to land on, or null for the default. */
  tab: SettingsTab | null;
  openSettings: (tab?: SettingsTab) => void;
  closeSettings: () => void;
}

const INITIAL_SETTINGS = { open: false, tab: null } satisfies Omit<
  SettingsState,
  'openSettings' | 'closeSettings'
>;

export const useSettingsStore = create<SettingsState>()((set) => ({
  ...INITIAL_SETTINGS,
  openSettings: (tab) => set({ open: true, tab: tab ?? null }),
  closeSettings: () => set({ ...INITIAL_SETTINGS }),
}));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/stores/settingsStore.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/stores/settingsStore.ts frontend/src/stores/settingsStore.test.ts
git commit -m "feat(settings): add a store for the settings modal's open state"
```

---

### Task 4: `SettingsHost`, and `AuthRow` becomes a trigger

**Files:**
- Create: `frontend/src/app/SettingsHost.tsx`
- Modify: `frontend/src/app/AppProviders.tsx:21-31`
- Modify: `frontend/src/features/account/AuthRow.tsx:24,61,68`
- Test: `frontend/src/app/SettingsHost.test.tsx`

**Interfaces:**
- Produces: `<SettingsHost />`, mounted once per page inside `AppProviders`, renders `SettingsModal` when the store says open.

This is what makes the Settings hint honest on `poi.html`, which mounts `AvailabilityWeek` but no `AuthRow` and therefore has no settings entry point today.

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/app/SettingsHost.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SettingsHost } from './SettingsHost';
import { useSettingsStore } from '@/stores/settingsStore';

vi.mock('@/features/account/SettingsModal', () => ({
  SettingsModal: ({ initialTab }: { initialTab?: string }) => (
    <div data-testid="settings-modal">{initialTab ?? 'none'}</div>
  ),
}));

afterEach(() => useSettingsStore.getState().closeSettings());

describe('SettingsHost', () => {
  it('renders nothing while the store is closed', () => {
    render(<SettingsHost />);
    expect(screen.queryByTestId('settings-modal')).toBeNull();
  });

  it('renders the modal on the tab the store names', () => {
    useSettingsStore.getState().openSettings('booking');
    render(<SettingsHost />);
    expect(screen.getByTestId('settings-modal')).toHaveTextContent('booking');
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/app/SettingsHost.test.tsx`
Expected: FAIL — cannot resolve `./SettingsHost`.

- [ ] **Step 3: Implement**

Create `frontend/src/app/SettingsHost.tsx`:

```tsx
// The settings modal's one mount point, on every page.
//
// It hangs off the store rather than off the account pill because the pill is on
// the map page alone, while the surfaces that send a user to Settings are not.
import { SettingsModal } from '@/features/account/SettingsModal';
import { useSettingsStore } from '@/stores/settingsStore';

export function SettingsHost() {
  const open = useSettingsStore((state) => state.open);
  const tab = useSettingsStore((state) => state.tab);
  const closeSettings = useSettingsStore((state) => state.closeSettings);

  if (!open) return null;
  return <SettingsModal initialTab={tab ?? undefined} onClose={closeSettings} />;
}
```

In `AppProviders.tsx`, render `<SettingsHost />` as the last child inside the existing `ToastProvider`, beside `{children}`.

In `AuthRow.tsx`: delete the `settingsOpen` state and the `SettingsModal` import and render; read `const openSettings = useSettingsStore((state) => state.openSettings);` and change the pill's handler to `onClick={() => openSettings()}`. The component's returned fragment now holds the pill alone.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/app src/features/account`
Expected: PASS, including the existing `AuthRow` tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/SettingsHost.tsx frontend/src/app/SettingsHost.test.tsx frontend/src/app/AppProviders.tsx frontend/src/features/account/AuthRow.tsx
git commit -m "feat(settings): mount the settings modal app-wide behind the store"
```

---

### Task 5: `DayDetail` sign-in link

**Files:**
- Modify: `frontend/src/features/availability/DayDetail.tsx:38-53,126-129`
- Test: `frontend/src/features/availability/DayDetail.test.tsx` (create if absent)

**Interfaces:**
- Produces: `DayDetailProps.onSignIn: () => void`, called from the `signed-out` message.

- [ ] **Step 1: Write the failing test**

Create or append `frontend/src/features/availability/DayDetail.test.tsx`:

```tsx
it('offers sign-in from the signed-out message', async () => {
  const onSignIn = vi.fn();
  render(
    <DayDetail
      day={{ date: '2026-09-08', status: 'reserved', campsites: [] } as never}
      watching={false}
      unavailable="signed-out"
      busy={false}
      onToggleWatch={() => {}}
      onRetryWatches={() => {}}
      onSignIn={onSignIn}
    />,
  );
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  expect(onSignIn).toHaveBeenCalledOnce();
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/availability/DayDetail.test.tsx`
Expected: FAIL — no button named "Sign in" (the copy is inert text today).

- [ ] **Step 3: Implement**

Add to `DayDetailProps`:

```tsx
  /** Starts the hosted sign-in flow from the signed-out message. */
  onSignIn: () => void;
```

Thread it through the `DayDetail` and `DayAction` parameter lists, and replace the `signed-out` branch:

```tsx
    case 'signed-out':
      return (
        <span className="cg-day-detail-meta">
          <LinkButton onClick={onSignIn}>Sign in</LinkButton> to set availability alerts.
        </span>
      );
```

In `AvailabilityWeek.tsx`, pass `onSignIn={() => signIn()}` to `DayDetail`, importing `signIn` from `@/api/auth-api`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/availability`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/availability/DayDetail.tsx frontend/src/features/availability/DayDetail.test.tsx frontend/src/features/availability/AvailabilityWeek.tsx
git commit -m "feat(availability): make the day panel's sign-in hint actionable"
```

---

### Task 6: Watch sign-in gate popover

**Files:**
- Create: `frontend/src/domain/watch/WatchPanelHead.tsx`
- Create: `frontend/src/domain/watch/WatchSignInGate.tsx`
- Modify: `frontend/src/domain/watch/WatchEditor.tsx:86-100`
- Modify: `frontend/src/domain/watch/watch-editor.css`
- Modify: `frontend/src/features/availability/WatchPopover.tsx:28-52,93-127`
- Test: `frontend/src/domain/watch/WatchSignInGate.test.tsx`

**Interfaces:**
- Produces: `<WatchPanelHead title subtitle onClose />`; `<WatchSignInGate title subtitle onSignIn onClose />`; `WatchPopoverProps.gate?: 'signed-out'`.

The gate reuses the editor's shell so the popover a signed-out visitor gets is the same object in the same place as the one they get after signing in. `WatchPopover` keeps sole ownership of anchoring and dismissal — the gate is content, not a second popover.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/domain/watch/WatchSignInGate.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { WatchSignInGate } from './WatchSignInGate';

describe('WatchSignInGate', () => {
  it('names the night and offers sign-in', async () => {
    const onSignIn = vi.fn();
    render(
      <WatchSignInGate
        title="Watch Tuff Campground"
        subtitle="Tuesday, September 8"
        onSignIn={onSignIn}
        onClose={() => {}}
      />,
    );
    expect(screen.getByText('Tuesday, September 8')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(onSignIn).toHaveBeenCalledOnce();
  });

  it('closes', async () => {
    const onClose = vi.fn();
    render(
      <WatchSignInGate title="Watch Tuff Campground" subtitle="Tuesday, September 8" onSignIn={() => {}} onClose={onClose} />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/domain/watch/WatchSignInGate.test.tsx`
Expected: FAIL — cannot resolve `./WatchSignInGate`.

- [ ] **Step 3: Implement**

Create `frontend/src/domain/watch/WatchPanelHead.tsx`:

```tsx
import { Icon } from '@ui';

export interface WatchPanelHeadProps {
  title?: string;
  subtitle?: string;
  onClose?: (() => void) | null;
}

/** The title block and close affordance both watch panels share. */
export function WatchPanelHead({ title, subtitle, onClose }: WatchPanelHeadProps) {
  return (
    <div className="rt-watch-editor-head">
      <div>
        {title ? <div className="rt-watch-editor-title">{title}</div> : null}
        {subtitle ? <div className="rt-watch-editor-subtitle">{subtitle}</div> : null}
      </div>
      {onClose ? (
        <button type="button" className="rt-watch-editor-icon" aria-label="Close" onClick={onClose}>
          <Icon name="close" aria-hidden="true" />
        </button>
      ) : null}
    </div>
  );
}
```

Replace the head markup in `WatchEditor.tsx` with `<WatchPanelHead title={title} subtitle={subtitle} onClose={onClose} />`, keeping its existing props.

Create `frontend/src/domain/watch/WatchSignInGate.tsx`:

```tsx
// What a signed-out visitor gets where the watch editor would be.
//
// The same shell, in the same place, so signing in swaps the contents rather than
// moving the surface — and so the grid can offer a reserved night at all, which
// it could not while the cell was inert.
import { WatchPanelHead } from './WatchPanelHead';

export interface WatchSignInGateProps {
  title: string;
  subtitle: string;
  onSignIn: () => void;
  onClose: () => void;
}

export function WatchSignInGate({ title, subtitle, onSignIn, onClose }: WatchSignInGateProps) {
  return (
    <div className="rt-watch-editor" role="group" aria-label="Availability watch sign-in">
      <WatchPanelHead title={title} subtitle={subtitle} onClose={onClose} />
      <p className="rt-watch-editor-gate-text">
        Sign in to get an alert when a site opens up that night.
      </p>
      <div className="rt-watch-editor-actions rt-watch-editor-actions--stretch">
        <button type="button" className="rt-watch-editor-save" onClick={onSignIn}>
          Sign in
        </button>
      </div>
    </div>
  );
}
```

Append to `watch-editor.css`:

```css
.rt-watch-editor-gate-text {
  margin: 0;
  color: var(--rt-muted);
  line-height: 1.35;
}
.rt-watch-editor-actions--stretch .rt-watch-editor-save {
  flex: 1 1 auto;
  min-height: 36px;
}
```

In `WatchPopover.tsx`, add to the props interface:

```tsx
  /** Renders the sign-in gate instead of the editor. */
  gate?: 'signed-out';
  onSignIn: () => void;
```

and branch inside the portal, before the `WatchEditor`:

```tsx
      {gate === 'signed-out' ? (
        <WatchSignInGate
          title={`Watch ${poiName}`}
          subtitle={longDayLabel(date)}
          onSignIn={onSignIn}
          onClose={onClose}
        />
      ) : (
        <WatchEditor … />
      )}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/domain/watch src/features/availability`
Expected: PASS, including the existing `WatchEditor` tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/domain/watch frontend/src/features/availability/WatchPopover.tsx
git commit -m "feat(watch): add a sign-in gate that reuses the watch editor shell"
```

---

### Task 7: Grid opens the watch gate when signed out

**Files:**
- Modify: `frontend/src/features/availability/SiteMatrix.tsx:73,202-206,556-560,636-680`
- Modify: `frontend/src/features/availability/AvailabilityWeek.tsx:136-158,335-345,395-410`
- Test: `frontend/src/features/availability/AvailabilityWeek.test.tsx`

**Interfaces:**
- Consumes: Task 6's `gate` prop.
- Produces: `SiteMatrixProps['view'].watchGate: 'ready' | 'signed-out' | 'blocked'` replacing `canWatch: boolean`.

One value replaces the boolean because the cell now has three behaviours, not two: open the editor, open the gate, or stay inert.

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/features/availability/AvailabilityWeek.test.tsx`, following the file's existing render helper and fetch mocking for an anonymous visitor whose watch access is `unauthorized`:

```tsx
it('offers sign-in from a reserved cell when signed out', async () => {
  renderWeek({ authenticated: false });
  const cell = await screen.findByRole('button', { name: /tap to sign in and set an availability watch/i });
  await userEvent.click(cell);
  expect(await screen.findByRole('group', { name: 'Availability watch sign-in' })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/availability/AvailabilityWeek.test.tsx`
Expected: FAIL — no such button; a signed-out reserved cell renders an inert `<span>`.

- [ ] **Step 3: Implement**

In `SiteMatrix.tsx`, replace `canWatch: boolean` on the `view` object and on `MatrixRowProps`/`MatrixCellProps` with:

```tsx
  watchGate: 'ready' | 'signed-out' | 'blocked';
```

Thread it through `MatrixRow` to `MatrixCell` in place of `canWatch`, and change the watchable-cell branch:

```tsx
    const watched = watchedDates.has(day.date);
    if (isWatchableKind(state.kind) && (watchGate !== 'blocked' || watched)) {
      const signedOut = watchGate === 'signed-out' && !watched;
      return (
        <td className={cellClass}>
          <button
            type="button"
            className={`cg-site-matrix-cell-button cg-site-matrix-cell-watch${watched ? ' is-watched' : ''}`}
            aria-label={
              watched
                ? `${aria}; availability watch set, tap to manage`
                : signedOut
                  ? `${aria}; tap to sign in and set an availability watch`
                  : `${aria}; tap to set an availability watch`
            }
            onClick={(event) => onOpenWatch(event.currentTarget, day.date)}
          >
            {state.label}
          </button>
        </td>
      );
    }
```

In `AvailabilityWeek.tsx`, derive the gate beside the existing `watchUnavailable`:

```tsx
  // Three behaviours, not two: open the editor, offer sign-in, or stay inert.
  const watchGate: 'ready' | 'signed-out' | 'blocked' =
    watchUnavailable === null ? 'ready' : watchUnavailable === 'signed-out' ? 'signed-out' : 'blocked';
```

Pass `watchGate` in the `view` object in place of `canWatch`, and pass to `WatchPopover`:

```tsx
          gate={watchGate === 'signed-out' ? 'signed-out' : undefined}
          onSignIn={() => signIn()}
```

Keep the existing `canWatch` local if other call sites still read it; otherwise delete it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/availability`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/availability
git commit -m "feat(availability): let a signed-out visitor open a watch from the grid"
```

---

### Task 8: Gated add-to-cart row in the book popover

**Files:**
- Modify: `frontend/src/features/availability/CellBookPopover.tsx:16-40,88-125`
- Modify: `frontend/src/features/availability/availability.css:966-1020`
- Modify: `frontend/src/features/availability/SiteMatrix.tsx:76,598,653,736-760`
- Modify: `frontend/src/features/availability/AvailabilityWeek.tsx:155-160,335-345`
- Modify: `frontend/src/domain/watch/WatchEditor.tsx:79-84,180-195` and its `ToggleRow`
- Test: `frontend/src/features/availability/CellBookPopover.test.tsx` (create)

**Interfaces:**
- Consumes: Task 1's `CartGate`.
- Produces: `CellBookPopoverProps.cart` as a discriminated union of `{ state: 'ready'; onAddToCart; busy }`, `{ state: 'signed-out'; onSignIn }`, `{ state: 'no-credentials'; onOpenSettings }`.

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/features/availability/CellBookPopover.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { CellBookPopover } from './CellBookPopover';

const anchor = () => document.body.appendChild(document.createElement('button'));

describe('CellBookPopover', () => {
  it('sends a signed-out visitor to sign-in from the cart row', async () => {
    const onSignIn = vi.fn();
    render(
      <CellBookPopover
        anchor={anchor()}
        onOpenBooking={() => {}}
        cart={{ state: 'signed-out', onSignIn }}
        onClose={() => {}}
      />,
    );
    expect(screen.getByText('Sign in to hold sites from here')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /add to cart/i }));
    expect(onSignIn).toHaveBeenCalledOnce();
  });

  it('sends a user without credentials to Settings', async () => {
    const onOpenSettings = vi.fn();
    render(
      <CellBookPopover
        anchor={anchor()}
        onOpenBooking={() => {}}
        cart={{ state: 'no-credentials', onOpenSettings }}
        onClose={() => {}}
      />,
    );
    expect(screen.getByText('Add rec.gov login in Settings')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /add to cart/i }));
    expect(onOpenSettings).toHaveBeenCalledOnce();
  });

  it('holds the site when the caller may drive the cart', async () => {
    const onAddToCart = vi.fn();
    render(
      <CellBookPopover
        anchor={anchor()}
        onOpenBooking={() => {}}
        cart={{ state: 'ready', onAddToCart, busy: false }}
        onClose={() => {}}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: /add to cart/i }));
    expect(onAddToCart).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/availability/CellBookPopover.test.tsx`
Expected: FAIL — TS error that `cart` is not a prop.

- [ ] **Step 3: Implement**

In `CellBookPopover.tsx`, add the second width constant and replace `onAddToCart`/`cartBusy` with the union:

```tsx
/** Matches `--rt-cell-book-pop-width` in availability.css. */
const POPOVER_WIDTH_PX = 200;
/** A gated row carries a second line, so it takes the watch editor's width. */
const POPOVER_WIDTH_WITH_HINT_PX = 240;

export type CellCart =
  | { state: 'ready'; onAddToCart: () => void; busy: boolean }
  | { state: 'signed-out'; onSignIn: () => void }
  | { state: 'no-credentials'; onOpenSettings: () => void };
```

Compute `const width = cart.state === 'ready' ? POPOVER_WIDTH_PX : POPOVER_WIDTH_WITH_HINT_PX;`, pass `width` into `positionFor`, and set it on the host's inline style as a `--rt-cell-book-pop-width` custom property.

Render the second row from the union. The hint copy is exactly `Sign in to hold sites from here` and `Add rec.gov login in Settings`:

```tsx
      <button
        type="button"
        className={`cg-cell-book-pop-row${cart.state === 'ready' ? ' cg-cell-book-pop-row--cart' : ' cg-cell-book-pop-row--gated'}`}
        disabled={cart.state === 'ready' && cart.busy}
        onClick={() => {
          if (cart.state === 'ready') cart.onAddToCart();
          else if (cart.state === 'signed-out') cart.onSignIn();
          else cart.onOpenSettings();
        }}
      >
        <CartIcon />
        <span className="cg-cell-book-pop-text">
          <span>Add to cart</span>
          {cart.state === 'signed-out' ? (
            <span className="cg-cell-book-pop-hint">Sign in to hold sites from here</span>
          ) : cart.state === 'no-credentials' ? (
            <span className="cg-cell-book-pop-hint">Add rec.gov login in Settings</span>
          ) : null}
        </span>
      </button>
```

Append to `availability.css`:

```css
/* A gated row is the plain row plus a second line: the branded tint stays the
   badge of the action that actually holds a site. */
.cg-cell-book-pop-row--gated {
  height: auto;
  min-height: 52px;
  border-top: 1px solid var(--rt-border);
}
.cg-cell-book-pop-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.cg-cell-book-pop-hint {
  color: var(--rt-muted);
  font-size: 11px;
  line-height: 1.25;
}
```

In `SiteMatrix.tsx`, replace `canAddToCart: boolean` on the view and cell props with `cartGate: CartGate` plus the two new handlers `onSignIn` and `onOpenSettings` on the events object. Compute:

```tsx
  const hasCartRow = cartGate !== 'unsupported';
  const openPopover = armed && hasCartRow;
```

Use `hasCartRow` in place of `canAddToCart` in the cell's `onClick` and `aria-label`, and build the `cart` prop:

```tsx
          cart={
            cartGate === 'ready'
              ? { state: 'ready', onAddToCart: () => onAddToCart(id, day.date), busy: isCartActionPending(cartAction) }
              : cartGate === 'signed-out'
                ? { state: 'signed-out', onSignIn }
                : { state: 'no-credentials', onOpenSettings }
          }
```

In `AvailabilityWeek.tsx`, replace `const canAddToCart = supportsAddToCart(capabilities);` with:

```tsx
  const signedIn = Boolean(useMe().data?.user);
  const cart = cartGate(capabilities, signedIn);
```

and pass `cartGate: cart` in the view, plus `onSignIn: () => signIn()` and `onOpenSettings: () => openSettings('booking')` in events, where `const openSettings = useSettingsStore((state) => state.openSettings);`.

In `WatchEditor.tsx`, widen `ToggleRowProps['help']` to `React.ReactNode`, add optional `onSignIn?: () => void` and `onOpenSettings?: () => void` to `WatchEditorProps`, and make `atcHelp` return a node:

```tsx
function atcHelp(
  canAtc: boolean,
  cartWithoutCredentials: boolean,
  signedIn: boolean,
  onSignIn?: () => void,
  onOpenSettings?: () => void,
): React.ReactNode {
  if (canAtc) return 'Try to hold a matching site.';
  if (!cartWithoutCredentials) return 'Unavailable for this watch scope.';
  if (signedIn) {
    return onOpenSettings ? (
      <>
        <LinkButton onClick={onOpenSettings}>Add your rec.gov login</LinkButton> in Settings to hold sites.
      </>
    ) : (
      'Add rec.gov credentials in Settings'
    );
  }
  return onSignIn ? (
    <>
      <LinkButton onClick={onSignIn}>Sign in</LinkButton> to enable add-to-cart.
    </>
  ) : (
    'Sign in to enable add-to-cart'
  );
}
```

Forward both callbacks from `WatchPopover` (add them to its props and pass through) and supply them from `AvailabilityWeek` and from `AlertsPanel.tsx:270`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run`
Expected: PASS, whole suite.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(availability): name the unlock step on a gated add-to-cart"
```

---

### Task 9: Verify, document, and open the PR

**Files:**
- Create: `docs/design-references/capability-gate-hints.png`
- Modify: `docs/frontend-components.md`

- [ ] **Step 1: Run the full gate**

```bash
cd frontend && npm run typecheck && npm run lint && npm test && node ../scripts/check-color-tokens.mjs
```
Expected: all green. Fix anything that is not before continuing.

- [ ] **Step 2: Add the screenshot and a short doc note**

Copy the rendered mockup to `docs/design-references/capability-gate-hints.png`. Add a paragraph to `docs/frontend-components.md` recording the rule: gated capabilities show the control and name the unlock step, and `SettingsHost` is the modal's one mount point.

- [ ] **Step 3: Commit and push**

```bash
git add docs
git commit -m "docs: record the capability-gate hint pattern"
git push -u origin feat/gate-hints-signin-recgov
```

- [ ] **Step 4: Open the PR**

Use `.github/pull_request_template.md`'s Summary / Test plan / Notes headings, and embed the screenshot with the raw URL for this branch:

```
![Capability gate hints](https://raw.githubusercontent.com/wwchen/roadtrip/feat/gate-hints-signin-recgov/docs/design-references/capability-gate-hints.png)
```
