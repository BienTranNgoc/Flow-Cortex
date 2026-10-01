#!/usr/bin/env bash
#
# End-to-end telemetry check for the observability overlay. Run it after one
# "Check a video file" run (upload, run, download) with the overlay up, passing
# the X-Trace-Id of the POST /api/v1/workflows/{id}/runs response:
#
#   scripts/check-telemetry.sh <trace-id>
#
# Asserts that:
#   - the trace store holds that trace as one chain across flow-cortex and
#     flow-worker: HTTP request > job publication > worker processing (with the
#     media-inspection child) > result handling > a state write under it;
#   - the log store returns lines carrying that trace id from both services;
#   - the metrics store scrapes backend, rabbitmq, minio and postgres-exporter, all up;
#   - neither the trace store nor the log store holds X-Amz-Signature,
#     X-Amz-Credential or X-Amz-Security-Token (any case) anywhere in the last
#     SINCE_MINUTES (default 60), and the scanned traces include the given trace and
#     the upload-grant and outputs-URL requests, the two that sign URLs.
# Exits non-zero on any miss.
#
# The scan reads what the stores' search returns for that window: data older than
# the window, or stamped with a time outside it, is not read.
#
# Tempo and Loki are not published, so they are queried from inside the grafana
# container, which must provide curl (grafana/grafana does). Needs bash, python3,
# GNU date and docker on the host. Env: COMPOSE_PROJECT_NAME (default flow-cortex),
# PROMETHEUS_URL (default http://127.0.0.1:9090), SINCE_MINUTES, WAIT_SECONDS
# (default 90), TOTAL_TIMEOUT_SECONDS (default 600; the whole check fails after it).

set -uo pipefail

TRACE_ID="${1:-}"
if [[ ! "$TRACE_ID" =~ ^[0-9a-f]{32}$ ]]; then
  echo "usage: $0 <32-hex trace id from the X-Trace-Id header>" >&2
  exit 2
fi

if [[ -z "${CHECK_TELEMETRY_BOUNDED:-}" ]]; then
  CHECK_TELEMETRY_BOUNDED=1 timeout "${TOTAL_TIMEOUT_SECONDS:-600}" "$0" "$@"
  status=$?
  if (( status == 124 )); then
    echo "check-telemetry: gave up after ${TOTAL_TIMEOUT_SECONDS:-600} s"
  fi
  exit "$status"
fi

PROJECT="${COMPOSE_PROJECT_NAME:-flow-cortex}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://127.0.0.1:9090}"
SINCE_MINUTES="${SINCE_MINUTES:-60}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"
SERVICES=(flow-cortex flow-worker)
TARGET_JOBS=(backend rabbitmq minio postgres-exporter)

failures=0
pass() { echo "PASS  $*"; return 0; }
fail() { echo "FAIL  $*"; failures=$((failures + 1)); return 0; }

grafana_container="$(docker ps -q \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --filter "label=com.docker.compose.service=grafana" | head -n1)"

# GET a URL on the overlay network (tempo/loki are expose-only).
inside() {
  docker exec "$grafana_container" curl -sS --fail-with-body --max-time 20 "$@"
}

now_s=$(date +%s)
start_s=$((now_s - SINCE_MINUTES * 60))

