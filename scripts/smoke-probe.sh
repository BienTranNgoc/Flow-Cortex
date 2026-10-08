#!/usr/bin/env bash
#
# Compose smoke for the media engine. Against a running stack started with the
# `dev` profile (it seeds member1, admin1 and the "Check a video file" workflow):
#
#   SPRING_PROFILES_ACTIVE=dev docker compose up -d --wait
#   scripts/smoke-probe.sh
#
# Checks, in order:
#   - a wrong byte count on the presigned PUT is refused with 403 (the
#     content-length is signed);
#   - a 1-second clip, generated inside the worker image, uploaded from this host
#     and checked by "Check a video file", ends `completed` with videoCodec h264;
#   - its technical details download answers 200 as an attachment named
#     video-details.json;
#   - the admin Runs list shows that run;
#   - a text file uploaded as .mp4 ends with the step error `probe_failed`.
# Needs no internet. Exits non-zero on the first miss.
#
# The upload URL is signed for the host the browser reaches (localhost:9000),
# so the PUT and the download run here, not inside a container.
# `docker compose` honours COMPOSE_FILE, COMPOSE_ENV_FILES and COMPOSE_PROJECT_NAME
# as usual. Env: API_URL (default http://localhost:8080), POLL_SECONDS (default 120).
# Needs bash, curl, jq and docker.

set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
POLL_SECONDS="${POLL_SECONDS:-120}"
WORKFLOW_NAME="Check a video file"

cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*" >&2; exit 1; }

login() {
  local email=$1 password=$2
  curl -sS --fail-with-body -H 'Content-Type: application/json' \
    -d "$(jq -n --arg e "$email" --arg p "$password" '{email: $e, password: $p}')" \
    "$API_URL/api/v1/auth/login" | jq -er .token
}

# GET or POST (with a JSON body) as the given token; prints the response body.
api() {
  local token=$1 path=$2 body=${3:-}
  if [[ -n "$body" ]]; then
    curl -sS --fail-with-body -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body" "$API_URL$path"
  else
    curl -sS --fail-with-body -H "Authorization: Bearer $token" "$API_URL$path"
  fi
}

# Requests an upload grant for a file name and a declared size; prints the grant.
grant() {
  local name=$1 size=$2
  api "$member" /api/v1/uploads "$(jq -n --arg n "$name" --argjson s "$size" '{fileName: $n, size: $s}')"
}

# PUTs a file to a grant's URL; prints the HTTP status.
put() {
  local file=$1 url=$2
  curl -sS -o "$work/put-body" -w '%{http_code}' -T "$file" -H "Content-Type: video/mp4" "$url"
}

# Uploads a file and starts the workflow on it; prints the run id.
start_run() {
  local file=$1 name=$2 g code
  g="$(grant "$name" "$(stat -c %s "$file")")" || fail "upload grant for $name"
  code="$(put "$file" "$(jq -er .url <<<"$g")")"
  [[ "$code" == 200 ]] || fail "PUT $name answered $code: $(cat "$work/put-body")"
  api "$member" "/api/v1/workflows/$workflow/runs" \
    "$(jq -n --arg u "$(jq -er .uploadId <<<"$g")" --arg n "$name" '{uploadId: $u, originalName: $n}')" \
    | jq -er .id
}

# Polls a run every 2 s until it leaves `running`; prints the run.
await_run() {
  local run=$1 deadline=$((SECONDS + POLL_SECONDS)) body
  while :; do
    body="$(api "$member" "/api/v1/runs/$run")" || fail "GET run $run"
    if [[ "$(jq -r .status <<<"$body")" != running ]]; then
      echo "$body"
      return
    fi
    ((SECONDS < deadline)) || fail "run $run still running after ${POLL_SECONDS} s: $(jq -c '.steps[0] | {status, pickedUpAt, errorCode}' <<<"$body")"
    sleep 2
  done
}

member="$(login member1@flowcortex.dev 'MemberPass123!')" || fail "member1 login (is the dev profile on?)"
admin="$(login admin1@flowcortex.dev 'AdminPass123!')" || fail "admin1 login"
workflow="$(api "$member" /api/v1/workflows \
  | jq -er --arg n "$WORKFLOW_NAME" 'first(.[] | select(.name == $n) | .id)')" \
  || fail "workflow \"$WORKFLOW_NAME\" not listed for member1"
pass "member1 signed in; \"$WORKFLOW_NAME\" is $workflow"

