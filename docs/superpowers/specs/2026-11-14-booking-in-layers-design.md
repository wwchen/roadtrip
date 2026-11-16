# Booking and rec.gov settings in layers, with a relay that names its results

**Status:** approved by the owner on 2026-11-14, after they chose the approach in conversation
("2: both ends"). Calls marked **(my call)** were made while writing it; the owner approved the
document that contains them.

**Parent spec:** `2026-11-10-add-to-cart-on-vercel-design.md`, which shipped as #868 on 2026-11-14.
This spec reshapes what that one built. It adds no feature.

## Why

A review of #868 produced six fixes for edge cases. The owner's answer was that they care less
about patching edge cases than about the shape of the code: transformation in mappers, application
logic in controllers, data mutations in the repository. Their bet is that the edge cases stop
existing once the code is legible. This spec is that reshape for the booking and rec.gov settings
code, on both sides of the relay.

## Goal

Each kind of work in this slice has one home, a reader can tell which home a file is from its
path, and a script fails the build when a file does another home's work.

Success:

- No controller, model, or ports file in this slice mentions an HTTP status, a wire field name, a
  cookie, `config`, or a database handle. Section 7 lists the files.
- The relay's wire has one result type per operation, each with a required tag. No reader has to
  infer what a combination of optional fields means.
- The public HTTP contract is unchanged apart from the four deliberate changes listed under
  "Behaviour that changes".
- The existing service-level assertions on status, body, and cookie still pass, re-pointed at the
  new seam.

## Anti-goals

- A reshape that changes what a user sees, beyond the four listed changes.
- A second convention that only this slice follows forever. The rule and its guard are written so
  that the rest of `apps/web/src/server` joins by adding lines to a table.
- New machinery for its own sake: no validation library, no code generation, no new table.

## Owner's decisions (2026-11-14)

- **D1 Scope is what #868 added.** Booking, rec.gov settings, and profile settings in `apps/web`,
  plus the Kotlin relay. The older read-only services keep their shape until a later pass.
- **D2 Both ends.** The relay's wire changes, not only the TypeScript that reads it.
- **D3 Edge cases are not the target.** Where a review finding survives the reshape, it is listed
  under "Non-goals" with the reason, not patched here.
- **D4 The Next.js design principles are written where agents load them.** The owner's words:
  "design principles (nextjs specific) should be in claude.md", then "read this too" with a link
  to Next.js's guide for coding agents (`nextjs.org/docs/app/guides/ai-agents`). Section 8 gives
  the text and says which files hold it.

## Today

Read on master `65db3df8`.

**The web app (`apps/web/src/server`)**

- `service/booking/addToCartService.ts` (140 lines) and
  `service/settings/recgovCredentialService.ts` (236 lines) each do five jobs: parse the request
  body, decide, build the wire body with its HTTP status (and the MFA cookie), read `config` and
  construct their own dependencies, and call the repository and the relay.
  `service/settings/settingsService.ts` (87 lines) does the first four.
- Both services return `{ status, body, cookie? }`. `serviceResponse.ts` imports its input type
  from the service, so the dependency points upward.
- `client/bookingRelay.ts` is `relay(path, body: object)`. It checks only that the answer has a
  boolean `ok`, then casts it to `BookingRelayResponseDto`, one type with ten optional fields.
  Sixteen sites in the two services read those fields by hand. Seven sites build request bodies as
  object literals; the generated request types are exported and unused.
- `null` means "not configured" in three places: `relay`, `cipherKey`, and `config.booking`.
- `repo/userSettingsRepo.ts` returns a wire DTO. `repo/campsiteCatalogRepo.ts` decodes booking
  identity by hand into three row shapes.
- `auth/currentUser.ts` is the one place every route gets its user. `repo/appUserRepo.ts` does not
  select `app_user.status`, so a disabled account is refused nowhere.
- `service/availability/availabilityMatrixService.ts:204-211` reads the current user, the cipher
  key, and the credentials repository inline to decide the grid's add-to-cart state. With sign-in
  off it answers `signed_out`, so production today offers every anonymous reader a sign-in action
  that cannot complete.
- Four ways turn a result into a `Response` in this slice: `serviceResponse`, an inline
  `Response.json`, a route-local `apiError`, and `requireAppUser`'s 401. Two of them omit
  `cache-control`.
- The repo has no written layering rule for `apps/web`; `AGENTS.md` describes Kotlin's.
- `next` 16.4.5 writes its own instructions for coding agents into `apps/web/AGENTS.md` when
  `next dev` runs under one. `apps/web/.gitignore` has ignored that file since #831, and there is
  no `apps/web/CLAUDE.md`, so no Claude session loads it. Section 8 has the details.

**The Kotlin relay (`backend/src/main/kotlin/ca/floo/roadtrip`)**

