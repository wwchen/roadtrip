# The interface: one question, three screens

The product today is four pages, thirteen blocks, a filter panel, a legend, a
basemap picker, a watch table, a trigger selector and an 857-line availability
matrix. It presents inventory. A camper at 4pm does not have an inventory
problem — they have a decision problem, and every screen in the product hands
them the inputs to that decision instead of its answer.

This redesigns the user-facing interface from scratch around a single rule. The
pollers/runs/changes dashboard is operator surface and is out of scope.

## The brief

Phone first, on the road. The question is always some form of *where am I
sleeping*, asked in one of four ways:

- "I'm on the road this week; the route is already picked."
- "The weekend of Oct 25, flexible on where."
- "Upper Pines, whichever weekend works."
- "Upper Pines, Oct 25" — and nothing is open.

A place is decided on three things and no others: **can I get a site**, **how
far and how far off my path**, **what it is like when I arrive**. Per-carrier
cell coverage and Supercharger proximity are data the product holds; they are
not decision inputs and do not appear in a candidate's reasoning.

## The rule

Two knobs — **where** and **when**.

*Where* is **pinned** (one place) or **open** (an area). *When* has three
values, not two: **a night**, **a window** of nights, or **open**. The third
value is not symmetry for its own sake — a trip pins a route and a five-night
window and then answers night by night, so a two-value *when* is broken by the
first state this design has.

> **The narrowest pinned knob titles the screen. The widest open knob becomes
> the answer.**

| where | when | screen | the answer is |
|---|---|---|---|
| route pinned | a window | **Your nights** | one row per night, gaps first |
| open | a night or a window | **Where** | ranked places on a map |
| pinned | open | **Place** | which dates are open |
| pinned | a night or window, nothing open | **Place**, empty | an alert, or the reopening date |

**Pinning a knob is a navigation; unpinning it is going back.** There is no
other navigation model in the product — no tab bar, no menu, no modal routing.
A notification that fires opens the Place screen with both knobs pinned, which
is the one state the user asked to be told about.

### Where the clock starts

Every screen states a drive time, so every screen must say what it is measured
from, and the answer differs by state. Unstated, it would be inconsistent across
three screens and silently wrong in the ranking, since detour is derived from it:

| state | origin | shown as |
|---|---|---|
| on a trip, tonight's night | current position | `4h 10m from here` |
| on a trip, a later night | the previous settled night, else the route's start | `2h 30m from Lassen` |
| a weekend, no trip | the profile's home | `4h 10m from Oakland` |
| no position and no home | the area's own centre, and the time is omitted | distance only |

The origin is never implicit: the candidate card and the Place peek both name
it. A phone that has refused location permission falls to the row below it
rather than guessing.

## Screen 1 — Your nights

Exists only when a trip exists. Title is the route (`Oakland → Bend · 5
nights`). Body is one row per night, in date order, each row in exactly one of
four states:

- **Settled** — place name and site number. One line, no verdict, no photo.
- **Empty, candidates exist** — `Nobody yet` plus how many places in the corridor
  can hold that night, from the stay check, and the one button that matters.
  Tonight's empty row is the only element in the product that gets a tinted
  background.
- **Empty, nothing open** — `Nobody yet · nothing open` and the number of
  alerts already working that night.
- **Alerting** — a night with live alerts shows only that; it is not re-offered
  as a search.

Tapping an empty night pins *when* to that night and goes to **Where**. The
trip's stops are a consequence of what was settled, never a list the user
maintains: there is no reorder, no drag, no insert.

## Screen 2 — Where

Map, full screen, with the question as a pill at the top: `Yosemite · Oct
25–26`. Its left half re-opens the area, its right half the dates. The pill and one
candidate card are the only things that float over the map.

Pins carry the stay check's `open_sites` — sites that can hold the *whole* stay,
not a per-night count — on **every** pin, including the selected one,
because the map's job is to rank at a glance and a pin that changes meaning when
tapped is a rule the user has to learn. One card sits above the map's bottom
edge for the selected candidate:

