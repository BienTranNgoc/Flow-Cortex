#!/usr/bin/env bash
#
# Block any migration whose version is not strictly newer than the latest
# version already on origin/development. Catches the "copied from another
# branch and forgot to bump" failure mode: Flyway refuses to start when a file
# claims a version at or before one the DB has already applied.
#
# Usage: check-migration-version.sh [<base-ref>]
#   base-ref defaults to origin/development
# Exit:  0 = every added migration is > latest on base and HEAD has no duplicate version
#        1 = an added migration is <= latest, is not named V<yyyy.MM.dd>.NNN__*.sql,
#            two migrations in HEAD share a version, or git could not be read
#
# An added migration is a file present in HEAD and absent from base, compared
# tree against tree (no merge base needed, so a depth-1 checkout works); a
# rename is checked as an add under its new name.

set -euo pipefail

BASE_REF="${1:-origin/development}"
MIG_DIR="backend/boot/app/src/main/resources/db/migration"
VERSION_RE='V[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]{3}'

# A missing base must never silently pass.
if ! git rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null; then
  echo "✗ check-migration-version: base ref '$BASE_REF' not found locally" >&2
  echo "  Run 'git fetch --no-tags --depth=1 origin +refs/heads/development:refs/remotes/origin/development' first, or pass a different base." >&2
  exit 1
fi

# git failures are surfaced; `|| true` stays only on greps, where status 1 means "no match".
if ! base_files=$(git ls-tree -r --name-only "$BASE_REF" -- "$MIG_DIR"); then
  echo "✗ check-migration-version: cannot read the migrations on $BASE_REF" >&2
  exit 1
fi
if ! head_files=$(git ls-tree -r --name-only HEAD -- "$MIG_DIR"); then
  echo "✗ check-migration-version: cannot read the migrations on HEAD" >&2
  exit 1
fi
if ! changed=$(git diff --no-renames --name-only --diff-filter=A "$BASE_REF" HEAD -- "$MIG_DIR"); then
  echo "✗ check-migration-version: cannot diff $BASE_REF against HEAD" >&2
  exit 1
fi

latest=$(printf '%s\n' "$base_files" | grep -oE "/${VERSION_RE}__[^/]+\.sql$" | grep -oE "^/${VERSION_RE}" | tr -d '/' | sort -uV | tail -1 || true)

bad=0

# No two migrations in HEAD may share a version, added by this change or not.
dups=$(printf '%s\n' "$head_files" | grep -oE "[^/]+$" | grep -oE "^${VERSION_RE}__" | sed 's/__$//' | sort | uniq -d || true)
for d in $dups; do
  echo "✗ check-migration-version: version $d is used by more than one migration:" >&2
  printf '%s\n' "$head_files" | grep -F "/${d}__" | sed 's/^/    /' >&2 || true
  bad=1
done

added=$(printf '%s\n' "$changed" | grep -E '/V[^/]*__[^/]*\.sql$' || true)

while IFS= read -r f; do
  [ -z "$f" ] && continue
  name=${f##*/}
  v=$(printf '%s' "$name" | grep -oE "^${VERSION_RE}__" | sed 's/__$//' || true)
  if [ -z "$v" ]; then
    echo "✗ check-migration-version: $f" >&2
    echo "    name does not match V<yyyy.MM.dd>.NNN__<description>.sql (ADR-0004)." >&2
    bad=1
    continue
  fi
  [ -z "$latest" ] && continue

  # sort -V: oldest first. v is ok only when it sorts strictly after latest.
  if [ "$v" = "$latest" ] || [ "$(printf '%s\n%s\n' "$latest" "$v" | sort -V | tail -1)" != "$v" ]; then
    echo "✗ check-migration-version: $f" >&2
    echo "    version $v is not newer than the latest on $BASE_REF ($latest)" >&2
    echo "    Fix: rename the file to V<yyyy.MM.dd>.NNN with the next free NNN, strictly greater than $latest." >&2
    echo "    Flyway refuses to start against a DB that has already applied $latest. See AGENTS.md §15." >&2
    bad=1
  fi
done <<< "$added"

if [ "$bad" -ne 0 ]; then
  exit 1
fi

if [ -z "$added" ]; then
  echo "✓ check-migration-version: no new migrations vs $BASE_REF (latest = ${latest:-none})"
  exit 0
fi

echo "✓ check-migration-version: all $(printf '%s\n' "$added" | wc -l | tr -d ' ') new migration(s) > ${latest:-none}"
exit 0