- `service/booking/BookingRelayController.kt` gets sealed results from the companion port and
  `RecGovProfileHold` (`ProfileHoldOutcome`, `CompanionLoginResult`, `CompanionActionResult`,
  `CompanionSessionHealth`) and flattens all of them into `BookingRelayResponseDto`.
- Every answer is HTTP 200, including refusals. A timeout is 504 with the same DTO.
- `code` is an open set: Kotlin emits some and passes the companion's `error` string through for
  the rest. `category` is closed: `caller_action`, `retry_later`, `upstream`.
- The companion allows one operation per profile at a time (`companion/src/profilePool.js`
  `acquire`). A contender gets `profile_busy`, which reaches the web app as a refusal with category
  `retry_later`. Kotlin adds no lock of its own and needs none.
- mini-ca has never deployed the relay: its last successful deploy predates #868.

**The generator (`backend/.../apigen`)**

- `DescriptorWalk.kt:90-94` refuses sealed and polymorphic types on purpose: "the discriminator
  mapping is undesigned; design it before serving this type." Enums come out as string-literal
  unions. A nullable response field comes out as `field?: T`.

## Approaches

1. **TypeScript only.** Split the services into layers and decode today's envelope once. Smallest.
   The ten-optional-field envelope stays on the wire, and the decoder has to infer each variant.
2. **Both ends, results named on the wire (chosen).** Everything in 1, and the relay answers with
   one tagged result per operation. Costs a Kotlin change and a contract regeneration. Cheap now
   because nothing deployed speaks the old wire.
3. **Generate the mappers from the contract.** Emit runtime decoders from the Kotlin DTOs. Helps
   every route but touches the generator and CI for about sixty lines of hand parsing in this
   slice.

Dropped earlier: moving the decisions into Kotlin (works against leaving mini-ca), a validation
library (a second source of truth beside the contract), and a holds table in Neon (that state
belongs with the browser profile, and the companion already guards it).

## Design

### 1. The layers

| Home | Path under `apps/web/src/server` | Does | Never |
|---|---|---|---|
| Route | `src/app/api/**/route.ts` | Declares `dynamic` and `maxDuration`, calls the route wrapper | Imports a repository or a client |
| HTTP mapper | `mapper/http/` | Parses a request body into a command. Turns an outcome into status, body, and cookie | Imports a repository, a client, or a database handle |
| Relay mapper | `mapper/relay/` | Builds relay request bodies. Decodes relay results into domain results | Imports a repository |
| Row mapper | `mapper/row/` | Decodes raw SQL rows into domain values | Imports a contract DTO |
| Controller | `service/<area>/*Controller.ts` | Takes plain values and ports. Returns an outcome | Imports `contract`, `config`, `db`, `appDb`, `drizzle-orm`, `client/`, `repo/`, `mapper/`, or `next/*` |
| Model and ports | `service/<area>/model.ts`, `ports.ts` | Domain types, outcome unions, port interfaces | Imports anything but `@roadtrip/domain` and each other |
| Repository | `repo/` | Reads and writes through Drizzle. Returns domain rows | Imports `contract`, `service/*Controller`, `mapper/http`, or `client/` |
| Client | `client/` | Implements a port over HTTP, using the relay mapper | Imports a repository or a controller |
| Wiring | `wiring/` | The only place that reads `config` and builds ports | Is imported by a controller, mapper, or repository |

"Controller" is the owner's word and Kotlin's (`BookingRelayController` lives in
`service/booking`), so the files are named `*Controller.ts` and stay under `service/`
**(my call)**.

An outcome is a discriminated union on `kind`. A port is a TypeScript interface in `ports.ts`.
"Not configured" is a port implementation that answers `unavailable`, not a `null` the controller
checks.

### 2. The seam every route uses

`mapper/http/signedIn.ts` builds one wrapper from a current-user lookup, and `wiring/signedIn.ts`
gives it the live lookup. A route becomes a declaration:

```ts
export const dynamic = 'force-dynamic';
export const maxDuration = 120;
export const POST = signedIn(async ({ user, readJson }) => {
  const command = parseAddToCart(await readJson());
  if (command.kind === 'invalid') return command.answer;
  return addToCartAnswer(await addToCart(user, command.value, bookingPorts()));
});
```

The wrapper resolves the current user (401 `unauthenticated` when there is none), gives the
handler a `readJson()` that reads the JSON body when the handler asks for it, calls the handler,
and turns the returned `HttpAnswer` into a `Response` with
`cache-control: private, no-store` on every path. It replaces `serviceResponse.ts`, the inline
`Response.json` in the add-to-cart route, and the `requireAppUser` preamble in each route.

`mapper/http/httpAnswer.ts` holds `HttpAnswer<T> = { status, body, cookie? }`, the named status
constants for this slice, and the error-body builder. `STATUS_*` constants leave the two services.

### 3. Domain model (`service/booking/model.ts`, `service/settings/model.ts`)

No table changes. What is missing today is types that name states the code already has.

