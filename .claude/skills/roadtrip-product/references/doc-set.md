# The Roadtrip doc set

Eight living documents. The published artifact is canonical; the HTML in
`docs/design-references/` is a **snapshot** kept in the repo so this skill, CI and
anyone without artifact access can read it offline.

**Do not edit the snapshots.** Edits made here are lost on the next refresh. Change the
artifact, then re-run the refresh (below).

## Milestones

| Doc | Snapshot | Canonical |
|---|---|---|
| Vision — the point of view | `docs/design-references/roadtrip-vision.html` | [artifact](https://claude.ai/code/artifact/064368ac-3804-499d-8816-c913e2fabf04) |
| M0 — Reskin | `docs/design-references/roadtrip-m0-reskin.html` | [artifact](https://claude.ai/code/artifact/f65da110-5a18-4154-bf04-2e8bd1c2a194) |
| M1 — The park surface | `docs/design-references/roadtrip-m1-park-surface.html` | [artifact](https://claude.ai/code/artifact/d173de8a-1332-456a-8438-3d3e7caa8031) |
| M2 — Assistant & MCP | `docs/design-references/roadtrip-m2-assistant.html` | [artifact](https://claude.ai/code/artifact/8833520f-385d-4caa-bbff-b640aa1cdc47) |

## Research

| Doc | Snapshot | Canonical |
|---|---|---|
| Personas — who we build for | `docs/design-references/roadtrip-personas.html` | [artifact](https://claude.ai/code/artifact/9ff9ab0e-2bdd-46f5-b3fa-bed1c15448b2) |
| Market & rivals | `docs/design-references/roadtrip-user-research.html` | [artifact](https://claude.ai/code/artifact/c2e87639-7b74-4f7d-96de-3ca5e942f63f) |
| Crazy 8s — the date window | `docs/design-references/roadtrip-crazy-eights-date-window.html` | [artifact](https://claude.ai/code/artifact/2740e8f3-a408-437c-95b3-be0652083eff) |
| Build timeline | `docs/design-references/roadtrip-build-timeline.html` | [artifact](https://claude.ai/code/artifact/4c8a0e5f-45b2-426c-8348-90339764f310) |

Also snapshotted: `roadtrip-slack-notifications.html`.

## Deep links worth knowing

The Personas doc carries stable anchors, so cite the claim rather than the page:

| Anchor | What's there |
|---|---|
| `#changed` | What changed since the Vision, and the one finding that reorders the roadmap |
| `#key` / `#tail` | The three we build for / the two we design around |
| `#weekender` `#beginner` `#organizer` `#quartermaster` `#loyalist` | Individual personas |
| `#stacks` | Every app each persona uses across six trip stages, and the pattern |
| `#groups` | Why families and youth orgs break every planning tool |
| `#reach` | Who this product will not reach, and which barriers we can actually move |
| `#sources` | Every load-bearing statistic graded A/B/C, with the two that were withdrawn |

The Vision carries `#problem`, `#bet`, `#values`, `#pillars`, `#research`, `#influence`,
`#users`, `#scope`.

## Refreshing the snapshots

The artifacts are edited on claude.ai and the repo copies go stale silently. Before
relying on a snapshot for planning work, re-read the artifact and overwrite the local
file. In Claude Code: `Artifact` with `action: "read"` saves the full HTML, then copy it
over the snapshot.

**Check the date before trusting a snapshot.** If it is more than a few weeks old and the
question is load-bearing, refresh it first or say plainly that you are reading a
possibly-stale copy.
