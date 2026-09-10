# Booking port and alias links

**Date:** 2026-09-10
**Status:** Design for phase 4a of the 2026-09-09 audit (findings 6 and 7, issues #735, #736)
**Follows:** #744, #745, #746, #747

## What the code does today

**Alias links (finding 7).** `CampflareCampgroundsEtl` reads `connections.ridb_facility_id` (or a `/campgrounds/<id>` URL) and stamps `booking_provider = recgov`, `booking_provider_ref = <facility>` on a row whose `data_provider` is Campflare; `CampflareCampsitesEtl` does the same from `/campsites/<id>` URLs. Locally that is 5,569 of 10,963 Campflare campgrounds and 113,763 of 299,857 Campflare campsites. There is no alias table: a Campflare row is claimed by two availability providers because `CampflareAvailabilityProvider.supportsCampground` overrides the default to match on `data_provider_ref` while every other provider matches on `booking_provider`. Which one serves is the registration order in DI (rec.gov first), which one polls is the same, and disabling Campflare silently promotes rec.gov. `AvailabilityBookingTargetResolver.declaredTarget` reads the stored ref raw and says in its own doc that this is sound only while rec.gov is the only booking adapter. `CampgroundCta` has no Campflare branch, so a Campflare row that carries no rec.gov ref has `booking_system = null`.

**Booking port (finding 6).** `BookingAdapter` and `BookingAdapterRegistry` exist and are provider-neutral, but around them: `BookingActionService` returns `Held(RECGOV_CART_URL)` for any adapter's success and gates on `RecGovCredentialsConfigured`; `WatchCapabilityService.canFulfilAddToCart` and `WatchTriggerCapabilityValidator` gate on the same port ("atc requires rec.gov credentials in Settings"); `BookingActionCodes` carries `recgov_confirmation_disabled`, `recgov_dates_not_offered`, `recgov_no_reserve_button`; `AtcTriggerActionHandler` emits `roadtrip.recgov.atc` and `roadtrip.recgov.atc.duration` for every provider; the email ATC renderer says "held in your recreation.gov cart" for any vendor; credentials live in `user_settings.recgov_username` / `recgov_password_cipher` (V53), with no room for a second vendor.

## Design

### Aliases

A campground or campsite has one primary booking ref (`booking_provider`, `booking_provider_ref`) and zero or more aliases: other providers' identities for the same inventory.

```kotlin
@Serializable data class BookingAlias(val provider: BookingProvider, val ref: String)
// Campground.bookingAliases: List<BookingAlias>; Campsite.bookingAliases: List<BookingAlias>
// stored as JSONB `booking_aliases` on both tables (CatalogColumnJson, the typed-JSONB rule), GIN-indexed
```

- The Campflare ETLs emit `bookingProvider = CAMPFLARE`, `bookingProviderRef = <campflare id>` and `bookingAliases = listOfNotNull(recgovRef?.let { BookingAlias(RECGOV, it) })` for campgrounds and campsites. No ETL writes another vendor's identity as its primary.
- `AvailabilityProvider.supportsCampground` default becomes: enabled and (`primary.provider == id` or an alias has `provider == id`). `parentRefFor` returns the primary ref when it is this provider's, else the alias parsed as this provider's `BookingProviderRef`. `vendorSiteIdFor(campsite)` reads the campsite's primary when it is this provider's, else the alias, else the data ref as today. `CampflareAvailabilityProvider` loses its `dataProviderRef` overrides; the default covers it once the primary is Campflare.
- Provider preference stays the DI list order (rec.gov before Campflare), so a Campflare row with a rec.gov alias is still served and polled by rec.gov, with the same `parent_ref` key as today (the alias ref); live pollers are unaffected.
- `AvailabilityBookingTargetResolver` offers the primary and each alias to the adapter registry in that order; the first adapter that claims one wins. The doc caveat about "sound only while rec.gov is the only adapter" is retired with it.
- `CampgroundRepo.findPoiDetail` resolves the detail's `bookingRef` as the serving provider's ref (`BookingHorizonResolver.servingProvider(campground).parentRefFor(campground)`), so the CTA, `booking_system`, and `availability_supported` follow the provider that actually serves. `CampgroundCta` gains a Campflare branch (`bookingSystem = "Campflare"`, the existing `View on Campflare` info CTA) for rows nobody else claims.
- `RefLinkRepo`'s ref lookups (`campgroundIdsByBookingRef`, `parentCampgroundBookingRefsForCampsite`, …) match primary or alias (`booking_aliases @> '[{"provider":…,"ref":…}]'`).

**Migration `V59__booking_aliases.sql`**: adds `booking_aliases JSONB NOT NULL DEFAULT '[]'` with an array CHECK and a GIN index on both tables, then canonicalizes the one shape the code no longer accepts: rows with `data_provider = 'campflare'` and `booking_provider = 'recgov'` become primary Campflare (`booking_provider = 'campflare'`, `booking_provider_ref = data_provider_ref`) with `booking_aliases = [{"provider":"recgov","ref":<old ref>}]`. This derives from existing columns only (no source-payload backfill) and is idempotent; `make data-import` rewrites the same rows identically.

### Booking port

```kotlin
interface BookingAdapter {
    val id: BookingProvider
    fun can(action: BookingAction): Boolean
    fun targetFor(campsite: Campsite, parentRef: BookingProviderRef?): BookingTarget?
    fun canFulfil(user: UserId): Boolean                       // credentials stored for this provider
    suspend fun addToCart(target: BookingTarget, user: UserId, …): AddToCartOutcome  // Held(cartUrl, provider) from the adapter
    val failureCategories: Map<String, FailureCategory>         // the adapter's own codes
}
```

- `RecGovBookingAdapter` owns `RECGOV_CART_URL`, the `recgov_*` codes, and `canFulfil` (its `RecGovCredentialsConfigured`). `BookingActionService` loses the cart constant, the credential port, and the vendor codes: it asks the registry for the adapter that claims the target, checks `adapter.canFulfil(caller)`, and returns what the adapter returns. `AddToCartOutcome.Held` carries `cartUrl` and `provider`.
- `WatchCapabilityService.canFulfilAddToCart(requester, campsites)` asks the adapter that claims the scope; `WatchTriggerCapabilityValidator`'s detail names the provider ("atc requires <provider display> credentials in Settings").
- Metrics: `roadtrip.recgov.atc` and `roadtrip.recgov.atc.duration` become `roadtrip.booking.atc` and `roadtrip.booking.atc.duration` with a `provider` attribute; `grafana/dashboards/recgov-atc.json` reads the new names filtered on `provider = "recgov"`. The keepalive metric stays rec.gov's (it is rec.gov-only).
- Credentials: `user_booking_credentials(user_id BIGINT, provider TEXT, username TEXT, secret_cipher BYTEA, updated_at, PRIMARY KEY (user_id, provider))` in `V60__user_booking_credentials.sql`, copying the V53 columns for provider `recgov` (a shape move from existing columns, idempotent) and leaving the old columns in place until a later cleanup. `UserBookingCredentialsRepo` replaces the `recgov_*` members of `UserSettingsRepo`; `RecGovCredentialService` reads and writes provider `recgov` through it; `BookingCredentialsConfigured(provider, user)` replaces `RecGovCredentialsConfigured`. The settings routes and DTOs keep their `/api/settings/recgov` shape: they are rec.gov's own settings surface.
- Copy: the email ATC renderer and Slack footer take the provider's display name and cart URL from the outcome instead of literals; the frontend's cart copy ("Site held in your rec.gov cart", "Open rec.gov cart") renders the served `booking_system`. The rest of the frontend's vendor label tables are finding 9 (phase 4b).

## Risks

- **Two shapes for one campground during deploy**: none; V59 rewrites the Campflare rows before the new jar starts, and the old jar reads `booking_provider`/`booking_provider_ref` as before (a Campflare primary looks to the old rec.gov provider like a non-rec.gov row, so the old jar would serve those campgrounds through Campflare until upgraded). Rollback after V59 keeps working: the old Campflare override claims by data ref.
- **Poller keys**: rec.gov's `parentRefFor` on a Campflare row returns the alias ref, so `parent_ref` stays `<facility id>`; a test pins it against the V59 shape.
- **ATC on Campflare rows**: the target resolver offers the rec.gov alias to the rec.gov adapter, so holds keep working for the 113,763 aliased campsites; a test pins POI 8149's shape.
- **Credentials move**: V60 copies rows; the old columns stay; `RecGovCredentialServiceTest` runs against the new repo.