- `ActiveUser`: a `UserProfile` (`service/auth/model.ts`) whose `status` is `active`. `currentAppUser()` returns `null` for
  any other status, so a disabled account is signed out on every route that asks for the current
  user, inside this slice and outside it, with no new status code **(my call)**. `appUserRepo`
  selects `status`. This is behaviour change 4.
- `StayWindow { startDate, endDate }`, valid by construction (end after start).
- `Refusal { category: 'caller_action' | 'retry_later' | 'upstream'; code: string; detail?: string }`.
  `code` stays an open string because the companion's set is open.
- `GatewayFault`: `not_configured | unanswered | unreachable | unauthorized | redirected | malformed | rejected`.
- Gateway results, one per operation. Every `timed_out` carries the relay's refusal
  (`relay_timeout`, `retry_later`, and its detail):
  - `HoldResult`: `held { cartUrl }`, `blocked { refusal }`, `timed_out { refusal, cartUrl? }`,
    `unavailable { fault }`.
  - `LoginResult`: `signed_in`, `mfa_required { challengeId, expiresAt? }`, `failed { refusal }`,
    `timed_out { refusal }`, `unavailable { fault }`.
  - `ActionResult` (refresh, verify): `ok`, `failed { refusal }`, `timed_out { refusal }`,
    `unavailable { fault }`.
  - `SessionHealth`: `active`, `never_logged_in`, `inactive { code? }`, `check_failed { code?, detail? }`,
    `unavailable { code?, detail? }`. A health timeout and a gateway fault both decode to
    `unavailable`, with code `relay_timeout` for the first, as the status route reports today.
  - `SignOutResult`: `wiped { signedOut }`, `failed { signedOut, refusal }`,
    `timed_out { refusal }`, `unavailable { fault }`. As today, `unavailable { not_configured }`
    means there is no profile to wipe and the save or removal goes ahead; every other result but
    `wiped` is `recgov_profile_wipe_failed`.
- `PendingChallenge`: `open { challengeId }`, `none`, or `expired`. The HTTP mapper decodes the
  MFA cookie into it; the controller decides what each means. With no cipher key configured, the
  codec the wiring supplies opens every cookie as `none` and issues nothing, as today.
- Controller outcomes: `AddToCartOutcome`, `SaveCredentialsOutcome`, `RemoveCredentialsOutcome`,
  `LoginOutcome`, `VerifyOutcome`, `RecgovStatus`, `SettingsView`, `ProfileUpdateOutcome`,
  `AddToCartCapability`. Each variant carries only domain values. `LoginOutcome` says whether the
  MFA challenge is over (`challenge: 'keep' | 'end' | { issue: MfaChallenge }`); it does not
  mention a cookie. A missing or expired challenge is a `failed` outcome with today's codes
  (`mfa_challenge_unknown`, `mfa_challenge_expired`) and `challenge: 'end'`, not a parse error.

### 4. Ports (`service/booking/ports.ts`)

- `BookingGateway`: `hold`, `signIn`, `submitMfa`, `refresh`, `verify`, `health`, `signOut`. Each
  takes the user's id and plain values and returns the result type above. `client/bookingRelay.ts`
  implements it over HTTP; `unconfiguredBookingGateway` answers `unavailable { not_configured }`.
  The profile id (`n:<userId>`) is rendered in the relay mapper, its only home; `profileIdFor`
  leaves `auth/currentUser.ts`.
- `CredentialVault`: `status(userId)` gives `none | stored { username } | locked { username }`
  (`locked`: a row exists and no key is configured); `open(userId)` gives
  `unavailable | none | opened { username, password }`; `store`, `rename`, `remove`.
  `repo/credentialVault.ts` implements it over `bookingCredentialsRepo` and `secretCipher`. The
  controller never sees a key or ciphertext, and "has credentials" has one definition.
- `CampsiteCatalog.bookableCampsite(id)`, `StoredAvailability.takenIn(campsite, window)`, `Clock`.

### 5. Mappers

- `mapper/http/addToCartHttp.ts`, `recgovHttp.ts`, `settingsHttp.ts`: one `parse*` per request
  body and one `*Answer` per outcome type. Each `*Answer` is a `switch` on `kind` that the
  compiler checks for exhaustiveness. `recgovHttp.ts` is the only caller of
  `auth/mfaChallengeCookie.ts`: it opens the cookie into a challenge before the controller runs and
  turns `challenge: 'end' | { issue }` into a cookie change after.
- `mapper/relay/bookingRelayWire.ts`: request builders typed by the generated
  `BookingRelay*Request` types, and one decoder per result DTO. A decoder checks the tag and the
  fields that tag requires; anything else is `unavailable { malformed }`.
- `mapper/row/bookingIdentityRow.ts`: one decoder for `(booking_provider, booking_provider_ref,
  booking_aliases)`, used by the three row shapes in `campsiteCatalogRepo.ts`.
