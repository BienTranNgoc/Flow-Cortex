#!/usr/bin/env bash
#
# Block any migration whose version is older than the latest version already
# on origin/development. Catches the "copied from another branch and forgot to
# bump" failure mode where a developer renames the file description but leaves
# the date stamp, and Flyway then refuses to start because the file claims to
# have been authored before migrations the DB has already applied.
#
# Usage: check-migration-version.sh [<base-ref>]
#   base-ref defaults to origin/development
# Exit:  0 = all added migrations have a version >= latest on base
#        1 = at least one added migration is older than the latest on base
#
# Wired into CI; safe to run locally too (uses the same git tree you are on).

set -euo pipefail

BASE_REF="${1:-origin/development}"
MIG_DIR="backend/boot/app/src/main/resources/db/migration"
VERSION_RE='V[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+'

# If the base ref doesn't exist locally (shallow clone, no fetch yet), we
# cannot compare — fail closed so a missing base never silently passes.
if ! git rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null; then
  echo "✗ check-migration-version: base ref '$BASE_REF' not found locally" >&2
  echo "  Run 'git fetch origin development' first, or pass a different base." >&2
  exit 1
fi

# Latest version already on the base branch.
latest=$(git ls-tree -r --name-only "$BASE_REF" -- "$MIG_DIR" 2>/dev/null \
  | grep -oE "$VERSION_RE" \
  | sort -uV | tail -1 || true)

if [ -z "$latest" ]; then
  echo "✓ check-migration-version: no migrations on $BASE_REF — nothing to compare"
  exit 0
fi

# Files added in this branch (compared to base) under the migration dir.
# Renames show up as a delete + add, so we look at --diff-filter=A.
added=$(git diff --name-only --diff-filter=A "$BASE_REF"...HEAD -- "$MIG_DIR" \
  | grep -E "/${VERSION_RE}__[^/]+\.sql$" || true)

if [ -z "$added" ]; then
  echo "✓ check-migration-version: no new migrations vs $BASE_REF (latest = $latest)"
  exit 0
fi

bad=0
while IFS= read -r f; do
  v=$(printf '%s' "$f" | grep -oE "$VERSION_RE" || true)
  [ -z "$v" ] && continue

  # sort -V: oldest first. The higher of {latest, v} must equal v for v to be ok.
  higher=$(printf '%s\n%s\n' "$latest" "$v" | sort -V | tail -1)
  if [ "$higher" != "$v" ]; then
    echo "✗ check-migration-version: $f" >&2
    echo "    version $v is older than latest on $BASE_REF ($latest)" >&2
    echo "    Fix: rename the file to a version >= $latest (use today's date+time)." >&2
    bad=1
  fi
done <<< "$added"

if [ "$bad" -ne 0 ]; then
  echo "" >&2
  echo "  Flyway will refuse to start against a DB that has already applied" >&2
  echo "  $latest if this branch ships a $v. See AGENTS.md §15." >&2
  exit 1
fi

echo "✓ check-migration-version: all $(echo "$added" | wc -l | tr -d ' ') new migration(s) >= $latest"
exit 0
