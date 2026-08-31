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

Every route requires the shared secret `COMPANION_API_TOKEN` in the
`x-companion-token` header — `GET /`, `/docs`, `/openapi.json` and
`/screenshot` included. The only exemption is `GET /health` from loopback,
which is what the Compose healthcheck uses and which cannot hold a secret.

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

Because the header is required on `GET /` too, the operator page is not
directly loadable in a plain browser tab. Fetch it with the header
(`curl -H "x-companion-token: …" http://127.0.0.1:8770/`) or use a
header-injecting client; the page then carries a token field of its own, and
every control it drives sends the header and the profile id.

## Browser-profile pool

`profile_id` — the roadtrip user id, an opaque string here — is **required**
on `POST /login`, `POST /logout`, `POST /refresh`, `POST /atc`,
`POST /verify` and `GET /screenshot`. There is no shared fallback profile:
a request without it is rejected with `400 profile_id_required`.

- Each profile id maps to its own persistent Chromium user-data directory at
  `<browser-session volume>/profiles/<profile_id>`.
- Playwright persistent contexts are one browser process per directory, so
  residency is a real memory cost. `COMPANION_MAX_CONCURRENT_BROWSERS`
  (default 3) caps on-demand launches; the least recently used idle profile is
  evicted to make room, and a launch fails with `browser_cap_reached` only
  when every resident profile is busy.
- **Keep-warm (armed) profiles are exempt from the cap.** These are the
  profiles backing at least one active `atc` watch. The backend pushes the
  armed set; the companion defaults to none. When armed profiles alone exceed
  the cap, the companion logs and `GET /health` reports
  `pool.keep_warm_overflow` rather than evicting an armed profile.
- **Per-profile busy lock:** no two mutating operations run concurrently on
  one profile (`409 profile_busy`). Different profiles never block each other.
  **Health is lock-free** — the settings status row must answer while a login
  is mid-flight.

## Routes

`GET /openapi.json` and `/docs` are generated from `src/apiContract.js` and
are authoritative; this is the shape.

| route | purpose |
| --- | --- |
| `GET /` | Operator page (profile id + token fields, login, ATC, screenshot). |
| `GET /health` | Companion health. With `?profile_id=` it reports that profile's `recgov_auth`, busy flag and pool residency. Lock-free; tokenless from loopback only. |
| `POST /login` | Two-phase credential login for one profile. |
| `POST /logout` | Click through the rec.gov logout flow in one profile. |
| `POST /refresh` | Force a session refresh for one profile (the keepalive path). |
| `POST /verify` | Dry-run session check. Never places a hold. |
| `POST /atc` | One-shot add-to-cart in one profile. |
| `GET /screenshot` | Live PNG of a recreation.gov page from one profile. |
| `GET /screenshot/diagnostics/{file}` | Stored login/ATC diagnostic PNG. |

### Two-phase MFA

1. `POST /login` with `profile_id`, `username`, `password`. If rec.gov prompts
   for a code, the response is `401` with `error: "mfa_required"`, a
   `challenge_id` and an `expires_at`. The challenge **holds the profile's
   busy lock** until it is completed or expires; the TTL is minutes-scale
   (`COMPANION_MFA_CHALLENGE_TTL_MS`, default 5 minutes) because rec.gov
   delivers codes by email or SMS.
2. `POST /login` with `profile_id`, `challenge_id` and `mfa_code` completes
   it. An unknown id is `mfa_challenge_unknown`; a lapsed one is
   `mfa_challenge_expired`, and the lock is released.

Credentials are held in memory for the life of the attempt (and of a pending
challenge) only. The companion persists no passwords — only Chromium profile
state. The backend is the credential custodian.

### Failed-login backoff

A credential login that does not end logged in arms a per-profile in-memory
marker; the next login inside `COMPANION_FAILED_LOGIN_BACKOFF_MS` (default
60s) is refused with `429 login_backoff` and a `retry_after_ms`. This guards
against rec.gov lockouts from rapid repeats.

### `POST /verify` (dry run)

Loads the rec.gov account page in the profile and reads
`GET /api/cart/shoppingcart` from page context. That exercises the session,
the fingerprint cookie and Akamai without needing a campsite target. It
**never clicks Reserve**, so no test ever costs a real cart hold. `200` means
the session is live; `401` carries `verify.error`
(`recgov_not_authenticated` or `recgov_cart_unreachable`).

## Configuration

| variable | default | meaning |
| --- | --- | --- |
| `COMPANION_API_TOKEN` / `COMPANION_API_TOKEN_FILE` | — / `/run/secrets/companion_api_token` | Shared secret; unset fails closed. |
| `COMPANION_HOST` / `COMPANION_PORT` | `0.0.0.0` / `8770` | Listen address. |
| `COMPANION_BROWSER_PROFILE` | `$HOME/.campsite-companion/browser-session` | Root of the profile pool. |
| `COMPANION_MAX_CONCURRENT_BROWSERS` | `3` | Cap on on-demand resident browsers. |
| `COMPANION_MFA_CHALLENGE_TTL_MS` | `300000` | Pending MFA challenge lifetime. |
| `COMPANION_FAILED_LOGIN_BACKOFF_MS` | `60000` | Suppression window after a failed login. |
| `HEADLESS` | true in Docker | Headed Chromium for operator login. |
| `RECGOV_LOGIN_TIMEOUT_MS` | `120000` | Manual-login wait. |

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
