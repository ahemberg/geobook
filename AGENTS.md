# Geobook

Geobook is a self-hosted Spring Boot and React app that keeps one owner's GPS tracks and Google location history and shows them on a map.

- Build and test everything with `./mvnw verify`. It needs JDK 27; Maven installs Node.
- In a new worktree, run `npm ci` in the frontend first. Never symlink `node_modules`: the build's `npm ci` deletes the link target.
- Before pushing, run the Gitleaks secret scan with `python3 scripts/gitleaks.py`. It needs Docker.
- Never commit real location data unless the owner has marked it shareable.

## Read when relevant

- [Building and testing](docs/agents/building-and-testing.md): checks, formatters, browser and container tests, upgrades.
- [Backend](docs/agents/backend.md): package layers, SQL, Flyway migrations.
- [Frontend](docs/agents/frontend.md): before any UI change.
- [Importing external data](docs/agents/importing.md): before changing GPX or Takeout import.
- [Deploying to pinas](docs/development.md#deploy-to-pinas): when asked to deploy.

## Agent skills

- Issue tracker: GitHub Issues for `ahemberg/geobook`. Read [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md) before ticket work.
- Triage labels: the five defaults. Read [docs/agents/triage-labels.md](docs/agents/triage-labels.md) before triaging.
- Domain docs: single-context layout. Read [docs/agents/domain.md](docs/agents/domain.md) before exploring the codebase.
- Review standards: review diffs against [docs/agents/review-standards.md](docs/agents/review-standards.md), the standards source for `/code-review`.
