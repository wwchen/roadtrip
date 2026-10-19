# Availability read path — separation of concerns

Status: approved in brainstorming (architecture and vocabulary), awaiting spec
review. Lands as three PRs after [#803](https://github.com/wwchen/roadtrip/pull/803)
merges.

## Original ask

> i want cleaner separation of concerns

Asked which seam was unclean, the answer was all four of the ones on offer:

1. `AvailabilityObservationBatch` doing double duty as "what the vendor said"
   and as a response scaffold.
2. The `AvailabilityLoader` / `CampsiteAvailabilityService` split — who owns
   freshness, windows, provider choice and answer derivation.
3. The repo's read surface: five near-identical "current cell per
   campsite/date" queries.
4. The adapters: the same classification loop hand-rolled five times.

Asked how it should land, the answer was: one spec now, three sequenced PRs once
#803 merges, rather than a third stacked branch.

Then: "is availability on cg or cs level? let's rename as well" — so the
vocabulary is in scope, and the rename rides along with the three PRs rather
than becoming a fourth.

## The grain question, which the names got wrong

Availability lives at two levels, and conflating them is the root of the mess:

- The stored **fact** is campsite-level. `availability` is keyed
  `(campsite_id, target_date)`: one night of one campsite.
- The **fetch** is campground-level. Every vendor answers that way — rec.gov by
  facility-month, Campflare by bulk campground call, ReserveAmerica by park
  matrix, ReserveCalifornia by facility grid, Aspira by map.
- The **answer** is a campground-level aggregation of campsite-level facts: the
  week's rollup per day, plus a cell per campsite.

So the cache key is campsite×night while the cache *decision* is a campground
window. Four overlapping vocabularies grew out of that: campsite/reservable,
campground/poi/parent/scope, catalog, and batch/slice/cell.

## Vocabulary

One rule: **cell** is a campsite-night fact; **stream** is one campsite across a
window; **window** is the campground-level unit of fetching and caching. Nothing
else gets a noun.

| now | becomes | why |
|---|---|---|
| `PoiAvailabilitySlice` | `CampgroundWindow` | "slice" names nothing; it is a campground window of cells |
| `AvailabilityObservationBatch` | `CampgroundObservations` | names grain and scope, not "a batch of stuff" |
| `AvailabilityCell` + `AvailabilityRepo.CurrentCell` | one `AvailabilityCell` | two types for one idea |
| `CampsiteStream` (private to `PoiAvailabilityFusion`) | promoted to the model | the per-campsite-across-a-window idea, rebuilt in four places today |
| `CampsiteAvailabilityService` | `CampgroundAvailabilityReader` + `AvailabilityReadPolicy` | it reads campgrounds, not campsites — and it is two jobs |
| `poiAvailabilitySlice(...)` | `campgroundWindow(poiId, …)` | drops the poi/campsite collision in one name |
| `catalogAvailability`, `catalogSites`, `AspiraCatalogCampsite` | drop "catalog" | a third word for "the campsites we know about" |
| `lastReservableCount`, `observationsForReservable` | campsite | leftovers from the Reservable→Campsite rename (#407) |
| `parentRef` / `scope` / `claimedRef` | `campgroundRef` | one campground identity at a vendor, not three — Kotlin side only: `availability_fetch_call.parent_ref` and `AvailabilityPollerSchema`'s `@SerialName("parent_ref")` are a column and a wire field and do not move |

POI and campground stay distinct: a POI is a map pin that may have a campground,
so `findByPoi` is honest.

## Architecture

```
                    request shape stops here
  route → controller ──────────┬──────────────────────────────
                               │
                     CampgroundAvailabilityReader ──→ AvailabilityReadPolicy
                               │                        window (target+fetch),
                               │                        serving provider, TTL, horizon
                               ├──→ AvailabilityLoader ──→ AvailabilityRepo
                               │      owns the store         one query family
                               │            └──→ FailoverFetcher ──→ port ──→ adapters
                               ↓
                        CampgroundWindow          ← read model, data only
                               ↓
                     response layer (pure, no I/O)
                     fuse · staySummary · envelopes · seasonBlock
```

Eight units, one job each:

- **Adapter** — vendor shape to keyed observations. Knows its vendor; knows
  nothing of windows policy, the store or responses.
- **Port** (`AvailabilityProvider`) — facts for a campground and a window.
- **FailoverFetcher** — candidate order, cooldowns, the per-vendor coverage
  choice (`coversCampgroundPerCall`), attempt records for tracing.
- **AvailabilityLoader** — the only code that touches the `availability` table:
  "can the store answer these cells" and "record these facts".
- **AvailabilityReadPolicy** — pure window, provider, TTL and horizon
  resolution from a campground, its date context and the capabilities.
- **CampgroundWindow** — the read model: campground, window, the campsites this
  read answers for, their streams, cache metadata, reopen dates.
- **Response layer** — pure functions over the model.
- **AvailabilityRepo** — one `currentCells` query family plus the write.

Dependency direction is one-way: the response layer sees only the model, nothing
above the loader knows SQL, and nothing below the controller knows what a request
filtered to.

The poller keeps its own policy: `PollerFetchPlan`, its own window, its own
cadence. It never touches `AvailabilityReadPolicy` or `CampgroundWindow` — it is a
second writer into the same cells, not a second reader of the same model. It does
share the port and the fetcher, so when the port's return type changes the
poller's path changes with it: `GroupFetchResult`, `CatalogAvailabilityBatcher`,
`AvailabilityPollExecutor` and `AvailabilityRunService.writeObservations` all hold
the old batch type today and move to `CampgroundObservations`. That is a type
change, not a behaviour change, and it is in scope.

### The read model

```kotlin
internal data class CampgroundWindow(
    val poiId: Long,
    val campground: Campground,
    val window: ResolvedDateWindow,
    val earliestDate: LocalDate,
    val latestDate: LocalDate?,
    val campgroundCampsites: List<Campsite>,
    val campsites: List<Campsite>,
    val streams: List<CampsiteStream>,
    val provider: BookingProvider,
    val campgroundRef: BookingProviderRef?,
    val cache: AvailabilityCacheBlock,
    val pollingSupported: Boolean,
    val reopensOnByCampsite: Map<Long, LocalDate>,
)
```

Data only. Every derivation is a free function over it, so the model cannot grow
behaviour and become the next god-object. `streams` is built once by the reader,
which removes the four rebuildings of `campsiteWindowStatuses` that exist today
(fusion, `staySummary`, the per-campsite envelopes, and the season-block gate).

`provider` and `campgroundRef` are on the model because the wire needs them:
`AvailabilityResponseDto` carries `provider` and `scope_ref`, which today come
off the batch. The policy knows the serving provider typed, which is better than
the string literal each adapter writes today, and the observations carry the
campground's vendor ref.

`campsites` is what this read answers for; `campgroundCampsites` is what the
fetch covered. Both are needed: the first narrows every answer, the second is
what the port is handed when the vendor covers a campground per call.

## Data flow

**A filtered read, store cold.** The controller resolves the POI's campground and
campsites, applies the `site_type` filter, and calls the reader with both lists.
The policy resolves the target and fetch windows and picks the serving provider.
The loader finds the required cells unanswered, so it calls the fetcher, which
hands each candidate the campsite set its `coversCampgroundPerCall` allows; the
adapter returns keyed observations; the loader records every one of them and
reads the request's cells back. The reader asks the repo for reopen dates for the
campsites whose window classifies closed, builds the model, and the response
layer renders it.

**The same window again, inside the TTL.** The loader finds every required cell
answered and fresh, and returns them without a provider call. Everything
downstream is unchanged, including the reopen date, because it is read from the
table rather than carried on a fetch.

**An unfiltered read after a filtered one.** The first read's fetch covered the
campground, so the cells of the campsites the filter dropped are already stored:
no provider call. This is the behaviour #803 added and this spec must not lose.

**A poll tick.** `DbAvailabilityTargetResolver` resolves the poller to its POI's
campsites under one `(provider, campgroundRef)`, the fetcher and port run as
above, the loader records, and the transitions returned by the write are what
fire watch alerts. No policy, no read model, no response layer.

## Error handling

Unchanged in behaviour; only its placement is stated. Adapters translate vendor
failures into `AvailabilityProviderError`. The fetcher catches those per
candidate, records an attempt, demotes the provider and tries the next; a
cancellation is handed back rather than recorded as a provider failure. When no
candidate answers, the reader throws the last attempt's error. Routes map
`AvailabilityServiceError` and `AvailabilityProviderError` to status codes. The
response layer cannot fail: it is pure functions over data already in hand.

One rule the port keeps from #803: an adapter never emits a cell for a campsite
it did not ask the vendor about, because a cell written on no question claims
coverage the store will later serve.

## Testing

- **Policy** — pure, so windows, horizons and TTLs are table tests with no DB.
- **Loader** — against the shared test DB, as today: freshness per cell, what a
  wider batch stores, the UNKNOWN back-fill staying with the request's targets.
- **Response layer** — pure functions over hand-built `CampgroundWindow`s, which
  is strictly easier than today's hand-built batches.
- **Reader** — one DB-backed test per flow above, including the one-provider-call
  payoff that `CampgroundCellCacheTest` already asserts.
- **Port contract** — `AvailabilityProviderContractTest` keeps holding all five
  adapters to "every campsite given gets keyed cells, nothing unkeyed".
- The renames must not change a single assertion's meaning. Any test whose
  expectations change during a rename PR is a signal the rename changed
  behaviour, and the change gets its own commit with its own reasoning.

## PR sequencing

**PR 1 — the port returns facts, and the read model appears.**
`CampgroundObservations` replaces the batch on the port side;
`CampgroundWindow` replaces `PoiAvailabilitySlice`; the loader stops shaping
responses; `CampsiteAvailabilityService` splits into the policy and the reader;
the season block becomes a pure function. Renames in this area ride along.

**PR 2 — the repo's read surface.** One `currentCells(campsites, dates,
freshAtOrAfter?)` family replaces `readCurrent`, `hasFreshCoverage`,
`freshlyUnavailableDates`, `earliestBookableDates` and the write's snapshot read,
with filtering in Kotlin. `AvailabilityCell` and `CurrentCell` become one type.

**PR 3 — the adapters.** One shared observation helper replaces the three copies
of `observationsForReservable`; vendor id resolution is collected rather than
scattered; the "catalog" vocabulary goes.

Each PR is behaviour-preserving except where this spec says otherwise, and each
is green on `make test` before the next starts.

## Non-goals

- The poller's policy, window and cadence. Its *types* follow the port, per the
  architecture note above.
- `maxPollWindowDays` and the fetch-window policy's *values*: this moves where
  they are decided, not what they are.
- ReserveAmerica's park matrix carrying sibling campgrounds' cells, which needs a
  caller that knows those campgrounds.
- POI versus campground as distinct concepts.
- Any change to the wire contract. The DTOs and `/api/docs` schemas are
  unchanged by all three PRs; `make api-types` should produce no diff.

## Risks

- **The read model becoming a god-object.** Mitigated by keeping it data-only
  with derivations as free functions, and by `campsites` versus
  `campgroundCampsites` being the only two campsite lists it holds.
- **Rename churn hiding a behaviour change.** Mitigated by the testing rule
  above: renames land in their own commits and change no assertion.
- **Conflicts with #803 and #801.** Mitigated by sequencing after they merge,
  which is why this is a spec and not a fourth stacked branch.
