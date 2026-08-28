> **ARCHIVED — superseded 27 August 2026. Do not apply this as product guidance.**
>
> This is the original product thesis, kept as a record of a decision rather than as
> instructions. It argues the wedge is **EV charging permission at campsites** and
> proposes four per-vehicle ratings on every campsite.
>
> The Vision has since rejected that framing. Value 1, *"Serve the trip, not the
> vehicle,"* states plainly: *"Roadtrip started as EV-aware routing. Most road trippers
> do not drive electric, and EV-only caps the market before anything else is proven.
> Range and chargers survive as constraints a trip can carry. They stopped being the
> product."* M1a lists EV range under out-of-scope.
>
> **What survived, and where it now lives:** the trust model (§6) and the honesty rules
> it implies are now the Vision's values; the booking-window and connectivity constraints
> (§4) are in `product-context.md`; the cancellation-alert competitors (§5) are in the
> Market & rivals doc; the discovery/MCP argument (§10) became milestone M2.
>
> Read `../SKILL.md` and `product-context.md` for current guidance. This file is here so
> nobody re-derives the EV thesis from scratch and so the reasoning behind dropping it
> stays legible.

---

# Roadtrip — User Research Reference

**Purpose:** A portable, stable description of who Roadtrip is for and what problems it solves. Paste this into any new chat before asking for feature work, so every feature gets evaluated against the same user rather than a freshly invented one.

**Authored by:** Matt Lew
**Created:** 5 August 2026
**Last updated:** 5 August 2026
**Status:** Living document. Update when new evidence changes a belief — not when a new feature idea needs justifying. Record the date and what changed when you do.

---

## 1. Primary user — "The EV road-tripper who sleeps in the car"

An EV owner (disproportionately Tesla, increasingly Rivian and others) who takes multi-day driving trips and car-camps for some or all nights — either in the vehicle itself with a mattress setup, or in a tent at a drive-up campsite.

**Defining trait:** They are solving two problems simultaneously that every existing tool treats separately — *where does the car get energy* and *where do I sleep*. No product on the market answers both in one place.

**What they are not:** Not RV owners (different power needs, different campgrounds, different tooling). Not backcountry backpackers (no vehicle at the site). Not day-trippers (no overnight constraint).

### Trip shapes (bimodal — design for both)
| Shape | Frequency | Planning behaviour |
|---|---|---|
| Weekend / short trip, 2–4 nights | Most common | Light planning, often last-minute, high tolerance for improvisation |
| Multi-week national park loop, 2–3 weeks | Regular among enthusiasts | Heavy planning, booking windows matter enormously |
| Multi-month epic (e.g. 48 states in 132 days) | Rare | Extreme planning, but also extreme adaptability |

### Motivation
Cost is a genuine driver, not a rationalisation. One documented trip: 53 nights on the road, ~$750 total lodging (~$14/night), with 25 of those nights car-camping costing ~$104 total. Fuel savings ran roughly $2,000 versus a 25mpg gas car. Supercharging runs ~6–7¢/mile against ~13¢/mile for gas. **The EV makes the road trip cheap; car camping makes it cheaper. These users are optimising, and they notice.**

---

## 2. Core problem (the one thing to keep returning to)

> **"Electric hookup available" does not mean "you may charge your car here."**

Mainstream camping tools (Recreation.gov, The Dyrt, Campendium, Hipcamp, Campflare) all filter by hookup amperage. **None** filter by whether EV charging is actually permitted at that hookup. Many campgrounds with 30A/50A pedestals explicitly ban vehicle charging — with signage, and with real fines ($30–$500 documented at 13+ parks).

The user discovers this **on arrival, at night, with a low battery, often with no cell service.** That is the failure moment the entire product exists to prevent.

### Three shapes the problem takes (use these as test cases for any feature)
1. **Yosemite — the false negative.** No at-site charging, but free Level 2 chargers sit within walking distance of several campgrounds. A flat "no charging" flag drives users away from a park that works fine.
2. **Zion — the unknown.** Hookups exist; whether a car may use them is unpublished. Neither "yes" nor "no" is honest. The system needs a third answer.
3. **Grand Canyon — the split entity.** South Rim is well served; North Rim has nothing at the lodge, campground, or connecting roads, and is 200+ road miles from the South Rim. **Any data keyed to park name instead of individual campground is wrong.** Same applies to Yellowstone, Great Smoky Mountains, Denali.

---

## 3. Jobs to be done

Phrased as the user would phrase them, in rough priority order:

