# Issue tracker: GitHub

Issues and specs live in GitHub Issues for `ahemberg/geobook`.
Use the `gh` CLI from this repo. It infers the repository from the remote.

## Operations

- Create an issue: `gh issue create --title "..." --body-file <file>`.
- Read an issue: `gh issue view <number> --json number,title,body,labels,comments`.
- List issues: `gh issue list --state open --json number,title,body,labels,comments`. Add label filters as needed.
- Comment: `gh issue comment <number> --body-file <file>`.
- Apply labels: `gh issue edit <number> --add-label "..."`.
- Remove labels: `gh issue edit <number> --remove-label "..."`.
- Close: `gh issue close <number> --comment "..."`.

Write multiline issue bodies and comments to a temporary file, preserving actual newlines, and pass it with `--body-file`.

When a skill says "publish to the issue tracker", create a GitHub issue.
When it says "fetch the relevant ticket", read the issue and its comments.

## Pull requests as a triage surface

PRs as a request surface: no.

GitHub shares issue and PR numbers. For an ambiguous reference, try
`gh pr view <number>` first, then `gh issue view <number>`.

## Wayfinding operations

For `/wayfinder`, the map is one issue and its tickets are child issues.

- Map: label the issue `wayfinder:map`. Its body holds Notes, Decisions-so-far, and Fog.
- Child ticket: link it as a GitHub sub-issue. If sub-issues are unavailable, add it to the map's task list and put `Part of #<map>` at the top of its body. Use `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, or `wayfinder:task`.
- Blocking: use GitHub's native issue dependencies. Add an edge with `gh api --method POST repos/ahemberg/geobook/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`. Fetch the blocker's database ID with `gh api repos/ahemberg/geobook/issues/<number> --jq .id`. If dependencies are unavailable, put `Blocked by: #<number>` at the top of the child body.
- Frontier: consider open children in map order. Skip assigned tickets and tickets with open blockers. Native dependencies expose the count in `issue_dependencies_summary.blocked_by`.
- Claim: `gh issue edit <number> --add-assignee @me`.
- Resolve: comment with the answer, close the ticket, then add a brief finding and link to the map's Decisions-so-far.
