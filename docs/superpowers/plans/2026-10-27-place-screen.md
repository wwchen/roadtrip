# Place Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the POI drawer, the routed POI page and the availability matrix with one detented Place sheet whose stay claims come from the stay check.

**Architecture:** Three new `@ui` primitives (`Sheet`, `SheetHeader`, `SheetSection`) carry the detent mechanic; `domain/place/` holds the three components that know what a place and a night are; `features/place/PlaceSheet` composes them over the existing `POI_BLOCK_GROUPS` order, with `RULE_BEFORE_GROUP` as the half/full boundary. Every component lands with its stories before any query is wired.

**Tech Stack:** React 19 + TypeScript, Vite, `@lew-ds/lds-react` behind `@ui`, TanStack Query, Vitest + Testing Library, Storybook 9 (`@storybook/react-vite`).

**Spec:** `docs/superpowers/specs/2026-10-27-interface-redesign-design.md` — phase 1 of its Phasing section.

## Global Constraints

- **No code comments.** Not inline, not in KDoc/TSDoc on new symbols. Rationale goes in the commit message.
- **No inline magic constants.** Numeric, string and duration literals at call sites become named `const`.
- **Copy lives in `frontend/src/lib/strings.ts`.** Reuse `stayCopy.result`, `stayCopy.errorFor`, `stayCopy.checkedAgo` and `STAY_REFUSAL_COPY` as they are; extend only where a sheet says more than a card can. Aria labels stay beside the control that computes them.
- **`satisfies Record<StayAvailabilityStatus, …>`** on any new status-keyed map, so a status added server-side fails the build.
- **Never edit `frontend/src/api/generated/api-types.ts`.** No new endpoint in this phase, so no `make api-types` run and no `ApiContract.kt` row.
- **A stay claim never comes from day rollups.** `stayCopy.result` is fed `StaySummaryDto.open_sites` (sites bookable every night); `AvailabilityDay` rollups feed the nights strip and nothing else.
- **No cross-feature imports.** `npm run lint` enforces it; anything two features need lives in `domain/`, `@/lib` or `@ui`.
- **Phone viewport is the default** in stories and tests; desktop is a second story, never the first.
- Verification commands: `npm run test`, `npm run typecheck`, `npm run lint`, `npm run build-storybook`, all from `frontend/`.

## Review Focus

Five conditions the spec implies that no task's happy path exercises. Each one's test is attached to the task that owns the code.

1. **`done` arrives with `outcome: 'deadline_reached'` and no answer for the POI** — the sheet must stop saying "Checking…" and offer a retry, not hang. *(Task 6)*
2. **`from_cache: true` with `observed_at` absent** — the freshness line has nothing to date; it must be omitted rather than rendering "Checked undefined ago". *(Task 2)*
3. **A day missing from `days`, or present with an empty `cells`** — the strip must draw that night as unknown and stay tappable-or-not on its own data, never borrowing the neighbouring day's rollup. *(Task 3)*
4. **A `StayAvailabilityStatus` this build has never seen** — a newer server's status reaches a browser whose copy map predates it; the verdict falls back to `stayCopy.error` instead of rendering blank. *(Task 2)*
5. **Alert creation attempted while signed out or blocked** — the row keeps its shape and its action becomes the unlocking step, per the capability-gate rule. *(Task 4)*

---

### Task 1: The detented sheet

**Files:**
- Create: `frontend/src/ui/Sheet.tsx`, `frontend/src/ui/sheet.css`
- Create: `frontend/src/ui/Sheet.stories.tsx`, `frontend/src/ui/Sheet.test.tsx`
- Modify: `frontend/src/ui/index.ts` — add to the "Local additions" block