```
Upper Pines
Open both nights · showers · quiet      ← the ranking, explaining itself
4h 10m from here · $36 · fits 19 ft     ← the facts a pin cannot carry
Checked 20 min ago                      ← only when the answer is not live
[ Reserve ]
```

The green line is composed from the user's own rig preferences, in their words,
and is the only justification the interface ever offers. Swiping the card moves
the map; tapping it pins the place and goes to **Place**.

**The freshness line is not optional furniture.** A stay answer carries
`observed_at` and `from_cache`; a cached count can be wrong by the time it is
read, and the worst failure this product has is someone driving two hours on a
stale number. The line appears whenever the answer is not live, and the old POI
page's verified/provenance footer survives in the Place sheet for the same
reason.

The area comes from one of three things, which are one thing — a polygon: the
route corridor, a named region, or the viewport.

## Screen 3 — Place

No map. The place is pinned, so geography is settled and showing it again would
be the duplication this design exists to avoid. (A static approach map inside
*Getting there* is a different fact — the last mile — and is allowed.)

The screen is a sheet with three heights, and **a fact belongs to exactly one
height**:

- **Peek** — should I go here: name, verdict, the three deciding facts, the
  origin of the drive time, freshness if stale, Reserve.
- **Half** — which nights, exactly: the nights strip (each night tinted with its
  open count, the chosen night solid, shut nights dimmed), the sites open on the
  night you picked, Reserve, and — only if a night in view is shut — one row
  offering to alert on it.

  **Not the price.** The wire carries one `price` per POI — a `PriceDto` of
  `minimum`/`maximum`/`currency` on the POI detail — and no per-night, per-site
  figure anywhere. So "`$36/night`" in the sketch above was a number this product
  cannot produce. The place-level *from* price belongs to Peek, said once, and
  Half does not repeat it.
- **Full** — what it is like on arrival: photos, getting there, good to know,
  specs, contact, nearby, and the verified/provenance footer. The header
  compacts to a line; it does not repeat.

**Arriving from Where opens the sheet at Half, not Peek.** Peek's content is
exactly the candidate card the user just tapped, so presenting it again is the
repetition this design exists to prevent — sharing one `PlaceVerdict` component
makes the two states *consistent*, which is not the same thing as not saying it
twice. Peek is for cold arrivals only: a notification, a deep link, or a place
reached by name rather than off the map.

When *when* is open rather than pinned, Half becomes the whole screen and leads
with a sentence rather than a strip: *"Nov 8–9 is your first open weekend,"*
followed by every weekend in the horizon as a bar. The user asked an open
question and gets a stated answer, not a grid to read.

`domain/poi/blocks.ts` already encodes this fold — but **not** at
`RULE_BEFORE_GROUP`, which an earlier draft of this document asserted without
checking. That constant sits after `gettingThere`/`goodToKnow`, and those two
answer *what is it like when I arrive*, which is Full. The sheet's Half/Full
boundary is therefore after the `glance` group, and the rule hairline keeps its
own, separate meaning in the block order.

The sheet also renders **no `hero`**. A 176px photo at the top of a 395px Half
detent pushes `actions` — Reserve — below the fold, and Reserve is the one thing
Half must guarantee; a type omitting a block is already how this order works.
The consequence is that the sheet shows no photographs at all in phase 1, which
is a real gap against the Full list above and is left open rather than solved by
reordering: moving `hero` into Full would put it after `glance` on screen, and
"the sequence never changes" is the older and stronger rule. The block order survives this
redesign unchanged; what changes is that the groups become sheet detents instead
of scroll position, and the drawer/page variant split disappears, because the
sheet is the only presentation.

### The empty state is the feature

Both pinned, nothing open:

```
Nothing open.
I'll watch it for you.
Cancellations here land 3–4 times a week, usually 2 weeks out.
[ Alert me ]

If you'd rather not wait
  Hodgdon Meadow — same weekend, 30 min further out
  Upper Pines — Nov 8–9 instead
```

