# apps/web

Project-wide rules are in the repo root `AGENTS.md`. This file adds what is specific to the Next.js app.

`next` is hoisted to the repo root in this workspace. The bundled documentation the block below points to is at `../../node_modules/next/dist/docs/` from this directory, not in a `node_modules/` beside this file.

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
- **A cookie-authenticated write comes from this app's own pages.** The route wrapper in `wiring/signedIn.ts`, and the Neon Auth passthrough, refuse any method but GET, HEAD and OPTIONS with 403 `cross_origin` unless the browser sends `Sec-Fetch-Site: same-origin` or the `Origin` host equals `X-Forwarded-Host` or `Host`. `jsonBody` reads only a body declared `application/json`, so a plain HTML form never reaches a handler. A new route that needs a user gets both by using the wrapper.
- **Client components reach no server code.** A `'use client'` module, and anything it imports, never imports `src/server` or `@roadtrip/db`, and no source or env file uses a `NEXT_PUBLIC_` name. Do the server work in a server component or a route handler and pass plain props down.
- `scripts/check-server-only-imports.mjs` runs in CI and in `make test`, not in `next build`, and fails on the import rules, the `process.env` rule, and the client rules. It checks imports, not shape: it reads each file's own imports, not what they reach, and it cannot see a branch in a route, what a function returns, or which names a client takes from `config.ts`. Its rule table selects by directory: a new file under `mapper/http/`, `mapper/relay/`, `mapper/row/`, `repo/`, `client/`, `service/booking/` or `service/settings/`, a new `*Controller.ts`, `model.ts` or `ports.ts` in any `service/<area>/`, and a new `route.ts` are covered without an edit. A file in one of those places that breaks its rule is excepted by name in the table; code anywhere else under `src/server` has no rule. When either kind is touched, move it into these layers, and delete its exception if it has one, instead of extending the old shape.

Where the code does not match these rules yet:
- Only the routes under `app/api/booking` and `app/api/settings` are declarations. The other routes keep their older shape: many parse, branch and build responses themselves. The guard holds them to the route import rule and excepts seven of them from it.
- `service/booking/campgroundCta.ts` builds a contract type, and `service/availability/availabilityMatrixService.ts` imports `config`, a database handle, a mapper and `wiring/`. The second has one rule of its own in the table. The other files under `service/`, and the request parsers at the top of `src/server`, predate the layers and have no rule.
- Only the booking relay has a relay mapper. The other clients (`storedAvailability`, `availabilityEgress`, `mapbox`, `kotlinPassthrough`) handle their own wire shapes, and `kotlinPassthrough` also reads the `config` object and builds the response it passes through.
- The MFA cookie's name and sealing are in `auth/mfaChallengeCookie.ts`, which only `mapper/http` imports.

<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