**Interfaces:**
- Consumes: nothing.
- Produces:
```ts
export type SheetDetent = 'peek' | 'half' | 'full';
export const SHEET_DETENTS: readonly SheetDetent[];           // ascending
export interface SheetProps { detent: SheetDetent; onDetentChange: (next: SheetDetent) => void; label: string; children?: ReactNode }
export interface SheetSectionProps { from: SheetDetent; children?: ReactNode }
export interface SheetHeaderProps { full: ReactNode; compact: ReactNode; compactFrom?: SheetDetent }
```
`SheetSection` renders its children only when the sheet's current detent is at or above `from`; `from` is required, so content with no declared level cannot compile. `SheetHeader` renders `full` at `peek` and `compact` from `compactFrom` (default `'half'`) upward.

- [ ] **Step 1: Write the failing tests**

```tsx
test('renders a section at or below the current detent', () => {
  render(<Sheet detent="half" onDetentChange={noop} label="Upper Pines">
    <SheetSection from="peek">verdict</SheetSection>
    <SheetSection from="half">nights</SheetSection>
  </Sheet>);
  expect(screen.getByText('verdict')).toBeInTheDocument();
  expect(screen.getByText('nights')).toBeInTheDocument();
});

test('does not render a section above the current detent', () => {
  render(<Sheet detent="half" onDetentChange={noop} label="Upper Pines">
    <SheetSection from="full">specs</SheetSection>
  </Sheet>);
  expect(screen.queryByText('specs')).not.toBeInTheDocument();
});

test('the header compacts from half upward', () => {
  const { rerender } = render(<Sheet detent="peek" onDetentChange={noop} label="Upper Pines">
    <SheetHeader full={<span>full header</span>} compact={<span>compact header</span>} />
  </Sheet>);
  expect(screen.getByText('full header')).toBeInTheDocument();
  rerender(<Sheet detent="half" onDetentChange={noop} label="Upper Pines">
    <SheetHeader full={<span>full header</span>} compact={<span>compact header</span>} />
  </Sheet>);
  expect(screen.getByText('compact header')).toBeInTheDocument();
  expect(screen.queryByText('full header')).not.toBeInTheDocument();
});

test('the grabber raises and lowers the detent by keyboard', async () => {
  const onDetentChange = vi.fn();
  render(<Sheet detent="peek" onDetentChange={onDetentChange} label="Upper Pines" />);
  await userEvent.type(screen.getByRole('button', { name: /upper pines/i }), '{ArrowUp}');
  expect(onDetentChange).toHaveBeenCalledWith('half');
});
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `cd frontend && npx vitest run src/ui/Sheet.test.tsx`
Expected: FAIL — `Sheet` is not exported.

- [ ] **Step 3: Implement `Sheet`, `SheetHeader` and `SheetSection` in `frontend/src/ui/Sheet.tsx`**

The current detent reaches sections through a context rather than cloned children, so a consumer may nest sections inside its own markup. The grabber is a `<button>` named for the sheet, carrying `aria-expanded`, and Arrow Up/Down step through `SHEET_DETENTS`. Heights are CSS custom properties in `sheet.css`, one per detent, so the detent is a `data-` attribute and not a style object. Dragging is a pointer-move on the grabber that commits to the nearest detent on release.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd frontend && npx vitest run src/ui/Sheet.test.tsx`
Expected: PASS (4 tests)

- [ ] **Step 5: Write the stories**

`Sheet.stories.tsx` with one story per detent plus a `Desktop` story, all using the phone viewport by default. Export the `@ui` additions from `index.ts` in the "Local additions" block.

- [ ] **Step 6: Verify the catalog and the boundary rules**