The cadence sentence is not copy; it is computed from
`/api/availability/changes` history for that POI. This is the single highest-
value screen in the product and today it does not exist.

**It is wrong for a campground that is shut for the winter**, which is most of
the ones this design keeps using as examples. Upper Pines in late October is a
`closed_for_season` answer, not a `full` one, and offering to watch it is
offering an alert that cannot fire. That state gets its own sentence and its own
next action — *Closed until mid-May. Booking opens six months ahead; I'll tell
you when Upper Pines appears* — and the alternatives below it are places that
are actually open, not later dates at a closed one.

## The answer vocabulary is the server's

The screens above were drawn before this section was written, and drawing them
first is how two of them ended up assuming states the backend had already
enumerated. Every status and every outcome below is decided server-side and
generated into the frontend types; the browser renders the branch it is given
and invents no vocabulary of its own.

### One stay is one read

`POST /api/pois/availability/stays` answers *how does this stay look here*, and
its `open_sites` is defined as campsites bookable online **for every night of
the stay**. The per-day grid answers a different question and its day rollups
are per-night counts.

> **A stay claim never comes from day rollups.** "Open both nights" and anything
> behind a Reserve button read the stay check. The nights strip reads the grid
> and is per-night context only — it is never summed into a stay claim.

Three sites open Saturday and three open Sunday can mean no single site is open
both nights; `reservation-providers.md` already names the distinction
(`same_site` versus `any_combination` in watch evaluation), and
`frontend-components.md` already forbids letting a rollup stand in for a cell.
A Reserve button built on summed rollups is a button that lies, and the first
draft of this spec built one.

### Every status gets a sentence

**This copy already exists and is not rewritten.** `lib/strings.ts` ships
`stayCopy.result`, keyed by every `StayAvailabilityStatus` and typed
`satisfies Record<…>` so a new status fails the build; `stayCopy.errorFor
(outcome, reason)` for refusals, timeouts and failures, with
`beyond_booking_horizon`, `start_before_earliest` and `window_too_long` already
worded; and `stayCopy.checkedAgo(ago)` for the freshness line. The map's
existing availability check renders all of it today. **These states were missing
from the redesign, not from the product** — which is the more embarrassing of
the two, and the work is to carry the existing vocabulary onto the new screens,
extending the copy only where a sheet says more than a card can.

`StayAvailabilityStatus` — the Where card and the Place peek each render exactly
one of these, never a count dressed up as prose:

| status | what the screen says | next action |
|---|---|---|
| `open` | open, with the nights named | Reserve |
| `few_left` | open, and stated as scarce — distinct copy, not a smaller number | Reserve, sooner |
| `full` | nothing open | alert on the shut night |
| `closed_for_season` | closed, with when it reopens | alert on reopening, or see open places |
| `no_data` | we do not know yet | retry, and never a reassuring blank |

### Every refusal and failure gets one too

