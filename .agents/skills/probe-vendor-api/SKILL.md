---
name: probe-vendor-api
description: Reverse-engineer a campsite booking vendor's HTTP API by driving the real site in a headed browser, capturing the network log, and probing the discovered endpoints into JSON fixtures. Use when adding a new BookingProvider adapter, or when an existing adapter is missing fields the vendor's site clearly displays.
---

# probe-vendor-api

A booking vendor's "API" is whatever its SPA actually calls. There's no
spec. The only ground truth is the network tab. This skill is the
playbook for going from "the site shows site name C13" to a JSON fixture
of the endpoint that returned it.

When you finish, write findings to `docs/booking-providers/<vendor>.md`
using the structure in `docs/booking-providers/aspira.md` as the
template.

## When to use

- Adding a new `BookingProvider` adapter (rec.gov, Aspira tenant N+1,
  Camis-direct, ReservAuto, etc.).
- An existing adapter's `reservables.name` is null and the booking site
  clearly shows site labels — the catalog endpoint exists, we just
  haven't found it.
- A vendor's site shows attributes (electrical, length, capacity) that
  our heat-strip / alerts can't filter on. The attribute dictionary
  endpoint exists somewhere.
- Debugging a "the site says it's available, our adapter says it's not"
  bug. Often this is a query-param mismatch the bundle doesn't reveal.

## When NOT to use

- The vendor publishes a real API with docs (e.g. RIDB for rec.gov
  metadata). Use the documented surface.
- You only need to *call* an endpoint we already know about. That's
  a fetcher script, not a probe.

## Why this is harder than it looks

The first three things every agent (including past me) tries:

1. **`curl` the endpoints listed in the JS bundle.** Almost always
   blocked. Aspira's WAF (Azure App Gateway) and rec.gov's Akamai
   gate non-browser User-Agents and TLS fingerprints. Even if curl
   gets through, the JS bundle only lists the endpoints loaded on the
   landing route. The catalog endpoints we actually want are
   lazy-loaded chunks under `create-booking/results` etc., and those
   chunks come back as WAF-challenge HTML disguised as `chunk-*.js`.
2. **Read the bundle statically.** Same problem: lazy chunks aren't
   reachable without an authenticated browser session. `main-*.js`
   shows ~10% of the API surface.
3. **Use `browse` headless against the site.** Headless Chromium is
   detected by Akamai (`navigator.webdriver`, JA3, lack of mouse
   events) and bounced to a Captcha challenge.

The shortcut that actually works: **headed Chrome with the gstack
browse extension**, which gives you a real session with a real
fingerprint, plus a network buffer you can grep.

## Procedure

### 0. Pick a parking spot

Pick one specific park and date range where you know sites are visible.
Bookmark the deeplink. Examples:

- Aspira PC: `https://reservation.pc.gc.ca/create-booking/results?...`
  (a busy summer date at Banff Tunnel Mountain)
- Aspira WA: any state park, any future date (the booking horizon is
  365 days, so always populated)
- Rec.gov: `https://www.recreation.gov/camping/campgrounds/232447`
  (Yosemite Upper Pines)

Concrete is the goal. "Some campground" is not a probe.

### 1. Static recon (cheap, often misleading)

Fetch the homepage and the booking results route with curl, just to
enumerate what JS chunks the SPA references. Don't trust this list
to be complete.

```bash
curl -s -H "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<version> Safari/537.36" \
     -H "Accept: text/html" \
     "https://washington.goingtocamp.com/" -o /tmp/home.html
grep -oE '/api/[a-zA-Z0-9/_-]+' /tmp/home.html | sort -u
```

If `main-*.js` is reachable, grep its `/api/` literals. This gives
you a "things the landing route calls" list. **Mark it as such.** It
will be missing the endpoints you actually need.

If chunks come back starting with `<!doctype html>`, the WAF won't
let curl past — skip ahead to step 2.

### 2. Headed browser network capture (this is where the truth lives)

```bash
B=$HOME/.Codex/skills/gstack/browse/dist/browse
$B connect                         # headed Chromium, real fingerprint
$B network --clear
$B goto "<deeplink to the park's results page>"
sleep 3
$B click @e1                       # consent gate, if present
sleep 3
# drive the search to a state where named sites are visible
```

