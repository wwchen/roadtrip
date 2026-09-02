# The rec.gov companion

The companion (`companion/`) is a Node 22.9+ Playwright service that owns
every browser interaction with recreation.gov. Recreation.gov sits behind
Akamai, which flags datacenter IPs and headless Chromium, so a real Chromium
with a persistent, logged-in profile is the only thing that lands cart holds
reliably. The backend never touches a browser: it polls public availability
APIs and calls this HTTP contract.

This page owns the contract. `README.md`, `docs/installation.md` and
`docs/glossary.md` point here rather than restating it.

## Exposure invariant

**The companion never gets a public vhost.** It has no Compose `ports:`
mapping and no Caddy route, and that stays true. All user interaction is
proxied through backend routes, which is what makes per-user isolation
enforceable — the backend authenticates the user and only ever passes *their*
`profile_id`. Operator debugging uses an SSH port-forward or `docker exec`.

Transport is plain HTTP on the internal Docker network; TLS is not added.

## Authentication

Every route that returns data or touches a profile requires the shared secret
`COMPANION_API_TOKEN` in the `x-companion-token` header — `/docs`,
`/openapi.json` and `/screenshot` included. Two exemptions, both for things
that carry nothing:

- `GET /health` **from loopback**, which is what the Compose healthcheck uses
  and which cannot hold a secret.
- `GET /`, the **static operator shell**, from anywhere. It has no profile data
  and no secrets in it; the controls on the page send the token from their own
  field, and every route they call is still gated. Gating the shell made it
  unreachable in exactly the case it exists for — a browser cannot set a header
  on a navigation, and a request from the host through the Compose port mapping
  is not in-container loopback, so the health exemption did not cover it.

- The secret is registered in `secrets/registry.yaml` with
  `consumers: [backend, companion]`, so one value reaches both sides and
  cannot drift. See [secrets.md](secrets.md).
- The companion reads `COMPANION_API_TOKEN`, falling back to the file named by
  `COMPANION_API_TOKEN_FILE` (default `/run/secrets/companion_api_token`),
  which is how Compose delivers it.
- The backend reads it from `roadtrip.booking.recgov-atc.companion-api-token`
  and sends it from `client/companion/CompanionSessionClient.kt`, its only
  client to the companion — session routes and the ATC POST alike.
- **Unset fails closed:** every non-loopback-health request answers `503
  companion_auth_unconfigured`. Local development must export
  `COMPANION_API_TOKEN` before `npm start`.

The operator page loads in a plain browser tab. It then needs a token in its
own field before any of its controls will work, since every route they call is
gated.

## Browser-profile pool

`profile_id` — the roadtrip user id, an opaque string here — is **required**
on `POST /login`, `POST /logout`, `POST /destroy`, `POST /refresh`,
`POST /atc`, `POST /verify` and `GET /screenshot`. There is no shared fallback profile:
a request without it is rejected with `400 profile_id_required`.

- Each profile id maps to its own persistent Chromium user-data directory at
  `<browser-session volume>/profiles/<profile_id>`. Two concurrent cold
  callers for one profile share a single launch: two Chromiums on one
  user-data directory corrupt the profile.
