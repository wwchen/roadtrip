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
  and sends it from `client/companion/HttpRecGovAtcExecutor.kt`.
- **Unset fails closed:** every non-loopback-health request answers `503
  companion_auth_unconfigured`. Local development must export
  `COMPANION_API_TOKEN` before `npm start`.

The operator page loads in a plain browser tab. It then needs a token in its
own field before any of its controls will work, since every route they call is
gated.

## Browser-profile pool

`profile_id` — the roadtrip user id, an opaque string here — is **required**
on `POST /login`, `POST /logout`, `POST /refresh`, `POST /atc`,
`POST /verify` and `GET /screenshot`. There is no shared fallback profile:
a request without it is rejected with `400 profile_id_required`.

- Each profile id maps to its own persistent Chromium user-data directory at
  `<browser-session volume>/profiles/<profile_id>`. Two concurrent cold
  callers for one profile share a single launch: two Chromiums on one
  user-data directory corrupt the profile.
- Anything stored per session — the `recgov_cookies` Akamai workaround
  included — is keyed by profile id. The unkeyed legacy value belongs to the
  CLI's single profile and never enters a user's profile.
- Playwright persistent contexts are one browser process per directory, so
  residency is a real memory cost. `COMPANION_MAX_CONCURRENT_BROWSERS`
  (default 3) caps on-demand launches; the least recently used idle profile is
  evicted to make room. When every resident profile is busy the launch is
  refused with `503 browser_cap_reached` rather than evicting live work.
- **Keep-warm (armed) profiles are exempt from the cap.** These are the
  profiles backing at least one active `atc` watch. The companion cannot derive
  that — the watches live in the backend's database — so the backend's keepalive
  job pushes the whole set to `POST /keep-warm` on each sweep and the companion
  defaults to none. The push **replaces** rather than merges, which is what
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
| `POST /logout` | Click through the rec.gov logout flow in one profile. |
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
  (`recgov-<operation>-<timestamp>-<reason>.trace.zip`), and the response names
  it in `diagnostics.trace`.

**What the artifacts contain — read this before turning login tracing on.**

| artifact | contains | posture |
| --- | --- | --- |
| `/verify`, `/atc` traces | page state, network, DOM snapshots. **No raw password** — neither route ever holds one. | Traced unconditionally. |
| Failure screenshots | a rendered page. Login forms mask the password field. | Always kept on failure. |
| `/login` and MFA traces | **the typed rec.gov password**, in fill parameters and DOM snapshots. | **Off by default.** `COMPANION_TRACE_LOGIN=true` opts in. |

A login trace defeats the point of everything else in this design: the backend
seals the password with AES-256-GCM, the companion holds it in memory for one
attempt only, and V54 dropped even its last four characters from the database.
Writing it to a file on disk for debuggability would undo all of that. Turn it
on to debug a specific login, treat the output like the vault, and delete it
afterwards.

Open one with:

```sh
npx playwright show-trace recgov-login-2026-09-01T00-00-00-000Z-captcha_required.trace.zip
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
| `COMPANION_BROWSER_PROFILE` | `$HOME/.campsite-companion/browser-session` | Root of the profile pool. |
| `COMPANION_MAX_CONCURRENT_BROWSERS` | `3` | Cap on on-demand resident browsers. |
| `COMPANION_MFA_CHALLENGE_TTL_MS` | `300000` | Pending MFA challenge lifetime. |
| `COMPANION_FAILED_LOGIN_BACKOFF_MS` | `60000` | Suppression window after a failed login. |
| `COMPANION_MAX_DIAGNOSTIC_ARTIFACTS` | `40` | Failure screenshots + traces kept before the oldest are pruned. |
| `COMPANION_TRACE_LOGIN` | unset (off) | Trace `/login` and MFA completion. **The trace contains the typed password** — see above. |
| `RECGOV_DIAGNOSTIC_DIR` | `/tmp/campsite-companion/recgov-diagnostics` | Where those artifacts live. |
| `HEADLESS` | true in Docker | Headed Chromium for operator login. |
| `RECGOV_LOGIN_TIMEOUT_MS` | `120000` | Manual-login wait. |

The **backend** side has two knobs of its own. `RECGOV_KEEPALIVE_INTERVAL`
(default 15m) sets how often it re-pushes the armed set and refreshes those
profiles — see [observability.md](observability.md) for the metric it emits.
`RECGOV_FIRE_TIMEOUT` (default 30s) budgets the checks that run *before* a
hold — the session preflight and the one unattended re-login — separately from
the 180s a browser-driven cart run gets, so an ATC racing other users does not
spend the cart budget twice before it starts.

## Running it

```sh
cd companion
npm install
COMPANION_API_TOKEN=dev npm start   # http://127.0.0.1:8770
npm test                            # node --test, no browser needed
```

As a Compose service (opt-in profile, no published ports):

```sh
RECGOV_COMPANION_BROWSER_PROFILE=$HOME/.campsite-companion/browser-session \
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

## Operator CLI

These drive the single legacy profile directly (not the pool), for the
operator's own rec.gov session:

```sh
make recgov-login     # headed login; exits 0 on REC_GOV_AUTH_OK
make recgov-refresh   # forces the real refresh endpoint
make recgov-atc PAYLOAD=/tmp/recgov-atc.json   # places a REAL hold
```

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
