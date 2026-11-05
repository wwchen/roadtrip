# Verification Environment (Step 5) Plan

**Goal:** Opening a PR yields a Vercel preview URL serving `apps/web` from its own Neon branch.

**Status, 2026-11-04: met by the Neon Postgres integration on the Vercel project, not by code in this repo.** The seed pipeline this plan first described was built, run end to end, and then dropped. What remains is below under "Open".

**Spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` §8.1, §10 step 5. Neon facts from `docs/superpowers/2026-10-28-nextjs-read-app-rulings.md` Task 5.

## How previews work now

The Neon integration (installed 2026-11-04 on project `roadtrip-web`) creates a Neon branch `preview/<git branch>` from the default branch `main` for each preview deployment, and sets that branch's `DATABASE_URL` and `DATABASE_URL_UNPOOLED` as branch-scoped Preview environment variables before the build. It also created `vercel-dev` for the Development environment and marked `main` as Vercel's production branch, which blocks deleting it. Vercel's own git-triggered previews build every branch; there is no `apps/web/vercel.json`.

The trade this accepts, decided 2026-11-04: previews read a copy of `main`'s full catalog through the integration's role, rather than a small, fixed, read-only seed. It is simpler and needs no workflow, secrets or release artifacts in this repo.

## What was tried and removed (wwchen/roadtrip#837, closed)

Measured before it was dropped, so a later attempt starts from numbers rather than guesses:

- **Seed builder.** `make seed` drove the step-4 differential with `--emit-seed` over `BC Provincial Parks` and `BC Aspira Resources`: 2 m 38 s, 0 differences, 143 campgrounds, 143 pois, 10,636 campsites. Custom dump 1.6 MB, 44 MB restored (46 MB on Neon, PostGIS 3.3.10, loaded cleanly from a 3.4 dump).
- **Restore.** Emptying only `public` breaks a second restore (`schema "drizzle" already exists`); the restore has to drop every schema the restoring role owns. The `roadtrip_web` grants do not survive the drop and must be reapplied.
- **Branch per PR.** A workflow that branched `pr-<n>` from `seed` and ran `vercel deploy --env DATABASE_URL=…` worked on #837: the preview read the seed as `roadtrip_web`. Two Vercel constraints found on the way: a CLI deploy from the repository root uploads the whole checkout (22,464 files, over the 15,000 limit), and a `git.deploymentEnabled` rule cancels the remote build of a CLI deploy whose git metadata names a disabled branch. `vercel build` on the runner plus `deploy --prebuilt` avoids both.
- **Why it went.** With the integration installed, each PR got two branches (ours and the integration's), which on the free plan's 10 leaves room for about three open PRs, and nothing else consumed the seed.

`--emit-seed` in `scripts/importer-differential.mjs` (on master since #832) is unaffected, so a seed can be built again if a later step needs one.

## Decided 2026-11-04

- **D1. Role.** Previews connect as the integration's default role, the branch owner, not `roadtrip_web`. A preview can write to its own branch, which cannot reach `main`. The cost: a preview no longer exercises the read-only, SELECT-only path production uses, so a missing grant on `roadtrip_web` is caught in production, not in a preview.
- **D2. Cleanup.** The integration is connected through GitHub and deletes a preview's Neon branch when its git branch goes. Free plan: 10 branches; `main` and `vercel-dev` leave 8 for previews.
- **D3. Preview check: yes.** When Vercel reports a preview deployment ready, a workflow searches a small bbox around a fixed campground through `/api/campgrounds/search`, opens that campground's page, and requires both to answer 200 with its name. It reads through the protection-bypass header, so it needs Vercel's Protection Bypass for Automation enabled and its secret stored as the repository secret `VERCEL_AUTOMATION_BYPASS_SECRET`. The probe is Mahood Lake (`bcparks-strapi`, -120.0902, 52.1979), the only campground of that name in `main`; it is a name and a place, not a database id, because ids are not stable across reloads of `main`.
- **D4. Pinned captures** move to whichever of steps 6 and 9 first needs them, pinned from that step's own target list.