- `userSettingsRepo.notificationsFor` returns a `NotificationSettings` row; `settingsHttp.ts`
  builds `NotificationsDto` from it.
- `mapper/http/capabilityHttp.ts` turns an `AddToCartCapability` into `AddToCartCapabilityDto`.
  `service/booking/addToCartCapability.ts` stays a pure function and returns the domain value.
  `availabilityMatrixService.ts:204-211` is replaced by a call that gets the reader, the vault
  status, and whether sign-in is configured through `wiring/`, calls that function, and maps the
  result. The rest of that file is outside this spec.

"Sign-in is configured" has one definition: what `/api/me` reports as `auth_enabled`, which is
`authPort() !== null` today (`service/auth/meService.ts:12-13`). It becomes one exported function
in `auth/` that both `meService` and the capability call, so the two cannot disagree.

### 6. The relay's wire

`BookingRelayResponseDto` is deleted. Each operation answers with its own DTO whose first field
is a required enum. This is the encoding the generator supports today; extending the generator to
sealed types is left out **(my call)**, see "Non-goals".

| Route | Result DTO | Tag and its values | Other fields |
|---|---|---|---|
| `atc` | `BookingRelayHoldDto` | `outcome`: `held`, `blocked`, `timed_out` | `cart_url?`, `failure?` |
| `login`, `login/mfa` | `BookingRelayLoginDto` | `outcome`: `signed_in`, `mfa_required`, `failed`, `timed_out` | `challenge_id?`, `expires_at?`, `failure?` |
| `refresh`, `verify` | `BookingRelayActionDto` | `outcome`: `ok`, `failed`, `timed_out` | `failure?` |
| `health` | `BookingRelayHealthDto` | `session`: `active`, `never_logged_in`, `inactive`, `check_failed`, `unavailable` | `code?`, `detail?` |
| `signout` | `BookingRelaySignOutDto` | `outcome`: `wiped`, `failed`, `timed_out` | `signed_out`, `profile_destroyed` (both required), `failure?` |

`BookingRelayFailureDto { code: String, category: BookingRelayFailureCategory, detail: String? }`
is shared. `category` is a `@Serializable` enum, so TypeScript gets a closed union.

Required by tag, on every operation: `held` has `cart_url`; `blocked`, `failed`, and `timed_out`
have `failure`; `mfa_required` has `challenge_id`. A `timed_out` failure is today's
`relay_timeout`, `retry_later`, "the hold may still finish; check the cart". `timed_out` on `atc`
also keeps `cart_url`. A health timeout has no tag of its own: it answers
`session: unavailable` with `code: relay_timeout`.

Status stays as it is: 200 for an answer, 504 for a timeout, 400 and 401 as today.

Kotlin changes:

- `BookingRelayController` returns the domain results it already receives. `RelayAnswer` stays
  and becomes generic over them: `Answered(value)` or `TimedOut`. The controller builds no DTO and
  takes plain parameters, not request DTOs.
- `route/internal/BookingRelayWire.kt` (new) maps each sealed result to its DTO with an exhaustive
  `when`. `BookingRelayRoutes.kt` parses, calls, maps, and picks the status.
- `ApiContract.kt`: the seven relay rows name their own result class at 200 and 504. The row count
  does not change.
- `make api-types` regenerates `frontend/src/api/generated/api-types.ts`.

### 7. The guard

`scripts/check-server-only-imports.mjs` gains a rule table and a pass over every module under
`apps/web/src`, beside its existing client-boundary rule **(my call: extend it, not a new
script)**. A rule is `{ name, from, forbid }` or `{ name, from, only }`, where each entry is a
directory, a file, a package, or a pattern, and a rule may carry `except`: files it does not
cover yet. The table selects by directory, so a file added to a layer is covered without an edit:

- `model`: every `model.ts` and `ports.ts` in a `service/<area>/`. They import only
  `@roadtrip/domain` and each other.
- `controller`: everything under `service/booking/` and `service/settings/`, and every
  `*Controller.ts` in any `service/<area>/`. An allow-list: `@roadtrip/domain`, the files of those
  two areas, any area's `model.ts` or `ports.ts`, and the one library the date helper uses.
  `service/booking/campgroundCta.ts` is excepted; it still builds a contract type.
- `http mapper`, `relay mapper`, `row mapper`: everything under each `mapper/` directory.
- `repository` and `client`: everything under `repo/` and `client/`.
- `route`: every `route.ts` under `app/`. It may not import a repository, a client, a database
  handle or `config`. Seven routes that predate the layering are excepted by name.
- One narrow rule: `service/availability/availabilityMatrixService.ts` may not import
  `repo/bookingCredentialsRepo`.

`wiring/` has no rule: it is where the layers are joined. Nor has code under `src/server` outside
these directories. Type-only imports count as imports. Test
files are exempt. Only a file's own imports are read, not what those imports reach. A test fails
when an excepted file no longer breaks its rule, so the exception lists can only shrink.