# ---- trace store ---------------------------------------------------------------
check_trace() {
  local body summary
  body="$(inside "http://tempo:3200/api/v2/traces/$TRACE_ID")" || return 1
  summary="$(printf '%s' "$body" | python3 -c '
import json, sys
doc = json.load(sys.stdin)
trace = doc.get("trace", doc)
spans = {}
for rs in trace.get("resourceSpans", []):
    svc = next((a["value"].get("stringValue") for a in rs.get("resource", {}).get("attributes", [])
                if a["key"] == "service.name"), "?")
    for ss in rs.get("scopeSpans", []):
        for span in ss.get("spans", []):
            span["service"] = svc
            span["attrs"] = {a["key"] for a in span.get("attributes", [])}
            span["text"] = " ".join(str(a["value"].get("stringValue", "")) for a in span.get("attributes", [])).lower()
            spans[span.get("spanId")] = span

def find(svc, kind=None, name=None, attr=None, under=None, text=None):
    for s in spans.values():
        if s["service"] != svc or (kind and s.get("kind") != kind):
            continue
        if (name and name not in s.get("name", "")) or (attr and attr not in s["attrs"]):
            continue
        if text and text not in s["text"]:
            continue
        if under is not None:
            parent = spans.get(s.get("parentSpanId"))
            while parent is not None and parent is not under:
                parent = spans.get(parent.get("parentSpanId"))
            if parent is None:
                continue
        return s
    return None

server = find("flow-cortex", "SPAN_KIND_SERVER")
producer = find("flow-cortex", "SPAN_KIND_PRODUCER", under=server) if server else None
job = find("flow-worker", "SPAN_KIND_CONSUMER", under=producer) if producer else None
inspection = find("flow-worker", attr="process.executable.name", under=job) if job else None
result = find("flow-cortex", "SPAN_KIND_CONSUMER", name="media.results", under=job) if job else None
write = find("flow-cortex", "SPAN_KIND_CLIENT", under=result, text="update run_step") if result else None
chain = [("HTTP request", server), ("job publication", producer), ("worker processing", job),
         ("media inspection", inspection), ("result handling", result), ("step state write", write)]
missing = [label for label, span in chain if span is None]
counts = {}
for s in spans.values():
    counts[s["service"]] = counts.get(s["service"], 0) + 1
summary = " ".join(f"{svc}={n}" for svc, n in sorted(counts.items()))
if missing:
    print("MISSING " + ", ".join(missing) + " | " + summary)
else:
    print("OK " + " > ".join(s["service"] + ":" + s["name"] for _, s in chain) + " | " + summary)
')" || return 1
  echo "$summary"
  [[ "$summary" == OK* ]]
}

# ---- log store -----------------------------------------------------------------
check_logs() {
  local body
  body="$(inside -G "http://loki:3100/loki/api/v1/query_range" \
    --data-urlencode "query={service_name=~\"flow-cortex|flow-worker\"} | trace_id=\"$TRACE_ID\"" \
    --data-urlencode "start=${start_s}000000000" \
    --data-urlencode "limit=5000")" || return 1
  printf '%s' "$body" | python3 -c '
import json, sys
counts = {}
for stream in json.load(sys.stdin)["data"]["result"]:
    svc = stream["stream"].get("service_name", "?")
    counts[svc] = counts.get(svc, 0) + len(stream["values"])
print(" ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "none")
sys.exit(0 if all(counts.get(s, 0) > 0 for s in ("flow-cortex", "flow-worker")) else 1)
'
}

# ---- secrets in either store -----------------------------------------------------
# Every trace in the window is fetched in full and every log record (line, labels
# and structured metadata) is read back; a match anywhere counts, redacted or not.
# Both stores cap a result, so the window is read in slices, and a slice that
# reaches the cap fails the check instead of scanning part of it.
SLICE_SECONDS=300
TRACE_LIMIT=1000
LOG_LIMIT=5000
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

count_secret_hits_in_traces() {
  local from to ids
  : > "$scratch/ids"
  for ((from = start_s; from <= now_s; from += SLICE_SECONDS)); do
    to=$((from + SLICE_SECONDS))
    ids="$(inside -G "http://tempo:3200/api/search" \
      --data-urlencode "start=$from" --data-urlencode "end=$to" --data-urlencode "limit=$TRACE_LIMIT" |
      python3 -c 'import json,sys; print("\n".join(t["traceID"] for t in json.load(sys.stdin).get("traces", [])))')" || return 1
    if (( $(wc -w <<<"$ids") >= TRACE_LIMIT )); then
      echo "the slice starting $(date -d "@$from" +%T) holds $TRACE_LIMIT+ traces; lower SINCE_MINUTES"
      return 1
    fi
    printf '%s\n' "$ids" >> "$scratch/ids"
  done
  # A trace that spans two slices is listed twice.
  sort -u "$scratch/ids" | sed '/^$/d' > "$scratch/unique"
  # One line per trace: "<id> <body>", or "<id> FETCH_FAILED".
  docker exec -i "$grafana_container" sh -c \
    'while read -r id; do printf "%s " "$id"; curl -sS --fail --max-time 20 "http://tempo:3200/api/v2/traces/$id" || printf "FETCH_FAILED"; echo; done' \
    < "$scratch/unique" > "$scratch/traces"
  python3 - "$scratch/traces" "$TRACE_ID" <<'PY'
