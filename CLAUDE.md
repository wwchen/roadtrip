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

Backend layering rules:
- Prefer typed Kotlin/Java DTOs (`@Serializable` data classes or existing schema classes) for request/response bodies. Do not hand-build JSON strings in routes when a DTO can represent the shape.
- SQL, jOOQ DSL queries, table references, and persistence mapping belong in `repo` classes only. Routes and services call repo methods rather than embedding SQL.
- Routes are the HTTP shell: parse inputs, call services/repos through established boundaries, set status codes, and return DTOs.
- Keep business logic out of routes; put orchestration in `service` and persistence in `repo`.