Some sites need a few clicks to surface the catalog endpoints. Aspira
calls `/api/resourcelocation/resources` only after you've drilled into
a specific park's grid. Rec.gov calls
`/api/camps/availability/campground/{id}/month` only after you select
dates. Drive the UI until the page renders the named sites you care
about, then:

```bash
$B network 2>&1 | grep -oE '/api/[a-zA-Z0-9/_-]+' | sort -u
```

This is the **real** API surface. Compare against the bundle list —
the diff is the lazy-loaded surface that static analysis missed.

### 3. Probe each candidate endpoint

Use `browse js` to call the endpoint from inside the page (so cookies
and headers come along automatically) and dump the response.

```bash
# Sample a few keys, don't dump the whole thing yet — these payloads
# can be hundreds of KB.
$B js "fetch('/api/resourcelocation/resources?resourceLocationId=<location-id>').then(r=>r.json()).then(d=>{const ids=Object.keys(d);return JSON.stringify({count:ids.length,sample:ids.slice(0,3).map(id=>d[id])})})"
```

For each endpoint, capture:

- **URL with all query params** (some are required, some optional —
  the bundle won't tell you which)
- **HTTP method** (almost always GET for read endpoints)
- **Top-level response shape** (array vs object-keyed-by-id)
- **The 3-5 fields that map to our reservable / availability model**
- **Sentinel values** (Aspira uses negative sentinel IDs for "any
  equipment" and tenant-local maps — these matter)

Write each captured response to a fixture file at
`backend/src/test/resources/fixtures/<vendor>/<endpoint>.json`
so the doc and future tests can reference real bytes.

### 4. Cross-reference with what we already store

Before declaring a field "new", check whether we already have it under
a different name. Aspira's `resourceId` is the same value our existing
`AspiraResourcesEtl` already stores as `vendor_id` — that's the join
key, not a new column.

```bash
grep -rn "resourceId\|vendor_id" backend/src/main/kotlin/ca/floo/roadtrip/repo/
```

### 5. Open questions

You will find endpoints that look promising but you didn't probe deep
enough to be sure of the shape. List them in the doc under "Open
questions" with what you saw and what you'd test next. **Do not
guess at the response shape.** A wrong fixture is worse than a
"we don't know yet" line.

## Output

Write findings to `docs/booking-providers/<vendor>.md` with these
sections:

1. **Wire shape overview** — base URL, auth model, rate-limit story,
   WAF/anti-bot notes, error semantics.
2. **Endpoint catalog** — one section per endpoint:
   - URL + query params (mark required vs optional)
   - Response shape (full TypeScript-style annotation, not English)
   - Field-by-field mapping to our reservable / availability model
3. **Sentinel and ID dictionaries** — equipment categories, attribute
   IDs, status codes, anything keyed by opaque integer.
4. **Open questions** — endpoints we saw but didn't fully probe.
5. **Capture commands** — the literal `browse` invocations you used,
   so a future agent can re-run them.

Use `docs/booking-providers/aspira.md` as the template.

## Pitfalls

- **WAF challenges look like JSON.** Aspira returns 200 with HTML
  starting `<!doctype html>` when its WAF is upset. Always check the
  first byte of a response before parsing.
- **The bundle lies.** `main-*.js` only lists endpoints needed at
  page load. Lazy chunks behind clicks won't be there.
- **Sentinel IDs change per tenant.** A placeholder-style `mapId` in Parks
  Canada is a different park than the same ID in BC Parks. IDs are
  not portable across hosts.
- **Cookies matter.** Some endpoints 401 without an
  `__RequestVerificationToken` cookie that the SPA sets on first
  load. `browse js` automatically carries cookies; raw `curl`
  doesn't.
- **Don't probe at scale during exploration.** One park, one date
  range, until you understand the shape. WAFs notice patterns.

## After the probe

Hand off to:

1. `docs/booking-providers/<vendor>.md` — the doc you just wrote.
2. A follow-up issue / task to wire what you discovered into a real
   adapter (`service/booking/adapters/<vendor>/`) and ETL
   (`service/etl/<vendor>/`). The probe is reconnaissance, not
   implementation — keep them separate so the doc stays a clean
   artifact instead of getting smudged with "and then I tried…".
