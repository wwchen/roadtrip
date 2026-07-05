package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.notification.SlackContentAvailabilityRenderer
import ca.floo.roadtrip.service.notification.SlackContentWatchStatusRenderer
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.SlackNotificationServiceImpl
import ca.floo.roadtrip.service.notification.WatchOpening
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import ca.floo.roadtrip.service.ratelimit.VendorRateLimitConfig
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.BookingUrlTemplate
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvailabilityPollExecutorTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability")
        ctx.execute("DELETE FROM availability_fetch_call")
        ctx.execute("DELETE FROM availability_run")
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch_poller")
        ctx.execute("DELETE FROM availability_poller")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    /** Seeds a campground POI whose provider_ref resolves to ProviderRef.RecGov(campgroundId). */
    private fun seedPoi(
        campgroundId: String,
        cadenceOverrideSec: Int? = null,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at, cadence_override_sec
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz, ?
                ) RETURNING id
                """.trimIndent(),
                "poi-$campgroundId",
                """{"recgov_id": "$campgroundId"}""",
                cadenceOverrideSec,
            )!!
            .get("id", Long::class.java)

    /** Seeds one child reservable (site) linked to [poiId]. Returns its db id. */
    private fun seedReservable(
        poiId: Long,
        siteId: String,
    ): Long {
        val reservableId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO reservables (type, vendor, vendor_id, name, source)
                    VALUES ('site', 'recgov', ?, ?, 'test')
                    RETURNING id
                    """.trimIndent(),
                    siteId,
                    "Site $siteId",
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO reservable_pois (reservable_id, poi_id) VALUES (?, ?)",
            reservableId,
            poiId,
        )
        return reservableId
    }

    /** Seeds an ACTIVE poi-scoped watch. Returns its id. A NULL [cadenceSec]
     *  means "no watch-level cadence override" (V34), exercising the fall-through
     *  to the POI override / global default. */
    private fun seedWatch(
        poiId: Long,
        startDate: String,
        endDate: String,
        cadenceSec: Int? = 60,
        triggerKinds: List<String> = listOf("atc"),
        triggerConfig: String = "{}",
        stopWhenTriggered: Boolean = false,
    ): Long {
        val kindsLiteral = triggerKinds.joinToString(prefix = "ARRAY[", postfix = "]") { "'$it'" }
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        start_date, end_date, cadence_sec, trigger_kinds, trigger_config, stop_when_triggered
                    ) VALUES (
                        ?::date, ?::date, ?::int, $kindsLiteral, ?::jsonb, ?
                    ) RETURNING id
                    """.trimIndent(),
                    startDate,
                    endDate,
                    cadenceSec,
                    triggerConfig,
                    stopWhenTriggered,
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }

    /** Seeds an ACTIVE watch scoped to a single reservable (not the whole POI),
     *  so tests can prove the poller polls the full catalog regardless of how
     *  narrowly a watch is scoped. */
    private fun seedReservableWatch(
        reservableId: Long,
        startDate: String,
        endDate: String,
    ): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        start_date, end_date, cadence_sec, trigger_kinds, trigger_config, stop_when_triggered
                    ) VALUES (
                        ?::date, ?::date, 60, ARRAY['atc'], '{}'::jsonb, false
                    ) RETURNING id
                    """.trimIndent(),
                    startDate,
                    endDate,
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, reservable_id) VALUES (?, ?)", watchId, reservableId)
        return watchId
    }

    private fun membershipFor(provider: ReservationProvider): AvailabilityPollerMembership {
        val reservablesRepo = ReservableRepo(ctx)
        val registry = ReservationProviderRegistry(mapOf("test" to provider))
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                reservablesRepo = reservablesRepo,
                reservationProviders = registry,
                dateResolver = AvailabilityDateResolver(),
            )
        return AvailabilityPollerMembership(WatchScopeResolver(reservablesRepo), targets)
    }

    /** Links [watchId] onto its (provider, parentRef) poller via the production
     *  membership path, then returns the single resulting poller. */
    private fun linkWatch(
        provider: ReservationProvider,
        watchId: Long,
    ): AvailabilityPollerRepo.Poller {
        val watch = AvailabilityWatchRepo(ctx).findById(watchId)!!
        val pollers = AvailabilityPollerRepo(ctx)
        membershipFor(provider).sync(watch, pollers, tighterCadencePull = now())
        val pollerId = pollers.pollerIdsForWatch(watchId).single()
        return pollers.findById(pollerId)!!
    }

    /** A limiter double that always grants or always denies, recording every
     *  acquire request so tests can assert the token count. */
    private inner class RecordingLimiter(
        private val grant: Boolean,
    ) : VendorRateLimiter(VendorRateLimitConfig(), ds) {
        val requests = mutableListOf<Pair<String, Long>>()

        override fun tryAcquire(
            provider: String,
            tokens: Long,
        ): Boolean {
            requests += provider to tokens
            return grant
        }
    }

    /** One recorded send: the resolved channel, the fallback text, and the Block
     *  Kit blocks (both the openings alert and the status messages carry them). */
    private data class Post(
        val channel: String?,
        val text: String,
        val blocks: List<SlackBlockDto>?,
    ) {
        /** Everything a reader would see: fallback text + every block's text and
         *  fields. Deep-links render as `<url|label>` markup inside section text,
         *  so both label and url are captured by the section-text append. Lets
         *  content assertions ignore whether a string landed in the fallback or a
         *  block. */
        val allText: String
            get() =
                buildString {
                    append(text)
                    blocks?.forEach { b ->
                        b.text?.let { append('\n').append(it.text) }
                        b.fields?.forEach { append('\n').append(it.text) }
                    }
                }
    }

    /** A [SlackNotificationService] double that records every send and returns a
     *  configurable result, so alert tests never touch a live Slack workspace. It
     *  resolves the default channel the way the real impl does, and renders
     *  openings through the real [SlackContentAvailabilityRenderer], so both
     *  channel and message-content assertions stay meaningful. */
    private class RecordingSlackNotifications(
        var result: Boolean = true,
        private val defaultChannel: String? = "#camping",
    ) : SlackNotificationService {
        val posts = mutableListOf<Post>()

        override suspend fun sendWatchStatus(
            notice: WatchStatusNotice,
            channel: String?,
        ): Boolean {
            val (fallback, blocks) = SlackContentWatchStatusRenderer.render(notice)
            posts += Post(channel = channel ?: defaultChannel, text = fallback, blocks = blocks)
            return result
        }

        override suspend fun sendWatchOpenings(
            startDate: LocalDate,
            endDate: LocalDate,
            openings: List<WatchOpening>,
            channel: String?,
        ): Boolean {
            val (fallback, blocks) = SlackContentAvailabilityRenderer.openings(startDate, endDate, openings)
            posts += Post(channel = channel ?: defaultChannel, text = fallback, blocks = blocks)
            return result
        }
    }

    private fun targetsFor(provider: ReservationProvider): DbAvailabilityTargetResolver =
        DbAvailabilityTargetResolver(
            providerRefs = CampsiteProviderRepo(ctx),
            reservablesRepo = ReservableRepo(ctx),
            reservationProviders = ReservationProviderRegistry(mapOf("test" to provider)),
            dateResolver = AvailabilityDateResolver(),
        )

    /** Dispatcher with Slack disabled — a null-config service that no-ops and
     *  returns false. Default for tests that don't exercise alerting. */
    private fun disabledDispatcher(): WatchAlertDispatcher =
        WatchAlertDispatcher(
            slack = SlackNotificationServiceImpl(config = null),
            scopeResolver = WatchScopeResolver(ReservableRepo(ctx)),
            watches = AvailabilityWatchRepo(ctx),
            targets =
                DbAvailabilityTargetResolver(
                    providerRefs = CampsiteProviderRepo(ctx),
                    reservablesRepo = ReservableRepo(ctx),
                    reservationProviders = ReservationProviderRegistry(emptyMap()),
                    dateResolver = AvailabilityDateResolver(),
                ),
            pois = PoiServingRepo(ctx),
            availability = AvailabilityRepo(ctx),
            grafanaRootUrl = GRAFANA_ROOT_URL,
            appRootUrl = APP_ROOT_URL,
        )

    private fun dispatcherWith(
        provider: ReservationProvider,
        notifications: RecordingSlackNotifications,
        grafanaRootUrl: String? = GRAFANA_ROOT_URL,
        appRootUrl: String? = APP_ROOT_URL,
    ): WatchAlertDispatcher =
        WatchAlertDispatcher(
            slack = notifications,
            scopeResolver = WatchScopeResolver(ReservableRepo(ctx)),
            watches = AvailabilityWatchRepo(ctx),
            targets = targetsFor(provider),
            pois = PoiServingRepo(ctx),
            availability = AvailabilityRepo(ctx),
            grafanaRootUrl = grafanaRootUrl,
            appRootUrl = appRootUrl,
        )

    private fun executorFor(
        provider: ReservationProvider,
        limiter: VendorRateLimiter = RecordingLimiter(grant = true),
        alertDispatcher: WatchAlertDispatcher = disabledDispatcher(),
    ): AvailabilityPollExecutor {
        val reservablesRepo = ReservableRepo(ctx)
        val registry = ReservationProviderRegistry(mapOf("test" to provider))
        val dateResolver = AvailabilityDateResolver()
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                reservablesRepo = reservablesRepo,
                reservationProviders = registry,
                dateResolver = dateResolver,
            )
        return AvailabilityPollExecutor(
            pollers = AvailabilityPollerRepo(ctx),
            reservablesRepo = reservablesRepo,
            batcher = CatalogAvailabilityBatcher(),
            availability = AvailabilityRepo(ctx),
            runs = AvailabilityRunRepo(ctx),
            dateResolver = dateResolver,
            targets = targets,
            fetchCalls = AvailabilityFetchCallRepo(ctx),
            limiter = limiter,
            alertDispatcher = alertDispatcher,
        )
    }

    /** Fake provider that records each catalogAvailability call's window and
     *  returns one observation per requested reservable/day. */
    private class CountingRecgovProvider(
        var status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
        // When set, every observation is reported for this date instead of the
        // window start — lets a test place a transition on an exact day.
        var observationDate: LocalDate? = null,
        // Zero collapses the poll window to null so the batcher skips the group
        // (the only way a supported provider yields a null window now that the
        // window is vendor-derived, not watch-derived).
        maxPollWindowDays: Int = 60,
    ) : ReservationProvider {
        var calls: Int = 0
        var lastStart: LocalDate? = null
        var lastEnd: LocalDate? = null
        var lastReservableCount: Int = 0
        var mdcRunIdDuringCall: String? = null

        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 3650,
                maxPollWindowDays = maxPollWindowDays,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
            calls++
            lastStart = req.startDate
            lastEnd = req.endDate
            lastReservableCount = req.reservables.size
            mdcRunIdDuringCall = MDC.get("run_id")
            val observedAt = Instant.now()
            val observations =
                req.reservables.map { ref ->
                    ReservableDayObservation(
                        reservableId = ref.rid,
                        date = observationDate ?: req.startDate,
                        observedAt = observedAt,
                        status = status,
                    )
                }
            return AvailabilityObservationBatch(
                provider = "recgov",
                startDate = req.startDate,
                endDate = req.endDate,
                observations = observations,
                cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
            )
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override fun bookingUrlTemplate(
            reservable: Reservable,
            parentRef: ProviderRef,
        ): String = "https://example.test/book/${reservable.rid.vendorId}?d=${BookingUrlTemplate.START_DATE}"
    }

    private class RateLimitedProvider : ReservationProvider {
        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 3650,
                maxPollWindowDays = 60,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch =
            throw ReservationProviderError.RateLimited(RuntimeException("429"))

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")
    }

    // A window well in the future so both watches are fully live under the
    // target-local clamp; provider bookingHorizonDays is set high enough to cover it.
    private val farStart = LocalDate.now(ZoneOffset.UTC).plusYears(1)

    @Test
    fun `two live watches on one poller make one fetch over the vendor window`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            listOf("100", "101", "102").forEach { seedReservable(poiId, it) }
            // Two watches with different, far-future date windows. The poll
            // window is NOT derived from these dates — it's the vendor-max
            // window anchored at today (maxPollWindowDays = 60) — so the watch
            // dates only decide *whether* the poller runs, not how wide.
            val watchA = seedWatch(poiId, farStart.plusDays(5).toString(), farStart.plusDays(7).toString())
            val watchB = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchA)
            // watchB coalesces onto the same poller.
            AvailabilityWatchRepo(ctx).findById(watchB)!!.let { w ->
                membershipFor(provider).sync(w, AvailabilityPollerRepo(ctx), tighterCadencePull = now())
            }

            executorFor(provider).handle(poller)

            // ONE upstream call over the vendor window and all 3 sites once each.
            assertEquals(1, provider.calls)
            // Window is today-anchored and 60 days wide, independent of the
            // watches' year-out dates.
            val start = provider.lastStart!!
            val today = LocalDate.now(ZoneOffset.UTC)
            assertTrue(start == today || start == today.plusDays(1), "window starts at today's earliest bookable date, not the watch start")
            assertEquals(60L, ChronoUnit.DAYS.between(start, provider.lastEnd), "window spans the vendor cap, not the watch union")

            val runs = AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10)
            assertEquals(1, runs.size)
            assertEquals("completed", runs[0].status)

            val fetchCalls = AvailabilityFetchCallRepo(ctx).listForRun(runs[0].id)
            assertEquals(1, fetchCalls.size)
            assertEquals("ok", fetchCalls[0].outcome)
            assertEquals(3, fetchCalls[0].reservableCount)
            assertEquals("232447", fetchCalls[0].parentRef)
        }

    @Test
    fun `poller fetches the full campground catalog even when a watch scopes one site`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            val watchedSite = seedReservable(poiId, "100")
            // Two more sites nobody watches — they must still be polled.
            seedReservable(poiId, "101")
            seedReservable(poiId, "102")
            // A watch scoped to a single reservable, not the whole POI.
            val watchId = seedReservableWatch(watchedSite, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)

            executorFor(provider).handle(poller)

            // One call covering the WHOLE catalog (3 sites), though only 1 is watched.
            assertEquals(1, provider.calls)
            val runs = AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10)
            val fetchCalls = AvailabilityFetchCallRepo(ctx).listForRun(runs[0].id)
            assertEquals(1, fetchCalls.size)
            assertEquals(3, fetchCalls[0].reservableCount, "fetches the parent catalog, not just the watched site")
        }

    // --- Alerts (PR6): cube edge -> Slack notify ---

    private fun watchStatus(watchId: Long): String =
        ctx.fetchOne("SELECT status FROM availability_watch WHERE id = ?", watchId)!!.get("status", String::class.java)

    /** Records one interval row directly (no poll), so initial-notify tests
     *  can set the current window state without firing the transition path. */
    private fun seedCell(
        reservableId: Long,
        date: LocalDate,
        status: AvailabilityStatus,
    ) {
        AvailabilityRepo(ctx).recordObservations(
            runId = null,
            listOf(AvailabilityRepo.Observation(reservableId, date, status, now().toInstant())),
        )
    }

    @Test
    fun `reserved to available transition alerts the covering slack_notify watch on the default channel`() =
        runBlocking {
            // Observe on a watched date: the poll window is today-anchored and
            // vendor-wide, so pin the fake's observation into the watch window.
            val provider = CountingRecgovProvider(status = AvailabilityStatus.RESERVED, observationDate = farStart)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(2).toString(),
                    triggerKinds = listOf("slack_notify"),
                )
            val poller = linkWatch(provider, watchId)
            val notifier = RecordingSlackNotifications()
            val executor = executorFor(provider, alertDispatcher = dispatcherWith(provider, notifier))

            // First poll: RESERVED — a change (first observation) but not bookable, so no alert.
            executor.handle(poller)
            assertTrue(notifier.posts.isEmpty())

            // Second poll: the site opens. reserved -> available edge fires exactly once.
            provider.status = AvailabilityStatus.AVAILABLE
            executor.handle(poller)

            assertEquals(1, notifier.posts.size)
            val post = notifier.posts.single()
            assertEquals("#camping", post.channel)
            assertTrue(post.allText.contains("Campsites Available"), post.allText)
            assertTrue(post.allText.contains("Site 100"), post.allText)
            // The booking link comes from the provider (adapter), not the dispatcher.
            assertTrue(post.allText.contains("https://example.test/book/100"), post.allText)
            // The openings alert is Block Kit with no Grafana links — those stay on
            // the informational status messages.
            assertTrue(!post.allText.contains("/d/"), post.allText)
        }

    @Test
    fun `channel override in trigger_config wins over the default channel`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE, observationDate = farStart)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(2).toString(),
                    triggerKinds = listOf("slack_notify"),
                    triggerConfig = """{"channel":"#custom"}""",
                )
            val poller = linkWatch(provider, watchId)
            val notifier = RecordingSlackNotifications()

            // First observation of an already-open site alerts (chosen behavior).
            executorFor(provider, alertDispatcher = dispatcherWith(provider, notifier)).handle(poller)

            assertEquals(1, notifier.posts.size)
            assertEquals("#custom", notifier.posts.single().channel)
        }

    @Test
    fun `stop_when_triggered marks the watch done after a successful post`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE, observationDate = farStart)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(2).toString(),
                    triggerKinds = listOf("slack_notify"),
                    stopWhenTriggered = true,
                )
            val poller = linkWatch(provider, watchId)

            executorFor(provider, alertDispatcher = dispatcherWith(provider, RecordingSlackNotifications(result = true))).handle(poller)

            assertEquals("done", watchStatus(watchId))
        }

    @Test
    fun `a failed post leaves a stop_when_triggered watch active`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE, observationDate = farStart)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(2).toString(),
                    triggerKinds = listOf("slack_notify"),
                    stopWhenTriggered = true,
                )
            val poller = linkWatch(provider, watchId)

            executorFor(provider, alertDispatcher = dispatcherWith(provider, RecordingSlackNotifications(result = false))).handle(poller)

            assertEquals("active", watchStatus(watchId))
        }

    // --- Initial notify: dispatchInitial(watch) on create/update ---

    private fun findWatch(watchId: Long): AvailabilityWatchRepo.Watch = AvailabilityWatchRepo(ctx).findById(watchId)!!

    @Test
    fun `initial notify on an already-available window posts the openings alert`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), triggerKinds = listOf("slack_notify"))
            // Site is already bookable when the watch is created — no future edge exists.
            seedCell(reservableId, farStart, AvailabilityStatus.AVAILABLE)
            val notifier = RecordingSlackNotifications()

            dispatcherWith(provider, notifier).dispatchInitial(findWatch(watchId))

            assertEquals(1, notifier.posts.size)
            val post = notifier.posts.single()
            assertEquals("#camping", post.channel)
            assertTrue(post.allText.contains("Campsites Available"), post.allText)
            assertTrue(post.allText.contains("Site 100"), post.allText)
            assertTrue(post.allText.contains("https://example.test/book/100"), post.allText)
        }

    @Test
    fun `initial notify on a cold cube posts the unknown-state message`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), triggerKinds = listOf("slack_notify"))
            // No cube data: the imminent poll's first observation will be the real edge.
            val notifier = RecordingSlackNotifications()

            dispatcherWith(provider, notifier).dispatchInitial(findWatch(watchId))

            assertEquals(1, notifier.posts.size)
            val text = notifier.posts.single().allText
            assertTrue(text.contains("Watching"), text)
            assertTrue(text.contains("not checked yet"), text)
        }

    @Test
    fun `initial notify on a fully-reserved window posts the nothing-open message`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.RESERVED)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), triggerKinds = listOf("slack_notify"))
            seedCell(reservableId, farStart, AvailabilityStatus.RESERVED)
            val notifier = RecordingSlackNotifications()

            dispatcherWith(provider, notifier).dispatchInitial(findWatch(watchId))

            assertEquals(1, notifier.posts.size)
            assertTrue(
                notifier.posts
                    .single()
                    .allText
                    .contains("nothing available right now"),
                notifier.posts.single().allText,
            )
        }

    @Test
    fun `initial notify honors stop_when_triggered when the window is already available`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(2).toString(),
                    triggerKinds = listOf("slack_notify"),
                    stopWhenTriggered = true,
                )
            seedCell(reservableId, farStart, AvailabilityStatus.AVAILABLE)

            dispatcherWith(provider, RecordingSlackNotifications(result = true)).dispatchInitial(findWatch(watchId))

            assertEquals("done", watchStatus(watchId))
        }

    @Test
    fun `initial notify leaves a stop_when_triggered watch active when nothing is bookable yet`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(2).toString(),
                    triggerKinds = listOf("slack_notify"),
                    stopWhenTriggered = true,
                )
            val notifier = RecordingSlackNotifications()

            dispatcherWith(provider, notifier).dispatchInitial(findWatch(watchId))

            // The unknown-state message is informational, not a trigger.
            assertEquals(1, notifier.posts.size)
            assertEquals("active", watchStatus(watchId))
        }

    @Test
    fun `initial notify on a paused watch posts a lifecycle message and no openings`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), triggerKinds = listOf("slack_notify"))
            // Even with the site open, a paused watch reports status — not openings.
            seedCell(reservableId, farStart, AvailabilityStatus.AVAILABLE)
            ctx.execute("UPDATE availability_watch SET status = 'paused' WHERE id = ?", watchId)
            val notifier = RecordingSlackNotifications()

            dispatcherWith(provider, notifier).dispatchInitial(findWatch(watchId))

            assertEquals(1, notifier.posts.size)
            val text = notifier.posts.single().allText
            assertTrue(text.contains("Paused"), text)
            assertTrue(!text.contains("Campsites Available"), text)
        }

    @Test
    fun `initial notify skips a watch without the slack_notify kind`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            // Default trigger kind is "atc" — inert for Slack.
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            seedCell(reservableId, farStart, AvailabilityStatus.AVAILABLE)
            val notifier = RecordingSlackNotifications()

            dispatcherWith(provider, notifier).dispatchInitial(findWatch(watchId))

            assertTrue(notifier.posts.isEmpty())
        }

    @Test
    fun `a watch without the slack_notify kind is never posted`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            // default trigger_kinds = ['atc'] — no slack_notify, so it stays inert.
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)
            val notifier = RecordingSlackNotifications()

            executorFor(provider, alertDispatcher = dispatcherWith(provider, notifier)).handle(poller)

            assertTrue(notifier.posts.isEmpty())
        }

    @Test
    fun `a transition on a shorter watch's exclusive end date alerts only the longer watch`() =
        runBlocking {
            // Two watches coalesced onto one poller. The window is half-open
            // [start, end): the transition falls on A's end date (a checkout day,
            // not a watched night) but inside B's window. It must alert B only,
            // and must NOT mark A done despite A being stop_when_triggered.
            val transitionDate = farStart.plusDays(2)
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE, observationDate = transitionDate)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchA =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    transitionDate.toString(), // end == transition date -> excluded (half-open)
                    triggerKinds = listOf("slack_notify"),
                    triggerConfig = """{"channel":"#a"}""",
                    stopWhenTriggered = true,
                )
            val watchB =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(3).toString(), // covers the transition date
                    triggerKinds = listOf("slack_notify"),
                    triggerConfig = """{"channel":"#b"}""",
                )
            val poller = linkWatch(provider, watchA)
            AvailabilityWatchRepo(ctx).findById(watchB)!!.let { w ->
                membershipFor(provider).sync(w, AvailabilityPollerRepo(ctx), tighterCadencePull = now())
            }

            val notifier = RecordingSlackNotifications()
            executorFor(provider, alertDispatcher = dispatcherWith(provider, notifier)).handle(poller)

            assertEquals(1, notifier.posts.size)
            assertEquals("#b", notifier.posts.single().channel, "only the longer watch's window covers the transition")
            assertEquals("active", watchStatus(watchA), "the shorter watch never fired, so it must not be marked done")
        }

    @Test
    fun `openings alert carries no grafana dashboard links`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE, observationDate = farStart)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId =
                seedWatch(
                    poiId,
                    farStart.toString(),
                    farStart.plusDays(2).toString(),
                    triggerKinds = listOf("slack_notify"),
                )
            val poller = linkWatch(provider, watchId)
            val notifier = RecordingSlackNotifications()

            // The rich openings alert has a Reserve link, not Grafana links —
            // those live only on the informational status messages.
            executorFor(provider, alertDispatcher = dispatcherWith(provider, notifier)).handle(poller)

            assertEquals(1, notifier.posts.size)
            assertTrue(
                !notifier.posts
                    .single()
                    .allText
                    .contains("/d/"),
                "openings alert has no dashboard links",
            )
        }

    // --- Cadence fall-through (PR4) ---
    //
    // A watch's `cadenceSec` is a NULLABLE desired override (V34): NULL means
    // "no watch-level preference," which reaches the middle "poi override" rung
    // of the spec's `watch.cadence_sec ?? poi.cadence_override_sec ??
    // GLOBAL_DEFAULT_SEC` fall-through. The pure resolver is unit-tested for
    // every rung; the integration tests below prove the reachable paths (a NULL
    // watch cadence falls through to the POI override, an explicit watch cadence
    // wins) end-to-end.

    private fun watchWithCadence(cadenceSec: Int?): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo.Watch(
            id = 0,
            targets = emptyList(),
            reservableFilters = kotlinx.serialization.json.JsonObject(emptyMap()),
            startDate = farStart,
            endDate = farStart.plusDays(2),
            cadenceSec = cadenceSec,
            triggerKinds = emptyList(),
            triggerConfig = kotlinx.serialization.json.JsonObject(emptyMap()),
            stopWhenTriggered = false,
            status = ca.floo.roadtrip.service.availability.WatchStatus.ACTIVE,
            createdAt = now(),
            updatedAt = now(),
        )

    @Test
    fun `resolver falls through to poi override when a watch has no explicit cadence`() {
        // NULL watch cadence -> poi override (30), not GLOBAL_DEFAULT_SEC.
        assertEquals(30, resolveCadenceSec(listOf(watchWithCadence(null)), poiCadenceOverrideSec = 30))
    }

    @Test
    fun `resolver lets a tighter watch cadence win over a looser poi override`() {
        assertEquals(10, resolveCadenceSec(listOf(watchWithCadence(10)), poiCadenceOverrideSec = 30))
    }

    @Test
    fun `resolver falls through to GLOBAL_DEFAULT_SEC with no watch cadence and no poi override`() {
        assertEquals(300, resolveCadenceSec(listOf(watchWithCadence(null)), poiCadenceOverrideSec = null))
    }

    @Test
    fun `resolver takes the min across watches after each resolves its own fall-through`() {
        // watch A leans on the poi override (30); watch B has explicit 15 -> min = 15.
        val resolved = resolveCadenceSec(listOf(watchWithCadence(null), watchWithCadence(15)), poiCadenceOverrideSec = 30)
        assertEquals(15, resolved)
    }

    @Test
    fun `poi cadence override is read from the poller's representative poi and plumbs into next_run_at`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447", cadenceOverrideSec = 45)
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 30)
            val poller = linkWatch(provider, watchId)

            assertEquals(45, AvailabilityPollerRepo(ctx).cadenceOverrideForPoller(poller.id))

            val before = OffsetDateTime.now()
            val result = executorFor(provider).handle(poller)

            // watch cadence (30) is specified, so it wins over the override (45).
            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertEquals(30L, delaySec)
        }

    @Test
    fun `null watch cadence falls through to the poi override end-to-end`() =
        runBlocking {
            // Proves the middle rung is REACHABLE: a persisted watch with a NULL
            // cadence (V34) leans on pois.cadence_override_sec, not the global
            // default. Before V34 the column was NOT NULL and this rung was dead.
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447", cadenceOverrideSec = 45)
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = null)
            val poller = linkWatch(provider, watchId)

            val before = OffsetDateTime.now()
            val result = executorFor(provider).handle(poller)

            // NULL watch cadence -> poi override (45), not GLOBAL_DEFAULT_SEC (300).
            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertEquals(45L, delaySec)
        }

    @Test
    fun `explicit watch cadence wins over the poi override end-to-end`() =
        runBlocking {
            // The companion to the fall-through test: when the watch DOES express a
            // cadence, it takes precedence over the POI override.
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447", cadenceOverrideSec = 45)
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 20)
            val poller = linkWatch(provider, watchId)

            val before = OffsetDateTime.now()
            val result = executorFor(provider).handle(poller)

            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertEquals(20L, delaySec)
        }

    @Test
    fun `cadence is the min over live watches`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchSlow = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 300)
            val watchFast = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 30)
            val poller = linkWatch(provider, watchSlow)
            AvailabilityWatchRepo(ctx).findById(watchFast)!!.let { w ->
                membershipFor(provider).sync(w, AvailabilityPollerRepo(ctx), tighterCadencePull = now())
            }

            val before = OffsetDateTime.now()
            val result = executorFor(provider).handle(poller)

            // min(300, 30) = 30s on success.
            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertEquals(30L, delaySec)
        }

    @Test
    fun `governor starvation skips the fetch and reschedules soon without failing the run`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 60)
            val poller = linkWatch(provider, watchId)
            val denyingLimiter = RecordingLimiter(grant = false)

            val before = OffsetDateTime.now()
            val result = executorFor(provider, limiter = denyingLimiter).handle(poller)

            // No upstream call was made.
            assertEquals(0, provider.calls)
            // Exactly one acquire request, for this poller's provider.
            assertEquals(1, denyingLimiter.requests.size)
            assertEquals("recgov", denyingLimiter.requests.single().first)
            // Rescheduled soon (governor-starved), not on the 60s success cadence.
            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertTrue(delaySec in 1..30, "expected a short starved retry, got ${delaySec}s")
            // No run row — a starved tick is a non-event, not a failure.
            assertEquals(0, AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10).size)
        }

    @Test
    fun `governor success proceeds to fetch and consumes one token per bucket`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            listOf("100", "101").forEach { seedReservable(poiId, it) }
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 60)
            val poller = linkWatch(provider, watchId)
            val grantingLimiter = RecordingLimiter(grant = true)

            executorFor(provider, limiter = grantingLimiter).handle(poller)

            // Single-provider, single-parentRef, single-dateContext poller -> K = 1 bucket.
            assertEquals(1, provider.calls)
            assertEquals(1, grantingLimiter.requests.size)
            assertEquals("recgov" to 1L, grantingLimiter.requests.single())
            // A granted tick fetches and writes a completed run row as normal.
            val runs = AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10)
            assertEquals(1, runs.size)
            assertEquals("completed", runs[0].status)
        }

    @Test
    fun `null-window groups acquire zero tokens and are never starved`() =
        runBlocking {
            // A group's polling window is null only when the vendor exposes a
            // zero poll window (maxPollWindowDays = 0) — the window is
            // vendor-derived now, not watch-derived, so a live watch can never
            // produce a null window on its own. A null-window group is a
            // non-fetch: the governor must charge ZERO tokens, since a token
            // spent on a non-fetch could starve the bucket. Even a denying
            // limiter must not block this tick.
            val provider = CountingRecgovProvider(maxPollWindowDays = 0)
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 60)
            val poller = linkWatch(provider, watchId)
            val denyingLimiter = RecordingLimiter(grant = false)

            val result = executorFor(provider, limiter = denyingLimiter).handle(poller)

            // Zero groups have a real window -> zero tokens requested, and the
            // denying limiter never got the chance to starve the tick.
            assertEquals(0, denyingLimiter.requests.size)
            // No upstream call was made (all groups skipped for a null window).
            assertEquals(0, provider.calls)
            // Not blocked/starved: the tick proceeded and wrote a run row (no-op
            // completed run), so the poller keeps its normal cadence rather than
            // being wedged behind a starved retry.
            val runs = AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10)
            assertEquals(1, runs.size)
            assertEquals("completed", runs[0].status)
        }

    @Test
    fun `a poller with no live watches skips the fetch without mutating`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            // A watch whose window is entirely in the past (not live).
            val watchId = seedWatch(poiId, "2020-01-01", "2020-01-05")
            // Link it directly (liveness is ACTIVE + end>=today, and this end_date
            // is past, so link manually to exercise the empty-live-watch tick).
            val pollers = AvailabilityPollerRepo(ctx)
            val pollerId =
                pollers.upsertActive(provider = "recgov", parentRef = "232447", poiId = poiId, pullNextRunAt = now())
            pollers.linkWatch(watchId, pollerId)
            val poller = pollers.findById(pollerId)!!

            executorFor(provider).handle(poller)

            // No upstream call, no run row.
            assertEquals(0, provider.calls)
            assertEquals(0, AvailabilityRunRepo(ctx).listForPoller(pollerId, limit = 10).size)
            // The fetch tick does NOT tear down — teardown is the WatchReaper's job.
            // The poller stays active, its link stays, the watch stays active until
            // the reaper sweeps.
            assertEquals(true, pollers.findById(pollerId)!!.active)
            assertEquals(listOf(watchId), pollers.watchIdsForPoller(pollerId))
            val watchStatus =
                ctx
                    .fetchOne("SELECT status FROM availability_watch WHERE id = ?", watchId)!!
                    .get("status", String::class.java)
            assertEquals("active", watchStatus)
        }

    @Test
    fun `failure backs off using derived cadence and consecutive failures`() =
        runBlocking {
            val provider = RateLimitedProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 120)
            val poller = linkWatch(provider, watchId)
            val runsRepo = AvailabilityRunRepo(ctx)

            // Seed one prior failed run so this run's failure is the 2nd consecutive.
            val priorRunId = runsRepo.start(poller.id, now().minusMinutes(5))
            runsRepo.fail(priorRunId, error = "rate_limited", completedAt = now().minusMinutes(4), durationMs = 0)

            val before = OffsetDateTime.now()
            val result = executorFor(provider).handle(poller)

            val runs = runsRepo.listForPoller(poller.id, limit = 10)
            assertEquals("failed", runs[0].status)
            assertEquals("rate_limited", runs[0].error)

            // 2 consecutive failures -> 120 * 2^2 = 480s, above the flat 120s cadence,
            // and comfortably under BACKOFF_CEILING_SEC (3600s).
            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertTrue(delaySec in 400..3_600L)
        }

    @Test
    fun `first-sight observations each write a status-run row tagged with the poll run`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            listOf("100", "101").forEach { seedReservable(poiId, it) }
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)

            executorFor(provider).handle(poller)

            val runs = AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10)
            assertEquals("completed", runs[0].status)
            assertTrue(runs[0].snapshotCount > 0)

            val rows = AvailabilityRepo(ctx).listForRun(runs[0].id, limit = 100)
            // Provider returns one observation per reservable (2 sites) for the window
            // start; both are first-sight, so both are new status-runs on this run.
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.runId == runs[0].id })

            // MDC run_id propagated across the coroutine dispatch and cleared after.
            assertEquals(runs[0].id.toString(), provider.mdcRunIdDuringCall)
            assertNull(MDC.get("run_id"), "MDC should be cleared on this thread after handle() returns")
        }

    @Test
    fun `unchanged status across two runs bumps liveness in place and writes no new interval row`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE, observationDate = farStart)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)
            val repo = AvailabilityRepo(ctx)

            executorFor(provider).handle(poller)
            val rowsAfterFirst = repo.listForReservable(reservableId).size
            val currentAfterFirst = repo.readCurrent(listOf(reservableId), listOf(farStart)).single()

            Thread.sleep(5)
            executorFor(provider).handle(poller)
            val rowsAfterSecond = repo.listForReservable(reservableId).size
            val currentAfterSecond = repo.readCurrent(listOf(reservableId), listOf(farStart)).single()

            // No new status-run: the status did not change.
            assertEquals(rowsAfterFirst, rowsAfterSecond)
            // But the current row's liveness advanced in place, and the status held.
            assertTrue(currentAfterSecond.observedAt.isAfter(currentAfterFirst.observedAt))
            assertEquals(AvailabilityStatus.AVAILABLE, currentAfterSecond.status)
        }

    @Test
    fun `a status change writes exactly one new interval row (the transition)`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)
            val repo = AvailabilityRepo(ctx)

            executorFor(provider).handle(poller)
            val before = repo.listForReservable(reservableId).size

            provider.status = AvailabilityStatus.RESERVED
            executorFor(provider).handle(poller)
            val after = repo.listForReservable(reservableId).size

            assertEquals(before + 1, after)
        }

    @Test
    fun `run snapshot_count reflects transitions, not raw observation count`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            listOf("100", "101", "102").forEach { seedReservable(poiId, it) }
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)
            val runsRepo = AvailabilityRunRepo(ctx)

            // Run 1: 3 reservables, all first-sight -> 3 transitions.
            executorFor(provider).handle(poller)
            val run1 = runsRepo.listForPoller(poller.id, limit = 10).first()
            assertEquals(3, run1.snapshotCount)

            // Run 2: identical statuses -> 0 transitions.
            executorFor(provider).handle(poller)
            val run2 = runsRepo.listForPoller(poller.id, limit = 10).first()
            assertEquals(0, run2.snapshotCount)
        }
}

private const val GRAFANA_ROOT_URL = "http://grafana.test/dash"
private const val APP_ROOT_URL = "http://app.test"
