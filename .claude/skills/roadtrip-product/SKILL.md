---
name: roadtrip-product
description: Product context for Roadtrip, a camping trip-planning surface over public inventory (repo wwchen/roadtrip, live at roadtrip.floo.ca). Routes to the Vision, milestone and research documents, and carries the personas, the trust model and the filter every feature proposal must pass. Use this skill whenever the user is working on Roadtrip — designing or scoping a feature, writing a roadmap, estimating effort, drafting product docs or specs, making data-model decisions, writing UI copy, or asking "should we build X." Also use it for any camping, campground, availability, alerting or trip-planning product question in this repo's context, even when Roadtrip isn't named explicitly. Consult it before proposing features, not after — proposals that skip it tend to reinvent the user.
---

# Roadtrip — product context

**This skill is a router, not the source of truth.** The doc set is the source of truth,
and it changes weekly. Read the relevant document before deciding anything; use the
summaries here to know which one that is.

Roadtrip is a planning surface for camping trips over public inventory. It answers *where
can I actually go, when, and will it be any good*, then hands off to the operator to book.

## Read the docs, in this order

| Question | Read |
|---|---|
| Why does this product exist, what are the values? | Vision — start at `#problem`, then `#values` |
| Who is this for, what stops them? | Personas — `#key`, then `#reach` |
| What are we building right now? | M1 — `#frames` |
| What has already shipped, what's in flight? | Build timeline |
| Who else does this, and how do they fail? | Market & rivals |
| What does the date window screen want to be? | Crazy 8s |

`references/doc-set.md` has every URL, every local snapshot path, the stable deep-link
anchors, and how to refresh a stale snapshot. **Snapshots go stale silently — check the
date before relying on one for planning work.**

`references/product-context.md` is the durable summary: personas, the feature filter, the
judgment calls, the hard constraints, the trust model. Read it whenever the task involves
deciding *what* to build or *why*. Skip it only for narrow implementation work where the
product question is already settled.

There is deliberately no archived copy of the earlier EV-charging thesis in this
directory. It was superseded on 27 August 2026, everything worth keeping was folded into
`product-context.md` and the doc set, and git holds the rest:
`git show b9d2b942:.claude/skills/roadtrip-product/references/user-research.md`.

## The filter — apply to every feature proposal

1. **Which persona, and which of their blocked moments?**
2. **Does it lead with dates or with a destination?** Leading with a destination serves
   the persona we have least of — two of three key personas hold dates fixed.
3. **Does it state absences, or only features?**
4. **Does it claim more certainty than the data supports?** Stamp freshness, closed is not
   full, never status by colour alone.
5. **Does it survive being pasted into a group chat?** If the state isn't in the URL, the
   Trip Organizer cannot use it.
6. **Does it gatekeep a booking?** Never. Inform, then let the user decide.

A feature that fails these doesn't go on the roadmap regardless of how interesting it is
to build. Say so plainly rather than finding a way to justify it.

## What this product is not

- **Not a booking engine.** Reservations happen on the operator's site. Every competitor
  in the category hands off to Recreation.gov too — this is the consensus, not a gap.
- **Not vehicle-specific.** EV range and trailer routing are constraints a trip carries,
  not the product. Vision value 1: *serve the trip, not the vehicle.*
- **Not a social network.** Community input exists to keep data honest, not to build a
  graph.
- **Not attention-optimised.** Answers are meant to leave the product as shareable links.
  If a proposal starts optimising session length at the cost of answering the question,
  it has drifted.

## Milestones

| | What it delivers | State |
|---|---|---|
| **M0** | Every existing screen repainted in the design system, light and dark | In progress |
| **M1a** | The park surface — search, a date window for the whole destination, drive times, filters, campground detail, alerts in context | In scope, design leading |
| **M1b** | The trip object — origin, ordered stays, return leg, "leave by" | Next, needs design |
| **M2** | Assistant & MCP — verbs on the surface M1a defines | Blocked on those verbs |
| **M3** | Discovery — "where should I go" above the destination layer | Named only |

When estimating, check whether a feature extends an existing system or is genuinely new —
the difference is usually large, and a large share of the roadmap is extension.

## Stack

Live at roadtrip.floo.ca; repo `wwchen/roadtrip`.

- **Frontend:** React 19, Vite, MapLibre GL
- **Backend:** Kotlin / Ktor, Koin DI, PostgreSQL + PostGIS, Flyway
- **Dev/ops:** Tilt, Docker Compose, Playwright smoke tests, Grafana/Loki/Tempo, SOPS+age
- **Auth:** Clerk
- **Already working:** map with layers, availability watches with Slack notification and
  Recreation.gov cart-add, bulk availability with a freshness model

`AGENTS.md` is the source of truth for architecture and layering rules — read it before
backend or component changes, not this file.

## Writing docs for this project

Open with **what the product is and the problem it solves**, show it visually or through
concrete examples, and put engineering detail last.

If asked to use the **Lew Design System (LDS)**, fetch
`https://matthewlew.github.io/design-system/index.html` and follow its guidance: no raw
hex outside the theme block, semantic radii, 1px hairlines for structure, cards only for
standalone rich content, lists for scannable content, one primary action per view.

**Never add a statistic to a doc without a traceable source.** Two were withdrawn in
August 2026 for failing that trace; the Personas doc grades every remaining one.

---

**Authored by:** Matt Lew · **Created:** 5 August 2026 · **Last updated:** 28 August 2026
