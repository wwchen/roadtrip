## Project architecture rules

This file is the single source of truth for project-wide engineering rules
for any AI agent working in this repo (Claude Code, Codex, or otherwise).
Tool-specific instructions (e.g. Claude Code skill routing) live in
`CLAUDE.md`, which points back here for everything below.

Before backend architecture, route, service, repo, or model changes, read `docs/backend-architecture.md`.

Before frontend component, page, or design-system changes, read `docs/frontend-components.md`.

Before changes that touch campsite availability, alerts, or any reservation-provider integration (rec.gov, Aspira, Camis, future vendors), read `docs/reservation-providers.md`.

Backend layering rules:
- Prefer typed Kotlin/Java DTOs (`@Serializable` data classes or existing schema classes) for request/response bodies. Do not hand-build JSON strings in routes when a DTO can represent the shape.
- SQL, jOOQ DSL queries, table references, and persistence mapping belong in `repo` classes only. Routes and services call repo methods rather than embedding SQL.
- Layering is `routes -> service -> repo`: routes are the HTTP shell (parse inputs, call a service/controller, set status codes, return DTOs) and do not add new route-to-repo paths. When an existing route-to-repo path is touched, move it behind a service/controller instead of expanding it.
- Keep business logic out of routes; put orchestration in `service` and persistence in `repo`.

Design principles (apply to all code, all layers):
- **No inline magic constants.** Numeric, string, and duration literals at call sites are a smell. Extract to named `const val` (or env-driven config when the value is operationally tunable). Cadences, limits, timeouts, retry counts, default page sizes — all named.
- **Config-driven over hardcoded** when the value might reasonably differ across environments, customers, or scaling regimes. Wire through env vars / YAML registry / DB columns rather than recompiling. Default in code; override in config.
- **Layered abstractions, not flat ones.** Routes don't reach into repos; services don't construct HTTP responses; clients don't know about persistence. If a layer would have to import "downward" to do its job, the abstraction is wrong — re-shape the seam.
- **No leaky abstractions.** A port (e.g. `ReservationProvider`) hides upstream-specific shape from its callers. Adapters do not surface vendor types through the interface; provider-specific richness stays inside the adapter or in well-defined extension points.
- **Reusable components and CSS.** Always prefer existing shared components, layout primitives, and shared CSS before adding page-specific markup or styles. Before adding a new helper, check whether an existing one fits. Before duplicating a `when` block over a sealed type, ask whether the dispatch should become a registry. Three similar code sites are usually one missing abstraction.
- **No half-finished implementations.** If a method exists, it works. Stubs that throw `UnsupportedOperationException` are acceptable only as explicit capability gates (e.g. an adapter method whose provider capability is explicitly unsupported); they are not an excuse for "I'll fill this in later."
