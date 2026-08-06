---
name: roadtrip-product
description: Product context for Roadtrip, an EV-aware campsite search app (repo wwchen/roadtrip, live at roadtrip.floo.ca). Contains the user research, the core user problem, hard constraints, anti-goals, and the test cases every feature must pass. Use this skill whenever the user is working on Roadtrip — designing or scoping a feature, writing a roadmap, estimating effort, drafting product docs or specs, making data-model decisions, writing UI copy, or asking "should we build X." Also use it for any camping, campground, EV-charging, road-trip, or trip-planning product question in this repo's context, even when Roadtrip isn't named explicitly. Consult it before proposing features, not after — proposals that skip it tend to reinvent the user.
---

# Roadtrip — product context

Roadtrip answers a question nothing else does: **can I charge my car at this campsite, and if not, what's nearby that I can use?**

Existing camping tools filter by hookup amperage. None filter by whether EV charging is actually *permitted* at that hookup — and many campgrounds explicitly ban it, with fines. The user finds out on arrival, at night, low on battery, often with no signal. Preventing that moment is the product.

## Before proposing anything, read the research

`references/user-research.md` is the source of truth for who this is for. Read it whenever the task involves deciding *what* to build or *why*. Skip it only for narrow implementation work where the product question is already settled.

It contains:
| Section | Use it for |
|---|---|
| §1 Primary user | Who this is and isn't for; trip shapes |
| §2 Core problem + three test cases | The wedge, and how to falsify a feature |
| §3 Jobs to be done | Whether a proposal serves a real job |
| §4 Hard constraints | Reliability, per-vehicle compatibility, connectivity, booking windows |
| §5 Competitive landscape | What exists and why it fails |
| §6 Trust model | Confidence, freshness, never gatekeeping a booking |
| §7 Novice vs expert | Two entry points, one dataset |
| §8 Anti-goals | What this product is not |
| §9 What users ask an AI | Query patterns by trip stage |
| §10 Discovery and distribution | Being citable rather than trapping users |

## The filter — apply to every feature proposal

1. **Which job to be done (§3) does this serve?** If none, it's an anti-goal.
2. **Does it survive all three test cases (§2)?**
   - *Yosemite* — no at-site charging, but free chargers a short walk away. Does a flat "no" wrongly reject it?
   - *Zion* — hookups exist, permission unpublished. Is there an honest third answer between yes and no?
   - *Grand Canyon North Rim* — nothing at the lodge, campground, or roads in, and 200+ road miles from the South Rim. Is anything keyed to park name instead of individual campground?
3. **Does it respect the hard constraints (§4)?** Especially: is there a second option when the first fails, and is the answer per-vehicle?
4. **Does it claim more certainty than the data supports (§6)?**
5. **Does it work for a novice without configuration, and an expert without hand-holding (§7)?**

A feature that fails these doesn't go on the roadmap regardless of how interesting it is to build. Say so plainly rather than finding a way to justify it.

## Recurring judgment calls

- **Never gatekeep a booking.** A site with no charging is a fine place to sleep if the range supports it. Inform, then let the user decide.
- **Per-campground, never per-park.** Grand Canyon is the proof; Yellowstone, the Smokies and Denali split the same way.
- **Chargers are a first-class entity**, not a field on a campground. Campground ↔ charger is a proximity join, so a charger can serve many campsites and be maintained once.
- **Ratings are computed per vehicle**, since compatibility depends on the user's plug and adapters, not just on a charger existing. The same site legitimately rates differently for two people.
- **Freshness is visible, always.** A four-month-old single report must not look like a verified fact.
- **The escape hatch matters more than the recommendation.** Roughly a quarter of public chargers aren't working on arrival; every recommendation carries a pre-loaded second option.

## The four ratings

Computed per vehicle, shown on every campsite:

| Rating | Meaning | Canonical example |
|---|---|---|
| Charge here | Permitted at the site itself | Rare; say it loudly when true |
| Charge nearby | Usable charger within walking distance | Yosemite valley campgrounds |
| Short drive | Usable charger 1–15 miles out | Zion → Springdale |
| Arrive charged | Nothing compatible in range; still bookable | Grand Canyon North Rim |

## Stack and current state

Live at roadtrip.floo.ca; repo `wwchen/roadtrip`.

- **Frontend:** vanilla JS, MapLibre GL
- **Backend:** Kotlin / Ktor, Postgres + PostGIS, Docker Compose
- **Data:** fetcher → ETL pipeline with dated raw captures; campgrounds (Recreation.gov), Superchargers, and free-charger feeds (NREL AFDC, Open Charge Map) already downloading — the free-charger feeds are fetched but **not yet wired into the app**
- **Already working:** map with layers, cancellation-watch poller with Slack notifications and Recreation.gov cart-add
- **Decided, not built:** OSRM for routing; Auth0 for auth (BFF pattern, Ktor holds the session)

When estimating effort, check whether a feature extends one of these existing systems or is genuinely new — the difference is usually large, and roughly a third of the planned roadmap is extension rather than new build.

## Writing docs for this project

Product docs should open with **what the product is and the problem it solves**, show it visually or through concrete examples, and put engineering detail last. Use the three park examples rather than abstract descriptions — they do the explaining faster than any framing paragraph.

If asked to use the **Lew Design System (LDS)** for a document, fetch `https://matthewlew.github.io/design-system/index.html`, use the Roadtrip theme in dark mode (dark-native, uses all five surface steps), and follow its guidance: no raw hex outside the theme block, semantic radii, 1px hairlines for structure, cards only for standalone rich content and lists for scannable content, one primary action per view.

---

**Authored by:** Matt Lew · **Created:** 5 August 2026 · **Last updated:** 5 August 2026
