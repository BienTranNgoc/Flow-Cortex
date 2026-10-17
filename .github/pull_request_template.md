<!--
Title: Conventional Commits, e.g. `feat(auth): add password reset` — it becomes the squash
commit subject on development. GitHub pre-fills it from the branch name; replace it.
-->

## What & why

## How to test

## Notes for reviewers

## Checklist

<!--
Keep this list short — every unchecked box is friction. Only items the CI cannot
catch belong here. The Flyway-version row is the one exception: the script in
`scripts/check-migration-version.sh` runs in CI, but the author should still
self-attest, because the CI failure mode is the file already on disk with the
wrong name, not a style problem.
-->

- [ ] If this PR adds or changes a SQL migration, its version
      (`VYYYY.MM.DD.HHMM__…`) is **strictly newer** than the latest version
      already on `origin/development` (AGENTS.md §15).
