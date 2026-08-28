# Roadtrip — product context

The durable version of what the doc set says. When this and a doc disagree, **the doc
wins** — this file is a summary that goes stale, the artifacts are edited continuously.

**Last reconciled with the doc set:** 28 August 2026.

---

## 1. What the product is

A planning surface for camping trips over public inventory. It answers *where can I
actually go, when, and will it be any good* — and hands off to the operator to book.

**Not a booking engine.** Reservations happen on Recreation.gov and the state systems.
This is the surface that decides *what* to book. That is the whole category's standing
consensus, not a limitation: every competitor ends by handing the user to Recreation.gov.

**Not vehicle-specific.** EV range and trailer routing are constraints a trip can carry,
not the product. This replaced an earlier thesis built on EV charging permission at
campsites, dropped 27 August 2026 because EV-only caps the market before anything else is
proven. Recoverable if it is ever needed:
`git show b9d2b942:.claude/skills/roadtrip-product/references/user-research.md`.

## 2. Who it is for

Three personas we build for, two we design around. Full evidence in the Personas doc.

| | Who | The binding constraint |
|---|---|---|
| **Key 01** | **Flexible Weekender** | Dates fixed, destination negotiable. 54% of campers book within five weeks against a 6-month window, so she is structurally a fallback user. Satisficer. |
| **Key 02** | **Aspirational Beginner** | Not skill, not fear — *fit*. Only ~43% rate a first trip great; 87% of those keep camping. Every unstated absence lands as personal failure. |
| **Key 03** | **Trip Organizer** | One adult carries the trip. 19 hours, 83 messages, headcount that never settles. Needs the decision to be shareable, not re-explainable. |
| Tail 04 | Youth-Group Quartermaster | Rules no consumer software models. Deliberately not served. |
| Tail 05 | Site Loyalist | Wants site 43 in July. Already served by free 45-second scanners. |

**Two of three key personas are satisficers, and the category is built for maximisers.**
That is the strongest single argument for leading with the date window rather than with a
campground choice.

## 3. The filter — apply to every feature proposal

1. **Which persona, and which of their blocked moments?** If the answer is "power users,"
   check it against Tail 05 before believing it.
2. **Does it lead with dates or with a destination?** Leading with a destination serves
   the persona we have least of.
3. **Does it state absences, or only features?** Silence about no water, no showers, a
   40-minute walk is the failure that ends first camping trips.
4. **Does it claim more certainty than the data supports?** Stamp freshness. Closed is not
   full. Never render a status by colour alone.
5. **Does it survive being pasted into a group chat?** If the state isn't in the URL, the
   Organizer cannot use it.
6. **Does it gatekeep a booking?** Never. Inform, then let the user decide.

A proposal that fails these does not go on the roadmap regardless of how interesting it
is to build. Say so plainly rather than finding a way to justify it.

## 4. Recurring judgment calls

- **Per-campground, never per-park.** Grand Canyon is the proof; Yellowstone, the Smokies
  and Denali split the same way. Anything keyed to a park name is wrong.
- **Freshness is visible, always.** A four-month-old single report must not look like a
  verified fact.
- **The escape hatch matters more than the recommendation.** Every dead end carries a
  pre-loaded second option — that is what M1a-7 "ways forward" is for.
- **Statuses are words, not colours.** Green reservable / blue first-come / grey taken is
  the data language, and every one of them is also rendered as text.
- **First-come is a real status, not missing data.** It is the only tier still open when
  the Weekender looks — and it is a trap for the Beginner, who arrives to a FULL sign.
  Same inventory, opposite recommendation depending on who is looking.

## 5. Hard constraints that survive any thesis

- **Booking windows are brutal and non-uniform.** Most federal sites release exactly 6
  months ahead on a daily rolling 10am ET window. Yosemite uses monthly block release
  (5 months out, the 15th, 7am PT). Some state systems open 11 months out. Group
  facilities range from 14 days to 12 months *within the same agency* — never state a
  group booking rule globally.
- **Connectivity fails exactly where the stakes are highest.** Anything critical must
  survive being loaded in advance. Offline is both unmet and unrequested: nobody asks for
  it because they already reverted to paper.
- **Supply reappears near the date, but never at a discount.** Camping has no resale
  market and no dynamic pricing. The 1-day cancellation cliff on family sites is what
  makes alerting work; the payoff is availability, not a deal.
- **Party size is a step function.** Average party is ~2.4 people and the physical system
  is calibrated to it. Federal sites commonly cap at 6–8; 12 breaches the wilderness cap.

## 6. Trust model — the thing most likely to kill this

A confidently wrong answer is worse than no answer. The downside is a bad night in a
remote place, and they do not come back after one.

**Separate what we know from what we recommend.** State the fact, its age and its source
distinctly from any ranking built on it. "Verified June 2026 via park website" and
"reported by one visitor, March 2024" must never look the same.

The retention maths is why this is a business rule and not a nicety: only ~43% of first
camping trips are rated great, and 87% of those convert to a repeat camper. Every honesty
rule is retention machinery.

## 7. Where the evidence is weak

No user interviews and no usage data. Everything in the Personas doc is inference over
other people's surveys, and it says so. Two statistics were withdrawn in August 2026 for
failing a source trace — see the Personas doc `#sources`.

**Do not add a statistic to any doc without a traceable source.** Five interviews would
still test more than another month of desk research; when a question turns on user
behaviour we have not observed, say that rather than reaching for a number.
