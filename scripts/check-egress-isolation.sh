#!/usr/bin/env bash
#
# Egress isolation guard for the compose stack. Resolves the merged compose model
# for the base file, base + observability and base + AWS overlay, and fails when:
#   1. a service outside NON_INTERNAL_OK sits on a network that is not internal;
#   2. a service other than egress-proxy or egress-fetch is on the egress network;
#   3. egress-proxy or egress-fetch publishes a port;
#   4. the internal network (or the fetch network, when defined) is not internal: true;
#   5. a service other than egress-fetch is on the fetch network;
#   6. a proxy is on a network outside its set, or a service names egress-fetch.
# A new service lands on the default network, so it fails rule 1 until it is made
# internal-only or consciously added to NON_INTERNAL_OK below.
#
# Exit status: 0 clean, 1 isolation violation, 2 usage, 3 `docker compose config`
# failed (reported as "compose config failed: <file set>", never as a violation).
#
# Needs bash, python3 and docker. Env: EGRESS_CHECK_ROOT (directory holding the
# compose files and .env.example; default: the repository root).

set -uo pipefail

ROOT="${EGRESS_CHECK_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# Required variables of the overlay and of the base file, throwaway values only.
export DOMAIN="${DOMAIN:-egress-check.invalid}"
export SPRING_MAIL_HOST="${SPRING_MAIL_HOST:-smtp.egress-check.invalid}"
export SPRING_MAIL_PORT="${SPRING_MAIL_PORT:-587}"
export SPRING_MAIL_USERNAME="${SPRING_MAIL_USERNAME:-check}"
export SPRING_MAIL_PASSWORD="${SPRING_MAIL_PASSWORD:-check}"
export EGRESS_ACL_FILE="${EGRESS_ACL_FILE:-$ROOT/infra/egress-proxy/acl.yaml}"
export GF_SECURITY_ADMIN_PASSWORD="${GF_SECURITY_ADMIN_PASSWORD:-check}"
export POSTGRES_EXPORTER_PASSWORD="${POSTGRES_EXPORTER_PASSWORD:-check}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

file_sets=(
  "docker-compose.yml"
  "docker-compose.yml compose.observability.yaml"
  "docker-compose.yml compose.aws.yaml"
)

n=0
for set in "${file_sets[@]}"; do
  args=(--project-directory "$ROOT" --env-file "$ROOT/.env.example")
  for f in $set; do args+=(-f "$ROOT/$f"); done
  n=$((n + 1))
  if ! docker compose "${args[@]}" config --format json >"$tmp/$n.json" 2>"$tmp/$n.err"; then
    echo "compose config failed: $set" >&2
    sed 's/^/  /' "$tmp/$n.err" >&2
    exit 3
  fi
  printf '%s\n' "$set" >"$tmp/$n.name"
done

python3 - "$tmp" "$n" <<'PY'
import json
import sys

tmp, count = sys.argv[1], int(sys.argv[2])

NON_INTERNAL_OK = {
    "egress-proxy", "egress-fetch", "postgres", "rabbitmq", "minio", "mailpit",
    "frontend", "admin", "api-publish", "caddy", "minio-init", "rabbitmq-init",
    "otel-collector", "tempo", "loki", "prometheus", "grafana",
    "postgres-exporter", "pg-monitor-init",
}
PROXIES = {"egress-proxy", "egress-fetch"}
PROXY_NETWORKS = {"egress-proxy": {"internal", "egress"}, "egress-fetch": {"fetch", "egress"}}

violations = []
for i in range(1, count + 1):
    with open(f"{tmp}/{i}.json") as fh:
        model = json.load(fh)
    with open(f"{tmp}/{i}.name") as fh:
        label = fh.read().strip()
    services = model.get("services", {})
    networks = model.get("networks", {})

    def nets(svc):
        return list(svc.get("networks") or ["default"])

    for name, svc in sorted(services.items()):
        on = nets(svc)
        if name not in NON_INTERNAL_OK:
            open_nets = [n for n in on if not networks.get(n, {}).get("internal", False)]
            if open_nets:
                violations.append(f"[{label}] {name}: on non-internal network(s) {open_nets}")
        if "egress" in on and name not in PROXIES:
            violations.append(f"[{label}] {name}: on the egress network")
        if name in PROXIES and svc.get("ports"):
            violations.append(f"[{label}] {name}: publishes ports")
        if "fetch" in on and name != "egress-fetch":
            violations.append(f"[{label}] {name}: on the fetch network")
        if name in PROXY_NETWORKS and not set(on) <= PROXY_NETWORKS[name]:
            extra = sorted(set(on) - PROXY_NETWORKS[name])
            violations.append(f"[{label}] {name}: on network(s) outside its set {extra}")
        if name != "egress-fetch" and "egress-fetch" in json.dumps(svc.get("environment") or {}):
            violations.append(f"[{label}] {name}: environment names egress-fetch")

    if not networks.get("internal", {}).get("internal", False):
        violations.append(f"[{label}] network internal is not internal: true")
    if "fetch" in networks and not networks["fetch"].get("internal", False):
        violations.append(f"[{label}] network fetch is not internal: true")

if violations:
    print("egress isolation violations:")
    for v in violations:
        print(f"  {v}")
    sys.exit(1)
print("egress isolation: ok")
PY
