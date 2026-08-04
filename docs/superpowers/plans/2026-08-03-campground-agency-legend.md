# Campground Agency Legend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken four-bucket jurisdiction campground filter (federal/state/provincial/local) with a flat, viewport-scoped legend keyed on the one field every source populates consistently — the managing **agency** — so counts are always correct and Campflare campgrounds stop vanishing.

**Architecture:** The jurisdiction rollup (`federal`/`state`/`provincial`/`local`) was never a real domain type — it lived only as hand-written `subcategory:` labels in `poi-registry.yaml` plus a hardcoded frontend array, with nothing tying it to the unconstrained `campgrounds.kind TEXT` column. Agency is the atomic classification present on every campground row across every source. This plan (1) fixes the backend so Campflare writes a serving-readable `agency`, (2) rewrites the frontend legend to render one checkbox per agency **present in the current viewport**, sorted, with live counts, and (3) rewires the two `topbar.js` couplings that assumed jurisdiction categories. Selection persists per-agency (remember explicit un-checks; new agencies default on).

**Tech Stack:** Kotlin/Ktor + jOOQ (backend ETL + serving repo), vanilla ES modules + MapLibre GL (frontend), `node --test` for `.mjs` unit tests, JUnit for backend.

## Global Constraints

- **No inline magic constants.** Extract literals to named `const val` / `const`. (AGENTS.md)
- **SQL lives in `repo` only.** Routes/services call repo methods. (AGENTS.md)
- **No leaky abstractions.** Vendor-specific shape stays inside the adapter. (AGENTS.md)
- **No hand-curated POI data.** Every POI comes from a poller; do not add curated jurisdiction tables. Agency comes straight from source data. (memory: no-curated-data)
- **Backend build uses Gradle toolchain 21** — do NOT export `JAVA_HOME`; `./gradlew` at repo root provisions its own JDK. (memory: backend-build-jdk17)
- **ktlint runs separately** from tests in CI: `./gradlew :backend:ktlintCheck`. (memory: backend-ktlint-check)
- **Raw bytes are cached** under `data/raw/campflare-campgrounds-export/` — replay locally, do not re-crawl. (memory: raw-cache-no-refetch)
- **If `:backend:test` hangs locally**, push with `SKIP_PREPUSH=1` and read PR checks rather than loop-debugging the daemon. (memory: use-ci-over-local-gradle)
- Web tests run via: `find web -name '*.test.mjs' | xargs -n1 node --test` (see `Makefile` `test:` target).

## Context: the two backend bugs and the frontend leak

Confirmed against prod (`POST /api/pois`) and cached raw data:

1. **Campflare `kind` bug** — `CampflareCampgroundsEtl.kt:55` sets `kind = raw.stringField("kind")`, which is the vendor's own field (`'established'` / null), not a jurisdiction. Every *other* campground ETL sets `kind = ctx.subcategoryFor(etlSlug)` from the registry. Result: 800/918 campgrounds in a PNW bbox have `subcategory` = null or `'established'`.
2. **Campflare `management` bug** — `CampflareCampgroundsEtl.kt:69` stores the raw upstream object `{agency_name, agency_id, agency_website}` verbatim. The serving query (`PoiServingRepo.kt:93` and `:312`) reads `cg.management->>'agency'`, and every other ETL writes `{"agency": "..."}`. So Campflare's `agency` comes back null too. This is the bug this plan fixes: after the fix, agency is populated for 10,811/10,963 (98.6%) Campflare rows.
3. **Frontend viewport leak** — `web/layers.js` `cgKnownAgencies` is a module-level `Set` that only ever grows, so an agency panned-over once (e.g. NY State Parks) stays in the list forever. The new legend derives its rows purely from the current viewport's features.

`subcategory`/`kind` becomes irrelevant to the legend after this change. We do **not** delete the column or the ETL `subcategory` wiring in this plan (out of scope; other code may read it) — we simply stop the frontend legend from depending on it.

## File Structure

**Backend (fix agency population):**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtl.kt` — normalize `management` so the `agency` key is present.
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtlTest.kt` — assert the normalized management shape.