import json, re, sys
secret = re.compile(r"x-amz-(?:signature|credential|security-token)", re.I)
want = sys.argv[2].lstrip("0")
n = fetched = hits = 0
found_trace = upload = outputs_url = False
for line in open(sys.argv[1]):
    trace_id, _, body = line.strip().partition(" ")
    if not trace_id:
        continue
    n += 1
    if body == "FETCH_FAILED" or not body:
        continue
    fetched += 1
    hits += len(secret.findall(body))
    found_trace |= trace_id.lstrip("0") == want
    for rs in json.loads(body).get("trace", {}).get("resourceSpans", []):
        for ss in rs.get("scopeSpans", []):
            for span in ss.get("spans", []):
                if span.get("kind") != "SPAN_KIND_SERVER":
                    continue
                name = span.get("name", "")
                upload |= name == "http post /api/v1/uploads"
                outputs_url |= name.startswith("http get /api/v1/runs/") and name.endswith("/outputs/{name}/url")
print(f"{hits} hits in {fetched} of {n} traces; this trace scanned: {found_trace}; "
      f"upload grant scanned: {upload}; outputs URL scanned: {outputs_url}")
if not (upload and outputs_url):
    print("no upload/download in window")
sys.exit(0 if n > 0 and fetched == n and hits == 0 and found_trace and upload and outputs_url else 1)
PY
}

count_secret_hits_in_logs() {
  local from to
  : > "$scratch/logs"
  for ((from = start_s; from <= now_s; from += SLICE_SECONDS)); do
    to=$((from + SLICE_SECONDS))
    inside -G "http://loki:3100/loki/api/v1/query_range" \
      --data-urlencode 'query={service_name=~".+"}' \
      --data-urlencode "start=${from}000000000" --data-urlencode "end=${to}000000000" \
      --data-urlencode "limit=$LOG_LIMIT" >> "$scratch/logs" || return 1
    echo >> "$scratch/logs"
  done
  python3 - "$scratch/logs" "$LOG_LIMIT" <<'PY'
import json, re, sys
limit = int(sys.argv[2])
secret = re.compile(r"x-amz-(?:signature|credential|security-token)", re.I)
lines = hits = 0
for doc in open(sys.argv[1]):
    if not doc.strip():
        continue
    streams = json.loads(doc)["data"]["result"]
    count = sum(len(s["values"]) for s in streams)
    if count >= limit:
        print(f"a slice holds {limit}+ log records; lower SINCE_MINUTES")
        sys.exit(1)
    lines += count
    hits += len(secret.findall(json.dumps(streams)))
print(f"{hits} hits in {lines} log records")
sys.exit(0 if lines > 0 and hits == 0 else 1)
PY
}

# ---- metrics store targets -----------------------------------------------------
check_targets() {
  local body
  body="$(curl -sS --fail-with-body --max-time 10 "$PROMETHEUS_URL/api/v1/targets?state=active")" || return 1
  printf '%s' "$body" | python3 -c '
import json, sys
want = sys.argv[1:]
health = {}
for t in json.load(sys.stdin)["data"]["activeTargets"]:
    job = t["labels"].get("job", "?")
    health.setdefault(job, []).append(t["health"])
print(" ".join(j + "=" + ",".join(health.get(j, ["absent"])) for j in want))
sys.exit(0 if all(health.get(j) and all(h == "up" for h in health[j]) for j in want) else 1)
' "${TARGET_JOBS[@]}"
}

# Ingestion is batched in the services and the collector, so the checks that wait
# for new data are retried until WAIT_SECONDS have passed.
retry() {
  local label=$1 fn=$2 out deadline=$(( $(date +%s) + WAIT_SECONDS ))
  while :; do
    if out="$($fn 2>&1)"; then pass "$label: $out"; return; fi
    if (( $(date +%s) >= deadline )); then fail "$label: $out"; return; fi
    sleep 5
  done
}

echo "trace $TRACE_ID, project $PROJECT, window ${SINCE_MINUTES} min"
if [[ -z "$grafana_container" ]]; then
  fail "overlay not running: no grafana container in compose project '$PROJECT', so the trace and log stores cannot be queried"
else
  retry "trace store: one trace spanning ${SERVICES[*]}" check_trace
  retry "log store: lines for the trace from ${SERVICES[*]}" check_logs
  # A new trace reaches search some time after it can be fetched by id.
  retry "trace store: no presigned-URL secrets" count_secret_hits_in_traces
  if out="$(count_secret_hits_in_logs 2>&1)"; then pass "log store: no presigned-URL secrets ($out)"; else fail "log store secrets: $out"; fi
fi
retry "metrics store targets up: ${TARGET_JOBS[*]}" check_targets

if (( failures > 0 )); then
  echo "check-telemetry: $failures check(s) failed"
  exit 1
fi
echo "check-telemetry: all checks passed"