The script also gains one text rule beside its `NEXT_PUBLIC_` one: `process.env` is read in no
file under `apps/web/src` except `server/config.ts`, test files exempt **(my call)**. It matches
`process.env` and its ordinary other spellings (bracket access, destructuring, an alias, an import
of `node:process`). It holds on master today, so it costs one pattern and no code change.

### 8. The rules agents load

The owner asked for the Next.js design principles to be in `CLAUDE.md` and pointed at Next.js's
guide for coding agents (D4). What the guide and the installed version say, read for `next`
16.4.5 from `node_modules/next/dist/docs/01-app/02-guides/ai-agents.md`, the `agentRules.md`
config page beside it, and `node_modules/next/dist/server/lib/generate-agent-files.js`:

- When `next dev` detects a coding agent it writes a managed block into `AGENTS.md` in the app's
  directory, between `<!-- BEGIN:nextjs-agent-rules -->` and `<!-- END:nextjs-agent-rules -->`.
  The block tells the agent to read the documentation that ships inside the installed `next`
  package before writing code, because the version is newer than what a model was trained on.
- Next.js rewrites only what is between the markers, keeps the rest of the file, and says to
  commit it. It writes `AGENTS.md` only, never `CLAUDE.md`.
- `agentRules: false` in `next.config.ts` turns this off and removes the block.

What this repo does with it today:

- `apps/web/.gitignore` has ignored `/AGENTS.md` since #831, and there is no `apps/web/CLAUDE.md`.
  The block exists only on a machine where `next dev` has run under an agent, and no Claude
  session loads it.
- npm hoists `next` to the repo root. The block says to resolve `node_modules/next/dist/docs/`
  from its own directory, and `apps/web/node_modules/next` does not exist. From `apps/web` the
  docs are at `../../node_modules/next/dist/docs/`.

So the Next.js rules get their own file, the one Next.js already maintains, and a `CLAUDE.md`
beside it imports it the way the root one imports the root `AGENTS.md` **(my call; it replaces an
earlier draft that put the text in the root `AGENTS.md`)**:

| File | Change |
|---|---|
| `apps/web/.gitignore` | Drop the `/AGENTS.md` line |
| `apps/web/AGENTS.md` | Tracked. In order: a line saying the root `AGENTS.md` holds the project-wide rules; where the bundled docs are in this repo; the design principles below; the managed block |
| `apps/web/CLAUDE.md` | New, one line: `@AGENTS.md` |
| Root `AGENTS.md` | One line beside its three "Before … read …" lines: before changes under `apps/web`, read `apps/web/AGENTS.md` |

Claude Code's documented behaviour is to load a subdirectory's `CLAUDE.md` when it reads files
there, so these rules arrive when an agent works in `apps/web` and stay out of a backend session.
In a session on 2026-11-14 the nested `CLAUDE.md` did load when a file under `apps/web` was read.
Whether its `@AGENTS.md` import expands on that kind of load was not established, so the root line
stays as the fallback.

The managed block is produced by the installed generator (`writeAgentFiles` in the file above, or
one `next dev` run under an agent), not typed, so `next dev` finds it current and leaves the tree
clean. `agentRules` stays at its default. When a `next` upgrade changes the block, `next dev`
rewrites it and the diff is committed with the upgrade.

The four file changes and the block land first, in pull request 1, so the agents that build the
rest load the installed version's docs. One example of why that matters: those docs say `dynamic`
is removed from route segment config when Cache Components is on
(`03-file-conventions/02-route-segment-config/index.md`). This app does not enable Cache
Components, so `export const dynamic = 'force-dynamic'` in section 2 stands, and the principle
below says "route segment config" without naming the option.

The design principles are added to `apps/web/AGENTS.md` in pull request 3, with the first code
that follows them and the guard that enforces them. Written to master before then, they would
describe directories that do not exist.

The first seven principles are this spec's. The next two hold across `apps/web` today (nothing
under `src` names a hosting platform, and the existing guard enforces the client rules) but are
written nowhere an agent loads. The last line describes the guard, and the list under it names
where the code does not match the principles yet.