Run: `cd frontend && npm run typecheck && npm run lint && npm run build-storybook`
Expected: all three pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/ui/Sheet.tsx frontend/src/ui/sheet.css frontend/src/ui/Sheet.stories.tsx frontend/src/ui/Sheet.test.tsx frontend/src/ui/index.ts
git commit -m "feat(ui): a detented sheet whose sections declare their height"
```

---

### Task 2: `PlaceVerdict`

**Files:**
- Create: `frontend/src/domain/place/PlaceVerdict.tsx`, `PlaceVerdict.test.tsx`, `PlaceVerdict.stories.tsx`
- Create: `frontend/src/domain/place/drive-origin.ts`
- Create: `frontend/src/domain/place/stay-fixtures.ts` — `summary(over?: Partial<StaySummaryDto>): StayAnswer` and `dayOpen(date: string, open: number): AvailabilityDay`, used by every later task's tests and stories so the shape is built once
- Modify: `frontend/src/lib/strings.ts` — add only what a sheet says beyond a card

**Interfaces:**
- Consumes: `StayAnswer`, `isAnswered`, `seasonReopensOn` from `@/api/availability-api`; `stayCopy` from `@/lib/strings`.
- Produces:
```ts
export interface DriveOrigin { label: string; minutes: number | null }
export interface PlaceVerdictProps {
  name: string;
  answer: StayAnswer | null;          // null while the check is still streaming
  origin: DriveOrigin | null;
  reasons?: readonly string[];        // render-ready preference matches; empty until phase 2 ranks
  priceFrom?: string;
  fits?: string;
  reopensOn?: string;
}
```

- [ ] **Step 1: Write the failing tests**

```tsx
const summary = (over: Partial<StaySummaryDto> = {}): StayAnswer =>
  ({ poi_id: 1, outcome: 'answered', stay: { status: 'open', open_sites: 3, from_cache: false, ...over } });

test.each([
  ['open', 3, '3 sites open'],
  ['few_left', 1, '1 site left'],
  ['full', 0, 'Full'],
  ['closed_for_season', 0, 'Closed for the season'],
  ['no_data', 0, 'No data for these dates'],
] as const)('renders the %s status from stayCopy', (status, open, expected) => {
  render(<PlaceVerdict name="Upper Pines" answer={summary({ status, open_sites: open })} origin={null} />);
  expect(screen.getByText(expected)).toBeInTheDocument();
});

test('a refusal says why, not how many', () => {
  render(<PlaceVerdict name="Upper Pines" origin={null}
    answer={{ poi_id: 1, outcome: 'refused', reason: 'beyond_booking_horizon' }} />);
  expect(screen.getByText('Not bookable this far ahead yet')).toBeInTheDocument();
});

test('an unknown status falls back rather than rendering blank', () => {
  render(<PlaceVerdict name="Upper Pines" origin={null}
    answer={summary({ status: 'teleported' as StayAvailabilityStatus })} />);
  expect(screen.getByText(stayCopy.error)).toBeInTheDocument();
});

test('names the origin of the drive time', () => {
  render(<PlaceVerdict name="Upper Pines" answer={summary()} origin={{ label: 'here', minutes: 250 }} />);
  expect(screen.getByText(/4h 10m from here/)).toBeInTheDocument();
});

test('omits the freshness line when a cached answer has no observed_at', () => {
  render(<PlaceVerdict name="Upper Pines" answer={summary({ from_cache: true })} origin={null} />);
  expect(screen.queryByText(/^Checked/)).not.toBeInTheDocument();
});

test('shows the freshness line for a cached answer that has one', () => {
  render(<PlaceVerdict name="Upper Pines" origin={null}
    answer={summary({ from_cache: true, observed_at: '2026-10-27T15:47:00Z' })} />);
  expect(screen.getByText(/^Checked/)).toBeInTheDocument();
});

