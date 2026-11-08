#!/usr/bin/env bash
#
# Tests for scripts/check-migration-version.sh. Each case builds a throwaway
# git repo in a temp dir, runs the guard inside it and checks the verdict.
# Usage: bash scripts/check-migration-version.test.sh

set -uo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-migration-version.sh"
MIG_DIR="backend/boot/app/src/main/resources/db/migration"
LATEST_FILE="V2026.10.18.100__email_link_purpose_password_reset.sql"

# A hosted runner has no global git identity.
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

pass=0
fail=0
failed_cases=()
OUT=""
CODE=0
n=0

new_repo() {
  n=$((n + 1))
  REPO="$TMP_ROOT/case$n"
  mkdir -p "$REPO"
  git -C "$REPO" init -q -b development
  git -C "$REPO" config diff.renames true
  mkdir -p "$REPO/$MIG_DIR"
}

# write_mig <repo> <file>: distinct non-empty content so renames pair up.
write_mig() {
  printf 'select %s; -- %s\n' "$RANDOM$RANDOM" "$2" > "$1/$MIG_DIR/$2"
}

commit_all() {
  git -C "$1" add -A
  git -C "$1" commit -q -m "$2"
}

# base_repo: development holds the latest migration plus an older one; the
# base ref is published as origin/development, then HEAD moves to branch pr.
base_repo() {
  new_repo
  write_mig "$REPO" "V2026.10.11.001__product_video.sql"
  write_mig "$REPO" "$LATEST_FILE"
  commit_all "$REPO" base
  git -C "$REPO" update-ref refs/remotes/origin/development HEAD
  git -C "$REPO" checkout -q -b pr
}

run_guard() {
  OUT="$(cd "$1" && bash "$SCRIPT" "${2:-origin/development}" 2>&1)"
  CODE=$?
}

check() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = 1 ]; then
    pass=$((pass + 1))
    echo "PASS  $name"
  else
    fail=$((fail + 1))
    failed_cases+=("$name")
    echo "FAIL  $name"
    [ -n "$detail" ] && printf '      %s\n' "$detail"
    printf '%s\n' "$OUT" | sed 's/^/      | /'
  fi
}

has() { grep -qF -- "$1" <<<"$OUT"; }

expect_refused() { # name file [extra substring ...]
  local name="$1" file="$2" ok=1
  shift 2
  [ "$CODE" -eq 1 ] || ok=0
  has "$file" || ok=0
  has "no new migrations" && ok=0
  has "nothing to compare" && ok=0
  local s
  for s in "$@"; do has "$s" || ok=0; done
  check "$name" "$ok" "exit=$CODE, expected 1 naming $file"
}

# --- Version equal to the latest is refused
base_repo
write_mig "$REPO" "V2026.10.18.100__other_change.sql"
commit_all "$REPO" add
run_guard "$REPO"
expect_refused "Version equal to the latest is refused" "V2026.10.18.100__other_change.sql" "V2026.10.18.100"

# --- Version older than the latest is refused
base_repo
write_mig "$REPO" "V2026.10.18.050__other_change.sql"
commit_all "$REPO" add
run_guard "$REPO"
expect_refused "Version older than the latest is refused" "V2026.10.18.050__other_change.sql" "V2026.10.18.100"

# --- Version newer than the latest is accepted
base_repo
write_mig "$REPO" "V2026.10.19.001__other_change.sql"
commit_all "$REPO" add
run_guard "$REPO"
ok=1; [ "$CODE" -eq 0 ] || ok=0
check "Version newer than the latest is accepted" "$ok" "exit=$CODE, expected 0"

# --- No new migration passes
base_repo
printf 'x\n' > "$REPO/README.md"
commit_all "$REPO" add
run_guard "$REPO"
ok=1; [ "$CODE" -eq 0 ] || ok=0; has "no new migrations" || ok=0
check "No new migration passes" "$ok" "exit=$CODE, expected 0 with 'no new migrations'"

# --- Rename to an older version is refused
base_repo
git -C "$REPO" mv "$MIG_DIR/V2026.10.11.001__product_video.sql" "$MIG_DIR/V2026.10.05.001__product_video.sql"
commit_all "$REPO" rename
run_guard "$REPO"
expect_refused "Rename to an older version is refused" "V2026.10.05.001__product_video.sql"

# --- Depth-1 checkout refuses an older migration
# Built the way actions/checkout does, then the base fetched by explicit
# refspec, so no merge base exists.
base_repo
write_mig "$REPO" "V2026.10.05.009__older.sql"
commit_all "$REPO" add
git -C "$REPO" config uploadpack.allowAnySHA1InWant true
head_sha="$(git -C "$REPO" rev-parse HEAD)"
CI_DIR="$TMP_ROOT/ci-checkout"
mkdir -p "$CI_DIR"
git -C "$CI_DIR" init -q -b development
git -C "$CI_DIR" remote add origin "file://$REPO"
git -C "$CI_DIR" fetch -q --no-tags --depth=1 origin "$head_sha"
git -C "$CI_DIR" checkout -q --detach FETCH_HEAD
git -C "$CI_DIR" fetch -q --no-tags --depth=1 origin +refs/heads/development:refs/remotes/origin/development
git -C "$CI_DIR" config diff.renames true
run_guard "$CI_DIR"
expect_refused "Depth-1 checkout refuses an older migration" "V2026.10.05.009__older.sql"
ok=1; has "not found" && ok=0
check "Depth-1 checkout reaches a verdict (base ref present)" "$ok" "guard reported a missing base ref"

# --- Missing base ref fails closed
base_repo
run_guard "$REPO" origin/does-not-exist
ok=1; [ "$CODE" -eq 1 ] || ok=0; has "git fetch" || ok=0
check "Missing base ref fails closed" "$ok" "exit=$CODE, expected 1 telling to fetch the base"

# --- Unreadable base tree fails closed
base_repo
write_mig "$REPO" "V2026.10.19.001__other_change.sql"
commit_all "$REPO" add
tree="$(git -C "$REPO" rev-parse "origin/development^{tree}")"
rm -f "$REPO/.git/objects/${tree:0:2}/${tree:2}"
run_guard "$REPO"
ok=1; [ "$CODE" -ne 0 ] || ok=0
has "no new migrations" && ok=0
has "nothing to compare" && ok=0
check "Unreadable base tree fails closed" "$ok" "exit=$CODE, expected non-zero without a success message"

# --- Duplicate version in HEAD tree is refused
base_repo
write_mig "$REPO" "V2026.11.08.001__a.sql"
write_mig "$REPO" "V2026.11.08.001__b.sql"
commit_all "$REPO" add
run_guard "$REPO"
expect_refused "Duplicate version in HEAD tree is refused" "V2026.11.08.001__a.sql" "V2026.11.08.001" "V2026.11.08.001__b.sql"

# --- Added migration in non-ADR format is refused
base_repo
write_mig "$REPO" "V2026_10_05_001__x.sql"
commit_all "$REPO" add
run_guard "$REPO"
expect_refused "Added migration in non-ADR format is refused" "V2026_10_05_001__x.sql" "V<yyyy.MM.dd>.NNN"

echo
echo "passed: $pass  failed: $fail"
if [ "$fail" -ne 0 ]; then
  printf 'failed cases:\n'
  printf '  - %s\n' "${failed_cases[@]}"
  exit 1
fi
