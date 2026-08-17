# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, labels match role names: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Branch policy

Version development and maintenance happen on branches; `main` only receives *preliminarily polished versions*. Do not commit feature work directly to `main`.

- `main` — updated only when a version is preliminarily complete (merge from a version/maintenance branch). No direct development.
- Version branch (e.g. `v1.1`) — forked from `main`, carries a version's new development, merged back to `main` when polished.
- Maintenance branch (e.g. `v1.0`) — forked from `main`, carries bug fixes for a shipped version; may merge back to `main` when stable.
- Bug fixes for a shipped version go to its maintenance branch (and `main` only via merge after polish), never directly to `main`.
