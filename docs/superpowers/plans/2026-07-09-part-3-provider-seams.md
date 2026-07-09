# Provider Seams Implementation Plan (Part 3 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate the four provider concerns — data source, availability provider, alert provider, trigger action — and make availability multi-source: ordered candidates from the campground match group, per-campground preference, automatic failover on rate-limit/5xx with an in-process cooldown.

**Architecture:** `AvailabilityTargetResolver` returns an ordered candidate list instead of a single winner; a shared `FailoverAvailabilityFetcher` walks candidates on retryable failure and records observations against representative campsite ids. Watch lifecycle goes through an `AlertProviderRegistry` (internal poller = first implementation; Campflare hosted alerts = documented future adapter). `WatchAlertDispatcher`'s hardcoded `slack_notify` branch becomes a `TriggerActionHandler` registry.

**Tech Stack:** Kotlin, Ktor, jOOQ raw SQL, existing availability-provider adapter framework, kotlinx.serialization.

## Global Constraints

- This is **part 3 of 3**: it runs after part 1 (`2026-07-09-part-1-campsite-rename-cleanup.md`) and part 2 (`2026-07-09-part-2-per-vendor-catalog-matches.md`). Use post-rename names (`Campsite`, `CatalogCampsiteRef`, `GroupFetchResult.campsites`) and plan-2 artifacts (`campground_canonical`/`campsite_canonical` views with `member_ids`/`member_sources`, `campgrounds.preferred_availability_source`, `campsite_matches`, no `is_primary`).
- Build green per task, one commit per task; `./gradlew build` from repo root. Graphite local-only (`gt track`/`gt restack`, never `gt sync`). Multi-`-m` commits, never heredocs.
- No inline magic constants — cooldown duration is env-driven with a named default.
- Layer contract: routes never touch adapters or registries directly; new seams live under `service/availability/`.
- Contract docs (`docs/reservation-providers.md`, `docs/backend-architecture.md`) update in the same plan — they are the abstraction contract.
- Finish with `python3 scripts/test_grafana_canonical_catalog_dashboards.py` + web tests if web files touched.

## Scope Decisions

