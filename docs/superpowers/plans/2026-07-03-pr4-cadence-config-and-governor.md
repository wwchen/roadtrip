# PR4: Cadence Config + Vendor Governor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the per-POI cadence override fall-through (`watch.cadence_sec ?? poi.cadence_override_sec ?? GLOBAL_DEFAULT_SEC`) to the executor's cadence derivation, and add a durable, Postgres-backed Bucket4j token bucket keyed by vendor so the executor acquires K tokens (K = number of provider buckets it's about to fetch) **before** fetching — skipping and rescheduling soon on starvation instead of making the call.

**Architecture:** `pois.cadence_override_sec` is a plain nullable column (PR1 left this column for PR4 per the spec). The executor's existing `liveWatches.minOf { it.cadenceSec }` becomes a three-level fall-through per watch (`watch.cadenceSec` is already NOT NULL today — PR2 may or may not make it nullable; this plan treats "the watch specifies a cadence" and "the POI has an override" as independently optional and takes the tightest non-null value across all live watches' resolved cadences). The vendor governor is a new `VendorRateLimiter` service wrapping `Bucket4j`'s JDBC/Postgres proxy manager, configured per-provider from a YAML/env-driven capacity+refill config (mirrors the project's existing config-driven-over-hardcoded convention). The executor calls `limiter.tryAcquire(provider, tokens = bucketCount)` immediately before the fetch loop; on failure it skips the fetch entirely (no upstream call, no run-failure recorded) and reschedules `next_run_at` soon via a **governor-starved** branch distinct from both the success and backoff branches.

**Tech Stack:** Kotlin, Ktor, jOOQ, Postgres, Bucket4j (`bucket4j-postgresql` + `bucket4j-core`), Testcontainers/SharedDbTest, kotlinx.coroutines.

## Global Constraints

