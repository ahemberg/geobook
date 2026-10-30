# Domain docs

This repo uses a single-context layout.

## Before exploring

Read root `CONTEXT.md` and any decisions in `docs/adr/` relevant to the work.

If those files do not exist, proceed silently. Domain-modeling creates
them when terms or decisions are resolved.

## Layout

- `CONTEXT.md`: domain glossary and model for the repo.
- `docs/adr/`: architecture decision records.

## Vocabulary and decisions

Use the glossary's terms in issue titles, proposals, hypotheses, and test names.
If a needed concept is missing, reconsider the term or note the gap for domain-modeling.

If a proposal contradicts an ADR, identify the ADR and explain why
the decision should be reconsidered.

## Location history imports

Before finishing any Takeout or history import change, run `scripts/takeout-smoke.sh [archive]`.
It imports a real Google Takeout archive, `google-timeline.tar.gz` at the repo root by default, and skips when the file is missing.
Never commit the archive or any other real location data.

## Worktrees

In a new worktree, run `npm ci` in `src/main/frontend`.
Never symlink `node_modules` from another checkout. The Maven build runs `npm ci`, which deletes the contents of the link target.