**Frontend (flat agency legend):**
- Modify: `web/layers.js` — replace the four-category filter/selection/count model with an agency-keyed model driven by viewport features.
- Modify: `web/app.js` — `flattenPoi` stops rewriting `category` to jurisdiction; counts are computed per agency and pushed to `layers.js` instead of into four fixed `c-cg-*` spans.
- Modify: `index.html` — replace the four hardcoded `f-cg-*` rows with a single dynamic host container the legend renders into.
- Modify: `web/topbar.js` — rewire `enablePoiToggle` and the trip-corridor card `category` to the agency model; `campgroundFeaturePassesFilter` keeps working.
- Modify: `web/design-system/tokens.js` — campground pins use one color (agency count is too high to color-code); keep the token, drop the per-jurisdiction `match`.
- Modify: `web/layers.test.mjs` — unit tests for the new agency model (viewport scoping, persistent unchecks, null-agency bucket).

## Design decisions locked by this plan

- **Pin color:** campground dots render in a single color (`--rt-layer-cg`, aliased to today's federal green so the map looks unchanged). Agency is conveyed by the legend row, not the dot — 58+ agencies cannot be color-coded legibly. This is the one visible design change.
- **Null-agency features:** campgrounds with no `agency` (the 152 Campflare rows, 1.4%) render under a single `Uncategorized` legend row (constant label), default-on, so they are never silently invisible — the exact failure mode being fixed.
- **Selection model:** a module-level `Set` of **un-checked** agency names (`cgHiddenAgencies`). An agency not in the set is shown. New/unseen agencies are therefore on by default. Un-checks persist across pans even when the agency is off-screen.
- **Legend rows** = distinct `agency` (or the `Uncategorized` sentinel) among the current viewport's campground features, sorted `localeCompare`, each with its viewport count.

---

### Task 1: Campflare ETL writes a serving-readable `agency`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtl.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtlTest.kt`

**Interfaces:**
- Consumes: raw Campflare `management` object `{agency_name, agency_id, agency_website}` (all optional).
- Produces: `campgrounds.management` JSON that includes an `"agency"` string key (the human-readable agency name) when `agency_name` is present, so the serving query `cg.management->>'agency'` resolves. Preserve the original upstream keys alongside it (detail rendering may read them).

- [ ] **Step 1: Write the failing test**

Add to `CampflareCampgroundsEtlTest.kt`. Match the existing test style in that file (find how it builds a raw `JsonObject` and calls `transform`/`campgroundRecord` and asserts on the resulting `CampgroundUpsertCandidate.management`).

```kotlin
@Test
fun `management carries serving-readable agency key from agency_name`() {
    val raw = buildJsonObject {
        put("id", "kanaskat-palmer-wsp")
        put("name", "Kanaskat-Palmer State Park Campground")
        put("location", buildJsonObject { put("latitude", 47.32); put("longitude", -121.90) })
        put("management", buildJsonObject {
            put("agency_name", "Washington State Parks")
            put("agency_id", "washington-state-parks")
            put("agency_website", "https://parks.wa.gov")
        })
    }

    val candidate = (CampflareCampgroundsEtl().transform(raw, testCtx()).single()
        as TransformResult.Ok).value

    val mgmt = candidate.management as JsonObject
    assertEquals("Washington State Parks", mgmt["agency"]?.jsonPrimitive?.content)
    // original upstream keys preserved for detail rendering
    assertEquals("washington-state-parks", mgmt["agency_id"]?.jsonPrimitive?.content)
}

@Test
fun `management is null when upstream has no agency_name`() {
    val raw = buildJsonObject {
        put("id", "no-agency-cg")
        put("name", "Somewhere")
        put("location", buildJsonObject { put("latitude", 47.0); put("longitude", -121.0) })
        // no management block
    }
    val candidate = (CampflareCampgroundsEtl().transform(raw, testCtx()).single()
        as TransformResult.Ok).value
    assertNull(candidate.management)
}
```

If the test file has no `testCtx()` helper, build a `TransformCtx` the same way the existing tests in this file do (copy their setup); do not invent a new fixture.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests '*CampflareCampgroundsEtlTest*'`
Expected: FAIL — `mgmt["agency"]` is null (current code stores the raw block unchanged).

- [ ] **Step 3: Implement the management normalizer**

In `CampflareCampgroundsEtl.kt`, replace the `management = raw.objectField("management"),` line (currently line 69) with a call to a new private helper, and add the helper. The helper injects an `"agency"` key (from `agency_name`) into a copy of the upstream management object, preserving existing keys; returns null when there is no `agency_name`.

```kotlin
// in campgroundRecord(...), replace the management assignment:
management = normalizedManagement(raw.objectField("management")),
```

```kotlin
private const val AGENCY_KEY = "agency"
private const val AGENCY_NAME_KEY = "agency_name"

// Campflare ships management as {agency_name, agency_id, agency_website};
// the serving query and every other vendor read management->>'agency'.
// Promote agency_name to the canonical `agency` key while keeping the
// upstream keys for detail rendering. Null when upstream names no agency.
private fun normalizedManagement(management: JsonObject?): JsonObject? {
    val agencyName = management?.stringField(AGENCY_NAME_KEY) ?: return null
    return buildJsonObject {
        management.forEach { (key, value) -> put(key, value) }
        put(AGENCY_KEY, agencyName)
    }
}
```

Add any missing imports (`kotlinx.serialization.json.buildJsonObject`, `put`) — check the file's existing imports first.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests '*CampflareCampgroundsEtlTest*'`
Expected: PASS. If the daemon hangs, push with `SKIP_PREPUSH=1` and read PR checks (memory: use-ci-over-local-gradle).

- [ ] **Step 5: ktlint**

Run: `./gradlew :backend:ktlintCheck`
Expected: PASS (fix any formatting it flags).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtl.kt backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtlTest.kt
git commit -m "fix(campflare): promote agency_name to canonical management.agency key"
```

---

### Task 2: Frontend agency model in `layers.js` (pure functions + selection)

**Files:**
- Modify: `web/layers.js`
- Test: `web/layers.test.mjs`

**Interfaces:**
- Consumes: campground GeoJSON features whose `properties.agency` is a string or absent, and `properties.category === 'campground'`.
- Produces (exported):
  - `const UNCATEGORIZED_AGENCY = 'Uncategorized'` — sentinel label for null-agency features.
  - `featureAgency(featureOrProps) -> string` — returns the feature's agency, or `UNCATEGORIZED_AGENCY` when absent/blank.
  - `agenciesInViewport(geojson) -> string[]` — distinct agency labels present, sorted `localeCompare`.
  - `agencyCountsInViewport(geojson) -> Map<string, number>` — label → count.
  - `campgroundFeaturePassesFilter(featureOrProps) -> boolean` — true unless the feature's agency is currently un-checked. (Replaces the old jurisdiction-based version; same name, same export, so `topbar.js:1927` keeps working.)
  - `setAgencyHidden(agency, hidden) -> void`, `isAgencyHidden(agency) -> boolean` — selection state over the module-level `cgHiddenAgencies` set.
  - `onCampgroundFilterChange(listener)` — unchanged signature (kept for `topbar.js:2004`).

- [ ] **Step 1: Write the failing tests**

Add to `web/layers.test.mjs` (uses `node --test`; follow the existing import + `test(...)` style already in that file):

```javascript
import {
  UNCATEGORIZED_AGENCY, featureAgency, agenciesInViewport,
  agencyCountsInViewport, campgroundFeaturePassesFilter,
  setAgencyHidden, isAgencyHidden,
} from './layers.js';

const fc = (agencies) => ({
  type: 'FeatureCollection',
  features: agencies.map((a, i) => ({
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [-121 - i * 0.1, 47] },
    properties: a == null ? { category: 'campground' } : { category: 'campground', agency: a },
  })),
});

