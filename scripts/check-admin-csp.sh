#!/usr/bin/env bash
#
# Checks the admin console's Content-Security-Policy lets the browser reach the object store's
# public origin (uploads, thumbnails, previews) and nothing else beyond 'self'.
#
#   scripts/check-admin-csp.sh                      # image mode: runs the image with and without MEDIA_ORIGIN
#   ADMIN_URL=http://localhost:5174 EXPECTED_MEDIA_ORIGIN=http://localhost:9000 \
#     scripts/check-admin-csp.sh                    # live mode: checks a running console
#
# Image mode needs docker and the image (ADMIN_IMAGE, default flow-cortex-admin:local). Live mode
# needs only curl; pass the backend's S3_PUBLIC_ENDPOINT as EXPECTED_MEDIA_ORIGIN so a drift
# between the two is caught. Exits non-zero on the first miss.

set -euo pipefail

IMAGE="${ADMIN_IMAGE:-flow-cortex-admin:local}"
container=""
trap '[ -z "$container" ] || docker rm -f "$container" >/dev/null 2>&1 || true' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# The Content-Security-Policy header of GET $1, whitespace-normalised so an empty origin
# ("'self' ;") reads like the absent one ("'self';").
csp_of() {
  curl -fsS -D - -o /dev/null "$1" \
    | tr -d '\r' \
    | awk -F': ' 'tolower($1) == "content-security-policy" { print $2 }' \
    | sed -E 's/ +;/;/g; s/ +/ /g'
}

# $1 url, $2 origin ("" = none expected)
expect_origin() {
  local csp directive want
  csp="$(csp_of "$1")"
  [ -n "$csp" ] || fail "$1 sent no Content-Security-Policy"
  case "$csp" in *'$'* | *'{'*) fail "unrendered placeholder in: $csp" ;; esac
  for directive in img-src media-src connect-src; do
    want="$directive 'self'${2:+ $2};"
    case "$csp" in *"$want"*) ;; *) fail "expected \"$want\" in: $csp" ;; esac
  done
  case "$csp" in *"default-src 'self';"*) ;; *) fail "default-src is no longer 'self' alone: $csp" ;; esac
  case "$csp" in *"frame-ancestors 'none'"*"object-src 'none'"*"base-uri 'none'"*) ;; *) fail "hardening directives lost: $csp" ;; esac
  echo "ok: $1 media origin '${2:-none}'"
}

if [ -n "${ADMIN_URL:-}" ]; then
  [ -n "${EXPECTED_MEDIA_ORIGIN:-}" ] || fail "live mode needs EXPECTED_MEDIA_ORIGIN"
  expect_origin "$ADMIN_URL/" "$EXPECTED_MEDIA_ORIGIN"
  exit 0
fi

# $1 origin to pass as MEDIA_ORIGIN ("" = leave it unset). The API upstream is resolved when
# nginx starts, so it points at the container itself.
run_image() {
  local port
  if [ -n "$1" ]; then
    container="$(docker run -d --add-host backend:127.0.0.1 -e "MEDIA_ORIGIN=$1" -p 127.0.0.1::8080 "$IMAGE")"
  else
    container="$(docker run -d --add-host backend:127.0.0.1 -p 127.0.0.1::8080 "$IMAGE")"
  fi
  port="$(docker port "$container" 8080/tcp | head -n1 | sed 's/.*://')"
  for _ in $(seq 1 30); do
    curl -fsS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null && break
    sleep 0.5
  done
  expect_origin "http://127.0.0.1:$port/" "$1"
  docker rm -f "$container" >/dev/null
  container=""
}

run_image "https://media.example.test"
run_image "http://localhost:9000"
run_image ""