test('a closed season says when it reopens', () => {
  render(<PlaceVerdict name="Upper Pines" origin={null} reopensOn="2027-05-14"
    answer={summary({ status: 'closed_for_season', open_sites: 0 })} />);
  expect(screen.getByText(/reopens/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `cd frontend && npx vitest run src/domain/place/PlaceVerdict.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `driveTimeLabel(origin: DriveOrigin): string` in `drive-origin.ts` and `PlaceVerdict` in `PlaceVerdict.tsx`**

`driveTimeLabel` renders `minutes` as `Xh Ym` / `Ym` and appends `from ${label}`; `minutes: null` yields the label alone. The verdict's line is `stayCopy.result[status](open_sites)` for an answered stay, falling back to `stayCopy.error` when the status is not a key of the map, and `stayCopy.errorFor(outcome, reason ?? null)` otherwise. The freshness line is `stayCopy.checkedAgo(…)` and appears only when `from_cache` is true **and** `observed_at` is present. `answer: null` renders the name and `stayCopy.pending`. A `closed_for_season` status with `reopensOn` set renders a reopening sentence — new copy in `stayCopy`, since `stayCopy.result.closed_for_season` takes no date and a card had no room for one.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd frontend && npx vitest run src/domain/place/PlaceVerdict.test.tsx`
Expected: PASS (11 tests)

- [ ] **Step 5: Write the stories**

One story per status, one per outcome (`refused`, `timed_out`, `failed`), one stale, one pending, one `closed_for_season` carrying `reopensOn`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/domain/place frontend/src/lib/strings.ts
git commit -m "feat(place): one verdict component for every stay answer"
```

---

### Task 3: `NightsStrip` and the selected night's sites

**Files:**
- Create: `frontend/src/domain/place/NightsStrip.tsx`, `NightsStrip.test.tsx`, `NightsStrip.stories.tsx`
- Create: `frontend/src/domain/place/NightSites.tsx`, `NightSites.test.tsx`

**Interfaces:**
- Consumes: `AvailabilityDay` from `@/api/availability-api`; `summary`/`dayOpen` from `./stay-fixtures`.
- Produces:
```ts
export interface NightsStripProps {
  days: readonly AvailabilityDay[];
  dates: readonly string[];            // every night to draw, YYYY-MM-DD, in order
  selected: string | null;
  onSelect: (date: string) => void;
}
```
```ts
export interface NightSitesProps {
  day: AvailabilityDay | null;         // the selected night only
  onPick?: (campsiteId: number) => void;
}
```
`dates` is the window to draw and `days` is what the server answered for it, so a night the server omitted is still drawn — as unknown.

`NightSites` names the sites open **on the selected night**, from that day's `cells`. It is deliberately per-night: a site list for a whole stay is a stay fact, and the only read that answers it is the stay check, which returns a count rather than ids. Naming sites for one night is the grid's own answer and carries no stay claim — which is also why a user who picked a night is the only user who sees site numbers.

- [ ] **Step 1: Write the failing tests**

```tsx
test('draws a night the server omitted as unknown and unselectable', async () => {
  const onSelect = vi.fn();
  render(<NightsStrip dates={['2026-10-25', '2026-10-26']} days={[dayOpen('2026-10-25', 3)]}
    selected={null} onSelect={onSelect} />);
  const omitted = screen.getByRole('button', { name: /26/ });
  expect(omitted).toBeDisabled();
  await userEvent.click(omitted);
  expect(onSelect).not.toHaveBeenCalled();
});

test('a day with no cells is unknown, not borrowed from its neighbour', () => {
  render(<NightsStrip dates={['2026-10-25', '2026-10-26']}
    days={[dayOpen('2026-10-25', 3), { ...dayOpen('2026-10-26', 0), cells: [] }]}
    selected={null} onSelect={noop} />);
  expect(screen.getByRole('button', { name: /26/ })).toHaveAttribute('data-state', 'unknown');
});

test('a night with sites reports its own count and selects', async () => {
  const onSelect = vi.fn();
  render(<NightsStrip dates={['2026-10-25']} days={[dayOpen('2026-10-25', 3)]} selected={null} onSelect={onSelect} />);
  await userEvent.click(screen.getByRole('button', { name: /25/ }));
  expect(onSelect).toHaveBeenCalledWith('2026-10-25');
});
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `cd frontend && npx vitest run src/domain/place/NightsStrip.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `NightsStrip`**

Each date is a button carrying `data-state` of `open | full | unknown` and its own count; a date with no matching day, or a day with no cells, is `unknown` and disabled. The accessible name is the date plus its state, computed beside the button.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd frontend && npx vitest run src/domain/place/NightsStrip.test.tsx`
Expected: PASS (3 tests)

- [ ] **Step 5: Write the failing test for `NightSites`, implement it, and watch it pass**

```tsx
test('names the sites open on the selected night', () => {
  render(<NightSites day={dayOpen('2026-10-25', 3)} />);
  expect(screen.getAllByRole('listitem')).toHaveLength(3);
});

test('renders nothing when no night is selected', () => {
  const { container } = render(<NightSites day={null} />);
  expect(container).toBeEmptyDOMElement();
});
```

Run: `cd frontend && npx vitest run src/domain/place/NightSites.test.tsx`
Expected: FAIL, then PASS (2 tests)

- [ ] **Step 6: Write the stories, then commit**

Stories: a full week open, a week with two shut nights, a week with a gap in `days`, and a week of unknowns.

```bash
git add frontend/src/domain/place
git commit -m "feat(place): a nights strip that never borrows a neighbour's rollup"
```

---

### Task 4: `AlertRow`

**Files:**
- Create: `frontend/src/domain/place/AlertRow.tsx`, `AlertRow.test.tsx`, `AlertRow.stories.tsx`, `watch-gate.ts`

**Interfaces:**
- Consumes: `@ui`'s `Row` and `Button`; `watchCopy` from `@/lib/strings`.
- Produces:
```ts
export type WatchGate = 'ready' | 'signed-out' | 'blocked';
export interface AlertRowProps {
  night: string;                       // the shut night, YYYY-MM-DD
  gate: WatchGate;
  pending?: boolean;
  onCreate: (night: string) => void;
  onSignIn?: () => void;
}
```

- [ ] **Step 1: Write the failing tests**

```tsx
test('offers the alert when the gate is ready', async () => {
  const onCreate = vi.fn();
  render(<AlertRow night="2026-10-27" gate="ready" onCreate={onCreate} />);
  await userEvent.click(screen.getByRole('button', { name: /alert me/i }));
  expect(onCreate).toHaveBeenCalledWith('2026-10-27');
});

test('keeps its shape when signed out and offers the unlocking step', async () => {
  const onSignIn = vi.fn();
  render(<AlertRow night="2026-10-27" gate="signed-out" onCreate={noop} onSignIn={onSignIn} />);
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(onSignIn).toHaveBeenCalled();
});

test('falls back to inert text only when blocked', () => {
  render(<AlertRow night="2026-10-27" gate="blocked" onCreate={noop} />);
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/domain/place/AlertRow.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `WatchGate` in `watch-gate.ts` and `AlertRow` over `@ui`'s `Row`**

`ready` and `signed-out` both render a button, per the capability-gate rule; only `blocked` is inert. `pending` disables the button and shows the in-flight label.

- [ ] **Step 4: Run them and watch them pass, then story and commit**

Run: `cd frontend && npx vitest run src/domain/place/AlertRow.test.tsx`
Expected: PASS (3 tests)

```bash
git add frontend/src/domain/place
git commit -m "feat(place): the alert offer is a shut night, gated three ways"
```

---

### Task 5: `PlaceSheet`

**Files:**
- Create: `frontend/src/features/place/PlaceSheet.tsx`, `PlaceSheet.test.tsx`, `PlaceSheet.stories.tsx`
- Read for the block order: `frontend/src/domain/poi/blocks.ts`, `PoiPageShell.tsx`

**Interfaces:**
- Consumes: `Sheet`/`SheetHeader`/`SheetSection` from `@ui`; `PlaceVerdict`, `NightsStrip`, `NightSites`, `AlertRow` from `@/domain/place`; `POI_BLOCK_GROUPS`, `RULE_BEFORE_GROUP`, `POI_FOOTER_BLOCKS`, `PoiBlockSlots` from `@/domain/poi/blocks`; `summary`/`dayOpen` from `@/domain/place/stay-fixtures` in its tests.
- Produces:
```ts
export type PlaceArrival = 'from-map' | 'cold';
export interface PlaceSheetProps {
  name: string;
  arrival: PlaceArrival;
  verdict: ComponentProps<typeof PlaceVerdict>;
  nights: ComponentProps<typeof NightsStrip>;
  alert?: ComponentProps<typeof AlertRow>;
  blocks: PoiBlockSlots;
}
```
Blocks above `RULE_BEFORE_GROUP` render in a `SheetSection from="half"`, the rest and the footer blocks in `from="full"`, in `POI_BLOCK_GROUPS` order.

- [ ] **Step 1: Write the failing tests**

```tsx
test('arriving from the map opens at half, not peek', () => {
  render(<PlaceSheet {...props} arrival="from-map" />);
  expect(screen.getByTestId('place-sheet')).toHaveAttribute('data-detent', 'half');
});

test('arriving cold opens at peek', () => {
  render(<PlaceSheet {...props} arrival="cold" />);
  expect(screen.getByTestId('place-sheet')).toHaveAttribute('data-detent', 'peek');
});

test('the verdict ignores the grid when the stay says full', () => {
  render(<PlaceSheet {...props} arrival="cold"
    verdict={{ ...props.verdict, answer: summary({ status: 'full', open_sites: 0 }) }}
    nights={{ ...props.nights, days: [dayOpen('2026-10-25', 3), dayOpen('2026-10-26', 3)] }} />);
  expect(screen.getByText('Full')).toBeInTheDocument();
  expect(screen.queryByText(/sites open/)).not.toBeInTheDocument();
});

test('renders body blocks in POI_BLOCK_GROUPS order', () => {
  render(<PlaceSheet {...props} arrival="cold" blocks={everyBlock} />);
  const rendered = screen.getAllByTestId(/^block-/).map((el) => el.dataset.testid);
  expect(rendered).toEqual(expectedOrderFromBlocks());
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/place/PlaceSheet.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `PlaceSheet`**

The detent is local state seeded from `arrival`; the mapping from arrival to seed is a named constant, not a ternary at the call site. A group renders only when something in it does, so an omitted group leaves no stray hairline — the same rule `PoiPageShell` already keeps.

- [ ] **Step 4: Run them and watch them pass**

Run: `cd frontend && npx vitest run src/features/place/PlaceSheet.test.tsx`
Expected: PASS (4 tests)

- [ ] **Step 5: Write the stories**

The four answer states from the spec's matrix, plus a `closed_for_season` sheet and a sheet whose place omits the availability group entirely (a dropped pin).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/place
git commit -m "feat(place): the sheet, opening where the arrival says"
```

---

### Task 6: Wire the data

**Files:**
- Create: `frontend/src/features/place/use-place-stay.ts`, `use-place-stay.test.ts`
- Create: `frontend/src/features/place/PlaceSurface.tsx`
- Modify: `frontend/src/pages/map/MapPage.tsx` — mount `PlaceSurface` where `PoiDrawer` was
- Modify: `frontend/src/pages/poi/PoiPage.tsx` — render `PlaceSurface` with `arrival="cold"`

**Interfaces:**
- Consumes: `streamStays`, `isAnswered`, `requestPoiCampsitesAvailability` from `@/api/availability-api`; `PlaceSheet` from Task 5.
- Produces:
```ts
export type PlaceStayState =
  | { kind: 'checking' }
  | { kind: 'answered'; answer: StayAnswer }
  | { kind: 'unanswered' };            // the stream ended with no answer for this POI
export function usePlaceStay(poiId: number, nights: { start: string; end: string }): PlaceStayState;
```

- [ ] **Step 1: Write the failing tests**

```ts
test('resolves the one answer for the POI', async () => { /* stubbed stream: one answer, then done complete */ });

test('a deadline-reached done with no answer resolves unanswered, not checking', async () => {
  // stubbed stream: no answer events, then { kind: 'done', done: { outcome: 'deadline_reached' } }
  const { result } = renderHook(() => usePlaceStay(1, nights), { wrapper });
  await waitFor(() => expect(result.current.kind).toBe('unanswered'));
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/place/use-place-stay.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `usePlaceStay` over `streamStays` with a single-element `poiIds`**

The query consumes the generator to `done` and keeps the last answer whose `poi_id` matches; reaching `done` without one yields `unanswered`, which is what the sheet renders a retry from. `PlaceSurface` composes the hook, the week request and `PlaceSheet`, and owns nothing else.

- [ ] **Step 4: Run them and watch them pass**

Run: `cd frontend && npx vitest run src/features/place/use-place-stay.test.ts`
Expected: PASS (2 tests)

- [ ] **Step 5: Run the existing page tests**

Run: `cd frontend && npm run test`
Expected: `MapPage.test.tsx` and `PoiPage` tests pass against the new mount, or are updated in this commit to name `PlaceSurface`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/place frontend/src/pages/map/MapPage.tsx frontend/src/pages/poi/PoiPage.tsx frontend/src/pages/map/MapPage.test.tsx
git commit -m "feat(place): mount the sheet on the map and the routed page"
```

---

### Task 7: Delete what the sheet replaces

**Files:**
- Delete: `frontend/src/features/drawer/`, `frontend/src/features/availability/SiteMatrix.tsx`, `SiteMatrix.stories.tsx`, `SiteList.tsx`, `matrix-rows.ts`, `DayDetail.tsx`, `AvailabilityWeek.tsx`, `WeekNav.tsx` and their tests
- Delete: `frontend/src/pages/availability/`, `frontend/availability.html`
- Modify: `frontend/vite.config.ts` — drop the `availability` entry
- Modify: `frontend/src/api/api-endpoints.test.ts` — whatever it pins about now-unreached paths

**Interfaces:**
- Consumes: Task 6's mounts, which are what make these unreachable.
- Produces: nothing.

- [ ] **Step 1: Prove they are unreachable**

Run: `cd frontend && grep -rn "SiteMatrix\|AvailabilityWeek\|PoiDrawer\|pages/availability" src/ --include=*.tsx --include=*.ts | grep -v "features/availability\|features/drawer\|pages/availability"`
Expected: no hits outside the directories being deleted.

- [ ] **Step 2: Delete the files and the Vite entry**

- [ ] **Step 3: Run everything**

Run: `cd frontend && npm run test && npm run typecheck && npm run lint && npm run build && npm run build-storybook`
Expected: all pass. A failure here names a consumer step 1 missed.

- [ ] **Step 4: Commit**

Stage the deletions and the two edited files by explicit path — never `git add -A` or `git add -u`, because this checkout is shared with other sessions:

```bash
git rm -r frontend/src/features/drawer frontend/src/pages/availability frontend/availability.html
git rm frontend/src/features/availability/SiteMatrix.tsx frontend/src/features/availability/SiteMatrix.stories.tsx \
       frontend/src/features/availability/SiteList.tsx frontend/src/features/availability/matrix-rows.ts \
       frontend/src/features/availability/DayDetail.tsx frontend/src/features/availability/AvailabilityWeek.tsx \
       frontend/src/features/availability/WeekNav.tsx
git add frontend/vite.config.ts frontend/src/api/api-endpoints.test.ts
git commit -m "refactor(place): delete the matrix page and the drawer split"
```

---

### Task 8: The catalog's own documentation

**Files:**
- Create: `frontend/src/domain/place/place.mdx`

**Interfaces:**
- Consumes: the components from Tasks 1–5.
- Produces: nothing.

- [ ] **Step 1: Write the page**

An autodocs page carrying the where × when matrix, the detent table, and the rule that a stay claim never comes from day rollups — the spec's three rules, stated where the next person meets the components rather than only in the spec.

- [ ] **Step 2: Verify and commit**

Run: `cd frontend && npm run build-storybook`
Expected: PASS, with the page listed.

```bash
git add frontend/src/domain/place/place.mdx
git commit -m "docs(place): the rules, in the catalog with the components"
```
