# Agent Skills

This project uses [Agent Skills](https://agentskills.io/home) — a standardized format for extending AI agent capabilities with specialized knowledge and workflows.

Skills live in `.claude/skills/` and are automatically discovered by Claude Code and other compatible agents.

## How they work

1. **Discovery** — At startup, agents load only the `name` and `description` from each skill's frontmatter.
2. **Activation** — When a task matches a skill's description, the agent reads the full `SKILL.md` into context.
3. **Execution** — The agent follows the instructions, optionally running bundled scripts or loading referenced files.

## Directory structure

```
.claude/skills/
└── <skill-name>/
    ├── SKILL.md          # Required: YAML frontmatter + instructions
    ├── scripts/          # Optional: executable code
    ├── references/       # Optional: documentation
    └── assets/           # Optional: templates, resources
```

## Installed plugins (external skill packs)

Installed via `claude plugin install` from the [grafana/skills](https://github.com/grafana/skills) marketplace:

| Plugin | Scope | Skills included |
|--------|-------|-----------------|
| `grafana-core` | project | dashboarding, promql, alerting-irm, alloy, grafana-oss, beyla, opentelemetry |
| `grafana-lgtm` | project | loki, tempo, prometheus, mimir, pyroscope |

These activate automatically when working on Grafana dashboards, PromQL queries, Loki log queries, or alerting config.

To install additional Grafana skill packs:

```bash
claude plugin install grafana-cloud@grafana-skills --scope project
claude plugin install grafana-k6@grafana-skills --scope project
```

## Project-local skills

| Skill | Purpose |
|-------|---------|
| `probe-vendor-api` | Reverse-engineer a campsite booking vendor's HTTP API using headed browser network capture. Use when adding a new BookingProvider adapter or investigating endpoint shapes. |

## Creating a new skill

1. Create a directory under `.claude/skills/` with a kebab-case name.
2. Add a `SKILL.md` file with YAML frontmatter:

```markdown
---
name: my-skill-name
description: What it does and when to use it. Include keywords that help agents match tasks to this skill.
---

# my-skill-name

Instructions for the agent...
```

3. Keep `SKILL.md` under 500 lines. Move detailed references to separate files.
4. The `name` must match the directory name exactly (lowercase, hyphens only, no leading/trailing hyphens).

## Frontmatter fields

| Field | Required | Notes |
|-------|----------|-------|
| `name` | Yes | 1-64 chars, lowercase + hyphens, must match directory name |
| `description` | Yes | 1-1024 chars, describes what + when |
| `license` | No | License name or reference |
| `compatibility` | No | Environment requirements |
| `metadata` | No | Arbitrary key-value pairs |
| `allowed-tools` | No | Pre-approved tools (experimental) |

## Writing good descriptions

The description is the only thing agents see before activation. Make it count:

- State what the skill does (capabilities)
- State when to use it (trigger conditions)
- Include specific keywords agents will match on

## References

- [Agent Skills specification](https://agentskills.io/specification)
- [Best practices for skill creators](https://agentskills.io/skill-creation/best-practices.md)
- [GitHub: agentskills/agentskills](https://github.com/agentskills/agentskills)