1. "Find me somewhere to sleep tonight that won't leave my car stranded."
2. "Tell me before I book, not after I arrive."
3. "If I can't charge at the site, tell me what's nearby and whether my car can use it."
4. "Tell me if I have enough range to just skip charging tonight." *(Often the real answer — the site doesn't need power, the user needs to know they're fine.)*
5. "Let me know when a booked-out campground opens up."
6. "Don't make me plan for a failure I can't foresee." *(Occupied or broken chargers.)*
7. "I only want to drive N hours today." *(A time budget, distinct from an energy budget — a site can pass one and fail the other.)*

---

## 4. Hard constraints on any solution

- **Charger reliability is bad enough to be a design assumption, not an edge case.** ~72.5% of surveyed public stations were operational in one study. Any single-charger recommendation is a coin flip. **Every recommendation needs a pre-loaded second option.**
- **Compatibility is per-vehicle, not per-charger.** NACS / J1772 / CCS plus adapter ownership determines whether a nearby charger counts at all. The same campsite legitimately rates differently for two users.
- **Connectivity fails exactly where the stakes are highest.** Remote parks have poor or no cell service. Anything critical must survive being loaded in advance.
- **Booking windows are brutal and non-uniform.** Most federal sites release exactly 6 months ahead on a daily rolling 10am ET window; popular sites sell out in minutes. Yosemite uses monthly block release (5 months out, the 15th, 7am PT). Some state systems open 11 months out. Planning is not leisurely.
- **Charging-permission data has no public source.** It must be gathered manually or crowdsourced, and it goes stale. Freshness must be visible, never implied.

---

## 5. What the user already uses (and why it fails them)

Two ecosystems that do not talk to each other:

| Ecosystem | Tools | Blind spot |
|---|---|---|
| EV routing | ABRP (~$50/yr premium), PlugShare, Tesla nav | Knows nothing about where you sleep |
| Campground discovery | Recreation.gov, The Dyrt PRO (~$36–60/yr), Campendium, Roadtrippers/Roadpass (~$50–60/yr), iOverlander, AllStays | Knows nothing about whether your car can charge |
| Cancellation alerts | Campflare (free), Campnab, Schnerp (paid), PermitSnag (permits/lotteries) | No EV awareness at all |
| EV-specific | Camp and Charge — the only tool addressing the core problem | ~591 private RV parks only; no federal/state/BLM, no booking, no routing, no corridor search |

**Implication:** the gap is not a missing feature in an existing product. It's the join between two mature product categories that nobody has made.

---

## 6. Trust model (the thing most likely to kill the product)

A confidently wrong recommendation is worse than no recommendation. The user's downside is a bad night in a remote place, and they will not return after one.

**Design rule:** separate *what we know* from *what we recommend*.
- A rating describes **the state of the infrastructure**, with a separate, explicit statement of **confidence and age**.
- "Verified June 2026 via park website" and "reported by 1 visitor, March 2024" must not look the same.
- Never gatekeep a booking. Inform, then let the user decide. A site with no charging is still a fine place to sleep if the range supports it.
- The escape hatch matters more than the recommendation: a fast, low-effort day-of reroute view is what converts a broken plan into a minor annoyance instead of a crisis.

---

## 7. Novice vs. expert — two entry points, one dataset

- **Expert:** wants filters, corridor search, and to get out of the way. Already has opinions about buffer percentages.
- **Newly-bought-an-EV novice:** finds the whole task overwhelming — multiple apps, unclear costs, unclear logistics, no idea what a safe reserve is. **The answer is not a better how-to guide; it is safe defaults applied automatically so the guide isn't needed.** Existing advice (keep ~20% buffer, carry a portable charger, assume some stations are down) should be enforced by the tool rather than explained in an article.

---

## 8. Anti-goals

Things that look adjacent but are not this product:
- RV-specific tooling (tank levels, rig length, RV-safe routing)
- Being a booking platform — hand off to Recreation.gov and other authorities
- Social/community features for their own sake — community input exists to keep the charging data honest, not to build a network
- Trip-blogging, gear commerce, generic travel inspiration
- Gatekeeping bookings based on our own rating

---

## 9. What these users are asking an AI

Increasingly the journey does not start at a search box or an app — it starts at a chat window. These are the questions that precede or replace a visit, grouped by trip stage. *Inferred from the pain points above rather than measured; treat as hypotheses worth validating, and update with real query data when you have it.*

### Stage 1 — Dreaming and scoping (weeks or months out)
- "Plan me a week-long road trip through Utah in a Model Y"
- "Which national parks are realistic in an EV without a lot of anxiety?"
- "Is it cheaper to car camp than stay in motels on a two-week trip?"
- "What do I need to sleep in my Model Y — does Camp Mode drain the battery overnight?"
- "How far apart are chargers on the drive from Denver to Moab?"

