## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec

## Project architecture rules

Before backend architecture, route, service, repo, or model changes, read `docs/backend-architecture.md`.

Before changes that touch campsite availability, alerts, or any booking-provider integration (rec.gov, Aspira, Camis, future vendors), read `docs/booking-providers.md`.

Backend layering rules:
- Prefer typed Kotlin/Java DTOs (`@Serializable` data classes or existing schema classes) for request/response bodies. Do not hand-build JSON strings in routes when a DTO can represent the shape.
- SQL, jOOQ DSL queries, table references, and persistence mapping belong in `repo` classes only. Routes and services call repo methods rather than embedding SQL.
- Routes are the HTTP shell: parse inputs, call services/repos through established boundaries, set status codes, and return DTOs.
- Keep business logic out of routes; put orchestration in `service` and persistence in `repo`.

Design principles (apply to all code, all layers):
- **No inline magic constants.** Numeric, string, and duration literals at call sites are a smell. Extract to named `const val` (or env-driven config when the value is operationally tunable). Cadences, limits, timeouts, retry counts, default page sizes — all named.
- **Config-driven over hardcoded** when the value might reasonably differ across environments, customers, or scaling regimes. Wire through env vars / YAML registry / DB columns rather than recompiling. Default in code; override in config.
- **Layered abstractions, not flat ones.** Routes don't reach into repos; services don't construct HTTP responses; clients don't know about persistence. If a layer would have to import "downward" to do its job, the abstraction is wrong — re-shape the seam.
- **No leaky abstractions.** A port (e.g. `BookingProvider`) hides upstream-specific shape from its callers. Adapters do not surface vendor types through the interface; provider-specific richness stays inside the adapter or in well-defined extension points.
- **Reusable components.** Before adding a new helper, check whether an existing one fits. Before duplicating a `when` block over a sealed type, ask whether the dispatch should become a registry. Three similar code sites are usually one missing abstraction.
- **No half-finished implementations.** If a method exists, it works. Stubs that throw `UnsupportedOperationException` are acceptable only as explicit capability gates (e.g. an `AutoBooker` adapter that doesn't yet support a vendor); they are not an excuse for "I'll fill this in later."
