# Adding an availability provider

Use this when a new upstream can answer campsite booking or
availability questions. If the upstream only adds map pins and has no booking
surface, use [adding-a-data-source.md](adding-a-data-source.md)
instead.

This is a how-to. The stable architecture contract lives in
[backend-architecture.md](backend-architecture.md), and the provider abstraction
is explained in [reservation-providers.md](reservation-providers.md).

## Target Shape

Adding a provider should produce this shape:

```
clients/<vendor>/*Client.kt
service/availability/provider/adapters/<vendor>/*
service/api/<Vendor>PoiCtaProvider.kt          # only if POI drawer CTAs apply
service/etl/vendors/<vendor>/*                 # only if importing catalog data
models/<area>/*                                # shared DTOs/domain values only
backend/src/main/resources/poi-registry.yaml  # source + dataset wiring
```

Routes should not change for a new provider. Availability services should
continue to call the provider port, not vendor classes.

## Step 1 - Define The Provider Reference

Decide what stable identifiers the upstream needs to answer availability and
build booking links. That shape belongs in `ProviderRef` and must be parsed by
the single provider-ref parser.

Checklist:

- Add or extend the provider reference value under `models/domain`.
- Update `ProviderRefParser` to parse the JSON shape written into `pois.provider_ref`.
- Add parser tests for valid, malformed, and legacy payloads.
- Keep upstream-specific JSON behind the parsed reference. Routes should never
  parse `provider_ref` directly.

The reference should contain durable upstream identifiers, not labels, URLs
that can be recomputed, or values that only affect presentation.

## Step 2 - Add The Outbound Client

Create the raw HTTP client under `clients/<vendor>/`.

The client owns:

- HTTP transport setup, headers, timeouts, and request URLs.
- Upstream request and response parsing.
- Upstream-specific exceptions.
- Closing transport resources when needed.

The client must not own:

- Database writes.
- Ktor route responses.
- Availability polling cadence.
- User-facing DTOs.
- Provider-neutral status decisions that belong at the adapter boundary.

Add focused client tests with local/fake HTTP responses. Cover success, parser
edge cases, non-2xx responses, and rate-limit/block responses if the upstream
has them.

## Step 3 - Add The Availability Adapter

Create `service/availability/provider/adapters/<vendor>/`.

The adapter implements the availability-provider port and converts raw upstream
responses into provider-neutral availability observations. Provider-specific
richness stays inside the adapter unless there is an explicit extension point.

Checklist:

- Implement provider identity and capabilities.
- Implement provider-level availability if the upstream supports it.
- Implement catalog availability when linked local campsites should narrow
  the upstream response.
- Map upstream errors into typed provider errors.
- Add adapter tests for availability status mapping, missing upstream cells,
  catalog narrowing, wrong reference type, and error mapping.

If the provider cannot support an operation, use the explicit unsupported
capability path. Do not add stubs that throw because the implementation is
unfinished.

## Step 4 - Wire The Registry

Wire the adapter through the availability-provider registry/factory.

Checklist:

- Add the provider identity value if this is a new provider family.
- Add client lifecycle wiring to the provider client set.
- Add registry construction for the provider.
- Validate any per-tenant or per-source registry configuration at boot.
- Add registry tests that prove sources map to the correct adapter and that
  missing required config fails loudly.

Callers should continue to resolve providers through the registry. Do not
import the adapter directly from routes or availability services.

## Step 5 - Add Booking Links And POI CTAs

If the provider can produce user-facing booking links, keep URL construction
beside the adapter and expose drawer CTA behavior through a vendor-specific
`service/api/<Vendor>PoiCtaProvider.kt` file.

Checklist:

- Add a booking URL helper under `service/availability/provider/adapters/<vendor>/`.
- Add a booking display helper under the same vendor package if labels vary by
  provider or tenant.
- Add a vendor-specific POI CTA provider if campground-level drawer buttons
  should link to the booking site.
- Add or update CTA tests for precedence, labels, missing URL inputs, and dated
  links.

`PoiCta` should stay a coordinator: parse the row, ask providers in order, and
fall back to the generic info URL.

## Step 6 - Import Catalog Data When Needed

If watches or per-site availability need a local catalog, add ETL/import work.
Use [adding-a-data-source.md](adding-a-data-source.md) for the fetch and POI
pipeline details, then add campsite-specific pieces.

Checklist:

- Add source rows and ETL rows in `backend/src/main/resources/poi-registry.yaml`.
- Add fetcher scripts only when raw upstream capture is required.
- Add ETL code under `service/etl/vendors/<vendor>/`.
- Write `ProviderRef` payloads on parent POIs.
- Import campsites with stable vendor ids, types, names, loops, site types,
  and provider metadata.
- Add a campsite parent joiner (see `CampsiteParentJoiner`) when campsites need reparenting to the right campground.
- Add ETL and joiner tests with fixtures.

Catalog import is provider-specific; query and persistence code still belongs
in repos.

## Step 7 - Add Config And Operational Defaults

Values that may differ by environment, tenant, upstream host, or scale should
be config-driven.

Checklist:

- Add env vars for secrets, API roots, feature gates, or rate limits.
- Document new env vars in `.env.example`.
- Add per-source or per-tenant values to the YAML registry when they are part
  of data identity.
- Use named constants only for stable protocol defaults.
- Add rate-limit defaults and tests if the provider will be polled.

Do not bake tenant hosts, booking horizons, retry counts, or operational
cadences into call sites.

## Step 8 - Verify End To End

Run the narrow tests first, then broader checks.

Recommended commands:

```bash
./gradlew compileKotlin
./gradlew test --tests '*<Vendor>*'
./gradlew ktlintCheck
./gradlew test
```

For a provider with live-like HTTP parsing, add local HTTP fixture tests rather
than relying on the real upstream during CI.

## Review Checklist

Before opening the PR, check:

- Routes do not import vendor adapters or parse provider refs.
- Services call provider interfaces/registries, not vendor classes.
- Raw HTTP clients do not import repos or services.
- Adapter outputs are provider-neutral availability observations.
- Provider-specific CTA/link code lives in vendor files.
- DTOs and shared value types live under `models`.
- SQL and jOOQ stay in repos.
- Docs mention generic extension points, not a snapshot of current files.