- **Build needs JDK 17.** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew` from repo root.
- **jOOQ includes allowlist.** No new *application* table is introduced by the cadence override (it's a column on the existing `pois` table, already in the allowlist). Bucket4j's Postgres proxy manager creates and owns its own bucket-state table (see Task 3) — that table is Bucket4j's internal storage, not a project domain table, so it is **not** added to the jOOQ includes allowlist (nothing in the codebase queries it via jOOQ; Bucket4j manages it directly via JDBC).
- **Layering.** The governor is a `service`-layer component (`service/availability/VendorRateLimiter.kt` or `service/ratelimit/`), constructed once in `Main.kt` and injected into the executor exactly like every other collaborator. It must not leak Bucket4j types across the executor boundary beyond a `tryAcquire(vendor, tokens): Boolean`-shaped call — the executor should not import `io.github.bucket4j.*` directly if a thin wrapper avoids it (keeps the dependency swappable and matches "no leaky abstractions").
- **No inline magic constants.** Capacity, refill rate/period, and the governor's "reschedule soon" delay are all named `const val` defaults, overridable via config. `GLOBAL_DEFAULT_SEC` (already defined in the executor from PR1) stays the final fall-through rung.
- **Config-driven over hardcoded.** Per-vendor bucket capacity/refill must be overridable without a recompile — wire through the project's existing YAML registry or env var convention (check `config/` for the established pattern before inventing a new one) with in-code defaults.
- **SharedDbTest pattern.** New repo/service tests extend `SharedDbTest`.
- **Postgres timestamp rounding.** Same caveat as PR1/PR3 — truncate to `ChronoUnit.MICROS` before asserting `next_run_at`/lease-timestamp equality or ordering against a value that has round-tripped through Postgres.
- **Cadence is a target, not a guarantee (spec).** The governor gates the fetch, not `next_run_at`'s formula — `next_run_at = now + cadence` is computed the same way whether or not the fetch actually happened; only the *starved* branch diverges (reschedule soon, not `now + cadence`, since no work was done and no backoff penalty is warranted either).

## Cross-PR dependency (read before starting)

This plan assumes PR1 (V27–V28), PR2 (V29, per its plan doc), and PR3 (`docs/superpowers/plans/2026-07-03-pr3-availability-cube.md`, provisionally V30) have landed in that order. **This plan's migration is provisionally V31.** None of PR1/PR2/PR3 are merged as of this writing — confirm the actual next-free `V*` at execution time before creating the migration file; if PR3 slips or is reordered, renumber.

This plan also depends on PR3's `AvailabilityPollExecutor.writeCube` existing (the fetch loop shape it modifies is PR3's, not PR1's raw `appendSnapshots` loop) — if PR3 has not landed when this plan executes, adapt Task 4's diff to PR1's original `handle()` body instead.

---

## File Structure

**New files:**
- `backend/src/main/resources/db/migration/V31__poi_cadence_override.sql` — `pois.cadence_override_sec` column.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimiter.kt` — thin wrapper over Bucket4j's Postgres-backed proxy manager.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimitConfig.kt` — per-vendor capacity/refill config, YAML/env-driven with defaults.
- `backend/src/test/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimiterTest.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CadenceResolverTest.kt` (or inline in `AvailabilityPollExecutorTest.kt` if a separate resolver type isn't warranted — see Task 2).

**Modified:**
- `backend/build.gradle.kts` — add `bucket4j-core` + `bucket4j-postgresql` dependencies.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt` — cadence fall-through; pre-fetch token acquisition; governor-starved reschedule branch.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt` (or wherever `liveWatchesForPoller`'s `Watch` type is joined) — the join needs `pois.cadence_override_sec` alongside the watch row so the executor can resolve fall-through per watch without an extra query. Likely lands on `AvailabilityPollerRepo.liveWatchesForPoller`'s SELECT (join `pois`) rather than `AvailabilityWatchRepo` itself, since that's the poller-scoped read path — confirm which query the executor actually calls before touching a shared method other callers depend on.
- `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` — construct `VendorRateLimiter` + `VendorRateLimitConfig`, inject into `AvailabilityPollExecutor`.
- `config/` — add the vendor rate-limit config file/section (exact location depends on the project's established YAML registry; inspect `config/` before authoring — do not invent a new config-loading mechanism).

---

## Interfaces (locked signatures used across tasks)

```kotlin
// VendorRateLimitConfig.kt
data class VendorBucketConfig(
    val capacity: Long,       // max tokens
    val refillTokens: Long,   // tokens added per refill period
    val refillPeriod: Duration,
)

/** Per-vendor bucket config with a code-level default, overridable via
 *  config/env. Providers not explicitly configured fall through to
 *  DEFAULT_VENDOR_BUCKET. */
class VendorRateLimitConfig(
    overrides: Map<String, VendorBucketConfig> = emptyMap(),
) {
    fun forVendor(provider: String): VendorBucketConfig
}

// VendorRateLimiter.kt
/** Durable (Postgres-backed) per-vendor token bucket. A restart does not
 *  reset the budget -- state lives in Bucket4j's Postgres proxy table, not
 *  in process memory. */
class VendorRateLimiter(
    private val config: VendorRateLimitConfig,
    dataSource: DataSource,
) {
    /** Attempts to consume [tokens] from [provider]'s bucket. Returns true
     *  if acquired (caller may proceed with exactly that many upstream
     *  calls); false if insufficient tokens are available RIGHT NOW (no
     *  partial consumption on failure). Non-blocking -- never waits for
     *  refill. */
    fun tryAcquire(provider: String, tokens: Long): Boolean
}
```

```kotlin
// AvailabilityPollExecutor.kt -- cadence fall-through (replaces PR1's liveWatches.minOf { it.cadenceSec })
private fun resolveCadenceSec(liveWatches: List<AvailabilityWatchRepo.Watch>): Int {
    val resolved = liveWatches.map { w ->
        w.cadenceSec.takeIf { it > 0 }
            ?: w.poiCadenceOverrideSec?.takeIf { it > 0 }
            ?: GLOBAL_DEFAULT_SEC
    }
    return resolved.minOrNull() ?: GLOBAL_DEFAULT_SEC
}
```

> Note: the spec's fall-through is `watch.cadence_sec ?? poi.cadence_override_sec ?? GLOBAL_DEFAULT_SEC` **per watch**, then the poller's cadence is the **min across live watches** of that per-watch resolved value — not a poller-level fall-through. `AvailabilityWatchRepo.Watch` (or the poller-scoped read) must carry `poiCadenceOverrideSec: Int?` for this to be per-watch-resolvable; confirm PR2 hasn't already changed `Watch`'s shape in a way that conflicts (PR2 is watch-as-set; a watch's POI linkage may move from a single `poiId` column to `availability_watch_target` rows — if so, `poiCadenceOverrideSec` needs to resolve per *target*, and the poller-level cadence becomes min over the union of (watch × target) resolved cadences. Verify against PR2's landed shape before implementing this task, not against the pre-PR2 `Watch` shown in the Interfaces block above.)

---

### Task 1: Schema migration — `pois.cadence_override_sec`

**Files:**
- Create: `backend/src/main/resources/db/migration/V31__poi_cadence_override.sql`

**Interfaces:** adds nullable `cadence_override_sec INT` to `pois`, with the spec's `>= 5` check.

- [ ] **Step 1: Write the migration.**

```sql
-- PR4: per-POI cadence override. NULL = no override, fall through to
-- watch.cadence_sec's absence -> GLOBAL_DEFAULT_SEC. A hot ground gets a
-- tight override (e.g. 30s); a sleepy ground stays on the global default.
ALTER TABLE pois ADD COLUMN cadence_override_sec INT
  CHECK (cadence_override_sec IS NULL OR cadence_override_sec >= 5);
```

- [ ] **Step 2: Regenerate jOOQ + confirm compile.** `pois` is already in the includes allowlist; no allowlist change needed, but jOOQ's generated `Pois` record type gains a field.

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:generateJooq :backend:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```
git add backend/src/main/resources/db/migration/V31__poi_cadence_override.sql
git commit -m "feat(cadence): V31 migration -- pois.cadence_override_sec" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Cadence fall-through in the executor

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Modify: the poller-scoped watch-loading query (`AvailabilityPollerRepo.liveWatchesForPoller` as of PR1 — confirm current owner after PR2/PR3) to also select `pois.cadence_override_sec`.
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt` (extends PR1's existing `cadence is the min over live watches` test).

**Interfaces:** `AvailabilityWatchRepo.Watch` (or its poller-scoped read projection) gains `poiCadenceOverrideSec: Int?`; executor gains `resolveCadenceSec`.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun `cadence falls through watch then poi override then global default`() {
    // Fixture: poi with cadence_override_sec = 30; one watch on that poi with
    // cadence_sec left at the project's "no explicit preference" representation
    // (confirm: is that 0, a sentinel, or is cadence_sec always NOT NULL and this
    // whole per-watch branch is moot until PR2 makes it nullable? Read
    // AvailabilityWatchRepo's current CreateInput/column NOT NULL-ness before
    // writing this fixture -- do not assume nullability that doesn't exist yet).
    // Expect: poller cadence resolves to 30 (poi override), not GLOBAL_DEFAULT_SEC.
}

@Test fun `tighter watch cadence still wins over a looser poi override`() {
    // watch.cadence_sec = 10, poi.cadence_override_sec = 30 -> resolves to 10.
}

@Test fun `no watch cadence and no poi override falls through to GLOBAL_DEFAULT_SEC`() {
}

@Test fun `min is taken across multiple live watches after each resolves its own fall-through`() {
    // watch A on poi with override=30, watch B with explicit cadence_sec=15 on a
    // different poi (same parentRef/poller) -> poller cadence = 15.
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityPollExecutorTest*'`
Expected: FAIL (compile error until `poiCadenceOverrideSec` exists on `Watch`, or logic assertion failure once it compiles).

- [ ] **Step 3: Implement.** Add the join column to the SELECT, add `poiCadenceOverrideSec: Int?` to the `Watch` data class (or the poller-scoped projection type, if PR2/PR3 split it out), and replace the executor's cadence line with `resolveCadenceSec(liveWatches)` per the Interfaces block. Confirm the actual "no explicit watch cadence" representation in the landed `AvailabilityWatchRepo`/`AvailabilityWatchService` code before writing this — do not assume `0` or `null` without checking.

- [ ] **Step 4: Run to verify it passes; then full suite.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`

- [ ] **Step 5: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt
git commit -m "feat(cadence): watch -> poi override -> global default fall-through" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `VendorRateLimitConfig` — per-vendor bucket config

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimitConfig.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimitConfigTest.kt`
- Modify: `config/` — add per-vendor overrides using the project's existing config file format (inspect `config/*.yaml`/`.env`/whatever is already there for e.g. reservation-provider settings — `docs/reservation-providers.md` may document the existing per-vendor config surface; extend it rather than invent a parallel one).

**Interfaces:** `VendorRateLimitConfig.forVendor(provider: String): VendorBucketConfig`, per the Interfaces block above.

- [ ] **Step 1: Write the failing test.**

```kotlin
class VendorRateLimitConfigTest {
    @Test fun `unconfigured vendor gets the code-level default bucket`() {
        val config = VendorRateLimitConfig(overrides = emptyMap())
        val bucket = config.forVendor("recgov")
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, bucket.capacity)
    }

    @Test fun `configured vendor overrides the default`() {
        val config = VendorRateLimitConfig(overrides = mapOf(
            "aspira" to VendorBucketConfig(capacity = 5, refillTokens = 5, refillPeriod = Duration.ofSeconds(10)),
        ))
        assertEquals(5, config.forVendor("aspira").capacity)
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, config.forVendor("recgov").capacity) // untouched
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./gradlew :backend:test --tests '*VendorRateLimitConfigTest*'`

- [ ] **Step 3: Implement.** Named constants for the default bucket (`DEFAULT_VENDOR_BUCKET_CAPACITY`, `DEFAULT_VENDOR_BUCKET_REFILL_TOKENS`, `DEFAULT_VENDOR_BUCKET_REFILL_PERIOD`); a loader that reads the project's config source (YAML/env) into `overrides` at `Main.kt` construction time — the `VendorRateLimitConfig` class itself stays pure/testable (constructor takes the already-parsed map, no I/O inside).

- [ ] **Step 4: Run to verify it passes.**

Run: `./gradlew :backend:test --tests '*VendorRateLimitConfigTest*'`

- [ ] **Step 5: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimitConfig.kt backend/src/test/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimitConfigTest.kt
git commit -m "feat(governor): per-vendor rate limit config with code defaults" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `VendorRateLimiter` — Bucket4j Postgres-backed governor

**Files:**
- Modify: `backend/build.gradle.kts` — add Bucket4j deps.
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimiter.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimiterTest.kt`

**Interfaces:** `tryAcquire(provider, tokens): Boolean`, per the Interfaces block above.

- [ ] **Step 1: Add the dependency.**

```kotlin
// backend/build.gradle.kts, alongside the other implementation(...) lines
implementation("com.bucket4j:bucket4j-core:8.10.1")
implementation("com.bucket4j:bucket4j-postgresql:8.10.1")
```

(Confirm the latest stable `bucket4j-postgresql` version at implementation time — 8.10.1 is illustrative, not a locked pin. This adds JDBC-driven state storage; Bucket4j's Postgres module creates its own internal state table via its own migration/DDL path on first use, separate from Flyway — read the `bucket4j-postgresql` module's setup docs for whether it needs a one-time `CREATE TABLE` this project must run via a Flyway migration (likely, so the table exists deterministically rather than lazily) versus one it creates itself; if it needs an explicit `CREATE TABLE`, add it to `V31` (or a `V31b` if `V31` already committed) rather than leaving schema creation to a library's implicit runtime path in a project that otherwise treats Flyway as the single source of schema truth.

- [ ] **Step 2: Write the failing test.**

```kotlin
class VendorRateLimiterTest : SharedDbTest() {
    @Test fun `tryAcquire succeeds up to capacity then fails until refill`() {
        val config = VendorRateLimitConfig(overrides = mapOf(
            "recgov" to VendorBucketConfig(capacity = 2, refillTokens = 2, refillPeriod = Duration.ofSeconds(60)),
        ))
        val limiter = VendorRateLimiter(config, ds)  // ds from SharedDbTest
        assertTrue(limiter.tryAcquire("recgov", 1))
        assertTrue(limiter.tryAcquire("recgov", 1))
        assertFalse(limiter.tryAcquire("recgov", 1))  // exhausted
    }

    @Test fun `acquiring more tokens than available fails without partial consumption`() {
        val config = VendorRateLimitConfig(overrides = mapOf(
            "recgov" to VendorBucketConfig(capacity = 3, refillTokens = 3, refillPeriod = Duration.ofSeconds(60)),
        ))
        val limiter = VendorRateLimiter(config, ds)
        assertFalse(limiter.tryAcquire("recgov", 5))  // > capacity, fails outright
        assertTrue(limiter.tryAcquire("recgov", 3))   // full bucket still intact
    }

    @Test fun `buckets for different vendors are independent`() {
        val config = VendorRateLimitConfig(overrides = mapOf(
            "recgov" to VendorBucketConfig(1, 1, Duration.ofSeconds(60)),
            "aspira" to VendorBucketConfig(1, 1, Duration.ofSeconds(60)),
        ))
        val limiter = VendorRateLimiter(config, ds)
        assertTrue(limiter.tryAcquire("recgov", 1))
        assertTrue(limiter.tryAcquire("aspira", 1))  // separate bucket, not starved by recgov
    }

    @Test fun `budget survives limiter recreation (durable, not in-memory)`() {
        val config = VendorRateLimitConfig(overrides = mapOf(
            "recgov" to VendorBucketConfig(1, 1, Duration.ofSeconds(60)),
        ))
        VendorRateLimiter(config, ds).tryAcquire("recgov", 1)
        val secondInstance = VendorRateLimiter(config, ds)  // simulates a restart
        assertFalse(secondInstance.tryAcquire("recgov", 1))  // still exhausted -- state was in Postgres
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*VendorRateLimiterTest*'`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement.** Use Bucket4j's `PostgreSQLadvisoryLockBasedProxyManager` (or the SQL-based proxy manager variant — pick whichever `bucket4j-postgresql` ships as the documented non-blocking option; the "advisory lock" variant is the commonly recommended one for this use case) keyed by provider name, configured from `VendorBucketConfig` via `BucketConfiguration.builder().addLimit(Bandwidth.classic(capacity, Refill.intervally(refillTokens, refillPeriod)))`. `tryAcquire` delegates to the Bucket4j bucket's `tryConsume(tokens)`.

```kotlin
package ca.floo.roadtrip.service.ratelimit

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.Refill
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL
import javax.sql.DataSource

class VendorRateLimiter(
    private val config: VendorRateLimitConfig,
    dataSource: DataSource,
) {
    private val proxyManager = Bucket4jPostgreSQL.advisoryLockBasedBuilder(dataSource).build()

    fun tryAcquire(provider: String, tokens: Long): Boolean {
        val bucketConfig = config.forVendor(provider)
        val supplier = java.util.function.Supplier {
            BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(bucketConfig.capacity, Refill.intervally(bucketConfig.refillTokens, bucketConfig.refillPeriod)))
                .build()
        }
        val bucket = proxyManager.builder().build(provider.hashCode().toLong(), supplier)
        return bucket.tryConsume(tokens)
    }
}
```

> **Verify the exact Bucket4j 8.x Postgres API surface against the actual library
> javadoc/source at implementation time** — Bucket4j's proxy-manager builder API has
> changed across major versions (key type, `BucketProxy` vs `Bucket`, supplier
> signature). The sketch above communicates intent (durable, keyed-by-vendor,
> non-blocking `tryConsume`); do not copy it verbatim without confirming against the
> pinned dependency version's actual API.
> **Key collision risk:** `provider.hashCode().toLong()` is a placeholder — Bucket4j's
> proxy manager keys are typically `Long` or a generic `K`; if `Long`, hash collisions
> across provider names are a real (if rare) correctness bug. Prefer a proxy manager
> generic over `String` keys if the library version supports it; otherwise maintain an
> explicit `provider -> Long` mapping (e.g. a small enum ordinal table) rather than
> trusting `hashCode()`.

- [ ] **Step 4: Run to verify it passes.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*VendorRateLimiterTest*'`
Expected: BUILD SUCCESSFUL, all passing.

- [ ] **Step 5: Commit.**

```
git add backend/build.gradle.kts backend/src/main/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimiter.kt backend/src/test/kotlin/ca/floo/roadtrip/service/ratelimit/VendorRateLimiterTest.kt
git commit -m "feat(governor): Postgres-backed Bucket4j vendor rate limiter" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Wire the governor into the executor (pre-fetch acquire, starved reschedule)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`

**Interfaces:** executor constructor gains `limiter: VendorRateLimiter`; `handle` gains a governor-gate branch before the fetch.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun `governor starvation skips the fetch and reschedules soon without failing the run`() {
    val limiter = mockLimiterThatAlwaysDenies()  // test double, not the real Postgres-backed one
    val executor = buildExecutor(limiter = limiter, provider = CountingRecgovProvider())
    val result = runBlocking { executor.handle(poller) }
    assertEquals(0, provider.fetchCount)          // no upstream call was made
    assertTrue(result.nextRunAt.isBefore(OffsetDateTime.now().plusSeconds(GOVERNOR_STARVED_RETRY_SEC + 1)))
    assertEquals(0, runs.listForPoller(poller.id).size)  // no run row -- consistent with PR1's
                                                          // "empty window -> no run row" precedent;
                                                          // starvation is a non-event, not a failure
}

@Test fun `governor success proceeds to fetch exactly once and consumes the right token count`() {
    // K = number of distinct provider buckets about to be fetched this tick (for a
    // single-provider poller, K=1). Assert the limiter was asked for exactly K tokens.
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityPollExecutorTest*'`

- [ ] **Step 3: Implement.** Insert the acquire check after deriving `resolved` (the reservables/targets to fetch) and before calling `batcher.fetchByGroup`. K = the number of distinct `(provider, parentRef, dateContext)` buckets the batcher is about to fetch — for a single-poller-single-provider tick this is normally 1, but a poller can still resolve to >1 dateContext bucket (multi-timezone edge case already handled by the batcher); compute K from the same grouping the batcher itself would produce, not by assuming 1. If `AvailabilityTargetResolver`/`CatalogAvailabilityBatcher` doesn't already expose a cheap "how many buckets would this produce" method, add one rather than duplicating the grouping logic inline (avoids two sources of truth for the grouping key).

```kotlin
val bucketCount = batcher.countGroups(resolved)  // new method on CatalogAvailabilityBatcher, or
                                                   // inline via resolved.map { it.provider.id.name.lowercase() to parentRefKey(it.parentRef) }.distinct().size
                                                   // if adding a method to the batcher is out of scope for this PR
if (!limiter.tryAcquire(poller.provider, bucketCount.toLong())) {
    log.info("poller {} governor starved ({} tokens for {}); rescheduling soon", poller.id, bucketCount, poller.provider)
    return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(GOVERNOR_STARVED_RETRY_SEC))
}
```

Add `private const val GOVERNOR_STARVED_RETRY_SEC = 15L` (or whatever value discussion with the spec's "reschedules soon" settles on — named, not inline) near the executor's other constants.

- [ ] **Step 4: Wire `Main.kt`.** Construct `VendorRateLimitConfig` (from the config source established in Task 3) and `VendorRateLimiter(config, ds)`; pass into `AvailabilityPollExecutor`.

- [ ] **Step 5: Run to verify it passes; then full suite + ktlint.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`
Then: `./gradlew :backend:ktlintCheck`

- [ ] **Step 6: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt backend/src/main/kotlin/ca/floo/roadtrip/Main.kt backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt
git commit -m "feat(governor): executor acquires vendor tokens before fetching; skip+reschedule on starvation" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: End-to-end verification

**Files:** none (verification only). Uses the Tilt dev stack.

- [ ] **Step 1: Bring up the stack.** `tilt up`.

- [ ] **Step 2: Confirm cadence override takes effect.** Set a POI's `cadence_override_sec = 30` via SQL; confirm the poller's `next_run_at` cadence tightens on the next tick (`SELECT next_run_at, last_run_at FROM availability_poller WHERE id = ...` across two ticks, delta ≈ 30s).

- [ ] **Step 3: Confirm the governor caps fetch volume.** Configure a tiny bucket (e.g. capacity=1, refill=1/60s) for a test vendor via config override; bring several due pollers for that vendor; confirm via `availability_run` that only ≤ capacity runs actually fetched per interval and the rest show no run row but a `next_run_at` pulled forward by `GOVERNOR_STARVED_RETRY_SEC`.

- [ ] **Step 4: Confirm durability across restart.** Exhaust the bucket, restart the backend process, confirm the bucket is still exhausted (state survived in Postgres, not reset to full).

- [ ] **Step 5: Record the evidence** (SQL results + logs showing starvation log lines) in the PR description.

---

## Self-Review

**Spec coverage (PR4 bullet: "`pois.cadence_override_sec` fall-through + Bucket4j-Postgres governor at fetch"):**
- `pois.cadence_override_sec` column → Task 1. ✓
- fall-through `watch.cadence_sec ?? poi.cadence_override_sec ?? GLOBAL_DEFAULT_SEC`, min over live watches → Task 2. ✓
- Bucket4j token bucket backed by Postgres, keyed by vendor, capacity/refill from config → Task 3 + Task 4. ✓
- executor acquires K tokens before fetching; on failure skips + reschedules soon (no upstream call, no wasted 429) → Task 5. ✓
- budget survives a restart (Postgres-backed) → Task 4's durability test + Task 6 Step 4. ✓
- per-poller backoff stays as the reactive net → unchanged; explicitly not touched by this plan (PR1's backoff branch is orthogonal to the new starved branch — starvation never counts as a failure, so it never feeds `countConsecutiveFailures`). ✓

**Deliberately out of PR4 scope (documented):** force pull (PR5); alert eval over the cube (PR6, one sentence below).

**PR6 (out of scope):** Alert firing/notification evaluation over the cube is deferred to PR6. This PR's governor interacts with it only in that a force-pull-triggered fetch (PR5) will also draw tokens — not built here.

**Open risks flagged during planning:**
1. **Migration numbering is provisional (V31).** Depends on PR1 (V27/V28), PR2 (V29), PR3 (V30, itself provisional) landing first, in order. Confirm at execution time.
2. **PR2's watch shape is unknown to this plan.** The cadence fall-through's "per watch, per target" resolution (see the Interfaces section note) may need rework if PR2 moves POI linkage off a single `Watch.poiId` column onto `availability_watch_target` rows before PR4 starts. This plan describes the pre-PR2 shape as a starting point and explicitly flags the risk rather than silently assuming it.
3. **Bucket4j Postgres API surface not verified against a pinned version.** Task 4's code sketch communicates the intended shape (durable, keyed-by-vendor, non-blocking `tryConsume`) but must be checked against the actual `bucket4j-postgresql` version's javadoc before landing — proxy-manager builder signatures have changed across Bucket4j 7.x/8.x.
4. **Bucket4j's own schema creation vs Flyway.** Task 4 flags that Bucket4j's Postgres module may create its state table itself rather than via this project's Flyway pipeline; if so, an explicit Flyway migration should create it instead, to keep one source of schema truth. Needs verification against the library's actual setup docs.
5. **Key type for per-vendor buckets.** `provider.hashCode().toLong()` in the Task 4 sketch is a placeholder with a latent collision risk; needs either a native `String`-keyed proxy manager or an explicit enum-based mapping.
6. **K (token count per acquire) definition.** Spec says "K = number of provider buckets it's about to fetch." Task 5 computes this from the batcher's grouping, which can exceed 1 per poller in multi-dateContext edge cases; flagged so the implementer doesn't hardcode K=1.