- **Candidates, not a winner:** `ResolvedAvailabilityTarget` gains `candidates: List<ProviderCandidate>`; `provider`/`parentRef` remain as the preferred (first) candidate so the batcher's `GroupKey` and existing call sites keep working — grouping stays by preferred candidate; failover walks the rest per group.
- **Candidate ordering:** preference match (`campgrounds.preferred_availability_source`) → cooldown-healthy first (Kotlin-side reorder; SQL doesn't know cooldown state) → view winner order → `vendor_ref_id`.
- **Failover triggers:** `FetchOutcome.RATE_LIMITED`, `UPSTREAM_5XX`, `BLOCKED` (reuse the existing enum + `AvailabilityProviderError.toFetchOutcome()`); `OTHER` does not fail over (likely a bug, not an outage). Every attempt writes its own `availability_fetch_call` row (table already has `provider`; no migration).
- **Identity translation:** when a sibling vendor's row serves the fetch, its campsite refs come from that row's own campsite vendor refs; observations are recorded against the **representative** campsite ids via `campsite_matches`/`campsite_canonical` translation.
- **Cooldown:** in-memory per-`AvailabilityProviderId`, set on retryable failure, duration `AVAILABILITY_PROVIDER_COOLDOWN_SECONDS` (default named const). Cooling providers sort last but are never hard-excluded — a sole candidate is still tried.
- **Alert seam only:** `AlertProvider` port + `InternalPollerAlertProvider` + `AlertProviderRegistry`. No Campflare implementation; its contract (webhook route → `CellTransition` → same dispatcher) is documented in KDoc + docs.
- **Capability rename:** `AvailabilityProviderCapabilities.supportsAlerts` → `pollableForAlerts`; hosted-alert capability belongs to the alert seam. Check wire exposure (capability DTO serial names + web consumers) and rename both sides together.
- **Trigger registry:** `TriggerActionHandler` interface + map; `SlackNotifyHandler` preserves channel override and the "stopWhenTriggered only after delivery success" rule; unknown kinds stay inert (current `atc` behavior).
- **API:** `availability_provider` on POI detail is populated from the resolver's actual preferred candidate instead of the raw ref-shape source (`r.providerSource` in `PoiRoutes.kt` ~:370).

## File Structure

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ProviderCandidate.kt` (or inside the resolver file, matching one-type-per-file rule)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/FailoverAvailabilityFetcher.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ProviderCooldownTracker.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProvider.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/InternalPollerAlertProvider.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProviderRegistry.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerActionHandler.kt` + `SlackNotifyHandler.kt`
- Modify: `AvailabilityTargetResolver.kt`, `CampsiteProviderRepo.kt`, `AvailabilityLoader.kt`, `CatalogAvailabilityBatcher.kt`, `AvailabilityPollExecutor.kt`, `AvailabilityWatchService.kt`, `WatchAlertDispatcher.kt`, `AvailabilityProviderCapabilities.kt` + 5 adapters, `PoiRoutes.kt`, `Main.kt`/`RoadtripRuntime.kt` wiring
- Modify: `docs/reservation-providers.md`, `docs/backend-architecture.md`
- Tests: new `FailoverAvailabilityFetcherTest.kt`, `ProviderCooldownTrackerTest.kt`, `AlertProviderRegistryTest.kt`, `TriggerActionHandlerTest.kt`; extend `DbAvailabilityTargetResolverTest.kt`, `AvailabilityWatchServiceTest.kt`, `AvailabilityPollExecutorTest.kt`

---

### Task 1: Candidate enumeration across the match group

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteProviderRepo.kt`
- Test: extend `DbAvailabilityTargetResolverTest.kt` fixtures / repo test

**Interfaces:**
- Produces: `findProviderRefCandidates(poiId)` / `(poiIds)` now enumerate campground vendor refs across **all rows in the POI's match group**: join `campground_canonical` on the linked campground id to get `member_ids`, then `campground_vendor_refs` for every member. Each returned `CampsiteProviderRefRow` keeps `(poiId, source, providerRefJson, lng, lat)`. SQL ordering: `CASE WHEN vr.vendor = cc.preferred_availability_source THEN 1 ELSE 0 END DESC` → shape-usable first (`providerRefShapeSql`) → member order (winner first: `member_id = cc.id` first, then by member id) → `vendor_ref_id ASC`.

- [ ] **Step 1:** Test: two matched campgrounds (campflare winner + recgov sibling), POI on winner → candidates include both vendors, campflare first; setting `preferred_availability_source = 'recgov'` flips the order. Run → FAIL.
- [ ] **Step 2:** Implement the SQL (LATERAL over `unnest(cc.member_ids)`). Run → PASS.
- [ ] **Step 3: Commit** `git commit -m "feat: enumerate availability candidates across campground match group" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 2: Resolver returns ordered candidates

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityTargetResolver.kt`
- Test: `DbAvailabilityTargetResolverTest.kt`

**Interfaces:**
- Produces:
```kotlin
internal data class ProviderCandidate(
    val provider: AvailabilityProvider,
    val parentRef: ProviderRef,
    val catalogRef: CatalogCampsiteRef,
)
// ResolvedAvailabilityTarget gains:
//   val candidates: List<ProviderCandidate>   // ordered; first == preferred
// and keeps provider/parentRef/catalogRef as the first candidate's values
// (batcher GroupKey and all current call sites stay source-compatible).
```
- `resolve()` builds every parseable/handled candidate instead of `firstOrNull()`; per-candidate `catalogRefFor` picks the campsite ref matching that candidate's provider (existing logic, applied per candidate). Returns null only when no candidate resolves.

- [ ] **Step 1:** Test: dual-vendor fixture resolves with 2 candidates in repo order; single-vendor keeps 1; unparseable refs skipped. Run → FAIL.
- [ ] **Step 2:** Implement. Run → PASS. **Commit** `git commit -m "feat: availability resolver returns ordered provider candidates" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 3: ProviderCooldownTracker

**Files:**
- Create: `ProviderCooldownTracker.kt` + `ProviderCooldownTrackerTest.kt`

**Interfaces:**
```kotlin
internal class ProviderCooldownTracker(
    private val cooldown: Duration,
    private val clock: () -> Instant = Instant::now,
) {
    companion object { const val DEFAULT_COOLDOWN_SECONDS = 300L }  // env AVAILABILITY_PROVIDER_COOLDOWN_SECONDS
    fun recordFailure(id: AvailabilityProviderId)
    fun recordSuccess(id: AvailabilityProviderId)          // clears cooldown
    fun isCooling(id: AvailabilityProviderId): Boolean
    fun <T> sortHealthyFirst(items: List<T>, idOf: (T) -> AvailabilityProviderId): List<T>  // stable
}
```
- Thread-safe (ConcurrentHashMap of expiry instants). Env wiring follows the existing config pattern in `config/`.

- [ ] **Step 1:** Test with injected clock: failure → cooling; expiry → healthy; success clears; sort is stable and demotes cooling providers without dropping them. Run → FAIL.
- [ ] **Step 2:** Implement. Run → PASS. **Commit** `git commit -m "feat: in-process provider cooldown tracker" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 4: FailoverAvailabilityFetcher + wiring into both paths

**Files:**
- Create: `FailoverAvailabilityFetcher.kt` + `FailoverAvailabilityFetcherTest.kt`
- Modify: `AvailabilityLoader.kt`, `AvailabilityPollExecutor.kt` (fetch lambda passed to `CatalogAvailabilityBatcher.fetchByGroup`), `Main.kt`/`RoadtripRuntime.kt` wiring
- Modify: `repo/CampsiteProviderRepo.kt` (sibling campsite-ref translation query) or a small addition to `campsite_canonical` usage

**Interfaces:**
```kotlin
internal class FailoverAvailabilityFetcher(
    private val cooldowns: ProviderCooldownTracker,
) {
    data class AttemptRecord(
        val provider: AvailabilityProviderId,
        val parentRef: ProviderRef,
        val outcome: FetchOutcome,
        val durationMs: Int,
        val error: String?,
    )
    data class FailoverResult(
        val batch: AvailabilityObservationBatch?,   // non-null iff some attempt OK
        val servedBy: AvailabilityProviderId?,
        val attempts: List<AttemptRecord>,          // one per upstream call, for fetch-call trace rows
    )
    suspend fun fetch(
        candidates: List<ProviderCandidate>,        // resolver order; cooldown reorder applied inside
        campsites: List<Campsite>,
        window: ResolvedDateWindow,
        translateRefs: (ProviderCandidate) -> List<CatalogCampsiteRef>,
            // sibling candidate → that vendor's campsite refs, campsiteId = representative id
    ): FailoverResult
}
```
- Walk: for each candidate (cooldown-sorted), call `provider.catalogAvailability(...)`; on OK → `recordSuccess`, stop; on `AvailabilityProviderError` mapping to RATE_LIMITED/UPSTREAM_5XX/BLOCKED → `recordFailure`, record attempt, continue; on OTHER → record attempt, stop (no failover). Observations already carry campsite ids from `translateRefs`, so batch rows land on representative ids.
- Poller integration: `AvailabilityPollExecutor` passes a fetch lambda to the batcher — replace its direct `provider.catalogAvailability` call with the failover fetcher; write **one `availability_fetch_call` row per attempt** (the executor already writes per-group rows; extend to iterate `attempts`).
- Live integration: `AvailabilityLoader`'s adapter call goes through the same fetcher with the resolved target's candidates.
- `translateRefs` for the preferred candidate is the existing per-campsite ref lookup; for siblings, join `campsite_matches`/`campsite_canonical` member ids to fetch the sibling row's vendor refs while keeping `campsiteId` = representative id (add the repo query here).

- [ ] **Step 1:** Fetcher unit test with fake providers: OK-first → one attempt; rate-limited-then-OK → two attempts, servedBy = second, cooldown recorded; all-fail → null batch, attempts complete; OTHER → single attempt, no failover; cooling preferred provider demoted but sole candidate still tried. Run → FAIL.
- [ ] **Step 2:** Implement fetcher. Run → PASS. Commit `git commit -m "feat: failover availability fetcher" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`
- [ ] **Step 3:** Wire poller path (attempt→fetch-call rows; extend `AvailabilityPollExecutorTest`), then live path. `./gradlew build` → PASS.
- [ ] **Step 4: Commit** `git commit -m "feat: failover fetch in poller and live availability paths" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 5: availability_provider API field from resolver

**Files:**
- Modify: `routes/PoiRoutes.kt` (~:370 `availabilityProvider = r.providerSource`), the repo/service that supplies `providerSource`
- Test: `PoiServingRepoTest` / route contract test

- [ ] **Step 1:** Test: dual-vendor campground with `preferred_availability_source = 'recgov'` → detail response `availability_provider == "recgov"`; null preference → winner's provider. Run → FAIL.
- [ ] **Step 2:** Populate from the first candidate of `CampsiteProviderRepo.findProviderRefCandidates(poiId)` (same ordering the resolver uses — one source of truth; expose via the existing availability support service rather than calling the repo from the route). Run → PASS.
- [ ] **Step 3: Commit** `git commit -m "feat: availability_provider reflects resolver preference" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 6: Capability rename supportsAlerts → pollableForAlerts

**Files:**
- Modify: `AvailabilityProviderCapabilities.kt`, 5 adapters, all test usages (16 files — grep `supportsAlerts`)
- Check wire: grep `supportsAlerts\|supports_alerts` in `models/api/`, `routes/`, and `web/` — if serialized to the FE, rename the `@SerialName` and the web reads in this same task.

- [ ] **Step 1:** Mechanical rename (compile-driven; no behavior change). `./gradlew build` → PASS; web tests if wire field renamed.
- [ ] **Step 2: Commit** `git commit -m "refactor: rename supportsAlerts to pollableForAlerts" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 7: AlertProvider seam

**Files:**
- Create: `service/availability/alert/AlertProvider.kt`, `InternalPollerAlertProvider.kt`, `AlertProviderRegistry.kt` + `AlertProviderRegistryTest.kt`
- Modify: `AvailabilityWatchService.kt` (poller-membership calls move behind the seam; see `membershipFor` :31-32 and `deactivatePollersWithNoLinks` :63), wiring in `Main.kt`/`RoadtripRuntime.kt`
- Test: extend `AvailabilityWatchServiceTest.kt`

**Interfaces:**
```kotlin
/** Who detects openings for a watch. The internal poller is the default; a
 *  vendor-hosted implementation (e.g. Campflare's alert API) subscribes
 *  upstream in onWatchActivated, receives webhooks on a route it owns,
 *  normalizes payloads to CellTransition, and feeds the same
 *  WatchAlertDispatcher. A new alert provider is one file under
 *  alert/providers/<vendor>/ plus one registry row. */
internal interface AlertProvider {
    val id: String                                   // "internal_poller", later "campflare"
    val hostsAlerts: Boolean                         // false = platform polls; true = vendor pushes
    fun onWatchActivated(txn: DSLContext, watch: AvailabilityWatchRepo.Watch)
    fun onWatchDeactivated(txn: DSLContext, watch: AvailabilityWatchRepo.Watch)
}

internal class InternalPollerAlertProvider(/* deps = what AvailabilityWatchService.membershipFor needs */) : AlertProvider
internal class AlertProviderRegistry(private val providers: List<AlertProvider>) {
    fun forWatch(watch: AvailabilityWatchRepo.Watch): AlertProvider   // v1: always internal poller
}
```
- `InternalPollerAlertProvider` wraps today's `AvailabilityPollerMembership` sync + `deactivatePollersWithNoLinks` — behavior identical, calls relocated. Match the actual transaction shape in `AvailabilityWatchService` when implementing (hooks take the txn context because membership writes are transactional today).

- [ ] **Step 1:** Test: watch create/update/pause/delete drive `onWatchActivated`/`onWatchDeactivated` through the registry (fake provider records calls); poller membership behavior unchanged (existing `AvailabilityWatchServiceTest` assertions still green). Run → FAIL.
- [ ] **Step 2:** Implement + rewire. Run → PASS. **Commit** `git commit -m "feat: alert-provider seam (internal poller first implementation)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 8: TriggerActionHandler registry

**Files:**
- Create: `service/availability/TriggerActionHandler.kt`, `SlackNotifyHandler.kt` + `TriggerActionHandlerTest.kt`
- Modify: `WatchAlertDispatcher.kt` (:16 const moves into handler; :65 branch → registry lookup), wiring

**Interfaces:**
```kotlin
internal interface TriggerActionHandler {
    val kind: String
    /** Returns true iff delivery succeeded (drives stopWhenTriggered). */
    suspend fun fire(watch: AvailabilityWatchRepo.Watch, notice: WatchStatusNotice): Boolean
}
internal class SlackNotifyHandler(private val slack: SlackNotificationService) : TriggerActionHandler {
    companion object { const val KIND = "slack_notify" }
}
internal class TriggerActionRegistry(handlers: List<TriggerActionHandler>) {
    fun forKind(kind: String): TriggerActionHandler?   // null = inert (current `atc` behavior)
}
```
- Design detail: `WatchAlertDispatcher.postOpenings`/`statusNoticeForWatch` already build `WatchStatusNotice`/`WatchOpening` — shape `fire`'s parameters from what those actually pass to `SlackNotificationService` (adjust the signature to reality when reading the file; keep channel-override extraction (`triggerConfig["channel"]`) inside the handler; keep "watch goes DONE only after fire() returns true").

- [ ] **Step 1:** Test: known kind fires handler; unknown kind inert; `stopWhenTriggered` marks DONE only on `fire() == true`; channel override forwarded. Run → FAIL.
- [ ] **Step 2:** Implement, rewire dispatcher. Run → PASS. **Commit** `git commit -m "feat: trigger-action handler registry (slack_notify first handler)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

### Task 9: Contract docs + end-to-end verification

**Files:**
- Modify: `docs/reservation-providers.md`: Layout tree (+`alert/`, `FailoverAvailabilityFetcher`, `ProviderCooldownTracker`); new "Multi-source resolution" section (match-group candidates, preference column, failover walk, cooldown, per-attempt fetch-call rows); alert-seam section with the one-file+registry-row rule; trigger-registry section; capability rename; adapter matrix Campflare row → "hosted alerts: planned via AlertProvider".
- Modify: `docs/backend-architecture.md`: package tree gains `service/availability/alert/`.

- [ ] **Step 1:** Update both docs.
- [ ] **Step 2: E2E** on the local tilt stack (backend :8765): pick a dual-source campground (Upper Pines); `UPDATE campgrounds SET preferred_availability_source='recgov' WHERE id=...;` → `GET /api/pois/{id}` `availability_provider` flips, availability still loads, `availability_fetch_call.provider` shows recgov; flip to `campflare` → follows. Simulate failure (unset the preferred provider's API key / point its base URL at a dead port, restart) → drawer availability still loads via the sibling, fetch-call rows show both attempts.
- [ ] **Step 3:** `./gradlew build`, web tests, `python3 scripts/test_grafana_canonical_catalog_dashboards.py` → all PASS.
- [ ] **Step 4: Commit** `git commit -m "docs: multi-source availability, alert seam, trigger registry contracts" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

## Self-Review

- [ ] Batcher `GroupKey` still keys on the preferred candidate — no grouping regression; failover happens inside the group fetch.
- [ ] `OTHER` outcomes never fail over; cooling providers demoted, never excluded.
- [ ] Observations always land on representative campsite ids regardless of which vendor served.
- [ ] Alert seam adds zero behavior change in this plan (internal poller relocated, not rewritten).
- [ ] `supportsAlerts` fully renamed including any wire field + web reads.
- [ ] Signatures here match plan-1 renames (`Campsite`, `CatalogCampsiteRef`) and plan-2 artifacts (`campground_canonical.member_ids`, `preferred_availability_source`).
- [ ] Executor instructed to reconcile exact signatures (dispatcher/notice shapes, watch-service transaction shape) against the real files at implementation time — deviations noted in commits, not silently absorbed.