```markdown
## Design principles

Paths are under `apps/web/src/server`; a route is a `route.ts` under `apps/web/src/app`. "These layers" are route, mapper, controller, repository, client and wiring, for the code the guard's rule table covers.
- **Layering is `route -> mapper -> controller -> port`.** `repo/` and `client/` implement the ports, and `wiring/` joins them. A file's directory says which kind of work it does.
- **A route file is a declaration.** It sets its route segment config, as the literals Next.js requires, and hands one function per method to a route wrapper (`wiring/signedIn.ts` for a route that needs a user). That function parses with a mapper, returns the mapper's refusal when the request does not parse, calls one controller with ports from `wiring/`, and maps the outcome. It has no other branch and imports no repository, client, database handle, or `config`. The routes under `app/api/booking` and `app/api/settings` have this shape today.
- **Transformation belongs in mappers.** `mapper/http` parses a request into a command and turns an outcome into status, body, headers, and cookie. `mapper/relay` builds requests to another service and decodes its answers. `mapper/row` decodes raw SQL rows. This app's response statuses, wire field names, and cookies appear in no other layer; a client reads an upstream status only to tell one transport fault from another.
- **Application logic belongs in controllers** (`service/<area>/*Controller.ts`). A controller takes plain values and ports and returns an outcome. It imports only `@roadtrip/domain`, files of the layered service areas, and any area's `model.ts` or `ports.ts`: never `next/*`, `config`, a database handle, `drizzle-orm`, the contract types (`contract.ts` and the generated file it re-exports), `repo/`, `client/`, `mapper/`, or `wiring/`.
- **Data reads and writes belong in repositories** (`repo/`), through Drizzle. A repository returns domain rows or its port's own result, never a contract DTO. It reports what is stored and applies no application rule.
- **An outcome is a discriminated union on `kind`** that carries domain values only. A mapper turns it into an answer with a `switch` the compiler checks for exhaustiveness. Nothing below a mapper builds this app's response. Outcomes and other domain types live in `service/<area>/model.ts` and port interfaces in `ports.ts`; both import only `@roadtrip/domain` and each other.
- **"Not configured" is a port implementation, never a `null` a controller checks.** `wiring/` reads the `config` object and builds ports. Mappers, controllers and repositories never import `config`; a client takes its settings as arguments and imports at most a constant or a type from `config.ts`. `config.ts` is the only file under `src` that reads `process.env`.
- **The app does not know where it is deployed.** The same code runs on Vercel and in the docker image. Nothing under `src` names or branches on its hosting platform; a difference between deployments is a value in `config.ts`. `next.config.ts` is the one file that reads a platform's variables, to rename them to neutral ones (`BUILD_ENV`, `BUILD_SHA`, `BUILD_BRANCH`).
- **Client components reach no server code.** A `'use client'` module, and anything it imports, never imports `src/server` or `@roadtrip/db`, and no source or env file uses a `NEXT_PUBLIC_` name. Do the server work in a server component or a route handler and pass plain props down.
- `scripts/check-server-only-imports.mjs` runs in CI and in `make test`, not in `next build`, and fails on the import rules, the `process.env` rule, and the client rules. It checks imports, not shape: it reads each file's own imports, not what they reach, and it cannot see a branch in a route, what a function returns, or which names a client takes from `config.ts`. Its rule table selects by directory: a new file under `mapper/http/`, `mapper/relay/`, `mapper/row/`, `repo/`, `client/`, `service/booking/` or `service/settings/`, a new `*Controller.ts`, `model.ts` or `ports.ts` in any `service/<area>/`, and a new `route.ts` are covered without an edit. A file in one of those places that breaks its rule is excepted by name in the table; code anywhere else under `src/server` has no rule. When either kind is touched, move it into these layers, and delete its exception if it has one, instead of extending the old shape.

Where the code does not match these rules yet:
- Only the routes under `app/api/booking` and `app/api/settings` are declarations. The other routes keep their older shape: many parse, branch and build responses themselves. The guard holds them to the route import rule and excepts seven of them from it.
- `service/booking/campgroundCta.ts` builds a contract type, and `service/availability/availabilityMatrixService.ts` imports `config`, a database handle, a mapper and `wiring/`. The second has one rule of its own in the table. The other files under `service/`, and the request parsers at the top of `src/server`, predate the layers and have no rule.
- Only the booking relay has a relay mapper. The other clients (`storedAvailability`, `availabilityEgress`, `mapbox`, `kotlinPassthrough`) handle their own wire shapes, and `kotlinPassthrough` also reads the `config` object and builds the response it passes through.
- The MFA cookie's name and sealing are in `auth/mfaChallengeCookie.ts`, which only `mapper/http` imports.
```

The guard checks imports, not what a file writes. These rest on review: that statuses, wire field
names, and cookies stay in mappers; the shape of a route; that a repository applies no application
rule; which names a client takes from `config.ts`; and that nothing names the hosting platform. The
compiler checks that a `switch` over an outcome is exhaustive wherever the return type demands a
value.

### 9. Behaviour that changes

1. **The grid answers `unsupported` when sign-in is not configured.** Today it answers
   `signed_out`. This ships alone, in pull request 2.
2. **A relay answer missing a field its tag requires is 503 `booking_unavailable`.** Today a
   success without `cart_url` is 502. Neither is reachable with a correct relay.
3. **Every answer in this slice carries `cache-control: private, no-store`,** including the 401
   and the profile route's 400, which lack it today.
4. **A disabled account is signed out.** Today nothing reads `app_user.status`. The change is in
   `currentAppUser()`, so it reaches every route that asks for the current user, not only this
   slice. It ships in pull request 3. No account is disabled today: sign-in is off in production.

A gateway fault is also logged once with its kind, so an operator can tell a wrong token from an
unreachable relay. The public answer for both stays 503 `booking_unavailable`.

Everything else the frontend reads is unchanged: the response DTOs, the error codes, the statuses,
the cookie's name, path, and attributes, and each route's `maxDuration`.

## Testing

- **The oracle is the existing assertions.** The cases in `addToCartService.test.ts`,
  `recgovCredentialService.test.ts`, and `settingsService.test.ts` that assert status, body, or
  cookie move to tests of "parse, controller, answer" with fake ports, with their expectations
  unchanged except where one of the four listed changes applies. If one needs a different
  expectation for any other reason, that is a behaviour change this spec did not list, and the
  builder stops and says so.
- **New tests only where there is new logic:** each relay decoder (every tag, plus a malformed
  answer); the disabled-account refusal; the capability with sign-in off; the guard's rule table
  and its `process.env` rule.
- **No tests for the status tables.** The compiler checks each `switch` is exhaustive.
- **Route tests:** a test that only proves a mocked value passes through a route is deleted. Tests
  that pin `maxDuration`, the cookie's attributes, the 401, and `cache-control` stay.
- **Kotlin:** `BookingRelayRoutesTest` keeps its fourteen cases; the nine that read DTO fields
  decode the new result types. `RecGovProfileHoldTest` is untouched. `contractLedgerCheck` must
  pass, which needs each relay row's 200 body produced by a route test, as today.

## Order of work

Four pull requests, each one commit, none merged by an agent.

1. `(chore): agents working in apps/web read the installed Next.js docs`. The four file changes
   in section 8 and the managed block. No code. It goes first so the agents that build the other
   three load it.
2. `(fix): the grid offers add to cart only where sign-in is on`. Change 1, as a one-condition
   change in `availabilityMatrixService.ts` plus the shared "sign-in is configured" function.
   Small and independent.
3. `(refactor): booking and rec.gov settings in layers`. Sections 1 to 5 and 7, the design
   principles of section 8, and changes 2 to 4, against today's wire. No Kotlin change. The relay
   mapper decodes the flat envelope.
4. `(refactor): the booking relay names its results`. Section 6. Kotlin, the contract (with its
   re-export list in `apps/web/src/server/contract.ts`), and `mapper/relay/bookingRelayWire.ts`
   with its test. If the layers hold, no other TypeScript file changes; that is the check on pull
   request 3.

Pull request 4 should merge before the booking variables are set on Vercel. Until then the feature
is off and a mismatch between Vercel and mini-ca reaches nobody.

## Non-goals

- **The rest of `apps/web/src/server`.** Twenty-one other files define their own status constants
  and several services return wire shapes. They join the rule later.
- **Account linking rules in `appUserRepo.linkedAppUser`.** They are decisions living in a
  repository, from #859, and moving them needs a unit-of-work port. Later pass.
- **Sealed types in the generator.** The right long-term encoding, refused on purpose today. It
  deserves its own design.
- **`cart_url` on the public timeout answer.** The domain result now carries it to the HTTP
  mapper, but the public error body has no such field. Adding one is a contract change for both
  the Kotlin and TypeScript servers and a frontend change.
- **An Origin check on signed-in writes.** A missing control, not a shape problem. Its home would
  be the wrapper in section 2. It adds a status to nine contract rows, so it is its own change.
- **A key id on stored ciphertext, frontend copy for `booking_unavailable`, `relay_timeout`, and
  `invalid_window`, preview database cleanup, and the order of turning the feature on.** Review
  findings that are data format, frontend, or operations.
- **The frontend.** It does not change.
- **The rest of what Next.js's guide offers.** Agent feedback (`experimental.agentFeedback`, off
  by default; it has an agent draft reports about the project for Next.js), the `vercel/next.js`
  Skills, and the dev server's MCP endpoint. None is needed for this change, and the first is the
  owner's switch to flip.

## Risks

- **A behaviour drifts unnoticed.** The services have about forty assertions and the frontend has
  copy keyed on about thirty codes. The oracle rule in "Testing" is the control.
- **The guard is too blunt.** Counting type-only imports may force a type to move. That is the
  intent; if it forces something awkward, the builder reports it and does not weaken the rule.
- **Two conventions coexist** in `apps/web/src/server` until the later pass.
- **Pull request 3 is large.** About a thousand lines move. It is one commit because the pieces do
  not compile apart.
- **The managed block drifts.** A `next` upgrade can change its text, and the first `next dev`
  under an agent then leaves `apps/web/AGENTS.md` modified. Nothing in CI checks it; the diff is
  committed with the upgrade.

## Amended while planning (2026-11-14)

Found while writing `docs/superpowers/plans/2026-11-14-booking-in-layers.md`, after the owner
approved this document. Each keeps today's behaviour where the text above would have changed it,
or names something the text left out. None adds a behaviour change.

1. `GatewayFault` gains `unanswered`: the web app's own wait for the relay ran out. The list
   above had no home for today's client-side timeout.
2. `CredentialVault.status` has a third state, `locked`. Today a rename works on a row whose key
   is missing while settings read "not configured"; two states cannot say both.
3. `SessionHealth.check_failed` carries `detail?`, as the status route reports today.
4. `/api/me` answers anonymous for a disabled account. It does not go through `currentAppUser()`,
   so without this it would say "signed in" while every route says 401. This is part of change 4.
5. `USER_THEMES`, `UserTheme`, `USER_STATUSES` and `UserStatus` move to `@roadtrip/domain`, and
   `@roadtrip/db` re-exports them, because a model file may import only `@roadtrip/domain`.
6. `HttpAnswer.cookie` is the rendered `Set-Cookie` value, so `recgovHttp.ts` stays the only
   caller of the cookie module.
7. There is no `ProfileUpdateOutcome`. A bad profile body is a parse failure in the mapper.
8. On today's wire a refusal with no `category` reads as `upstream`, as the status table does
   today. Only a refusal with no `code` is malformed.

Found while building, after the plan was written:

9. A missing or expired MFA cookie is its own `LoginOutcome`, `challenge_gone { reason }`, where
   section 3 says it is a `failed` outcome. The relay can refuse with the same two codes, and
   today only the web app's own finding carries the detail text. With one variant the mapper
   could not tell them apart and added the detail to both.
10. Two of section 8's principles were reworded after checking them against the finished code,
    because as first written they were not true of it. "HTTP statuses … appear in no other layer"
    now says "this app's response statuses", since a client has to read an upstream status to
    classify a transport fault. "`wiring/` is the only place that reads `config`" now says "among
    these layers" and "the `config` object", since `auth/` and code outside the rule table read it,
    and a client imports a timeout constant and a type from `config.ts`. After the whole-branch
    review, two more: the opening line now says what "these layers" are, and "nothing below a
    mapper returns `{ status, body }`" now says "builds this app's response", since the relay
    client returns the upstream's status and body. Section 8's block above carries the corrected
    wording.
11. The settings ports (`RecgovPorts`, `SettingsPorts`) live in `service/settings/ports.ts`, as the
    principles say ports do. The plan first put them in `service/settings/model.ts`.
12. The decision "sign-in off, signed out, or has credentials" is a tested function,
    `readerStanding(ports)`, beside the capability function. Section 5 put it in `wiring/`, where
    it would have been logic in a folder that holds only construction.
13. `liveGateway()` and `liveVault()` live in `wiring/bookingAdapters.ts`, so no wiring file
    imports a service that imports wiring back.

## Amended after review (2026-11-14)

Found by an adversarial review of the four pull requests, after they were opened.

14. The route wrapper takes the current-user lookup as an argument. `mapper/http/signedIn.ts`
    builds the wrapper and `wiring/signedIn.ts` gives it the live lookup, so the HTTP mapper no
    longer reaches the app database through `auth/`. The wrapper also reads the body only when the
    handler calls `readJson()`: as first built it read and parsed a body on every request, where
    the routes with no body had never read one.
15. `liveReaderStanding()` opens the credential vault only when a signed-in reader's status is
    asked for. As first built it opened the app database before asking whether sign-in was
    configured. With sign-in off that threw, and the matrix reached `unsupported` through its
    error handler with a warning on every request, not through `readerStanding`. A malformed
    encryption key also hid the sign-in prompt from a signed-out reader.
16. The guard selects by directory and lists exceptions by name, where it first named the files it
    covered: a new controller, repository, client or route was unguarded. The controller rule is
    an allow-list, where its forbid list had missed `@roadtrip/db` and the generated contract file.
    The route rule covers every route and forbids a database handle and `config`. The HTTP mapper
    may not import a session lookup. The `process.env` rule matches the other ordinary spellings.
    Section 7 above is rewritten to match.
17. Four of section 8's principles said things the code contradicted (a route "decides nothing", a
    repository "decides nothing", who reads `config`, and that the guard "fails the build"). They
    now say what the code does and what the guard checks, and a list under them says where the
    code does not match yet and which code has no rule.
18. The relay reader accepts a tagged body only at a success status or 504, the statuses the relay
    answers with. A tagged body at any other status reads as `rejected`. Section 6 left the status
    unchecked once a body carried a tag, as the old reader had.
19. The two relay formats do not overlap and carry no version, so the two sides must be on the same
    one whenever booking is on. `docs/web-app.md` says so beside the variables that switch it on.

## Left to the builder

File and function names beyond those given here; how the fakes for the ports are written; whether
`signedIn` takes the handler alone or an options object for routes with no body; the wording of
the two lines at the top of `apps/web/AGENTS.md` and of the root pointer. The wording of the
design principles is not left to the builder: it is section 8's text.
