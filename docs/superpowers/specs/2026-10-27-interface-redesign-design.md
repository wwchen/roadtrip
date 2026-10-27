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

Two knobs — **where** and **when**. Each is either *pinned* or *open*.

> **The pinned knob becomes the title. The open knob becomes the answer.**

Four combinations, three screens, because "both pinned" is the Place screen with
nothing to offer:

| where | when | screen | the answer is |
|---|---|---|---|
| route pinned | nights open | **Your nights** | one row per night, gaps first |
| open | pinned | **Where** | ranked places on a map |
| pinned | open | **Place** | which dates are open |
| pinned | pinned, nothing open | **Place**, empty | an alert, and two alternatives |

**Pinning a knob is a navigation; unpinning it is going back.** There is no
other navigation model in the product — no tab bar, no menu, no modal routing.
A notification that fires opens the Place screen with both knobs pinned, which
is the one state the user asked to be told about.

## Screen 1 — Your nights

Exists only when a trip exists. Title is the route (`Oakland → Bend · 5
nights`). Body is one row per night, in date order, each row in exactly one of
four states:

- **Settled** — place name and site number. One line, no verdict, no photo.
- **Empty, candidates exist** — `Nobody yet` plus the count of places open
  within the corridor, and the one button that matters. Tonight's empty row is
  the only element in the product that gets a tinted background.
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

Pins carry the count of open sites — **every** pin, including the selected one,
because the map's job is to rank at a glance and a pin that changes meaning when
tapped is a rule the user has to learn. One card sits above the map's bottom
edge for the selected candidate:

```
Upper Pines
Open both nights · showers · quiet      ← the ranking, explaining itself
4h 10m · $36 · fits 19 ft               ← the facts a pin cannot carry
[ Reserve ]
```

The green line is composed from the user's own rig preferences, in their words,
and is the only justification the interface ever offers. Swiping the card moves
the map; tapping it pins the place and goes to **Place**.

The area comes from one of three things, which are one thing — a polygon: the
route corridor, a named region, or the viewport.

## Screen 3 — Place

No map. The place is pinned, so geography is settled and showing it again would
be the duplication this design exists to avoid. (A static approach map inside
*Getting there* is a different fact — the last mile — and is allowed.)

The screen is a sheet with three heights, and **a fact belongs to exactly one
height**:

- **Peek** — should I go here: name, verdict, the three deciding facts, Reserve.
- **Half** — which nights, exactly: the nights strip (each night tinted with its
  open count, the chosen night solid, shut nights dimmed), the specific site
  numbers and the price, Reserve, and — only if a night in view is shut — one
  row offering to alert on it.
- **Full** — what it is like on arrival: photos, getting there, good to know,
  specs, contact, nearby. The header compacts to a line; it does not repeat.

When *when* is open rather than pinned, Half becomes the whole screen and leads
with a sentence rather than a strip: *"Nov 8–9 is your first open weekend,"*
followed by every weekend in the horizon as a bar. The user asked an open
question and gets a stated answer, not a grid to read.

`domain/poi/blocks.ts` already encodes this fold — `RULE_BEFORE_GROUP` is
exactly the boundary between Half and Full. The block order survives this
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

## Alerts

A watch is **a night you tapped on a place you were already looking at**. It is
created from the Place screen's shut-night row and from nowhere else. It is
surfaced as a notification that reopens that Place screen. It is cancelled from
the same row it was created from.

There is no watch list, no watch table, no trigger selector, no watch form, and
no page that enumerates watches. The existing per-watch Slack/email delete and
modify links keep working — they are the management surface, and they already
exist.

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
rather than removing one. It is called out here rather than buried because if it
is rejected, the Your nights screen loses the *settled* state and becomes a list
of gaps and alerts only — still useful, materially less good.

## Deletions

Code that this design removes outright, not relocates:

- `pages/watches/` and `watches.html`; `features/watches/WatchTable.tsx`,
  `WatchForm.tsx`, `TriggerSelector.tsx`, `ManageWatchCard.tsx`
- `pages/availability/` and `availability.html` — the routed matrix page
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

1. **Place screen** — the sheet and its detents with the nights strip, over
   today's day rollups. Deletes the drawer/page split and the matrix page. The
   open-*when* variant (the weekend bars) waits on read 2 and lands with it.
2. **Where screen** — map, pins with counts, the candidate card. Needs the
   ranked answer and the rig profile.
3. **Alerts as a night** — the shut-night row and the empty state, including the
   cadence sentence. Deletes the watches surface.
4. **Your nights** — trips, the night states, the did-you-get-it prompt.

Phase 1 touches no backend contract and is the honest test of whether the
non-repetition rule holds in real copy with real data.

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

- Each screen gets a Storybook story per answer state, including every empty and
  gated state, since those are the states this design is actually about.
- Phone viewport is the default in stories and tests; desktop is the same
  components with more room, verified second.
- A test per non-repetition rule: no fact rendered at two sheet heights, and no
  candidate fact duplicated between pin and card.
- The existing page tests move with their surfaces; deleted surfaces take their
  tests with them.
- Copy assertions read from `lib/strings.ts`, never inline literals.

## Deferred

- Desktop layout beyond "the same components, more room".
- The operator dashboard (pollers, runs, changes).
- Offline behaviour on a phone with no signal, which this design implies matters
  and does not address.
- Superchargers, Planet Fitness and park polygons as map layers — they survive
  as layers but have no role in the answer, and their presentation is untouched
  here.