`refused` is about the dates and asking again cannot help:
`start_before_earliest` (the campground's season has not begun),
`beyond_booking_horizon` (past what the vendor sells yet — the weekend bars will
hit this routinely, since vendors sell roughly six months out, and the bar list
must end where the horizon does and say so), `window_too_long`.

`failed` carries a server-stated `retryable`, so the screen offers a retry only
when the server says one would help. `timed_out` is always worth asking again
and shows a retry.

Each of these is a state of the Where card and of the Place sheet, with copy in
`lib/strings.ts`, and each has a story. A state with no story is a state nobody
has looked at — and the previous draft of this spec demanded that coverage while
never specifying what the states looked like.

## Alerts

A watch is **a night you tapped on a place you were already looking at**. It is
created from the Place screen's shut-night row and from nowhere else. It is
surfaced as a notification that reopens that Place screen. It is cancelled from
the same row it was created from.

There is no watch list, no watch table, no trigger selector, no watch form, and
no page that enumerates watches. The existing per-watch Slack/email delete and
modify links keep working — they are the management surface, and they already
exist.

### What that costs, and what we keep anyway

A watch has **one or more targets**, each a `poi_id` or a `campsite_id`. A night
tapped on a place creates exactly one POI target, so taken literally this design
deletes two capabilities the API already has:

- **Many places, one weekend** — "tell me if anything in this result set opens."
  This is a real need and the Where screen is exactly where it is felt, so it
  keeps an action: alerting from the result set creates one watch with a target
  per candidate. It is the one watch a user can create without first pinning a
  place.
- **One specific site** — "site 42, because it is the one by the creek." Created
  from a site in the Half detent's stay list, which is the only place a user
  ever sees a site number.

Both are the same watch resource with a different target list, so neither needs
a form, a table, or a page. What is genuinely gone is *editing* a watch's
targets in-app: a watch is created from what the user was looking at, and
changed by deleting it and tapping again, with the Slack/email links as the
out-of-app path.

Trigger kinds and `watch_capabilities` gating stay exactly as the backend
defines them; the capability-gate rule from `docs/frontend-components.md` is
unchanged — a gated control keeps its shape and its action becomes the step that
unlocks it.

## The rig profile

Replaces the filter panel, the group-size stepper and the site-type dropdown.

- **Constraints** — rig type, length, party size. A site that cannot fit the rig
  is never a result; it is not a filtered-out result, so there is nothing to
  un-filter.
- **Preferences** — a short chip set (showers, water, hookups, quiet, dump
  station, pets). These never hide a place. They order the candidates and they
  compose the green line.

Set once, edited in settings, **never present as a control on the map**. The
accepted cost: changing "actually I don't care about showers tonight" is a trip
to settings. We take that cost because a filter button is an admission the
ranking cannot be trusted, and because the existing panel is the proof that such
a button grows.

## Component hierarchy

The layering from `docs/frontend-components.md` is unchanged — `@ui` primitives,
then `domain/` for things that know what a place or a night is, then `features/`
for surfaces, then `pages/` for composition, and no feature imports another. What
follows is the hierarchy this design adds inside those rules.

**Two different rules, often confused — this spec confused them in its first
draft:**

- *If two screens would state the same fact, they share one component.* This
  buys **consistency**: the card and the sheet cannot phrase a verdict
  differently. It does nothing about repetition — a shared component rendered in
  both places says the same thing twice, reliably.
- *A fact is rendered at one place in the flow.* This buys **non-repetition**,
  and it is enforced by two things that are not component sharing: `SheetSection`
  levels, and the detent a screen opens at (arriving from Where opens Half,
  because Peek is the card you just read).

### New in `@ui` (generic; knows nothing about campgrounds)

| Component | Why it is a primitive |
|---|---|
| `Sheet` | The detented bottom sheet: `peek \| half \| full`, controlled, drag and programmatic. Nothing in it is campground-specific. |
| `SheetHeader` | Compacts as the sheet rises instead of repeating what the body already said. |
| `SheetSection` | Declares the detent at which its content first appears. This is what makes "a fact belongs to exactly one height" a **type-level** rule: content without a declared level does not render. |
| `Stepper` | Numeric +/− row. Absorbs `GroupSizeStepper`, which was a feature component doing a primitive's job. LDS has no stepper. |
| `ChipGroup` | Layout and `role="group"` semantics over LDS's own `Chip`, which already has `selected`. Not a new chip. |

`MonthCalendar` already exists as a local addition and is the date-pinning
control; nothing new is needed for *when*.

**Verified against the installed LDS source**
(`@lew-ds/lds-react/src/index.d.ts`), which ships: Avatar, Banner, Button,
ButtonGroup, Card, Checkbox, Chip, CodeField, EmptyState, Icon, Inline, Link,
Menu, Modal, Nav, Radio, Row, Select, SegmentedControl, Skeleton, Table, Tabs,
Tag, TextField, Textarea, Toast, ToastProvider, Toggle, Tooltip. Three rows came
off this table as a result:

- `DefinitionRow` — **deleted**. `Row` takes `lead`/`title`/`subtitle`/`trail`/
  `chevron`, which is the *Full* detent's row, the nights list's row and the
  alert row.
- `ChoiceChips` — **reduced** to `ChipGroup`, because `Chip` already has
  `selected`.
- The answer states need no custom markup: `EmptyState`
  (`title`/`body`/`actions`) is the shape of closed-for-season, no-data and
  refused; `Banner` carries a stale or retryable answer; `Skeleton` covers a
  stay still streaming.

No sheet or drawer exists in LDS, so `Sheet`, `SheetHeader` and `SheetSection`
are the only genuinely new primitives in phase 1.

### New in `domain/`

These know the vocabulary and own no surface. They are where the redesign's
rules actually live:

- `domain/place/PlaceVerdict` — name, the reason line, the deciding facts, the
  drive-time origin, and freshness when stale. One component, two consumers (the
  Where candidate card and the Place sheet's cold-arrival Peek), so the two
  cannot phrase a verdict differently — and they are never both on the path at
  once, which is the detent rule's job, not this one's.
- `domain/place/NightsStrip` — per-night rollups: open count, chosen, shut.
- `domain/place/SeasonBars` — the weekend horizon, for open-*when*.
- `domain/place/AlertRow` — the shut-night offer; the only creator of a watch.
- `domain/place/CadenceLine` — the "cancellations land 3–4 times a week"
  sentence, from changes history.
- `domain/trip/NightRow` — one night in its four states.
- `domain/poi/blocks.ts` — **kept as-is**. It stays the only place the order
  lives; `SheetSection` levels are layered onto the existing groups, with
  `RULE_BEFORE_GROUP` as the half/full boundary it already describes.

### New in `features/`

- `features/where/` — `QuestionPill`, `CandidatePins` (the imperative MapLibre
  layer, per the existing map exception), `CandidateCard`.
- `features/place/` — `PlaceSheet`, which composes `Sheet` with the block order;
  this replaces both `PoiDrawer` and `PoiPageShell`'s variant split.
- `features/nights/` — `NightsList`.
- `features/rig/` — `RigPanel`, mounted in the existing `SettingsHost`.

### Dependency direction

```
pages/{where,place,nights}
   └── features/{where,place,nights,rig}
          └── domain/{place,trip,poi}
                 └── @ui  ──  @lew-ds/lds-react
```

`PlaceVerdict` is imported by two features, which is exactly why it is in
`domain/` and not in either of them.

## Storybook is the catalog, and it comes first

Storybook is already configured (`frontend/.storybook/`, a11y and docs addons,
stories beside their components). This design makes it the place the interface is
judged, not an afterthought:

- **Build order inside every phase is primitive → domain → feature → screen, and
  each one lands with its stories before it is wired to real data.** The design
  is reviewable in Storybook before a single query runs, which is what the
  throwaway HTML mockups were standing in for.
- **Except the map.** Pins are imperative MapLibre layers that own their own
  DOM, so the catalog can hold the `QuestionPill` and the `CandidateCard` but
  cannot judge the Where screen as a whole. That screen is verified in a browser
  against a real map, and saying otherwise would make the catalog look more
  authoritative than it is.
- **A story per state, and the state list is the wire's, not ours.** Every
  availability state, both watch gates, all four cart gate states, each night
  state, and every empty state. A state that has no story is a state nobody has
  looked at.
- **Screen-level stories** for the four answer states compose features over
  mocked query data. Production composition still belongs in `pages/`; these are
  the visual equivalent of the page tests that already do this.
- **The phone is the default viewport** in `preview.tsx`, with a desktop story
  per screen as the second case rather than the first.
- **The rules live next to the components.** An autodocs page carries the
  where × when matrix, the detent table, and the one-component-two-consumers
  rule, so the next person reads them in the catalog instead of in this spec.
- `npm run build-storybook` stays a verification step, so a broken catalog
  fails the build the way a stale `api-types.ts` already does.

## What this needs from the backend

Layering per `AGENTS.md`: every item below is a route over a service over a repo,
every new route gets its `ApiContract.kt` row with the status of each body it
serves, and `make api-types` carries the DTOs to the frontend.

1. **A ranked answer, not a verdict set.** `POST /api/pois/availability/stays`
   already streams a per-POI stay verdict, which is the hard half. What it does
   not do is order the results or say why. Ranking by detour, preference match
   and open count belongs in a service — the browser must not re-derive an order
   from facts, the same way it does not re-derive availability. The reason line
   ships render-ready, like `label` and `kind_label` already do, so the
   frontend keeps no vocabulary of its own.
2. **Per-night rollups over a season.** The nights strip reads the existing
   `GET /api/pois/{id}/campsites/availability` day rollups. The weekend bars
   need the same rollup over a multi-week horizon; whether that is a widened
   window or its own read is an implementation decision, not a design one.
3. **Cancellation cadence per POI.** A summary over
   `/api/availability/changes` for one POI: how often sites free up, and the
   typical lead time. The data is already recorded.
4. **Rig and preferences on the profile.** New fields on
   `/api/settings/profile`; the constraint half must reach the ranking service,
   not be applied in the browser.
5. **Trips.** Origin, destination, and a night per date. Today a trip lives in a
   store and a share link; the Your nights screen needs it to survive a reload
   on a phone.
6. **The reopening date for a closed season — already served.** The provider's
   season block rides the wire and `availability-api.ts` already narrows it with
   `seasonReopensOn(season)`. Nothing new is needed; the Place sheet reads it
   where today only one call site does.

### The honest gap: we do not know what you booked

Booking completes on Recreation.gov. Nothing reports back. "Tue 28 · Lassen ·
site 18" is a line this product currently cannot produce, and the Your nights
screen is built around it.

The fix is to ask, once, at the only moment the user knows the answer: when they
return from a booking hand-off, the night they were booking asks *did you get
it?* — one tap, yes or no. A yes settles the night; a no leaves it empty and
offers the alert. No inference, no scraping, no second system of record.

This is a product decision with a real cost (a night can be wrong if the user
ignores the prompt) and it is the one piece of the design that adds a question
rather than removing one.

**If it is rejected, more goes with it than one row state.** Screen 1 says the
trip's stops are a consequence of what was settled — that model is downstream of
knowing what was booked. Without the tap there is no settled night, so there is
no itinerary either, and Your nights degrades to a list of gaps and live alerts
with no record of the trip taking shape. That is still worth building, and it is
a materially smaller product than the one drawn above. The alternative is a
manual "I booked this" affordance, which is the same tap asked at a worse
moment.

## Deletions

Code that this design removes outright, not relocates:

- `pages/watches/` and `watches.html`; `features/watches/WatchTable.tsx`,
  `WatchForm.tsx`, `TriggerSelector.tsx`, `ManageWatchCard.tsx`
- ~~`pages/availability/` and `availability.html`~~ — **wrong, and kept.** This
  is not the matrix page. It is the shell for the operator Availability
  Dashboard: it mounts `ChangesTab`, `PollersTab`, `RunsTab` and `TabNav` from
  `features/availability-dashboard`, which the top of this document puts out of
  scope. The backend serves `/availability` from it, the watches page links to
  it, and `page-shells.test.ts` and the smoke test pin it. An implementer
  refused this instruction and was right to.
- `features/availability/SiteMatrix.tsx` (857 lines), `SiteList.tsx`,
  `matrix-rows.ts` — per-campsite × per-day grids are not a thing a phone shows
- `features/map/LegendPanel.tsx`, `BasemapPicker.tsx` — a legend explains a
  symbol set that this design does not have, and the basemap is a preference
- `features/trip/FilterPanel.tsx`, `GroupSizeStepper.tsx`, `CorridorSlider.tsx`,
  `StopRow.tsx` and the drag-to-reorder path in `TopBar.tsx`
- `features/drawer/` — the drawer/page duplication of the POI surface
- `features/alerts/AlertsPanel.tsx`

Retained and re-used: `domain/poi/blocks.ts` and its order, `PoiPageShell`'s
"render what you are told, in this order" contract, `features/availability/
site-detail-facts.ts`, `lib/strings.ts` as the one home for copy, the capability
gate rules, and every `@ui` primitive.

## Phasing

One design, four shippable pieces, each a plan of its own:

1. **Place screen** — the sheet and its detents: the nights strip from the grid,
   the verdict and the Reserve action from the stay check. Deletes the
   drawer/page split and the matrix page. The open-*when* variant (the weekend
   bars) waits on read 2 and lands with it.
2. **Where screen** — map, pins with counts, the candidate card. Needs the
   ranked answer and the rig profile.
3. **Alerts as a night** — the shut-night row and the empty state, including the
   cadence sentence. Deletes the watches surface.
4. **Your nights** — trips, the night states, the did-you-get-it prompt.

Phase 1 adds no endpoint — it calls the existing stay check with a single POI
beside the existing grid — but it is **not** rollup-only, which is what the first
draft of this spec assumed and *One stay is one read* corrects. It remains the
honest test of whether the non-repetition rule holds in real copy with real
data. It also front-loads every
new `@ui` primitive — `Sheet`, `SheetHeader`, `SheetSection`, `DefinitionRow` —
so phases 2 to 4 compose an existing catalog rather than inventing one each.
`Stepper` and `ChoiceChips` arrive with the rig profile in phase 2.

## Risks

- **The ranking is now load-bearing.** With no filter UI, a bad order has no
  user-side escape. Mitigation: the green line always states the reason, so a
  wrong answer is visibly wrong rather than silently wrong.
- **One card at a time on the Where screen** makes comparing the second and
  third candidate a swipe rather than a glance. Accepted: the map carries the
  comparison, and the decision the user is making is mostly "is this one good
  enough", not "rank these four".
- **No filter means no answer to "only places with hookups".** The preference
  chips rank rather than exclude, so a hookups-only user sees non-hookup places
  below the fold rather than never. If this proves wrong, the fix is a
  constraint, not a filter panel.
- **Deleting the matrix removes a power surface.** A user who wants to see every
  site against every day loses it. That view serves inventory inspection, which
  is the thing this redesign is deliberately not for.

## Testing

Story coverage is specified above and not restated here; what follows is what
has to be *asserted* rather than looked at.

- A test per non-repetition rule: no fact rendered at two sheet heights, no
  candidate fact duplicated between pin and card, and arrival from Where landing
  on Half rather than Peek. `SheetSection` makes the first one mostly
  structural, so that test guards the escape hatches.
- A test that no stay claim is computed from day rollups: the nights strip and
  the Reserve action read different sources, and the one asserting a whole-stay
  fact reads the stay check.
- Phone viewport is the default in tests as well as stories.
- The existing page tests move with their surfaces; deleted surfaces take their
  tests with them.
- Copy assertions read from `lib/strings.ts`, never inline literals.
- **The repo's own gates count as tests.** `scripts/check-token-usage.mjs` and
  `scripts/check-color-tokens.mjs` are CI, and a phase that invents a new
  component family invents new custom properties — exactly what they police. The
  first phase of this work shipped three task reviews deep before anyone ran
  them, because the verification list named `test`, `typecheck`, `lint` and
  `build-storybook` and stopped there. Every phase runs all of them.

## Deferred

- Desktop layout beyond "the same components, more room".
- The operator dashboard (pollers, runs, changes).
- Offline behaviour on a phone with no signal, which this design implies matters
  and does not address.
- Superchargers, Planet Fitness and park polygons as map layers — they survive
  as layers but have no role in the answer, and their presentation is untouched
  here.