- Anything stored per session — the `recgov_cookies` cookie jar included — is
  keyed by profile id (`recgov_cookies:<profile_id>`). The unkeyed legacy value
  belongs to the CLI's single profile and never enters a user's profile. See
  [Session durability](#session-durability) for the save/inject round trip.
- Playwright persistent contexts are one browser process per directory, so
  residency is a real memory cost. `COMPANION_MAX_CONCURRENT_BROWSERS`
  (default 3) caps on-demand launches; the least recently used idle profile is
  evicted to make room. When every resident profile is busy the launch is
  refused with `503 browser_cap_reached` rather than evicting live work.
- **Keep-warm profiles are exempt from the cap.** The companion cannot derive
  the set — the watches and credentials live in the backend's database — so the
  backend's keepalive job pushes the whole set to `POST /keep-warm` on each
  sweep and the companion defaults to none. The backend builds it as **owners of
  active `atc` watches, plus every user with rec.gov credentials whose profile
  has been signed in at least once**. Armed-watch owners come first and a
  never-signed-in profile is skipped (there is no session to keep alive), and
  the whole set is truncated to `BOOKING_MAX_KEEP_WARM_PROFILES` (default 25).
  Armed watches alone were too narrow: a user who logged in without an `atc`
  watch had nothing refreshing them, so their session lapsed within the hour. The push **replaces** rather than merges, which is what
  disarms a profile whose last `atc` watch was paused or deleted; an empty array
  disarms everyone. When armed profiles alone exceed the cap, the companion logs
  and `GET /health` reports `pool.keep_warm_overflow` rather than evicting an
  armed profile.
- **Per-profile busy lock:** no two mutating operations run concurrently on
  one profile (`409 profile_busy`). That includes `GET /screenshot`, which
  drives the browser and mutates the context's cookies. Different profiles
  never block each other. **Health is lock-free** — the settings status row
  must answer while a login is mid-flight.
- Session state that health reports — the refresh window, the last login
  diagnostic, the last auth result — is recorded per profile, so one user's
  failed login never appears on another user's status row.
- Failure codes are stable: `recgov_auth.error` is one of
  `recgov_login_failed`, `recgov_refresh_failed`, `recgov_not_authenticated`.
  The internal blocker (`mfa_required`, `captcha_required`,
  `login_link_not_found`, …) rides alongside in `recgov_auth.reason`, which is
  diagnostic — callers branch on `error`.

## Routes

`GET /openapi.json` and `/docs` are generated from `src/apiContract.js` and
are authoritative; this is the shape.

| route | purpose |
| --- | --- |
| `GET /` | Operator page (profile id + token fields, login, ATC, screenshot). |
| `GET /health` | Companion health. With `?profile_id=` it reports that profile's `recgov_auth`, busy flag and pool residency. Lock-free; tokenless from loopback only. |
| `POST /login` | Two-phase credential login for one profile. |
| `POST /logout` | Click through the rec.gov logout flow in one profile. Leaves the profile directory and stored cookie jar in place. |
| `POST /destroy` | Erase one profile: close the browser, delete its directory, its stored cookie jar and its failure diagnostics. Takes the busy lock. Idempotent. |
| `POST /refresh` | Force a session refresh for one profile. The keepalive job calls it for each armed profile. |
| `POST /keep-warm` | Replace the armed profile set (`{ "profile_ids": [...] }`). Lock-free; marks profiles, never drives a browser. |
| `POST /verify` | Dry-run session check. Never places a hold. |
| `POST /atc` | One-shot add-to-cart in one profile. |
| `GET /screenshot` | Live PNG of a recreation.gov page from one profile. |
| `GET /screenshot/diagnostics` | List stored failure diagnostics, newest first. |
| `GET /screenshot/diagnostics/{file}` | Download one diagnostic — a PNG, or a Playwright trace archive. |

### Two-phase MFA

1. `POST /login` with `profile_id`, `username`, `password`. If rec.gov prompts
   for a code, the response is `401` with `error: "mfa_required"`, a
   `challenge_id` and an `expires_at`. **The login page is left open on the
   prompt**, and the challenge **holds the profile's busy lock** until it is
   completed or expires; the TTL is minutes-scale
   (`COMPANION_MFA_CHALLENGE_TTL_MS`, default 5 minutes) because rec.gov
   delivers codes by email or SMS.
2. `POST /login` with `profile_id`, `challenge_id` and `mfa_code` types the
   code into **that same held page**. Phase two never navigates and never
   re-submits the username and password: a fresh login makes rec.gov issue a
   new code, which would invalidate the one the user is holding.
   - A rejected code answers `401 mfa_invalid`. It consumes the challenge but
     does **not** arm the failed-login backoff — a typo must not lock the user
     out of retrying. Start a new login to get a new challenge.
   - A missing `mfa_code` is refused with `400 mfa_required` and leaves the
     challenge and its held page intact.
   - An unknown id is `mfa_challenge_unknown`; a lapsed one is
     `mfa_challenge_expired`. Expiry releases the lock **and closes the held
     page** — an abandoned login must not leave a page sitting on rec.gov with
     credentials typed into it.

**Unattended logins never open a challenge.** `POST /login` with
`unattended: true` — the backend's fire-time re-login, never the Settings flow
— answers `401 mfa_required` with **no** `challenge_id`, closes the held page
and releases the profile lock immediately. Holding a challenge for a caller
that can never complete it would pin the profile busy for the whole TTL and
wedge the owner's own Test login, the keepalive refresh and any second ATC
behind a code nobody is going to type.

Because a pending challenge keeps its profile resident and locked, it occupies
a slot against the concurrency cap for up to the TTL. That is the accepted
trade for not making the user re-enter credentials; `GET /health` shows the
pending challenge (`mfa_pending`) and the cap so the cost is observable.

Credentials are held in memory for the life of the attempt (and of a pending
challenge) only. The companion persists no passwords — only Chromium profile
state. The backend is the credential custodian.

### Failed-login backoff

A credential login that does not end logged in arms a per-profile in-memory
marker; the next login inside `COMPANION_FAILED_LOGIN_BACKOFF_MS` (default
60s) is refused with `429 login_backoff` and a `retry_after_ms`. This guards
against rec.gov lockouts from rapid repeats. A rejected MFA code is not a
failed credential login and never arms it.

### `POST /verify` (dry run)

Loads the rec.gov account page in the profile and reads
`GET /api/cart/shoppingcart` from page context. That exercises the session,
the fingerprint cookie and Akamai without needing a campsite target. It
**never clicks Reserve**, so no test ever costs a real cart hold. `200` means
the session is live; `401` carries `verify.error`
(`recgov_not_authenticated` or `recgov_cart_unreachable`).

### Destroying a profile

`POST /destroy` is **the only operation that deletes profile state.** Everything
else preserves it on purpose: `logout` clicks through rec.gov's sign-out flow
and leaves both the Chromium user-data directory and the saved
`recgov_cookies:<profile_id>` jar exactly where they were, which is right for a
logout and wrong for a removal. A "remove my credentials" built on `logout`
alone leaves the user's rec.gov session material on this host.

It takes the per-profile busy lock, so it can never delete a user-data
directory out from under a live login, verify or ATC. In order it closes and
evicts the context, drops the profile from the keep-warm set (so the keepalive
sweep stops asking for it), deletes the profile's stored cookie jar, deletes
the profile directory, and deletes that profile's failure diagnostics —
reporting how many in `diagnostics_removed`.

- **The diagnostics sweep is load-bearing, not tidying.** A kept `/verify` or
  `/atc` trace records the network log, so it holds the same live session the
  cookie jar held; deleting the jar and leaving the archive would erase the copy
  and keep the original. Artifacts are swept after the browser is closed, so
  nothing can write a new one behind the sweep, and only the ones naming this
  profile are touched.
- **It fails loudly.** An artifact that cannot be deleted makes `POST /destroy`
  answer `500 profile_destroy_failed` rather than reporting a wipe over material
  still on disk. The backend already refuses the credential delete when destroy
  fails, so the user is told the truth and a retry converges.

- **Idempotent.** Destroying a profile that was never launched, or was already
  destroyed, is `200` with `directory_removed: false`. The caller asked for it
  to be gone and it is gone.
- **Exactly one profile.** No prefix or wildcard match, and an id whose
  directory would resolve outside the profiles root is refused with
  `400 invalid_profile_id` — checked against the resolved path, not just the id
  pattern, because the operation is a recursive delete.
- **Never the legacy unkeyed jar.** That belongs to the operator's CLI profile,
  not to any user.

The backend calls it from `DELETE /api/settings/recgov` after the sign-out, and
**the local credential delete is conditional on the destroy succeeding.** If the
profile cannot be destroyed the removal is refused with
`502 recgov_profile_wipe_failed` and the credential row is left intact, rather
than reporting a deletion that did not happen while the cookie jar stays on
disk. The same rule guards a credential *swap*: `PUT /api/settings/recgov` with a
different username destroys the replaced account's profile first and refuses the
save if it cannot, because the profile is keyed by user rather than by account —
without it the previous account's session survives, refresh-first login revives
it, and a hold lands on the account the user just replaced.

`profile_destroyed: false` therefore no longer means "the wipe failed"; it means
no companion is configured at all, so there was no saved session to erase. A
failed sign-out alone does not block removal — `logout` is the graceful half and
`destroy` is the load-bearing one — so `companion_signed_out` is still reported
separately.

The cost of this posture is deliberate: while the companion is unreachable a user
cannot remove or switch credentials, and the error tells them the booking service
needs attention.

### Session durability

**Sessions survive a container restart via the per-profile cookie store.**
Rec.gov's session cookies — including the `r1s-fingerprint` value its JWT is
pinned to — are session-scoped in Chromium: they live in memory and die with
the browser process, so the persistent profile directory alone does not carry
a login across a restart.

So the round trip is explicit:

- **Save.** Every auth-bearing operation that *succeeded* — `POST /login`
  (credential and MFA completion), `POST /refresh`, `POST /verify`, and the
  host-side `recgov:login` / `recgov:refresh` probes — exports the context's
  `recreation.gov` cookies to `recgov_cookies:<profile_id>`. Failure paths
  deliberately do not: a failed attempt's jar would overwrite a good one.
- **Inject.** `launchProfileContext` re-injects that key on every launch.
- **Never shared.** A profile only ever gets its own key. The unkeyed legacy
  `recgov_cookies` — the operator's documented cookie paste — belongs to the
  CLI's single profile and is never injected into a user's profile, and nothing
  writes the unkeyed key. A cookie jar is a session; sharing one is sharing an
  account.

The store file must live on the mounted volume. The container sets
`COMPANION_DIR=/var/lib/campsite-companion`, and Compose mounts the operator's
whole `$HOME/.campsite-companion` there (`RECGOV_COMPANION_DATA_DIR`) rather
than just the pool inside it. Left at its default the file would land in the
image's ephemeral `/root` and every container recreate — every Tilt rebuild,
every deploy — would sign every profile out. Host and container therefore name
the same `store.json`, which is also what lets a headed mint on the host reach
the container.

**Sharing the file means two writers, so the store commits by rename.** A
mutation writes a temp file beside `store.json`, fsyncs it and renames it over
the top, so no reader ever sees a half-written store and no crash leaves one.
Mutations also take `store.json.lock` — a wall-clock claim, since host and
container cannot read each other's pids — so neither side's read-modify-write
cycle can drop the key the other just saved. A write that cannot get the lock
fails with `store_busy` instead of clobbering, and a lock left by a killed
process goes stale on its own (`COMPANION_STORE_LOCK_STALE_AFTER_MS`, 60s by
default).

**An unparseable `store.json` is a hard failure, not an empty store.** Reads and
writes both raise `store_corrupt`, and nothing will write over the file: calling
it empty would report every user as signed out and then commit that emptiness
over every jar, which is how one torn write becomes permanent loss. Look at the
file, and move it aside if it is truly unrecoverable — the next write then
starts a fresh store, at the cost of every session that was in it.

**Treat `recgov_cookies*` as credential material.** The value is not a hint or
a cache: it *is* a live rec.gov session, and anyone holding it is signed in as
that account. It never leaves the companion host, and a store file holding one
deserves the same handling as a password file.

**A kept trace holds the same session.** A Playwright trace records the network
log with full request headers, so a `/verify` or `/atc` trace written on failure
contains the profile's live `cookie:` jar and the `authorization: Bearer …`
header the cart path injects — Playwright offers no redaction. Trace archives
are therefore credential material too: they are named with the profile they
belong to and `POST /destroy` deletes them, and an operator who wants a
cookie-clean diagnostics directory sets `COMPANION_TRACE_SESSION_OPS=false`.

`profile_id` has exactly one shape: the roadtrip user id as a decimal string
(`"7"`). Every backend caller derives it the same way; the store key and the
profile directory are both built from it, so a second shape would save a
session under a key the launch path never reads.

### Failure diagnostics and traces

Every browser operation — `/login` (both phases), `/verify`, `/atc` — runs
under a Playwright trace (screenshots, snapshots, sources). **`/refresh` does
not**: the keepalive sweep touches every armed profile on a cadence and would
churn artifacts for nothing.

The outcome decides what survives:

- **Success:** tracing stops with no path, so Playwright discards the buffer.
  Nothing is written — not written and later swept.
- **Failure:** the trace is written to the diagnostics directory beside the
  failure screenshot, under the same naming convention
  (`recgov-<operation>-<timestamp>-profile_<profile_id>-<reason>.trace.zip`),
  and the response names it in `diagnostics.trace`.

**Every artifact is named with the profile it belongs to.** That is what makes
`POST /destroy` able to erase them: the name is the only record of whose
material an archive holds. The profile segment is sanitized to
`[A-Za-z0-9_]` so it can be parsed back out of a name whose reason contains
hyphens. Artifacts with no segment — written before this convention, or by the
operator CLI's legacy profile — belong to no pooled profile, so no per-profile
wipe claims them; they age out through the prune bound.

**What the artifacts contain — read this before turning login tracing on.**

| artifact | contains | posture |
| --- | --- | --- |
| `/verify`, `/atc` traces | page state, DOM snapshots of the signed-in account and cart pages, and **the network log with request headers — the profile's live rec.gov session cookie jar and its `authorization: Bearer …`**. No raw password: neither route ever holds one, and neither can reach a login form (see below). | Traced by default; `COMPANION_TRACE_SESSION_OPS=false` turns it off. |
| Failure screenshots | a rendered page. Login forms mask the password field. | Always kept on failure. |
| `/login` and MFA traces | **the typed rec.gov password**, in fill parameters and DOM snapshots, on top of everything the row above lists. | **Off by default.** `COMPANION_TRACE_LOGIN=true` opts in. |

A login trace defeats the point of everything else in this design: the backend
seals the password with AES-256-GCM, the companion holds it in memory for one
attempt only, and V54 dropped even its last four characters from the database.
Writing it to a file on disk for debuggability would undo all of that. Turn it
on to debug a specific login, treat the output like the vault, and delete it
afterwards.

A session trace is milder but not clean: anyone who can read the file can act
as that user on rec.gov until the session lapses. Exposure is bounded — the
download route is token-gated, the companion has no published port, and the
directory is pruned — and the archive dies with the profile. Treat one you have
copied off the host like the store file.

**`/atc` never reaches a login form.** `resolveRecaccount` can fall through to
a manual-login wait on a headed browser; the ATC path passes
`allowManualLogin: false`, so a fired hold against a logged-out profile fails
fast with `recgov_not_authenticated` instead of parking the request for
`RECGOV_LOGIN_TIMEOUT_MS` waiting for a human, holding the profile lock, and
recording whatever got typed into a trace that is not gated on
`COMPANION_TRACE_LOGIN`. Nobody watches a fire path, and the hold is gone by
then anyway. Mint a session headed with `make recgov-login` instead.

Open one with:

```sh
npx playwright show-trace recgov-login-2026-09-01T00-00-00-000Z-profile_7-captcha_required.trace.zip
```

The directory is pruned to the newest `COMPANION_MAX_DIAGNOSTIC_ARTIFACTS`
(default 40) on every artifact write — screenshots and traces share the budget,
since a trace is the expensive one. Pruning runs on write rather than on a
timer: the directory only grows when something writes to it.

The operator page has a **Failure diagnostics** section listing what is stored
with per-artifact download buttons. Downloads go through fetch + the token
header, because the listing and download routes are gated like every other data
route — only the static shell is not.

Tracing never changes an outcome: if the tracing API itself throws, the
operation's own result stands and the trace is simply absent. It adds no waits,
which is what makes it acceptable on the ATC fire path — the one place failure
visibility matters most and nobody is watching.

## Configuration

| variable | default | meaning |
| --- | --- | --- |
| `COMPANION_API_TOKEN` / `COMPANION_API_TOKEN_FILE` | — / `/run/secrets/companion_api_token` | Shared secret; unset fails closed. |
| `COMPANION_HOST` / `COMPANION_PORT` | `0.0.0.0` / `8770` | Listen address. |
| `COMPANION_DIR` | `$HOME/.campsite-companion` | The companion's data directory. `store.json` — the per-profile cookie jars — lives here. **In Docker it must point inside the mounted volume**, or sessions die with the container. |
| `COMPANION_BROWSER_PROFILE` | `$COMPANION_DIR/browser-session` | Root of the profile pool. |
| `COMPANION_PROFILE_ID` | derived from the profile directory's name | Which pooled profile a host-side CLI run mints a session for. |
| `COMPANION_MAX_CONCURRENT_BROWSERS` | `3` | Cap on on-demand resident browsers. |
| `COMPANION_MFA_CHALLENGE_TTL_MS` | `300000` | Pending MFA challenge lifetime. |
| `COMPANION_FAILED_LOGIN_BACKOFF_MS` | `60000` | Suppression window after a failed login. |
| `COMPANION_MAX_DIAGNOSTIC_ARTIFACTS` | `40` | Failure screenshots + traces kept before the oldest are pruned. |
| `COMPANION_TRACE_LOGIN` | unset (off) | Trace `/login` and MFA completion. **The trace contains the typed password** — see above. |
| `COMPANION_TRACE_SESSION_OPS` | unset (on) | Trace `/verify` and `/atc`. **A kept trace contains the profile's live session cookies and bearer token** — see above. `false` keeps the screenshots and writes no session traces. |
| `RECGOV_DIAGNOSTIC_DIR` | `/tmp/campsite-companion/recgov-diagnostics` | Where those artifacts live. |
| `HEADLESS` | true in Docker | Headed Chromium for operator login. |
| `RECGOV_LOGIN_TIMEOUT_MS` | `120000` | Manual-login wait, headed login paths only. `/atc` and `/verify` never enter it. |

The **backend** side has two knobs of its own. `RECGOV_KEEPALIVE_INTERVAL`
(default 15m) sets how often it re-pushes the armed set and refreshes those
profiles — see [observability.md](observability.md) for the metric it emits.
`RECGOV_FIRE_TIMEOUT` budgets the checks that run *before* a hold — the session
preflight and the one unattended re-login — separately from the 180s a
browser-driven cart run gets, so an ATC racing other users does not spend the
cart budget twice before it starts. It defaults to a **third of
`companion-timeout`** (60s at the default) rather than a fixed number: as a
standalone 30s it sat below the companion's own refresh ceiling — a browser
launch, then navigation retries — so the recovery it pays for was cut off from
the backend side and reported as an unreachable companion.

## Running it

```sh
cd companion
npm install
COMPANION_API_TOKEN=dev npm start   # http://127.0.0.1:8770
npm test                            # node --test, no browser needed
```

As a Compose service (opt-in profile, no published ports):

```sh
RECGOV_COMPANION_DATA_DIR=$HOME/.campsite-companion \
  docker compose --profile pois --profile recgov-companion up -d recgov-companion backend
```

The container name lives under the `roadtrip-*` Compose project, so Alloy's
Docker log discovery ships its stdout/stderr to Loki.

### No auth check at boot

The companion runs **no** rec.gov auth check when it starts. It used to, and
that check signed in the single legacy profile — a browser process that,
once every route became profile-scoped, belonged to nobody. Profiles are
launched on demand and kept warm by the armed set instead. A consequence:
`GET /health` without a `profile_id` reports `recgov_auth.login_status:
"unchecked"` until something explicitly runs an unscoped check. That is
accurate rather than degraded — there is no companion-wide session to report.
Per-profile health (`?profile_id=`) is the answer callers want.

Per-profile auth status is **process memory**, so a restart — every deploy, every
Tilt rebuild — answers `login_status: "unchecked"` for profiles that do hold a
live session. Read alone that is indistinguishable from "never signed in", which
silently drops those users out of the keep-warm sweep and tells them "Not logged
in yet". So per-profile health also carries `has_stored_session`, true when the
profile's cookie jar is persisted: durable evidence a session existed. The
backend maps `unchecked` **with** a stored jar to an expired session (worth
refreshing, worth keeping warm) and only `unchecked` **without** one to
never-logged-in.

## Operator CLI

These drive the single legacy profile directly (not the pool), for the
operator's own rec.gov session:

```sh
make recgov-login     # headed login; exits 0 on REC_GOV_AUTH_OK
make recgov-refresh   # forces the real refresh endpoint
make recgov-atc PAYLOAD=/tmp/recgov-atc.json   # places a REAL hold
```

**Minting a session for a pooled profile from the host.** A headed login on
the host clears challenges a headless container cannot. Point
`COMPANION_BROWSER_PROFILE` at that profile's pool directory and the resulting
cookie jar is saved under the profile's key, so the container picks it up on
its next launch:

```sh
COMPANION_BROWSER_PROFILE=$HOME/.campsite-companion/browser-session/profiles/7 \
  npm --prefix companion run recgov:login
```

The profile id is the directory's own name; `COMPANION_PROFILE_ID` overrides
it. Point it anywhere else and the run stays an unkeyed legacy session — the
probe prints which it resolved.

**The container may be serving while you do this, but not that same profile.**
Host and container share this volume, and Chromium's singleton lock is the only
cross-process guard against two browsers writing one profile directory — which
is exactly the corruption that loses a real user's session. Neither side can
read the other's pid, so a launch publishes a heartbeat lease
(`roadtrip-owner.json`) in the profile directory and refuses to sweep a lock
whose lease is still being refreshed, failing with `profile_dir_busy` rather
than attaching a second browser. If you hit that, the profile is resident in the
container: `docker compose stop recgov-companion` (or wait for keep-warm to drop
it) and retry. A lease left by a hard-killed process goes stale on its own —
`COMPANION_OWNER_STALE_AFTER_MS`, 30s by default — and then sweeps normally.

`recgov:atc` writes browser logs to stderr and one JSON result to stdout:
exit `0` means `cart_added=true`, `1` means the browser ran but confirmed no
hold, `2` means invalid input.

## Known limitations

- **CAPTCHA cannot be solved remotely.** The companion detects challenges and
  surfaces `captcha_required`; only an operator at the headed browser can
  clear one. Retrying often passes without a challenge.
- **Unattended re-login fails under MFA**, by design — interactive MFA only.
  The keep-warm/refresh path makes this rare.
- All profiles share the companion host's egress IP. Accepted at current
  scale; a profile-per-proxy scheme is a possible future mitigation.
