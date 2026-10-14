#!/bin/sh
# One-shot object-store initialisation, run by the compose `minio-init` service
# (same image as `minio`; `mc` is bundled). Every step is idempotent, so a
# second `docker compose up` leaves the same buckets, lifecycles, users and
# policies. The image has no grep/sed: string checks use `case`.
set -eu

: "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}"
: "${BACKEND_S3_ACCESS_KEY:?}" "${BACKEND_S3_SECRET_KEY:?}"
: "${WORKER_S3_ACCESS_KEY:?}" "${WORKER_S3_SECRET_KEY:?}"
: "${PROVIDER_S3_ACCESS_KEY:?}" "${PROVIDER_S3_SECRET_KEY:?}"
RUN_INPUT_RETENTION_DAYS=${RUN_INPUT_RETENTION_DAYS:-30}
case "$RUN_INPUT_RETENTION_DAYS" in
    ''|0*|*[!0-9]*)
        echo "minio-init: RUN_INPUT_RETENTION_DAYS must be a whole number of days, at least 1" >&2
        exit 1
        ;;
    *) ;;
esac

DIR=$(dirname "$0")

# The root alias comes from the environment, so the root password never appears
# on a command line or in an mc config file. It is embedded in a URL, so both
# root values must be URL-safe.
case "${MINIO_ROOT_USER}${MINIO_ROOT_PASSWORD}" in
    *[!A-Za-z0-9._~-]*)
        echo "minio-init: MINIO_ROOT_USER/MINIO_ROOT_PASSWORD may use only letters, digits and . _ ~ -" >&2
        exit 1
        ;;
    *) ;;
esac
# mc reads alias X from MC_HOST_X; the alias is upper case to keep the variable so.
export MC_HOST_ROOT="http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000"

mc mb --ignore-existing ROOT/flowcortex-uploads ROOT/flowcortex-media

# `import` replaces the whole lifecycle configuration; `mc ilm rule add` would
# append a duplicate rule on every run.
mc ilm import ROOT/flowcortex-uploads < "$DIR/uploads-lifecycle.json"

# The backend tags each run-input copy it makes in the media bucket; only those
# objects expire here, so worker and provider outputs are never touched. The
# rule is built inline because its age comes from the environment. Like the
# uploads import above, this replaces every lifecycle rule on the media bucket:
# add rules here, not by hand, or the next init run drops them.
printf '%s\n' \
    '{"Rules":[{"ID":"expire-run-inputs","Status":"Enabled",' \
    '"Filter":{"Tag":{"Key":"flowcortex-retention","Value":"run-input"}},' \
    "\"Expiration\":{\"Days\":$RUN_INPUT_RETENTION_DAYS}}]}" \
    | mc ilm import ROOT/flowcortex-media

# `policy create` overwrites an existing policy of the same name; `user add`
# on an existing user only resets its secret. Keys go in on stdin (printf is a
# shell builtin), so no secret appears on a command line.
mc admin policy create ROOT flowcortex-backend "$DIR/backend-policy.json"
mc admin policy create ROOT flowcortex-worker "$DIR/worker-policy.json"
mc admin policy create ROOT flowcortex-provider "$DIR/provider-policy.json"
printf '%s\n%s\n' "$BACKEND_S3_ACCESS_KEY" "$BACKEND_S3_SECRET_KEY" | mc admin user add ROOT >/dev/null
printf '%s\n%s\n' "$WORKER_S3_ACCESS_KEY" "$WORKER_S3_SECRET_KEY" | mc admin user add ROOT >/dev/null
printf '%s\n%s\n' "$PROVIDER_S3_ACCESS_KEY" "$PROVIDER_S3_SECRET_KEY" | mc admin user add ROOT >/dev/null

# Re-attaching an attached policy is an error on the server; treat it as done.
attach() {
    local policy="$1" user="$2" out
    if out=$(mc admin policy attach ROOT "$policy" --user "$user" 2>&1); then
        echo "$out"
        return 0
    fi
    case "$out" in
        *"already in effect"*) echo "policy $policy already attached" ;;
        *) echo "$out" >&2; return 1 ;;
    esac
}

detach_if_present() {
    local policy="$1" user="$2" out
    if out=$(mc admin policy detach ROOT "$policy" --user "$user" 2>&1); then
        echo "$out"
        return 0
    fi
    case "$out" in
        *"not attached"*|*"not found"*) echo "policy $policy was not attached" ;;
        *) echo "$out" >&2; return 1 ;;
    esac
}

attach flowcortex-backend "$BACKEND_S3_ACCESS_KEY"
attach flowcortex-worker "$WORKER_S3_ACCESS_KEY"
# Upgrade an existing local volume safely: older releases attached the broad media-worker policy
# to this user. Merely attaching the new policy would leave those old read permissions effective.
detach_if_present flowcortex-worker "$PROVIDER_S3_ACCESS_KEY"
attach flowcortex-provider "$PROVIDER_S3_ACCESS_KEY"

echo "minio-init: done"