test('featureAgency falls back to the Uncategorized sentinel', () => {
  assert.equal(featureAgency({ category: 'campground' }), UNCATEGORIZED_AGENCY);
  assert.equal(featureAgency({ category: 'campground', agency: '  ' }), UNCATEGORIZED_AGENCY);
  assert.equal(featureAgency({ category: 'campground', agency: 'BC Parks' }), 'BC Parks');
});

test('agenciesInViewport lists distinct agencies sorted, incl. Uncategorized', () => {
  const list = agenciesInViewport(fc(['Ohio State Parks', 'BC Parks', 'BC Parks', null]));
  assert.deepEqual(list, ['BC Parks', 'Ohio State Parks', UNCATEGORIZED_AGENCY]);
});

test('agencyCountsInViewport counts per agency', () => {
  const counts = agencyCountsInViewport(fc(['BC Parks', 'BC Parks', null]));
  assert.equal(counts.get('BC Parks'), 2);
  assert.equal(counts.get(UNCATEGORIZED_AGENCY), 1);
});

test('new agencies pass the filter by default; unchecking hides only that one', () => {
  assert.equal(campgroundFeaturePassesFilter({ category: 'campground', agency: 'BC Parks' }), true);
  setAgencyHidden('BC Parks', true);
  assert.equal(isAgencyHidden('BC Parks'), true);
  assert.equal(campgroundFeaturePassesFilter({ category: 'campground', agency: 'BC Parks' }), false);
  assert.equal(campgroundFeaturePassesFilter({ category: 'campground', agency: 'Ohio State Parks' }), true);
  setAgencyHidden('BC Parks', false); // reset for other tests
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `node --test web/layers.test.mjs`
Expected: FAIL — new exports not defined.

- [ ] **Step 3: Implement the agency model**

In `web/layers.js`, replace the jurisdiction block (the `CG_SUBCATEGORIES`, `cgAgencySelections`, `cgKnownAgencies`, `campgroundLayerCategory`, `effectiveCampgroundCategory`, `featureCampgroundCategory`, `agencySelection`, `agenciesByCategory`, `campgroundFeaturePassesFilter`, and related helpers spanning roughly lines 47–167) with the agency model below. Keep `cgFilterListeners`, `notifyCampgroundFilterChanged`, `onCampgroundFilterChange`, and `lastCgGeojson` — they stay.

```javascript
export const UNCATEGORIZED_AGENCY = 'Uncategorized';
const CG_EMPTY_FC = { type: 'FeatureCollection', features: [] };

// Persistent selection: agencies the user explicitly UN-checked. Absence
// from this set == shown, so agencies never seen before default on and a
// pan to a new region shows everything there without re-enabling.
const cgHiddenAgencies = new Set();
const cgFilterListeners = new Set();
let lastCgGeojson = CG_EMPTY_FC;

function normalizeAgency(value) {
  return typeof value === 'string' ? value.trim() : '';
}

export function featureAgency(featureOrProps) {
  const props = featureOrProps?.properties || featureOrProps || {};
  return normalizeAgency(props.agency) || UNCATEGORIZED_AGENCY;
}

export function agencyCountsInViewport(geojson = lastCgGeojson) {
  const counts = new Map();
  for (const feature of geojson?.features || []) {
    const props = feature.properties || {};
    if (props.category !== 'campground') continue;
    const agency = featureAgency(props);
    counts.set(agency, (counts.get(agency) || 0) + 1);
  }
  return counts;
}

export function agenciesInViewport(geojson = lastCgGeojson) {
  return [...agencyCountsInViewport(geojson).keys()].sort((a, b) => a.localeCompare(b));
}

export function isAgencyHidden(agency) {
  return cgHiddenAgencies.has(agency);
}

export function setAgencyHidden(agency, hidden) {
  if (hidden) cgHiddenAgencies.add(agency);
  else cgHiddenAgencies.delete(agency);
}

export function campgroundFeaturePassesFilter(featureOrProps) {
  return !cgHiddenAgencies.has(featureAgency(featureOrProps));
}

function notifyCampgroundFilterChanged() {
  for (const listener of cgFilterListeners) listener();
}

export function onCampgroundFilterChange(listener) {
  cgFilterListeners.add(listener);
  return () => cgFilterListeners.delete(listener);
}
```

Note for the implementer: `campgroundLayerCategory` is removed here — Task 4 updates its two `topbar.js` callers and the `app.js` caller. Do not leave a dangling export. `cgHiddenAgencies` is module-scoped and shared with Task 3's render/filter code in the same file.

- [ ] **Step 4: Run to verify pass**

Run: `node --test web/layers.test.mjs`
Expected: the four new tests PASS. (Layer-install tests may still reference old symbols — Task 3 fixes those; if `layers.test.mjs` has pre-existing tests that import removed symbols, expect those to fail until Task 3. If so, note it and continue — Task 3 is the same file.)

- [ ] **Step 5: Commit**

```bash
git add web/layers.js web/layers.test.mjs
git commit -m "feat(map): agency-keyed campground filter model"
```

---

### Task 3: Render the dynamic agency legend + apply the map filter

**Files:**
- Modify: `web/layers.js` (the `installCGLayer` / `setCGData` render + filter path)
- Modify: `index.html` (replace four `f-cg-*` rows with one host)
- Modify: `web/design-system/tokens.js` (single campground pin color)
- Test: `web/layers.test.mjs`

**Interfaces:**
- Consumes: `agenciesInViewport`, `agencyCountsInViewport`, `isAgencyHidden`, `setAgencyHidden`, `campgroundFeaturePassesFilter`, `cgHiddenAgencies`, `UNCATEGORIZED_AGENCY` from Task 2 (same file).
- Produces:
  - `renderCampgroundLegend(geojson) -> void` — (re)renders one checkbox row per viewport agency into `#cg-agency-legend`, each showing `escapeHtml(agency)` + count, checked when `!isAgencyHidden(agency)`.
  - `applyCGFilter() -> void` — sets `cg-points` / `cg-points-hit` visibility + a MapLibre filter derived from hidden agencies.
  - Both called from `installCGLayer` and `setCGData` (replacing the old `renderCgAgencyControls` + `applyCGFilter`). Both exported (Task 4's `topbar.js` calls them).

- [ ] **Step 1: Update `index.html`**

Replace the four campground rows + nested hosts (lines ~1754–1769, the block containing `f-cg-federal` … `cg-agency-local`) with a single dynamic host. Keep the `Campgrounds` section-label line and its `cg-load-hint` span above it.

```html
  <div class="section-label">Campgrounds <span id="cg-load-hint" style="text-transform:none; color:var(--rt-hint); letter-spacing:0;">(zoom in to load)</span></div>
  <div id="cg-agency-legend" class="cg-agency-legend"></div>
```

- [ ] **Step 2: Single campground pin color in `tokens.js`**

In `web/design-system/tokens.js`, add `'--rt-layer-cg': '#2e7d32',` alongside the existing `--rt-layer-cg-*` entries (reuse today's federal green so the map is visually unchanged). Leave the existing per-jurisdiction tokens in place (other code / gallery may reference them); this task only adds the flat token and switches the paint expression in Step 4.

- [ ] **Step 3: Write the failing legend test**

Add to `web/layers.test.mjs`. This test drives `renderCampgroundLegend` against a minimal DOM. The file already sets up a DOM shim for the layer tests — reuse it; if it uses a specific helper to create elements, follow that. Assert row count, labels, counts, and checked state.

```javascript
import { renderCampgroundLegend } from './layers.js';

test('renderCampgroundLegend renders one checked row per viewport agency with counts', () => {
  const host = document.createElement('div');
  host.id = 'cg-agency-legend';
  document.body.appendChild(host);

  renderCampgroundLegend(fc(['BC Parks', 'BC Parks', 'Ohio State Parks', null]));

  const rows = host.querySelectorAll('label');
  assert.equal(rows.length, 3); // BC Parks, Ohio State Parks, Uncategorized
  const first = host.querySelector('input[type=checkbox][data-cg-agency="BC Parks"]');
  assert.ok(first);
  assert.equal(first.checked, true);
  assert.match(host.textContent, /BC Parks/);
  assert.match(host.textContent, /Ohio State Parks/);
  assert.match(host.textContent, /Uncategorized/);
  host.remove();
});
```

- [ ] **Step 4: Implement render + filter in `layers.js`**

Add `renderCampgroundLegend` and rewrite `applyCGFilter`; delete the old `renderCgAgencyControls`, `syncCampgroundCategoryCheckbox`, `checkedCampgroundCategories`, `filterCategoriesFor`, `campgroundFilterClause`, `onCgAgencyControlChange`, and `bindCGFilterControls`. `escapeHtml` is already imported at the top of the file.

```javascript
const CG_AGENCY_LEGEND_ID = 'cg-agency-legend';
const CG_LAYER_IDS = ['cg-points', 'cg-points-hit'];

export function renderCampgroundLegend(geojson = lastCgGeojson) {
  const host = document.getElementById(CG_AGENCY_LEGEND_ID);
  if (!host) return;
  const counts = agencyCountsInViewport(geojson);
  const agencies = [...counts.keys()].sort((a, b) => a.localeCompare(b));
  host.innerHTML = agencies.map(agency => `
    <label class="cg-agency-row">
      <input type="checkbox" data-cg-agency="${escapeHtml(agency)}"${isAgencyHidden(agency) ? '' : ' checked'}>
      <span class="legend-dot" style="background:var(--rt-layer-cg)"></span>
      ${escapeHtml(agency)} <span class="count">(${counts.get(agency)})</span>
    </label>
  `).join('');
}

function onLegendChange(e) {
  const target = e.target;
  if (!(target instanceof HTMLInputElement)) return;
  const agency = target.dataset.cgAgency;
  if (!agency) return;
  setAgencyHidden(agency, !target.checked);
  applyCGFilter();
  notifyCampgroundFilterChanged();
}

export function applyCGFilter() {
  const { map } = state;
  if (!map?.getLayer('cg-points') || !map?.getLayer('cg-points-hit')) return;
  const hidden = [...cgHiddenAgencies];
  // MapLibre can only test present properties; the Uncategorized sentinel
  // represents features with NO agency, so hiding it means excluding
  // agency-absent features, handled with a has-agency guard.
  const hideUncategorized = cgHiddenAgencies.has(UNCATEGORIZED_AGENCY);
  const namedHidden = hidden.filter(a => a !== UNCATEGORIZED_AGENCY);
  const clauses = ['all'];
  if (namedHidden.length > 0) {
    clauses.push(['!', ['in', ['get', 'agency'], ['literal', namedHidden]]]);
  }
  if (hideUncategorized) {
    clauses.push(['has', 'agency']);
  }
  const filter = clauses.length === 1 ? null : clauses;
  for (const id of CG_LAYER_IDS) {
    map.setLayoutProperty(id, 'visibility', 'visible');
    map.setFilter(id, filter);
  }
}
```

Bind the legend host listener once (delegated), replacing the old `bindCGFilterControls`. In `installCGLayer`, after the layers are added, call:

```javascript
lastCgGeojson = geojson || CG_EMPTY_FC;
renderCampgroundLegend(lastCgGeojson);
applyCGFilter();
if (!state.bound.cg) {
  document.getElementById(CG_AGENCY_LEGEND_ID)?.addEventListener('change', onLegendChange);
}
```

(The `state.bound.cg` guard already exists at the end of `installCGLayer`; fold the listener bind into that one-time path so re-installs on style reload don't stack listeners. Keep the existing `state.bound.cg = true` assignment.)

In `installCGLayer`, change the `circle-color` paint from the `['match', ['get', 'category'], ...]` expression to the flat token:

```javascript
'circle-color': token('--rt-layer-cg'),
```

Remove the now-unused `cgClassColors` import if nothing else in the file uses it (grep the file first).

Update `setCGData` to call `renderCampgroundLegend(lastCgGeojson)` instead of `renderCgAgencyControls(lastCgGeojson)`.

- [ ] **Step 5: Run tests**

Run: `node --test web/layers.test.mjs`
Expected: PASS, including any pre-existing layer-install tests (fix references to removed symbols; delete or rewrite the old jurisdiction tests to the agency model).

- [ ] **Step 6: Commit**

```bash
git add web/layers.js index.html web/design-system/tokens.js web/layers.test.mjs
git commit -m "feat(map): render viewport-scoped agency legend, single cg pin color"
```

---

### Task 4: Recompute counts in `app.js` and rewire `topbar.js` couplings

**Files:**
- Modify: `web/app.js`
- Modify: `web/topbar.js`

**Interfaces:**
- Consumes: `renderCampgroundLegend`, `applyCGFilter`, `featureAgency`, `setAgencyHidden`, `campgroundFeaturePassesFilter`, `onCampgroundFilterChange` from `layers.js`.
- Produces: no new exports; the bbox paint path drives the legend (counts live in the legend rows now), and `topbar.js` no longer references `campgroundLayerCategory`.

- [ ] **Step 1: Simplify `flattenPoi` in `app.js`**

The legend no longer keys on a rewritten `category`, and pins are one color, so stop rewriting `category` to a jurisdiction. `flattenPoi` for campgrounds becomes a pass-through (agency + category already present on the slim properties):

```javascript
function flattenPoi(f) {
  return f;
}
```

Remove the now-unused `campgroundLayerCategory` import from `app.js` (line ~21). If `flattenPoi` becomes a trivial identity used in only one place, inline it at the call site (`paintPois`, line ~294) and delete the function; either is fine — pick whichever leaves `paintPois` readable.

- [ ] **Step 2: Replace the four-count block in `paintPois` (`app.js`)**

Delete the `cgCounts` declaration, the reset lines (`cgCounts.federal = 0; …`), the `cg.forEach(...)` accumulation, and the `for (const [k, v] of Object.entries(cgCounts)) setCount('c-cg-' + k, v);` loop (lines ~318–326). The legend now shows per-agency counts and is re-rendered from the `cg` bucket inside `setCGData` (Task 3), so no count push is needed here. Keep `setCount('c-pf', …)`, `setCount('c-open', …)`, `c-np`, `c-sp` as-is.

(Grep confirmed only `app.js` wrote `c-cg-*`; no other reader.)

- [ ] **Step 3: Rewire `topbar.js` `enablePoiToggle` (line ~1016)**

When a search result opens a campground, the old code enabled `f-cg-<jurisdiction>`. Now there is one campground layer with per-agency filtering. Ensure the feature's agency is not hidden, then re-render + re-filter:

```javascript
function enablePoiToggle(category, feature) {
  if (category === 'campground') {
    setAgencyHidden(featureAgency(feature), false);
    applyCGFilter();
    renderCampgroundLegend();
    return;
  }
  let id = null;
  if (category === 'national-park') id = 'f-np';
  else if (category === 'state-park') id = 'f-sp';
  else if (category === 'planet_fitness_location' || category === 'planet-fitness') id = 'f-pf';
  else if (category === 'tesla_supercharger' || category === 'supercharger') id = 'f-open';
  const el = id ? document.getElementById(id) : null;
  // ...existing tail that checks/dispatches on `el` stays unchanged...
}
```

Update the `layers.js` import in `topbar.js` (line 20): drop `campgroundLayerCategory`, add `featureAgency`, `setAgencyHidden`, `applyCGFilter`, `renderCampgroundLegend`. (`campgroundFeaturePassesFilter`, `onCampgroundFilterChange`, `synthesizeClick` stay.)

- [ ] **Step 4: Rewire the trip-corridor card `category` (`topbar.js` line ~1821)**

The corridor card set `category: campgroundLayerCategory(p.subcategory || p.category)` (used only for a color swatch class). With one campground color, set it to the constant category:

```javascript
    const card = {
      id,
      name: 'Campground',
      sub: '',
      location: '',
      category: 'campground',
      // ...rest unchanged...
```

If the card's `category` drives a CSS class for a colored swatch, point that class at the single `--rt-layer-cg` token (check `web/campground-card.js` and the card CSS; if it switch-cased on jurisdiction, collapse to one). If it does not affect rendering, the constant is sufficient.

- [ ] **Step 5: Run the web test suite**

Run: `find web -name '*.test.mjs' | xargs -n1 node --test`
Expected: PASS. Fix any test that imported `campgroundLayerCategory` or asserted on `c-cg-*` spans.

- [ ] **Step 6: Commit**

```bash
git add web/app.js web/topbar.js
git commit -m "refactor(map): drive campground counts + search toggles off agency model"
```

---

### Task 5: End-to-end verification against live API

**Files:** none (verification only).

- [ ] **Step 1: Confirm the backend code path serves agency**

Prod data only updates on a Campflare re-ingest, so verify the *code* path rather than prod: re-run Task 1's tests and confirm the normalized `management` carries `agency`, which the serving query `management->>'agency'` reads. Note in the PR body that a Campflare re-ingest is required for the fix to appear in prod (a pipeline run, not a prod DB write — respects the production-DB guardrail).

- [ ] **Step 2: Drive the frontend (headless caveat)**

Per memory (`headless-qa-no-webgl`), the MapLibre app can't boot in the gstack browser — verify legend logic via the `.mjs` unit tests (viewport scoping, counts, null-agency, persistent unchecks). If a local Tilt stack is available (`tilt up`), load the app, zoom into WA, and confirm: (a) the legend lists WA-area agencies with non-zero counts, (b) panning to California swaps the list (no NY State Parks lingering — the leak fix), (c) un-checking an agency hides its pins and the choice survives a pan-away-and-back.

- [ ] **Step 3: Run the full local gate**

Run: `make test` (backend + ktlint + detekt + web + scripts). If `:backend:test` hangs, push with `SKIP_PREPUSH=1` and rely on PR checks (memory: use-ci-over-local-gradle).

- [ ] **Step 4: Open the draft PR**

Push the branch and open a draft PR summarizing: the two backend bugs (Campflare `kind` + `management` key), the flat viewport-scoped agency legend, the single-color pin design change, and the **required Campflare re-ingest** for prod effect. Write the PR body to a temp file and use `--body-file`, then delete the file (user global instructions).

---

## Self-Review

**Spec coverage:**
- Viewport-scoped flat agency legend → Tasks 2 (model) + 3 (render).
- Persistent un-check selection, new agencies default-on → Task 2 (`cgHiddenAgencies`), tested.
- Backend guarantees `agency` on every row → Task 1 (Campflare fix; other vendors already correct, confirmed in prod: BC Parks / Parks Canada / WA State Parks all populate `agency`).
- Null-agency safety net → `UNCATEGORIZED_AGENCY` bucket, Tasks 2/3.
- "NY parks shouldn't show in California" (the `cgKnownAgencies` leak) → fixed by deriving rows from viewport features only (Task 3), no accumulating set.
- topbar couplings (search toggle, corridor card, result filtering) → Task 4; `campgroundFeaturePassesFilter` kept same-named.

**Placeholder scan:** No TBDs; every code step has concrete code. The one "…existing tail…" in Task 4 Step 3 points at code the implementer is editing in place, bounded by the surrounding shown code.

**Type consistency:** `featureAgency`, `agenciesInViewport`, `agencyCountsInViewport`, `isAgencyHidden`, `setAgencyHidden`, `campgroundFeaturePassesFilter`, `renderCampgroundLegend`, `applyCGFilter`, `UNCATEGORIZED_AGENCY`, `cgHiddenAgencies` are defined in Tasks 2/3 and consumed with matching names/signatures in Tasks 3/4. `cgHiddenAgencies` is the single selection store used by both filter and render.

**Known residue (intentional, out of scope):** `campgrounds.kind`/`subcategory` and the registry `subcategory:` labels remain; only the legend stops depending on them. The per-jurisdiction color tokens remain in `tokens.js`. A follow-up could retire them once nothing reads them.