*What the product needs to be in this answer:* corridor-level feasibility, not individual listings. "This route works / this route has a 200-mile gap."

### Stage 2 — Choosing and booking (weeks out)
- "Can I charge my car at [campground]?" ← **the core question, and nothing on the internet answers it reliably**
- "Which campgrounds near Zion let EVs use the electric hookups?"
- "When do reservations open for Many Glacier / Watchman / Yosemite Valley?"
- "This campground is fully booked — is there any way to get a spot?"
- "Is a 50-amp hookup enough to charge a Tesla overnight?"
- "Do I need an adapter to use the chargers near [park]?"

*What the product needs to be in this answer:* the authoritative per-campground charging answer, with its date and source. This is the citation you own.

### Stage 3 — In-trip logistics (days or hours out)
- "I'm at 40% battery near Kanab — where can I camp tonight and charge tomorrow?"
- "Is there anywhere to charge at the Grand Canyon North Rim?" ← *answer is effectively no, and getting this wrong strands someone*
- "Cheapest place to charge between here and [destination]"
- "How long will it take to charge at a Level 2 while I sleep?"
- "Can I run out of battery from using Camp Mode all night?"

*What the product needs to be in this answer:* live state and range maths, personalised. This is where a generic model answer is weakest and yours is strongest.

### Stage 4 — Recovery, when the plan breaks
- "The charger at [place] is broken — what's the nearest alternative?"
- "The campground is full, what's nearby that I can charge at?"
- "I'm below 20% and the next charger is 90 miles away, what do I do?"
- "Is it legal to sleep in my car at a Walmart / trailhead / rest stop here?"

*What the product needs to be in this answer:* the pre-loaded second option, instantly. Recovery is the highest-stakes moment and the least served by every existing tool.

### Reading across the stages
- **Stage 2's core question is unanswerable from public data.** That is the wedge. Own it and every AI planning a trip has to reach for you.
- **Stages 3 and 4 need live state**, which no model has and no wiki keeps current — this is the durable reason to open the app rather than just ask.
- Several questions are **vehicle-specific** ("do I need an adapter," "will Camp Mode drain it") — a generic answer is a guess; a per-vehicle answer is only possible with a vehicle profile.
- The recovery questions are asked **under stress, often on bad connectivity.** Anything that only works with a full signal and a calm user doesn't count.

---

## 10. Discovery and distribution

Discovery increasingly happens through an AI, not a search engine. The strategic response is not to compete with the model for the answer — it is to be the source the model has to cite.

**The position:** charging-permission data is not published anywhere, so it isn't in any model's training data and no crawler finds it. Building it creates a source that doesn't otherwise exist. That is a stronger position than being another site with the same scraped campground listings.

**What follows from that:**
- **Be machine-readable on purpose** — structured data on every campsite and charger page, a clean public read endpoint, an `llms.txt`. Most competitors obstruct this because their model depends on trapping the session; there's no need to copy them.
- **Ship an MCP server.** Let someone connect Roadtrip to their assistant and ask the Stage 1–3 questions directly against real data. Small build on top of the API that has to exist anyway; the difference between being a destination and being infrastructure.
- **Let answers leave the product.** Shareable, re-rating links are portable answers that survive being pasted into a chat or a group thread. Making people return to the app to see an answer is the same trap-the-user pattern worth avoiding — and it makes the product uncitable.

**Why anyone still opens the app after the AI answers:**
| Reason | Why a model or a wiki can't do it |
|---|---|
| Live state | Charger working *now*, cancellation that appeared *this morning* |
| Action | Booking handoff, watches, the 6am alert when a window opens |
| Personalisation | Your plug, your adapter, your current range |
| The correction loop | Check-ins from people who arrived last night keep the data worth citing next month |

**Positioning line to hold to:** *the app plans with you, and answers wherever you ask.*

**Guardrail:** this section is about being useful in more places, not about capturing attention. If a proposal here starts optimising for session length or return visits at the cost of answering the question, it has drifted into the anti-goals in §8.

---

## 11. How to use this document

When bringing a feature idea into a new chat, paste this doc and then ask the feature question. Evaluate any proposal against:

1. **Which job to be done (§3) does this serve?** If none, it's an anti-goal.
2. **Does it survive all three test cases (§2)?** Yosemite, Zion, Grand Canyon North Rim.
3. **Does it respect the hard constraints (§4)?** Especially: is there a second option when the first fails, and is it per-vehicle?
4. **Does it claim more certainty than the data supports (§6)?**
5. **Does it work for the novice without configuration, and for the expert without hand-holding (§7)?**

If a feature can't be defended on those five, it doesn't go on the roadmap regardless of how interesting it is to build.