docker compose exec -T worker ffmpeg -y -v error \
  -f lavfi -i testsrc=duration=1:size=320x240:rate=25 -f lavfi -i sine=duration=1 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest /tmp/clip.mp4 \
  || fail "clip generation in the worker container"
docker compose cp worker:/tmp/clip.mp4 "$work/clip.mp4" >"$work/cp.log" 2>&1 \
  || { cat "$work/cp.log" >&2; fail "copy the clip out of the worker"; }
pass "clip generated in the worker image ($(stat -c %s "$work/clip.mp4") bytes)"

g="$(grant short.mp4 "$(($(stat -c %s "$work/clip.mp4") + 1))")" || fail "upload grant for the size check"
code="$(put "$work/clip.mp4" "$(jq -er .url <<<"$g")")"
[[ "$code" == 403 ]] || fail "PUT with a wrong byte count answered $code, expected 403"
pass "PUT with a wrong byte count refused (403)"

run="$(start_run "$work/clip.mp4" clip.mp4)" || fail "start a run on the clip"
body="$(await_run "$run")"
probe_step="$(jq -c '.steps[0]' <<<"$body")"
audio_step="$(jq -c '.steps[1]' <<<"$body")"

[[ "$(jq -r .status <<<"$body")" == completed ]] \
  || fail "clip run $run did not complete: $(jq -c '{status, errorMessage}' <<<"$body")"
[[ "$(jq -r .status <<<"$probe_step")" == completed ]] \
  || fail "probe step did not complete: $probe_step"
[[ "$(jq -r .status <<<"$audio_step")" == completed ]] \
  || fail "extract audio step did not complete: $audio_step"

codec="$(jq -r '.output | if type == "string" then fromjson else . end | .videoCodec' <<<"$probe_step")"
[[ "$codec" == h264 ]] || fail "clip run $run: videoCodec is '$codec', expected h264"
pass "clip run $run completed, videoCodec h264"

url="$(api "$member" "/api/v1/runs/$run/steps/1/outputs/video-details.json/url" | jq -er .url)" \
  || fail "download URL for video-details.json"
code="$(curl -sS -D "$work/headers" -o "$work/video-details.json" -w '%{http_code}' "$url")"
[[ "$code" == 200 ]] || fail "technical details download answered $code"
disposition="$(tr -d '\r' <"$work/headers" | awk 'tolower($1) == "content-disposition:" { sub(/^[^:]*: */, ""); print }')"
[[ "$disposition" == 'attachment; filename="video-details.json"' ]] \
  || fail "technical details content-disposition is '$disposition'"
jq -e . "$work/video-details.json" >/dev/null || fail "video-details.json is not JSON"
pass "technical details downloaded (200, $disposition)"

url="$(api "$member" "/api/v1/runs/$run/steps/2/outputs/source-audio.wav/url" | jq -er .url)" \
  || fail "download URL for source-audio.wav"
code="$(curl -sS -D "$work/audio-headers" -o "$work/source-audio.wav" -w '%{http_code}' "$url")"
[[ "$code" == 200 ]] || fail "audio download answered $code"

docker compose cp "$work/source-audio.wav" worker:/tmp/source-audio.wav >/dev/null
audio="$(docker compose exec -T worker ffprobe -v error -select_streams a:0 \
  -show_entries stream=codec_name,sample_rate,channels -of json /tmp/source-audio.wav)"
jq -e '.streams[0] | .codec_name == "pcm_s16le"
  and .sample_rate == "16000"
  and .channels == 1' <<<"$audio" >/dev/null \
  || fail "source-audio.wav is not mono 16 kHz pcm_s16le: $audio"
pass "source audio downloaded and verified"

api "$admin" "/api/v1/admin/runs?size=100" | jq -e --arg r "$run" 'any(.items[]; .id == $r)' >/dev/null \
  || fail "admin Runs list does not show run $run"
pass "admin Runs list shows run $run"

printf 'not a video' >"$work/bad.mp4"
run="$(start_run "$work/bad.mp4" bad.mp4)" || fail "start a run on the text file"
body="$(await_run "$run")"
error_code="$(jq -r '.steps[0].errorCode' <<<"$body")"
audio_status="$(jq -r '.steps[1].status' <<<"$body")"
[[ "$(jq -r .status <<<"$body")" == error && "$error_code" == probe_failed && "$audio_status" == pending ]] \
  || fail "text-file run $run ended $(jq -c '{status, step1: .steps[0] | {status, errorCode}, step2: .steps[1] | {status}}' <<<"$body"), expected error/probe_failed and pending step 2"
pass "text-file run $run ended with probe_failed"

echo "smoke-probe: all checks passed"
