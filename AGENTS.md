# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, labels match role names: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Branch model

`main` is the current release line and MUST stay shippable at all times.

- `main` — release line for the shipped version (currently 1.0.x; becomes 1.1.x when `v1.1` merges). Only bug fixes, version bumps, and release prep land here. NEVER develop features directly on `main`.
- `v1.1` — feature line for the next minor version (timeshift playback, channel liveness probing, channel-switch stats). All feature work happens here. When complete and tested, merge into `main` with a merge commit (never squash), then bump to 1.1.0 and tag.
- `v1.0` — maintenance line for released 1.0.x. NOT a standing branch: while `main` is still 1.0.x, `v1.0` is identical to `main` and must not be duplicated. Only if 1.0.x still needs patches after `main` moves to 1.1.x, recreate it with `git branch v1.0 <newest 1.0.x tag>`, commit hotfixes, tag `v1.0.x`, then merge forward into `main`.
- Tags — every release is tagged; tags are the anchor for recreating maintenance branches.

Sync rule: merge `main` into `v1.1` before starting feature work, and periodically while features are in flight, so the feature line never drifts behind the release line.
